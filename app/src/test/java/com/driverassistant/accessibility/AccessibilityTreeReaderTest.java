package com.driverassistant.accessibility;

import com.driverassistant.parser.AccessibilityNode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void preservesObservedUberOfferBrowseResourceIdsWithoutTreatingThemAsAnOffer() {
        List<AccessibilityNode> tree = List.of(
                new AccessibilityNode(null, null,
                        "com.ubercab.driver:id/ub_driver_offers_browse_pill_container",
                        "android.view.ViewGroup", -1, 0, 0, true),
                new AccessibilityNode("Oportunidades", null,
                        "com.ubercab.driver:id/ub__driver_job_offers_pill_title",
                        "android.widget.TextView", 0, 1, 1, true),
                new AccessibilityNode("2", null,
                        "com.ubercab.driver:id/ub__driver_job_offers_pill_badge",
                        "android.widget.TextView", 0, 1, 2, true));

        assertTrue(AccessibilityTreeReader.containsOfferStructureSignal(tree));
        assertFalse(AccessibilityTreeReader.containsOfferCandidate(tree));
        assertEquals("Oportunidades", com.driverassistant.diagnostics.DiagnosticTextSanitizer
                .sanitizeRelevantFragment("Oportunidades", ""));
        assertEquals("2", com.driverassistant.diagnostics.DiagnosticTextSanitizer
                .sanitizeRelevantFragment("2", tree.get(2).resourceId()));
    }
}
