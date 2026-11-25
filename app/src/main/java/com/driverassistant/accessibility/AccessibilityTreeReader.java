package com.driverassistant.accessibility;

import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Lê somente texto e descrições acessíveis; não persiste a árvore capturada. */
public final class AccessibilityTreeReader {
    private static final int MAX_NODES = 350;

    public List<String> read(AccessibilityNodeInfo root) {
        List<String> text = new ArrayList<>();
        walk(root, text, new int[]{0});
        return text;
    }

    private static void walk(AccessibilityNodeInfo node, List<String> output, int[] visited) {
        if (node == null) return;
        try {
            visited[0]++;
            appendIfPresent(output, node.getText());
            appendIfPresent(output, node.getContentDescription());
            if (visited[0] >= MAX_NODES) return;
            for (int index = 0; index < node.getChildCount() && visited[0] < MAX_NODES; index++) {
                walk(node.getChild(index), output, visited);
            }
        } finally {
            node.recycle();
        }
    }

    private static void appendIfPresent(List<String> output, CharSequence value) {
        if (value != null && !value.toString().trim().isEmpty()) output.add(value.toString());
    }
}
