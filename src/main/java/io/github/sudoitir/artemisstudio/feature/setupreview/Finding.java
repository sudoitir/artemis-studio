package io.github.sudoitir.artemisstudio.feature.setupreview;

import java.util.List;

/**
 * One mistake the review found (ADR-0106), in the words the screen shows.
 *
 * @param subject {@code cluster}, or {@code node:<id>} for a node-scoped code
 * @param title what is wrong, in one line
 * @param impact what it costs, in operator terms
 * @param evidence what each node reported that led here
 * @param recommendation what to do
 * @param snippet the {@code broker.xml} that fixes it, or null where there is no single fragment
 * @param caveats what the review could not see that bears on this finding
 * @param appliable the fix is an address setting that Broker configuration can apply
 */
public record Finding(
        String code,
        Category category,
        Severity severity,
        String subject,
        String title,
        String impact,
        List<Evidence> evidence,
        String recommendation,
        String snippet,
        List<String> caveats,
        boolean appliable) {

    public Finding {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        caveats = caveats == null ? List.of() : List.copyOf(caveats);
    }

    /** One observed value. {@code node} is the node's name, or null for a cluster-wide fact. */
    public record Evidence(String node, String key, String value) {}
}
