package io.github.sudoitir.artemisstudio.service;

/** The four id/selector message operations (ADR-0020). */
public enum MessageAction {
    MOVE("MOVE_MESSAGES"),
    RETRY("RETRY_MESSAGES"),
    DELETE("DELETE_MESSAGES"),
    EXPIRE("EXPIRE_MESSAGES");

    private final String auditName;

    MessageAction(String auditName) {
        this.auditName = auditName;
    }

    public String auditName() {
        return auditName;
    }

    public static MessageAction fromPath(String raw) {
        try {
            return valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown message action: " + raw);
        }
    }
}
