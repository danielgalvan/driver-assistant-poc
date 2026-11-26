package com.driverassistant.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.driverassistant.diagnostics.DiagnosticCapturePolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Private, append-only device capture. It never sends records outside the app. */
public final class DiagnosticCaptureStore {
    public static final int DEFAULT_STORAGE_LIMIT_MB = 50;
    public static final long SESSION_MAX_DURATION_MILLIS = DiagnosticCapturePolicy.DEFAULT_MAX_DURATION_MILLIS;
    private static final String TAG = "DiagnosticCapture";
    private static final String PREFERENCES = "driver_assistant_capture_settings";
    private static final String LIMIT_MB_KEY = "storage_limit_mb";
    private static final long MB = 1024L * 1024L;
    private static final long FILE_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final String EVENTS_FILE = "capture.jsonl";
    private static final String STATE_FILE = "capture-state.properties";
    private static final Object INSTANCE_LOCK = new Object();
    private static volatile DiagnosticCaptureStore instance;

    private final File directory;
    private final File eventsFile;
    private final File stateFile;
    private final SharedPreferences preferences;
    private final HandlerThread writerThread;
    private final Handler writer;
    private final DiagnosticCapturePolicy policy = new DiagnosticCapturePolicy();
    private final Object lock = new Object();

    private volatile boolean active;
    private volatile boolean clearing;
    private volatile long eventCount;
    private String sessionId;
    private long sessionStartedAt;
    private long sessionMaxStorageBytes;
    private long reservedBytes;
    private long nextSequence;
    private String stopReason;
    private String lastSkippedReason;
    private long lastSkipRecordAt;
    private long skippedEventsSinceRecord;
    private long lastSkippedEventUptime;

    public static DiagnosticCaptureStore get(Context context) {
        DiagnosticCaptureStore value = instance;
        if (value != null) return value;
        synchronized (INSTANCE_LOCK) {
            if (instance == null) instance = new DiagnosticCaptureStore(context.getApplicationContext());
            return instance;
        }
    }

    private DiagnosticCaptureStore(Context context) {
        directory = new File(context.getFilesDir(), "diagnostics");
        eventsFile = new File(directory, EVENTS_FILE);
        stateFile = new File(directory, STATE_FILE);
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        writerThread = new HandlerThread("driver-diagnostic-capture");
        writerThread.start();
        writer = new Handler(writerThread.getLooper());
        restoreSession();
    }

    /** Starts only after an explicit tap in the Driver Assistant UI. */
    public boolean startCapture() {
        synchronized (lock) {
            expireIfNeededLocked(System.currentTimeMillis());
            if (active || clearing || !directory.exists() && !directory.mkdirs()) return false;
            cleanupExpiredFileLocked();
            int limitMb = storageLimitMb();
            long maxBytes = limitMb * MB;
            long existingBytes = eventsFile.exists() ? eventsFile.length() : 0;
            long now = System.currentTimeMillis();
            if (!policy.start(now, existingBytes, maxBytes, SESSION_MAX_DURATION_MILLIS)) {
                stopReason = "storage_limit_reached_clear_or_export";
                return false;
            }
            sessionId = UUID.randomUUID().toString();
            sessionStartedAt = now;
            sessionMaxStorageBytes = maxBytes;
            reservedBytes = existingBytes;
            nextSequence = 0;
            eventCount = 0;
            stopReason = null;
            lastSkippedReason = null;
            lastSkipRecordAt = 0;
            skippedEventsSinceRecord = 0;
            lastSkippedEventUptime = 0;
            active = true;
            writeStateLocked();
            appendLocked("SESSION_STARTED", object("source", "manual"), false);
            return active;
        }
    }

    public void stopCapture(String reason) {
        synchronized (lock) {
            if (!active) return;
            flushSkippedEventsLocked(false);
            if (!active) return;
            String safeReason = reason == null || reason.isBlank() ? "manual" : reason;
            appendLocked("SESSION_STOPPED", object("reason", safeReason), true);
            active = false;
            stopReason = safeReason;
            policy.stop(safeReason);
            writeStateLocked();
        }
    }

    public void record(String kind, JSONObject details) {
        synchronized (lock) {
            if (!active) return;
            expireIfNeededLocked(System.currentTimeMillis());
            if (!active) return;
            appendLocked(kind, details, false);
        }
    }

    /** Dedupe identical trees, while keeping every distinct offer candidate as an append-only snapshot. */
    public boolean shouldCaptureTreeSnapshot(String fingerprint, boolean offerCandidate) {
        synchronized (lock) {
            expireIfNeededLocked(System.currentTimeMillis());
            if (!active) return false;
            return policy.shouldPersistSnapshot(fingerprint, offerCandidate, System.currentTimeMillis());
        }
    }

