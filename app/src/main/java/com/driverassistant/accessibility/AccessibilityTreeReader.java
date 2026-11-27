package com.driverassistant.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;

import com.driverassistant.parser.AccessibilityNode;
import com.driverassistant.diagnostics.DiagnosticTextSanitizer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Copies a bounded accessibility snapshot; callers must not persist node text or descriptions. */
public final class AccessibilityTreeReader {
    private static final int MAX_NODES = 500;
    private static final int MAX_EVENT_ANCESTORS = 8;
    private static final String[] OFFER_STRUCTURE_IDS = {
            "ub_driver_offers_browse_pill_container",
            "ub_driver_offers_browse_offer_pill_view",
            "ub__driver_job_offers_pill_title",
            "ub__driver_job_offers_pill_badge"
    };

    public List<AccessibilityNode> read(AccessibilityNodeInfo root) {
        return read(root, MAX_NODES);
    }

    /** Copies at most {@code maxNodes}; the supplied root is consumed and recycled. */
    public List<AccessibilityNode> read(AccessibilityNodeInfo root, int maxNodes) {
        List<AccessibilityNode> result = new ArrayList<>();
        if (maxNodes > 0) walk(root, -1, 0, result, maxNodes);
        else if (root != null) root.recycle();
        return result;
    }

