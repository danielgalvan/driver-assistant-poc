package com.driverassistant.lifecycle;

import com.driverassistant.parser.AccessibilityNode;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Detects the selected Uber Driver tab without depending on Android framework classes. */
public final class UberPageContextDetector {
    public enum Page { HOME, OTHER, UNKNOWN }

    private static final String[] HOME_LABELS = {"pagina inicial", "inicio", "home"};
    private static final String[] OTHER_PAGE_LABELS = {
            "descubra", "discover", "ganhos", "caixa de entrada", "mensagens", "menu"
    };

    public Page detect(List<AccessibilityNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return Page.UNKNOWN;
        boolean selectedHome = false;
        boolean selectedOther = false;
        for (AccessibilityNode node : nodes) {
            if (node == null || !node.visibleToUser()) continue;
            boolean selected = isSelected(node, nodes);
            if (!selected) continue;
            if (matchesPage(node, HOME_LABELS, "home")) selectedHome = true;
            else if (matchesPage(node, OTHER_PAGE_LABELS, "discover", "earnings", "inbox", "menu")) {
                selectedOther = true;
            }
        }
        if (selectedHome == selectedOther) return Page.UNKNOWN;
        return selectedHome ? Page.HOME : Page.OTHER;
    }

    private static boolean isSelected(AccessibilityNode node, List<AccessibilityNode> nodes) {
        AccessibilityNode current = node;
        for (int depth = 0; current != null && depth <= 3; depth++) {
            if (current.selected() || current.checked() || selectionInText(current.text())
                    || selectionInText(current.contentDescription())) return true;
            int parentOrder = current.parentOrder();
            if (parentOrder < 0 || parentOrder >= nodes.size()) break;
            AccessibilityNode parent = nodes.get(parentOrder);
            if (parent == current) break;
            current = parent;
        }
        return false;
    }

    private static boolean selectionInText(String value) {
        String normalized = normalize(value);
        return normalized.contains("selecionado") || normalized.contains("selecionada")
                || normalized.contains("selected") || normalized.contains("aba ativa")
                || normalized.contains("active tab");
    }

    private static boolean matchesPage(AccessibilityNode node, String[] labels, String... resourceCues) {
        String text = normalize(node.text());
        String description = normalize(node.contentDescription());
        String id = normalize(node.resourceId());
        for (String label : labels) {
            String expected = normalize(label);
            if (containsLabel(text, expected) || containsLabel(description, expected)) return true;
        }
        if (id.contains("navigation") || id.contains("bottom") || id.contains("tab")) {
            for (String cue : resourceCues) if (id.contains(normalize(cue))) return true;
        }
        return false;
    }

    private static boolean containsLabel(String value, String label) {
        if (value.isEmpty()) return false;
        return value.equals(label) || value.startsWith(label + " ") || value.endsWith(" " + label)
                || value.contains(" " + label + " ");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }
}
