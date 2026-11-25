package com.driverassistant.parser;

/** Framework-independent snapshot of one accessibility element. Text stays transient. */
public final class AccessibilityNode {
    private final String text;
    private final String contentDescription;
    private final String resourceId;
    private final String className;
    private final int parentOrder;
    private final int depth;
    private final int order;
    private final boolean visibleToUser;

    public AccessibilityNode(String text, String contentDescription, String resourceId,
                             String className, int parentOrder, int depth, int order) {
        this(text, contentDescription, resourceId, className, parentOrder, depth, order, true);
    }

    public AccessibilityNode(String text, String contentDescription, String resourceId,
                             String className, int parentOrder, int depth, int order,
                             boolean visibleToUser) {
        this.text = text;
        this.contentDescription = contentDescription;
        this.resourceId = resourceId;
        this.className = className;
        this.parentOrder = parentOrder;
        this.depth = depth;
        this.order = order;
        this.visibleToUser = visibleToUser;
    }

    public static AccessibilityNode textOnly(String text, int order) {
        return new AccessibilityNode(text, null, null, null, -1, 0, order);
    }

    public String text() { return text; }
    public String contentDescription() { return contentDescription; }
    public String resourceId() { return resourceId; }
    public String className() { return className; }
    public int parentOrder() { return parentOrder; }
    public int depth() { return depth; }
    public int order() { return order; }
    public boolean visibleToUser() { return visibleToUser; }

    public String accessibleText() {
        if (!visibleToUser) return "";
        if (text == null || text.isBlank()) return contentDescription == null ? "" : contentDescription.trim();
        if (contentDescription == null || contentDescription.isBlank()
                || text.trim().equalsIgnoreCase(contentDescription.trim())) return text.trim();
        return text.trim() + " " + contentDescription.trim();
    }
}