    /**
     * Captures a small immutable context around an event source before the live accessibility
     * hierarchy can advance. The source itself remains owned by the caller.
     */
    public List<AccessibilityNode> readEventContext(AccessibilityNodeInfo source, int maxNodes) {
        if (source == null || maxNodes <= 0) return List.of();
        List<AccessibilityNodeInfo> ancestors = new ArrayList<>();
        AccessibilityNodeInfo cursor = AccessibilityNodeInfo.obtain(source);
        AccessibilityNodeInfo selected = null;
        try {
            for (int depth = 0; cursor != null && depth < MAX_EVENT_ANCESTORS; depth++) {
                ancestors.add(cursor);
                String id = cursor.getViewIdResourceName();
                if (matchesOfferStructureId(id)) {
                    selected = cursor;
                    break;
                }
                AccessibilityNodeInfo parent = cursor.getParent();
                cursor = parent;
            }
            if (selected == null && !ancestors.isEmpty()) {
                // A few levels above a leaf usually includes neighboring fare/route fields while
                // avoiding a full map root. The traversal remains capped by maxNodes.
                selected = ancestors.get(Math.min(3, ancestors.size() - 1));
            }
            return selected == null ? List.of() : read(AccessibilityNodeInfo.obtain(selected), maxNodes);
        } finally {
            for (AccessibilityNodeInfo ancestor : ancestors) ancestor.recycle();
        }
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
        Map<Integer, AccessibilityNode> byOrder = indexByOrder(nodes);
        Map<Integer, List<AccessibilityNode>> siblings = siblingsByParent(nodes);
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
                item.put("selected", node.selected());
                item.put("checked", node.checked());
                String context = localContext(node, byOrder, siblings);
                item.put("text", DiagnosticTextSanitizer.sanitizeRelevantFragment(node.text(), context));
                item.put("content_description", DiagnosticTextSanitizer.sanitizeRelevantFragment(
                        node.contentDescription(), context));
                result.put(item);
            } catch (JSONException ignored) { /* Keep the remaining ordered nodes. */ }
        }
        return result;
    }

    /** Stable digest for snapshot deduplication; it is not used in offer recognition. */
    public static String diagnosticFingerprint(List<AccessibilityNode> nodes) {
        StringBuilder value = new StringBuilder();
        Map<Integer, AccessibilityNode> byOrder = indexByOrder(nodes);
        Map<Integer, List<AccessibilityNode>> siblings = siblingsByParent(nodes);
        if (nodes != null) for (AccessibilityNode node : nodes) {
            if (node == null) continue;
            String context = localContext(node, byOrder, siblings);
            value.append(node.order()).append('|').append(node.parentOrder()).append('|').append(node.depth())
                    .append('|').append(node.resourceId()).append('|').append(node.className())
                    .append('|').append(node.visibleToUser()).append('|').append(node.selected())
                    .append('|').append(node.checked()).append('|')
                    .append(DiagnosticTextSanitizer.sanitizeRelevantFragment(node.text(), context)).append('|')
                    .append(DiagnosticTextSanitizer.sanitizeRelevantFragment(node.contentDescription(), context)).append('\n');
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
        Map<Integer, AccessibilityNode> byOrder = indexByOrder(nodes);
        Map<Integer, List<AccessibilityNode>> siblings = siblingsByParent(nodes);
        for (AccessibilityNode node : nodes) {
            if (node == null || !node.visibleToUser()) continue;
            String context = localContext(node, byOrder, siblings);
            text.append(' ').append(DiagnosticTextSanitizer.sanitizeRelevantFragment(node.accessibleText(), context));
        }
        String normalized = text.toString().toLowerCase(Locale.ROOT);
        boolean money = normalized.contains("r$") || normalized.contains("brl");
        boolean offerCue = normalized.contains("aceitar") || normalized.contains("selecionar")
                || normalized.contains("oferta") || normalized.contains("corrida")
                || normalized.contains("uberx") || normalized.contains("uber x") || normalized.contains("comfort")
                || normalized.contains("priority") || normalized.contains("nova chamada");
        return money && offerCue;
    }

    /** Structural clue for queue priority only; it is not proof that a ride request exists. */
    public static boolean containsOfferStructureSignal(List<AccessibilityNode> nodes) {
        if (nodes == null) return false;
        for (AccessibilityNode node : nodes) {
            if (node != null && node.visibleToUser() && matchesOfferStructureId(node.resourceId())) return true;
        }
        return false;
    }

    /** Fast in-memory fingerprint; the raw string is never written to diagnostics. */
    public static String contentFingerprint(List<AccessibilityNode> nodes) {
        StringBuilder value = new StringBuilder();
        if (nodes != null) for (AccessibilityNode node : nodes) {
            if (node == null) continue;
            value.append(node.order()).append('|').append(node.parentOrder()).append('|')
                    .append(node.resourceId()).append('|').append(node.className()).append('|')
                    .append(node.visibleToUser()).append('|').append(node.selected()).append('|')
                    .append(node.checked()).append('|').append(node.accessibleText()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.toString().hashCode()) + ':' + value.length();
        }
    }

    private static boolean matchesOfferStructureId(String id) {
        if (id == null) return false;
        for (String expected : OFFER_STRUCTURE_IDS) if (id.endsWith(expected)) return true;
        return false;
    }

    /** Broad signal for preserving a changed tree during capture; it does not affect parser acceptance. */
    public static boolean containsRelevantDiagnosticText(List<AccessibilityNode> nodes) {
        if (nodes == null) return false;
        Map<Integer, AccessibilityNode> byOrder = indexByOrder(nodes);
        Map<Integer, List<AccessibilityNode>> siblings = siblingsByParent(nodes);
        for (AccessibilityNode node : nodes) {
            if (node == null || !node.visibleToUser()) continue;
            String context = localContext(node, byOrder, siblings);
            if (!DiagnosticTextSanitizer.sanitizeRelevantFragment(node.text(), context).isEmpty()
                    || !DiagnosticTextSanitizer.sanitizeRelevantFragment(node.contentDescription(), context).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Map<Integer, AccessibilityNode> indexByOrder(List<AccessibilityNode> nodes) {
        Map<Integer, AccessibilityNode> result = new HashMap<>();
        if (nodes != null) for (AccessibilityNode node : nodes) if (node != null) result.put(node.order(), node);
        return result;
    }

    private static Map<Integer, List<AccessibilityNode>> siblingsByParent(List<AccessibilityNode> nodes) {
        Map<Integer, List<AccessibilityNode>> result = new HashMap<>();
        if (nodes != null) for (AccessibilityNode node : nodes) {
            if (node != null) result.computeIfAbsent(node.parentOrder(), ignored -> new ArrayList<>()).add(node);
        }
        return result;
    }

    private static String localContext(AccessibilityNode node, Map<Integer, AccessibilityNode> byOrder,
                                       Map<Integer, List<AccessibilityNode>> siblings) {
        StringBuilder context = new StringBuilder();
        appendContext(context, node.accessibleText());
        appendContext(context, node.resourceId());
        appendContext(context, node.className());
        AccessibilityNode parent = byOrder.get(node.parentOrder());
        if (parent != null) {
            appendContext(context, parent.accessibleText());
            appendContext(context, parent.resourceId());
            appendContext(context, parent.className());
        }
        List<AccessibilityNode> peers = siblings.get(node.parentOrder());
        if (peers != null) {
            int index = peers.indexOf(node);
            for (int offset = Math.max(0, index - 2); offset <= Math.min(peers.size() - 1, index + 2); offset++) {
                if (offset == index) continue;
                AccessibilityNode peer = peers.get(offset);
                appendContext(context, peer.accessibleText());
                appendContext(context, peer.resourceId());
            }
        }
        return context.toString();
    }

    private static void appendContext(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) target.append(' ').append(value);
    }

    private static void walk(AccessibilityNodeInfo node, int parentOrder, int depth,
                             List<AccessibilityNode> output, int maxNodes) {
        if (node == null || output.size() >= maxNodes) return;
        int order = output.size();
        try {
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            CharSequence className = node.getClassName();
            boolean visible = node.isVisibleToUser();
            output.add(new AccessibilityNode(toString(text), toString(description),
                    node.getViewIdResourceName(), className == null ? null : className.toString(),
                    parentOrder, depth, order, visible, node.isSelected(), node.isChecked()));
            if (!visible) return;
            int childCount = node.getChildCount();
            for (int index = 0; index < childCount && output.size() < maxNodes; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                walk(child, order, depth + 1, output, maxNodes);
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
