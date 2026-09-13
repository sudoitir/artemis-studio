package io.github.sudoitir.artemisstudio.kernel.plugin.api;

/**
 * One topic on the cluster event stream (ADR-0018).
 *
 * @param carriesData whether events carry a payload (ADR-0027) rather than a change signal
 */
public record TopicDef(String name, boolean carriesData) {

    public static TopicDef signal(String name) {
        return new TopicDef(name, false);
    }
}
