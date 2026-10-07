package io.github.sudoitir.artemisstudio.feature.brokerconfig.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * The broker configuration comparison API (ADR-0043, ADR-0178). Read-only introspection: no
 * broker state changes and no audit event is written.
 *
 * <p>{@code @Schema} on every component so the generated OpenAPI document (and the
 * frontend's {@code schema.d.ts}) declares requiredness and nullability honestly
 * (ADR-0019): required unless marked {@code nullable = true}.
 */
public final class ConfigViews {

    private ConfigViews() {}

    /**
     * One node in the comparison.
     *
     * @param unavailableKind the classified failure ({@code UNREACHABLE}, {@code UNAUTHORIZED}, …)
     *     of a node that could not be read; such a node is left out of every majority and every
     *     state, so its absent keys never read as missing
     * @param reducedSurface true when the node is not serving and answered with fewer attributes
     *     than a serving node exposes; it contributes only the keys it exposes
     */
    @Schema(description = "One node in a configuration comparison")
    public record ConfigNodeView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean available,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(requiredMode = REQUIRED) boolean reducedSurface,
            @Schema(nullable = true) String unavailableKind,
            @Schema(nullable = true) String unavailableReason) {}

    /**
     * A node's value for one key.
     *
     * @param missing true when the node answered but does not have the key; {@code value} is
     *     then null, never an empty string
     */
    @Schema(description = "One node's value for a configuration key")
    public record ConfigNodeValueView(
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(nullable = true) String value,
            @Schema(requiredMode = REQUIRED) boolean missing) {}

    /** One distinct value of a key that has no majority, with the nodes that hold it. */
    @Schema(description = "A distinct value and the nodes that hold it")
    public record ConfigValueGroupView(
            @Schema(requiredMode = REQUIRED) String value,
            @Schema(requiredMode = REQUIRED) List<ConfigNodeValueView> nodes) {}

    /**
     * One compared key.
     *
     * @param state {@code SAME} | {@code DIFFERENT} | {@code MISSING_ON_SOME}
     * @param stateWord the same thing as a phrase, so the UI never carries state by colour alone
     * @param classification {@code DRIFT} | {@code EXPECTED} | {@code UNCLASSIFIED}
     * @param values each node that counts for this key, in node order
     * @param majority the value more than half of the nodes that return the key hold; null when
     *     there is none
     * @param outliers the nodes whose value differs from the majority, including nodes missing the
     *     key; empty when there is no majority
     * @param valueGroups every distinct value with its nodes when there is no majority
     * @param drift true when the key is classified as drift and its nodes do not all agree
     */
    @Schema(description = "One configuration key, compared across every node")
    public record ConfigKeyView(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(requiredMode = REQUIRED) String state,
            @Schema(requiredMode = REQUIRED) String stateWord,
            @Schema(requiredMode = REQUIRED) String classification,
            @Schema(requiredMode = REQUIRED) boolean drift,
            @Schema(requiredMode = REQUIRED) List<ConfigNodeValueView> values,
            @Schema(nullable = true) String majority,
            @Schema(requiredMode = REQUIRED) List<ConfigNodeValueView> outliers,
            @Schema(requiredMode = REQUIRED) List<ConfigValueGroupView> valueGroups) {}

    @Schema(description = "One section of the comparison")
    public record ConfigSectionView(
            @Schema(requiredMode = REQUIRED) String section,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) List<ConfigKeyView> keys) {}

    /**
     * What the comparison found, counted over the drift class and the expected class only;
     * unclassified differences are listed but never counted.
     *
     * @param driftKeys keys classified as drift on which the nodes do not agree
     * @param driftNodes nodes that differ from the majority on a drift key, or that hold one of
     *     several values on a drift key with no majority
     * @param expectedKeys differences that are correct by design and were set aside
     */
    @Schema(description = "Counts for a configuration comparison")
    public record ConfigSummaryView(
            @Schema(requiredMode = REQUIRED) int driftKeys,
            @Schema(requiredMode = REQUIRED) int driftNodes,
            @Schema(requiredMode = REQUIRED) int expectedKeys) {}

    /** One configuration key and its value on a single node. */
    @Schema(description = "One configuration key on one node")
    public record NodeConfigEntryView(
            @Schema(requiredMode = REQUIRED) String key,
            @Schema(nullable = true) String value,
            @Schema(requiredMode = REQUIRED) String classification) {}

    /** One section of a single node's configuration. */
    @Schema(description = "One section of a node's configuration")
    public record NodeConfigSectionView(
            @Schema(requiredMode = REQUIRED) String section,
            @Schema(requiredMode = REQUIRED) String label,
            @Schema(requiredMode = REQUIRED) List<NodeConfigEntryView> entries) {}

    /**
     * One node's effective broker configuration, read live.
     *
     * <p>This is the settings a node is <em>actually running with</em>, resolved by
     * the broker — not the {@code broker.xml} on disk, which Studio never reads and
     * never writes.
     *
     * @param available false when the node could not be read at all; the sections are
     *     then empty and {@code unavailableReason} says why, rather than an empty
     *     configuration being presented as a fact
     * @param matchesCompared how many address-setting match patterns were resolved
     * @param matchesAvailable how many were known about; when it exceeds
     *     {@code matchesCompared} the cap applied, and {@code note} says so
     */
    @Schema(description = "One node's effective broker configuration")
    public record NodeConfigView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) UUID nodeId,
            @Schema(requiredMode = REQUIRED) String nodeName,
            @Schema(requiredMode = REQUIRED) boolean available,
            @Schema(requiredMode = REQUIRED) boolean active,
            @Schema(nullable = true) String unavailableReason,
            @Schema(requiredMode = REQUIRED) List<NodeConfigSectionView> sections,
            @Schema(requiredMode = REQUIRED) int matchesCompared,
            @Schema(requiredMode = REQUIRED) int matchesAvailable,
            @Schema(nullable = true) String note) {}

    /**
     * @param nodes every node the request covered, available or not, each with its reason when
     *     it could not be read
     * @param comparable false when fewer than two nodes answered; the sections are then empty and
     *     {@code notes} says why
     * @param matchesCompared how many address-setting match patterns were compared
     * @param matchesAvailable how many were known about; when it exceeds
     *     {@code matchesCompared} the cap applied, and {@code notes} says so
     * @param notes limitations stated plainly: the address-setting cap, a passive backup's reduced
     *     surface, a comparison that could not be made
     */
    @Schema(description = "Broker configuration compared across every node of a cluster")
    public record ConfigDiffView(
            @Schema(requiredMode = REQUIRED) UUID clusterId,
            @Schema(requiredMode = REQUIRED) List<ConfigNodeView> nodes,
            @Schema(requiredMode = REQUIRED) boolean comparable,
            @Schema(requiredMode = REQUIRED) List<ConfigSectionView> sections,
            @Schema(requiredMode = REQUIRED) ConfigSummaryView summary,
            @Schema(requiredMode = REQUIRED) int matchesCompared,
            @Schema(requiredMode = REQUIRED) int matchesAvailable,
            @Schema(requiredMode = REQUIRED) List<String> notes) {}
}
