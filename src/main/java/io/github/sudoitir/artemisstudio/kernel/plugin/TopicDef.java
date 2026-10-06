package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * One topic on the cluster event stream (ADR-0018).
 *
 * <p>A topic declares the permission it needs. A subscriber who holds it on the cluster, through a grant,
 * receives every event of the topic. Anyone else receives an event only when it names a queue or address
 * they may read, cut to those; an event about the cluster as a whole is not for them.
 *
 * @param carriesData whether events carry a payload (ADR-0027) rather than a change signal
 * @param permission the permission that lets a subscriber see every event of the topic; {@code null}
 *     is the cluster's read permission
 */
public record TopicDef(String name, boolean carriesData, String permission) {

    /** A change signal that needs the cluster's read permission. */
    public static TopicDef signal(String name) {
        return signal(name, null);
    }

    public static TopicDef signal(String name, String permission) {
        return new TopicDef(name, false, permission);
    }

    public static TopicDef data(String name, String permission) {
        return new TopicDef(name, true, permission);
    }
}
