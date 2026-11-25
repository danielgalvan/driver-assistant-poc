package com.driverassistant.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;

import com.driverassistant.parser.AccessibilityNode;

import java.util.ArrayList;
import java.util.List;

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
                    + " class=" + className + " id=" + id + " textChars=" + textLength
                    + " descriptionChars=" + descriptionLength);
        }
        return result;
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
                    parentOrder, depth, order));
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
