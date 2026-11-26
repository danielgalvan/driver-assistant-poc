package com.driverassistant.accessibility;

import com.driverassistant.parser.AccessibilityNode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AccessibilityTreeReaderTest {
    @Test
    public void joinsNearbyFragmentContextForCandidateDiagnostics() {
        List<AccessibilityNode> tree = List.of(
                new AccessibilityNode(null, null, "offer_card", "android.widget.LinearLayout", -1, 0, 0),
                new AccessibilityNode("Nota", null, null, "android.widget.TextView", 0, 1, 1),
                new AccessibilityNode("4,95", null, null, "android.widget.TextView", 0, 1, 2),
                new AccessibilityNode("R$", null, null, "android.widget.TextView", 0, 1, 3),
                new AccessibilityNode("20,03", null, null, "android.widget.TextView", 0, 1, 4),
                new AccessibilityNode("Aceitar", null, null, "android.widget.Button", 0, 1, 5));

        assertTrue(AccessibilityTreeReader.containsOfferCandidate(tree));
    }

    @Test
    public void recordsRelevantChangedTreeEvenWhenItHasNoExplicitOfferCue() {
        List<AccessibilityNode> tree = List.of(
                AccessibilityNode.textOnly("R$", 0),
                AccessibilityNode.textOnly("20,03", 1));
        assertTrue(AccessibilityTreeReader.containsRelevantDiagnosticText(tree));
        assertFalse(AccessibilityTreeReader.containsOfferCandidate(tree));
    }
}
