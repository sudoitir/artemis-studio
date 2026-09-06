package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.security.Permissions;

/**
 * The lifecycle operations Studio exposes (ADR-0049). One enum shared by the HTTP
 * API and the single MCP {@code queue_lifecycle} tool, so a kind cannot be
 * reachable through one and not the other.
 *
 * <p>Holding a message-level permission implies none of these. Purging a queue and
 * destroying one are different authorities: one empties a resource the operator
 * keeps, the other removes the resource itself.
 */
public enum LifecycleKind {
    CREATE_QUEUE("CREATE_QUEUE", "QUEUE", Permissions.QUEUE_CREATE, false),
    UPDATE_QUEUE("UPDATE_QUEUE", "QUEUE", Permissions.QUEUE_UPDATE, false),
    DELETE_QUEUE("DELETE_QUEUE", "QUEUE", Permissions.QUEUE_DELETE, true),
    PAUSE_QUEUE("PAUSE_QUEUE", "QUEUE", Permissions.QUEUE_PAUSE, false),
    RESUME_QUEUE("RESUME_QUEUE", "QUEUE", Permissions.QUEUE_PAUSE, false),
    RESET_QUEUE_COUNTER("RESET_QUEUE_COUNTER", "QUEUE", Permissions.QUEUE_UPDATE, false),
    CREATE_ADDRESS("CREATE_ADDRESS", "ADDRESS", Permissions.QUEUE_CREATE, false),
    DELETE_ADDRESS("DELETE_ADDRESS", "ADDRESS", Permissions.QUEUE_DELETE, true);

    private final String auditName;
    private final String targetType;
    private final String permission;
    private final boolean destructive;

    LifecycleKind(String auditName, String targetType, String permission, boolean destructive) {
        this.auditName = auditName;
        this.targetType = targetType;
        this.permission = permission;
        this.destructive = destructive;
    }

    public String auditName() {
        return auditName;
    }

    public String targetType() {
        return targetType;
    }

    public String permission() {
        return permission;
    }

    /**
     * Whether this kind destroys something that cannot be restored. Destructive
     * kinds require a typed confirmation in the UI and a matching {@code confirm}
     * argument over MCP, and a queue delete additionally goes through the bulk cap.
     */
    public boolean destructive() {
        return destructive;
    }

    /** Whether re-running this kind leaves the cluster in the same state. */
    public boolean idempotent() {
        return this != RESET_QUEUE_COUNTER;
    }

    public boolean isAddressKind() {
        return this == CREATE_ADDRESS || this == DELETE_ADDRESS;
    }

    /** Parse a path segment such as {@code create-queue}. */
    public static LifecycleKind fromPath(String path) {
        String needle = path == null ? "" : path.replace('-', '_').toUpperCase();
        for (LifecycleKind kind : values()) {
            if (kind.name().equals(needle)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown lifecycle operation '" + path + "'.");
    }
}
