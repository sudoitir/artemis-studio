package io.github.sudoitir.artemisstudio.security;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalogue of the permission strings the application checks (ADR-0038). This is
 * a catalogue for discoverability and for {@code GET /api/v1/permissions}, not a
 * closed set — {@code role_permission.action} is free-form data by design, and a
 * role may legally hold a string not listed here.
 */
public final class Permissions {

    public static final String WILDCARD = "*";

    public static final String CLUSTER_READ = "cluster:read";
    public static final String CLUSTER_WRITE = "cluster:write";
    public static final String ENVIRONMENT_READ = "environment:read";
    public static final String ENVIRONMENT_WRITE = "environment:write";
    public static final String MESSAGE_READ = "message:read";
    public static final String MESSAGE_SEND = "message:send";
    public static final String MESSAGE_MOVE = "message:move";
    public static final String MESSAGE_DELETE = "message:delete";
    public static final String QUEUE_PURGE = "queue:purge";
    public static final String ALERT_READ = "alert:read";
    public static final String ALERT_WRITE = "alert:write";
    public static final String SETTINGS_READ = "settings:read";
    public static final String SETTINGS_WRITE = "settings:write";
    public static final String USER_ADMIN = "user:admin";
    public static final String TOKEN_ADMIN = "token:admin";

    /** Permission string -> short human label, for the role editor. */
    public static Map<String, String> catalogue() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(CLUSTER_READ, "View clusters and topology");
        m.put(CLUSTER_WRITE, "Register, rediscover, or remove clusters");
        m.put(ENVIRONMENT_READ, "View environments");
        m.put(ENVIRONMENT_WRITE, "Create, rename, or remove environments");
        m.put(MESSAGE_READ, "Browse messages");
        m.put(MESSAGE_SEND, "Send messages");
        m.put(MESSAGE_MOVE, "Move or retry messages");
        m.put(MESSAGE_DELETE, "Delete or expire messages");
        m.put(QUEUE_PURGE, "Purge queues");
        m.put(ALERT_READ, "View alert rules and firings");
        m.put(ALERT_WRITE, "Create, edit, or delete alert rules and channels");
        m.put(SETTINGS_READ, "View operational settings");
        m.put(SETTINGS_WRITE, "Change operational settings and rotate credentials");
        m.put(USER_ADMIN, "Manage users, roles, and grants");
        m.put(TOKEN_ADMIN, "Manage API tokens");
        return m;
    }

    private Permissions() {}
}
