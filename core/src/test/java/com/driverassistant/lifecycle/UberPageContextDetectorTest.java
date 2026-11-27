package com.driverassistant.lifecycle;

import com.driverassistant.parser.AccessibilityNode;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class UberPageContextDetectorTest {
    private final UberPageContextDetector detector = new UberPageContextDetector();

    @Test public void detectsSelectedHomeTabFromAccessibilityState() {
        List<AccessibilityNode> nodes = List.of(
                node("Página inicial", null, "navigation/home", -1, 0, true),
                node("Descubra", null, "navigation/discover", -1, 1, false));

        assertEquals(UberPageContextDetector.Page.HOME, detector.detect(nodes));
    }

    @Test public void detectsSelectedDiscoverTabAndRejectsHomeOffers() {
        List<AccessibilityNode> nodes = List.of(
                node("Página inicial", null, "navigation/home", -1, 0, false),
                node("Descubra", null, "navigation/discover", -1, 1, true));

        assertEquals(UberPageContextDetector.Page.OTHER, detector.detect(nodes));
    }

    @Test public void readsSelectionFromTabParent() {
        List<AccessibilityNode> nodes = List.of(
                node(null, null, "navigation/discover", -1, 0, true),
                node("Descubra", null, null, 0, 1, false));

        assertEquals(UberPageContextDetector.Page.OTHER, detector.detect(nodes));
    }

    @Test public void remainsUnknownWhenSelectedTabCannotBeConfirmed() {
        List<AccessibilityNode> nodes = List.of(
                node("Página inicial", null, "navigation/home", -1, 0, false),
                node("Descubra", null, "navigation/discover", -1, 1, false));

        assertEquals(UberPageContextDetector.Page.UNKNOWN, detector.detect(nodes));
    }

    @Test public void conflictingSelectedTabsRemainUnknown() {
        List<AccessibilityNode> nodes = List.of(
                node("Página inicial", null, "navigation/home", -1, 0, true),
                node("Descubra", null, "navigation/discover", -1, 1, true));

        assertEquals(UberPageContextDetector.Page.UNKNOWN, detector.detect(nodes));
    }

    private static AccessibilityNode node(String text, String description, String resourceId,
                                          int parent, int order, boolean selected) {
        return new AccessibilityNode(text, description, resourceId, "android.widget.Button",
                parent, parent < 0 ? 0 : 1, order, true, selected, false);
    }
}
