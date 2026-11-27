package com.driverassistant.capture;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;

import com.driverassistant.diagnostics.DiagnosticTextSanitizer;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** ML Kit Latin OCR client. The image and raw recognition text remain transient in memory. */
public final class OcrTextRecognizer implements AutoCloseable {
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private volatile boolean closed;

    public void process(Bitmap bitmap, Executor callbackExecutor, Callback callback) {
        if (bitmap == null || bitmap.isRecycled() || closed) {
            callback.onFailure("recognizer_unavailable", 0);
            return;
        }
        long started = SystemClock.elapsedRealtime();
        try {
            Task<Text> task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            task.addOnSuccessListener(callbackExecutor, text -> {
                long elapsed = Math.max(0, SystemClock.elapsedRealtime() - started);
                try {
                    callback.onSuccess(new Recognition(text, bitmap.getWidth(), bitmap.getHeight(), elapsed));
                } catch (RuntimeException exception) {
                    callback.onFailure(exception.getClass().getSimpleName(), elapsed);
                }
            }).addOnFailureListener(callbackExecutor, exception -> callback.onFailure(
                    exception.getClass().getSimpleName(), Math.max(0, SystemClock.elapsedRealtime() - started)));
        } catch (RuntimeException exception) {
            callback.onFailure(exception.getClass().getSimpleName(), Math.max(0, SystemClock.elapsedRealtime() - started));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        recognizer.close();
    }

    public interface Callback {
        void onSuccess(Recognition recognition);
        void onFailure(String reason, long processingMillis);
    }

    public static final class Recognition {
        private static final int MAX_DIAGNOSTIC_BLOCKS = 40;
        private static final int MAX_DIAGNOSTIC_LINES = 180;
        private static final int MAX_DIAGNOSTIC_ELEMENTS = 600;
        private final int imageWidth;
        private final int imageHeight;
        private final long processingMillis;
        private final List<Block> blocks;
        private final List<VisualTextLine> lines;

        private Recognition(Text text, int imageWidth, int imageHeight, long processingMillis) {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.processingMillis = processingMillis;
            List<Block> copiedBlocks = new ArrayList<>();
            List<VisualTextLine> copiedLines = new ArrayList<>();
            int order = 0;
            for (Text.TextBlock textBlock : text.getTextBlocks()) {
                List<Line> blockLines = new ArrayList<>();
                for (Text.Line textLine : textBlock.getLines()) {
                    Rect lineBounds = safeBounds(textLine.getBoundingBox());
                    List<Element> elements = new ArrayList<>();
                    for (Text.Element textElement : textLine.getElements()) {
                        elements.add(new Element(textElement.getText(), safeBounds(textElement.getBoundingBox()),
                                textElement.getConfidence()));
                    }
                    Line line = new Line(textLine.getText(), lineBounds, textLine.getConfidence(), elements);
                    blockLines.add(line);
                    copiedLines.add(new VisualTextLine(line.text(), line.bounds().left, line.bounds().top,
                            line.bounds().right, line.bounds().bottom, order++));
                }
                copiedBlocks.add(new Block(textBlock.getText(), safeBounds(textBlock.getBoundingBox()), blockLines));
            }
            blocks = List.copyOf(copiedBlocks);
            lines = List.copyOf(copiedLines);
        }

        public int imageWidth() { return imageWidth; }
        public int imageHeight() { return imageHeight; }
        public long processingMillis() { return processingMillis; }
        public List<Block> blocks() { return blocks; }
        public List<VisualTextLine> lines() { return lines; }

        /** Persists geometry and only sanitized OCR strings; raw recognized text is never serialized. */
        public JSONObject toSanitizedJson() {
            JSONObject result = new JSONObject();
            try {
                result.put("image_width", imageWidth);
                result.put("image_height", imageHeight);
                result.put("block_count", blocks.size());
                result.put("line_count", lines.size());
                result.put("element_count", elementCount());
                JSONArray blockArray = new JSONArray();
                int linesWritten = 0;
                int elementsWritten = 0;
                boolean truncated = blocks.size() > MAX_DIAGNOSTIC_BLOCKS;
                for (Block block : blocks) {
                    if (blockArray.length() >= MAX_DIAGNOSTIC_BLOCKS) { truncated = true; break; }
                    JSONObject blockJson = new JSONObject();
                    blockJson.put("bounds", boundsJson(block.bounds()));
                    String blockContext = block.text();
                    blockJson.put("text", safeDiagnosticText(block.text(), blockContext));
                    JSONArray lineArray = new JSONArray();
                    for (Line line : block.lines()) {
                        if (linesWritten >= MAX_DIAGNOSTIC_LINES) { truncated = true; break; }
                        JSONObject lineJson = new JSONObject();
                        lineJson.put("bounds", boundsJson(line.bounds()));
                        lineJson.put("text", safeDiagnosticText(line.text(), blockContext + " " + line.text()));
                        lineJson.put("confidence", line.confidence());
                        JSONArray elementArray = new JSONArray();
                        for (Element element : line.elements()) {
                            if (elementsWritten >= MAX_DIAGNOSTIC_ELEMENTS) { truncated = true; break; }
                            JSONObject elementJson = new JSONObject();
                            elementJson.put("bounds", boundsJson(element.bounds()));
                            elementJson.put("text", safeDiagnosticText(element.text(), blockContext + " " + line.text()));
                            elementJson.put("confidence", element.confidence());
                            elementArray.put(elementJson);
                            elementsWritten++;
                        }
                        if (line.elements().size() > elementArray.length()) truncated = true;
                        lineJson.put("elements", elementArray);
                        lineArray.put(lineJson);
                        linesWritten++;
                    }
                    if (block.lines().size() > lineArray.length()) truncated = true;
                    blockJson.put("lines", lineArray);
                    blockArray.put(blockJson);
                }
                result.put("blocks", blockArray);
                result.put("diagnostic_truncated", truncated);
            } catch (Exception ignored) {
                return new JSONObject();
            }
            return result;
        }

        private static String safeDiagnosticText(String text, String context) {
            return DiagnosticTextSanitizer.sanitizeRelevantFragment(text, context);
        }

        private int elementCount() {
            int count = 0;
            for (Block block : blocks) for (Line line : block.lines()) count += line.elements().size();
            return count;
        }

        private static JSONArray boundsJson(Rect bounds) {
            JSONArray values = new JSONArray();
            values.put(bounds.left); values.put(bounds.top); values.put(bounds.right); values.put(bounds.bottom);
            return values;
        }

        private static Rect safeBounds(Rect bounds) { return bounds == null ? new Rect() : new Rect(bounds); }
    }

    public static final class Block {
        private final String text;
        private final Rect bounds;
        private final List<Line> lines;
        private Block(String text, Rect bounds, List<Line> lines) {
            this.text = text; this.bounds = new Rect(bounds); this.lines = List.copyOf(lines);
        }
        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public List<Line> lines() { return lines; }
    }

    public static final class Line {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private final List<Element> elements;
        private Line(String text, Rect bounds, float confidence, List<Element> elements) {
            this.text = text; this.bounds = new Rect(bounds); this.confidence = confidence;
            this.elements = List.copyOf(elements);
        }
        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
        public List<Element> elements() { return elements; }
    }

    public static final class Element {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private Element(String text, Rect bounds, float confidence) {
            this.text = text; this.bounds = new Rect(bounds); this.confidence = confidence;
        }
        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
    }
}