    public void recordSnapshotSkipped(long sourceEventUptimeMillis, String reason) {
        synchronized (lock) {
            if (!active) return;
            expireIfNeededLocked(System.currentTimeMillis());
            if (!active) return;
            long now = System.currentTimeMillis();
            String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
            if (!safeReason.equals(lastSkippedReason)) {
                flushSkippedEventsLocked(false);
                if (!active) return;
                lastSkippedReason = safeReason;
                lastSkipRecordAt = now;
                lastSkippedEventUptime = sourceEventUptimeMillis;
                appendLocked("TREE_NOT_CAPTURED", object("event_time_uptime_ms", sourceEventUptimeMillis,
                        "reason", safeReason, "suppressed_repeats", 0), false);
                return;
            }
            skippedEventsSinceRecord++;
            lastSkippedEventUptime = sourceEventUptimeMillis;
            if (now - lastSkipRecordAt >= 5_000) {
                flushSkippedEventsLocked(false);
                lastSkipRecordAt = now;
            }
        }
    }

    public void clearCapture(Runnable onComplete) {
        synchronized (lock) {
            if (clearing) return;
            clearing = true;
            if (active) {
                policy.stop("cleared");
                active = false;
                stopReason = "cleared";
                writeStateLocked();
            }
            writer.post(() -> {
                eventsFile.delete();
                stateFile.delete();
                synchronized (lock) {
                    clearing = false;
                    policy.stop("cleared");
                    active = false;
                    sessionId = null;
                    sessionStartedAt = 0;
                    reservedBytes = 0;
                    nextSequence = 0;
                    eventCount = 0;
                    stopReason = null;
                    lastSkippedReason = null;
                    lastSkipRecordAt = 0;
                    skippedEventsSinceRecord = 0;
                    lastSkippedEventUptime = 0;
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (onComplete != null) onComplete.run();
                });
            });
        }
    }

    /** Exports the closed capture file as a ZIP with one valid JSON event array. */
    public void exportZip(OutputStream destination) throws IOException {
        if (destination == null) throw new IOException("Destino de exportação indisponível");
        CountDownLatch barrier = new CountDownLatch(1);
        writer.post(barrier::countDown);
        try {
            if (!barrier.await(30, TimeUnit.SECONDS)) throw new IOException("Tempo limite ao gravar diagnóstico");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Exportação interrompida", interrupted);
        }
        synchronized (lock) {
            if (active) throw new IOException("Encerre a captura antes de exportar");
        }

        ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8);
        zip.putNextEntry(new ZipEntry("diagnostics.json"));
        writeUtf8(zip, "{\"schema_version\":1,\"exported_at\":\"" + timestamp(System.currentTimeMillis())
                + "\",\"events\":[");
        boolean first = true;
        long invalidLinesSkipped = 0;
        if (eventsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(eventsFile), StandardCharsets.UTF_8), 32 * 1024)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try { new JSONObject(line); }
                    catch (JSONException incompleteWrite) { invalidLinesSkipped++; continue; }
                    if (!first) writeUtf8(zip, ",");
                    writeUtf8(zip, line);
                    first = false;
                }
            }
        }
        writeUtf8(zip, "]}");
        zip.closeEntry();
        if (invalidLinesSkipped > 0) {
            zip.putNextEntry(new ZipEntry("export-warnings.txt"));
            writeUtf8(zip, "Registros JSON incompletos ignorados: " + invalidLinesSkipped
                    + ". Os demais eventos foram preservados.\n");
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    public Status status() {
        synchronized (lock) {
            expireIfNeededLocked(System.currentTimeMillis());
            long fileBytes = eventsFile.exists() ? eventsFile.length() : 0;
            long capBytes = active ? sessionMaxStorageBytes : storageLimitMb() * MB;
            return new Status(active, eventCount, fileBytes, capBytes, stopReason,
                    active ? Math.max(0, System.currentTimeMillis() - sessionStartedAt) : 0,
                    storageLimitMb(), sessionId);
        }
    }

    public String currentSessionId() {
        synchronized (lock) { return sessionId; }
    }

    public boolean isActive() { return status().active(); }

    public int storageLimitMb() {
        return preferences.getInt(LIMIT_MB_KEY, DEFAULT_STORAGE_LIMIT_MB);
    }

    /** The selected limit applies to the next session; an active session keeps its displayed cap. */
    public boolean setStorageLimitMb(int megabytes) {
        if (megabytes != 10 && megabytes != 25 && megabytes != 50 && megabytes != 100) return false;
        preferences.edit().putInt(LIMIT_MB_KEY, megabytes).apply();
        return true;
    }

    private void appendLocked(String kind, JSONObject details, boolean force) {
        if (!force && !active) return;
        long now = System.currentTimeMillis();
        long sequence = nextSequence + 1;
        JSONObject record = new JSONObject();
        try {
            record.put("session_id", sessionId == null ? JSONObject.NULL : sessionId);
            record.put("sequence", sequence);
            record.put("timestamp", timestamp(now));
            record.put("elapsed_ms", sessionStartedAt == 0 ? 0 : Math.max(0, now - sessionStartedAt));
            record.put("kind", kind == null ? "UNKNOWN" : kind);
            record.put("details", details == null ? new JSONObject() : details);
        } catch (JSONException ignored) {
            return;
        }
        String line = record.toString() + "\n";
        long bytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (!force) {
            DiagnosticCapturePolicy.AppendResult result = policy.reserve(now, bytes);
            if (result != DiagnosticCapturePolicy.AppendResult.APPENDED) {
                String reason = result == DiagnosticCapturePolicy.AppendResult.DURATION_LIMIT
                        ? "duration_limit" : result == DiagnosticCapturePolicy.AppendResult.STORAGE_LIMIT
                        ? "storage_limit" : "inactive";
                autoStopLocked(reason);
                return;
            }
        } else if (sessionMaxStorageBytes > 0 && bytes > sessionMaxStorageBytes - reservedBytes) {
            return;
        }
        nextSequence = sequence;
        eventCount = sequence;
        reservedBytes += bytes;
        writer.post(() -> appendLine(line));
    }

    private void appendLine(String line) {
        try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(eventsFile, true), StandardCharsets.UTF_8))) {
            output.write(line);
            output.flush();
        } catch (IOException failure) {
            Log.e(TAG, "Falha ao gravar sessão de diagnóstico", failure);
            synchronized (lock) {
                if (active) autoStopLocked("storage_write_error");
            }
        }
    }

    private void autoStopLocked(String reason) {
        if (!active) return;
        active = false;
        stopReason = reason;
        policy.stop(reason);
        flushSkippedEventsLocked(true);
        appendLocked("SESSION_AUTO_STOPPED", object("reason", reason), true);
        writeStateLocked();
    }

    private void flushSkippedEventsLocked(boolean force) {
        if (skippedEventsSinceRecord <= 0 || lastSkippedReason == null) return;
        long suppressed = skippedEventsSinceRecord;
        skippedEventsSinceRecord = 0;
        appendLocked("TREE_NOT_CAPTURED", object("event_time_uptime_ms", lastSkippedEventUptime,
                "reason", lastSkippedReason, "suppressed_repeats", suppressed), force);
        lastSkipRecordAt = System.currentTimeMillis();
    }

    private void expireIfNeededLocked(long now) {
        if (!active) return;
        String reason = policy.expireIfNeeded(now);
        if (reason != null) autoStopLocked(reason);
    }

    private void restoreSession() {
        if (!directory.exists() && !directory.mkdirs()) return;
        Properties state = new Properties();
        if (stateFile.exists()) {
            try (FileInputStream input = new FileInputStream(stateFile)) { state.load(input); }
            catch (IOException failure) { Log.w(TAG, "Estado da captura não pôde ser lido", failure); }
        }
        boolean wasActive = Boolean.parseBoolean(state.getProperty("active", "false"));
        sessionId = emptyToNull(state.getProperty("session_id"));
        sessionStartedAt = parseLong(state.getProperty("started_at"), 0);
        sessionMaxStorageBytes = parseLong(state.getProperty("max_storage_bytes"), storageLimitMb() * MB);
        repairTrailingLine();
        reservedBytes = eventsFile.exists() ? eventsFile.length() : 0;
        nextSequence = lastSequenceForSession(sessionId);
        eventCount = nextSequence;
        if (wasActive && eventsFile.exists()) {
            long now = System.currentTimeMillis();
            boolean resumed = sessionId != null && policy.resume(now, sessionStartedAt, nextSequence,
                    reservedBytes, sessionMaxStorageBytes, SESSION_MAX_DURATION_MILLIS);
            if (resumed) {
                active = true;
                stopReason = null;
                appendLocked("SESSION_RESUMED", object("source", "service_reconnect"), false);
                return;
            }
            active = false;
            stopReason = now - sessionStartedAt >= SESSION_MAX_DURATION_MILLIS
                    ? "duration_limit" : "storage_limit";
            writeStateLocked();
        }
        cleanupExpiredFileLocked();
    }

    private void cleanupExpiredFileLocked() {
        if (!active && eventsFile.exists()
                && System.currentTimeMillis() - eventsFile.lastModified() > FILE_RETENTION_MILLIS) {
            eventsFile.delete();
            stateFile.delete();
            reservedBytes = 0;
            nextSequence = 0;
            eventCount = 0;
        }
    }

    private void writeStateLocked() {
        if (!directory.exists() && !directory.mkdirs()) return;
        Properties state = new Properties();
        state.setProperty("active", Boolean.toString(active));
        state.setProperty("session_id", sessionId == null ? "" : sessionId);
        state.setProperty("started_at", Long.toString(sessionStartedAt));
        state.setProperty("max_storage_bytes", Long.toString(sessionMaxStorageBytes));
        state.setProperty("stop_reason", stopReason == null ? "" : stopReason);
        File temporary = new File(directory, STATE_FILE + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            state.store(output, "Driver Assistant local diagnostic capture state");
            output.flush();
            try {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            Log.e(TAG, "Falha ao persistir estado da captura", failure);
        }
    }

    private long lastSequenceForSession(String expectedSessionId) {
        if (expectedSessionId == null || !eventsFile.exists() || eventsFile.length() == 0) return 0;
        try (RandomAccessFile file = new RandomAccessFile(eventsFile, "r")) {
            long position = file.length() - 1;
            if (position < 0) return 0;
            while (position >= 0) {
                file.seek(position);
                if (file.readByte() == '\n' && position != file.length() - 1) {
                    position++;
                    break;
                }
                position--;
            }
            if (position < 0) position = 0;
            long length = file.length() - position;
            if (length <= 0 || length > 1_048_576) return 0;
            byte[] bytes = new byte[(int) length];
            file.seek(position);
            file.readFully(bytes);
            JSONObject last = new JSONObject(new String(bytes, StandardCharsets.UTF_8).trim());
            return expectedSessionId.equals(last.optString("session_id")) ? last.optLong("sequence", 0) : 0;
        } catch (IOException | JSONException failure) {
            Log.w(TAG, "Último registro da sessão não pôde ser recuperado", failure);
            return 0;
        }
    }

    private void repairTrailingLine() {
        if (!eventsFile.exists() || eventsFile.length() == 0) return;
        try (RandomAccessFile file = new RandomAccessFile(eventsFile, "rw")) {
            long end = file.length() - 1;
            file.seek(end);
            if (file.readByte() == '\n') return;
            long position = end;
            while (position >= 0) {
                file.seek(position);
                if (file.readByte() == '\n') {
                    file.setLength(position + 1);
                    return;
                }
                position--;
            }
            file.setLength(0);
        } catch (IOException failure) {
            Log.w(TAG, "Registro parcial não pôde ser recuperado", failure);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public static JSONObject object(Object... keyValues) {
        JSONObject result = new JSONObject();
        if (keyValues == null) return result;
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            try {
                Object value = keyValues[index + 1];
                result.put(String.valueOf(keyValues[index]), value == null ? JSONObject.NULL : value);
            }
            catch (JSONException ignored) { /* Invalid diagnostic value is omitted. */ }
        }
        return result;
    }

    public static JSONArray array(Iterable<?> values) {
        JSONArray result = new JSONArray();
        if (values != null) for (Object value : values) result.put(value);
        return result;
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String timestamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    public static final class Status {
        private final boolean active;
        private final long eventCount;
        private final long bytes;
        private final long limitBytes;
        private final String stopReason;
        private final long elapsedMillis;
        private final int configuredLimitMb;
        private final String sessionId;

        private Status(boolean active, long eventCount, long bytes, long limitBytes,
                       String stopReason, long elapsedMillis, int configuredLimitMb, String sessionId) {
            this.active = active;
            this.eventCount = eventCount;
            this.bytes = bytes;
            this.limitBytes = limitBytes;
            this.stopReason = stopReason;
            this.elapsedMillis = elapsedMillis;
            this.configuredLimitMb = configuredLimitMb;
            this.sessionId = sessionId;
        }

        public boolean active() { return active; }
        public long eventCount() { return eventCount; }
        public long bytes() { return bytes; }
        public long limitBytes() { return limitBytes; }
        public String stopReason() { return stopReason; }
        public long elapsedMillis() { return elapsedMillis; }
        public int configuredLimitMb() { return configuredLimitMb; }
        public String sessionId() { return sessionId; }
        public double usedMegabytes() { return bytes / (double) MB; }
        public double limitMegabytes() { return limitBytes / (double) MB; }
    }
}
