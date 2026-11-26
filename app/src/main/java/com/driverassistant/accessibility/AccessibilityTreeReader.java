package com.driverassistant.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;

import com.driverassistant.parser.AccessibilityNode;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Copies a bounded accessibility snapshot; callers must not persist node text or descriptions. */
public final class AccessibilityTreeReader {
    private static final int MAX_NODES = 500;

    public List<AccessibilityNode> read(AccessibilityNodeInfo root) {
        List<AccessibilityNode> result = new ArrayList<>();
        walk(root, -1, 0, result);
        return result;
    }

    /** Structure-only diagnostic summary. It intentionally omits all accessible strings. */
    public static List<String> structureSummary(List<AccessibilityNode> nodes) {
        List<String> result = new ArrayList<>();
        if (nodes == null) return result;
        for (AccessibilityNode node : nodes) {
            if (node == null) continue;
            String id = node.resourceId() == null ? "-" : node.resourceId();
            String className = node.className() == null ? "?" : simpleClass(node.className());
            int textLength = node.text() == null ? 0 : node.text().length();
            int descriptionLength = node.contentDescription() == null ? 0 : node.contentDescription().length();
            result.add("#" + node.order() + " parent=" + node.parentOrder() + " depth=" + node.depth()
                    + " class=" + className + " visible=" + node.visibleToUser() + " id=" + id + " textChars=" + textLength
                    + " descriptionChars=" + descriptionLength);
        }
        return result;
    }

    /** Ordered, privacy-filtered node snapshot for an explicitly started capture session. */
    public static JSONArray diagnosticTree(List<AccessibilityNode> nodes) {
        JSONArray result = new JSONArray();
        if (nodes == null) return result;
        for (AccessibilityNode node : nodes) {
            if (node == null) continue;
            JSONObject item = new JSONObject();
            try {
                item.put("order", node.order());
                item.put("parent_order", node.parentOrder());
                item.put("depth", node.depth());
                item.put("resource_id", node.resourceId() == null ? JSONObject.NULL : node.resourceId());
                item.put("class", node.className() == null ? JSONObject.NULL : node.className());
                item.put("visible", node.visibleToUser());
                item.put("text", DiagnosticTextSanitizer.sanitizeRelevant(node.text()));
                item.put("content_description", DiagnosticTextSanitizer.sanitizeRelevant(node.contentDescription()));
                result.put(item);
            } catch (JSONException ignored) { /* Keep the remaining ordered nodes. */ }
        }
        return result;
    }

    /** Stable digest for snapshot deduplication; it is not used in offer recognition. */
    public static String diagnosticFingerprint(List<AccessibilityNode> nodes) {
        StringBuilder value = new StringBuilder();
        if (nodes != null) for (AccessibilityNode node : nodes) {
            if (node == null) continue;
            value.append(node.order()).append('|').append(node.parentOrder()).append('|').append(node.depth())
                    .append('|').append(node.resourceId()).append('|').append(node.className())
                    .append('|').append(node.visibleToUser()).append('|')
                    .append(DiagnosticTextSanitizer.sanitizeRelevant(node.text())).append('|')
                    .append(DiagnosticTextSanitizer.sanitizeRelevant(node.contentDescription())).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.toString().hashCode());
        }
    }

    public static boolean containsOfferCandidate(List<AccessibilityNode> nodes) {
        if (nodes == null) return false;
        StringBuilder text = new StringBuilder();
        for (AccessibilityNode node : nodes) {
            if (node == null || !node.visibleToUser()) continue;
            text.append(' ').append(DiagnosticTextSanitizer.sanitizeRelevant(node.accessibleText()));
        }
        String normalized = text.toString().toLowerCase(Locale.ROOT);
        boolean money = normalized.contains("r$") || normalized.contains("brl");
        boolean offerCue = normalized.contains("aceitar") || normalized.contains("selecionar")
                || normalized.contains("oferta") || normalized.contains("corrida")
                || normalized.contains("uberx") || normalized.contains("comfort")
                || normalized.contains("priority") || normalized.contains("nova chamada");
        return money && offerCue;
    }

    private static void walk(AccessibilityNodeInfo node, int parentOrder, int depth,
                             List<AccessibilityNode> output) {
        if (node == null || output.size() >= MAX_NODES) return;
        int order = output.size();
        try {
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            output.add(new AccessibilityNode(toString(text), toString(description),
                    node.getViewIdResourceName(), node.getClassName() == null ? null : node.getClassName().toString(),
                    parentOrder, depth, order, node.isVisibleToUser()));
            for (int index = 0; index < node.getChildCount() && output.size() < MAX_NODES; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                walk(child, order, depth + 1, output);
            }
        } finally {
            node.recycle();
        }
    }

    private static String toString(CharSequence value) { return value == null ? null : value.toString(); }

    private static String simpleClass(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }
}
