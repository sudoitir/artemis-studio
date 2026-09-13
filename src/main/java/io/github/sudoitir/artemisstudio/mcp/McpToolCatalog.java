package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.service.ConnectionCloseKind;
import io.github.sudoitir.artemisstudio.service.LifecycleKind;
import io.github.sudoitir.artemisstudio.service.MessageAction;
import io.github.sudoitir.artemisstudio.service.ResourceKind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one description of the MCP surface (ADR-0054).
 *
 * <p>Everything the server says about itself is generated from here: the
 * {@code studio_help} tool, the {@code studio://tools} resource, and the
 * {@code instructions} block sent at initialisation. Before this existed the
 * instructions were retyped by hand in {@code application.yml} and had drifted —
 * they omitted {@code queue_lifecycle} and {@code message_body}, so the one text a
 * tool-searching host is guaranteed to read asserted, by omission, that two
 * capabilities did not exist. That is non-negotiable #5 broken by a duplicate, and
 * the fix is to have no duplicate.
 *
 * <p>{@code McpToolCatalogueTest} fails the build when a registered tool is missing
 * from this file, which is the same argument ADR-0045 made about listing size:
 * review does not catch drift, a build failure does.
 *
 * <p>This is documentation, not validation. Every discriminator is still checked in
 * {@link McpArgs} and every rejection names both the accepted values and
 * {@code studio_help}, so a model that reads none of this is inconvenienced, never
 * wrong.
 */
final class McpToolCatalog {

    private McpToolCatalog() {}

    /** The discovery tool's own name, referenced from rejection messages. */
    static final String HELP_TOOL = "studio_help";

    /** Whether a tool reads or changes something — the axis tools may not be grouped across. */
    enum Posture {
        READ,
        MUTATE
    }

    /**
     * @param name the parameter
     * @param values the values it accepts, when it is a discriminator
     * @param shape the JSON body shape, when it takes one
     * @param note anything a model gets wrong without being told
     */
    record Param(String name, List<String> values, String shape, String note) {

        static Param values(String name, List<String> values, String note) {
            return new Param(name, values, null, note);
        }

        static Param shape(String name, String shape, String note) {
            return new Param(name, null, shape, note);
        }

        static Param note(String name, String note) {
            return new Param(name, null, null, note);
        }
    }

    /**
     * @param name the tool name, exactly as registered
     * @param summary one line, for the index — what an operator would call it
     * @param params the detail the schema deliberately leaves out
     */
    record Entry(String name, Posture posture, String summary, List<Param> params) {}

    private static final String DRY_RUN = "Defaults to true. A dry run reports the affected count and changes nothing.";

    private static final String CONFIRM =
            "Required to turn dryRun off on a destructive operation, and must equal the subject's name. "
                    + "This gate exists because the caller is a model: it stops an inferred or hallucinated "
                    + "name from becoming a real destruction.";

    private static final String OVERRIDE =
            "A separate gate from confirm: it answers \"this many messages really is intended\", against the "
                    + "server's bulk cap. Defaults to false and is never satisfied by confirm.";

    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "diagnose",
                    Posture.READ,
                    "Health of a cluster, or of one queue end to end. Start here.",
                    List.of(Param.note(
                            "queue",
                            "Omit for the cluster: HA role per node, who is live, split-brain, replication "
                                    + "lag, clock skew and firing alerts. Give a queue name for that queue: depth "
                                    + "and trend, consumers, paused, slow consumers, DLQ and recent events."))),
            new Entry(
                    "list_resources",
                    Posture.READ,
                    "Merged cross-node listing of one kind of cluster resource.",
                    List.of(Param.values("kind", names(ResourceKind.values()), null))),
            new Entry(
                    "browse_messages",
                    Posture.READ,
                    "Message headers on a queue, or the full body of one message.",
                    List.of(
                            Param.note(
                                    "messageId",
                                    "Omit for a capped page of headers. Give the numeric id from a header row "
                                            + "for that message's full body and properties."),
                            Param.note("filter", "Broker filter expression, e.g. JMSPriority > 5."))),
            new Entry(
                    "metric_series",
                    Posture.READ,
                    "A server-bucketed timeseries for one metric.",
                    List.of(
                            Param.values(
                                    "metric",
                                    List.of("messageCount", "consumerCount", "messagesAdded", "messagesAcked"),
                                    null),
                            Param.note("queue", "Omit for the whole cluster."),
                            Param.note("window", "e.g. 15m, 6h, 2d. Units s, m, h, d. Defaults to 1h."))),
            new Entry(
                    "config_diff",
                    Posture.READ,
                    "Classified configuration differences between two nodes.",
                    List.of(Param.note(
                            "nodeA",
                            "Node ids come from cluster://{clusterId}/topology. Omit both to compare the "
                                    + "cluster's HA pair."))),
            new Entry(
                    "trace_request_reply",
                    Posture.READ,
                    "Request-reply tracing: flows, latency and timeouts, expectations, or why there are none.",
                    List.of(
                            Param.values(
                                    "mode", List.of("flows", "stats", "expectations", "diagnostics"), "Default flows."),
                            Param.note("window", "e.g. 15m. Defaults to 15m."))),
            new Entry(
                    "activity_log",
                    Posture.READ,
                    "Broker events, or Studio's own audit trail.",
                    List.of(Param.values("source", List.of("broker_events", "audit"), "Default broker_events."))),
            new Entry(
                    HELP_TOOL,
                    Posture.READ,
                    "This index, or one tool's accepted values and body shapes.",
                    List.of(Param.note("topic", "A tool name. Omit for the index of every tool."))),
            new Entry(
                    "message_action",
                    Posture.MUTATE,
                    "Move, retry, delete, expire or purge messages on a queue.",
                    List.of(
                            Param.values("action", names(MessageAction.values()), "purge is also accepted."),
                            Param.note(
                                    "messageIds",
                                    "Comma-separated ids from browse_messages, or use filter instead. move "
                                            + "additionally needs targetQueue."),
                            Param.note("dryRun", DRY_RUN),
                            Param.note("confirm", CONFIRM),
                            Param.note("override", OVERRIDE))),
            new Entry(
                    "queue_lifecycle",
                    Posture.MUTATE,
                    "Create, update, pause, resume or destroy a queue, address or divert, cluster-wide.",
                    List.of(
                            Param.values("kind", names(LifecycleKind.values()), null),
                            Param.shape(
                                    "config",
                                    "{ address, routingType (ANYCAST|MULTICAST), durable, filter, maxConsumers, "
                                            + "purgeOnNoConsumers, exclusive, nonDestructive, ringSize, "
                                            + "forwardingAddress, routingName }",
                                    "create_queue needs at least address and routingType. On update_queue only "
                                            + "the fields you send change; address, routingType, name and durable "
                                            + "cannot change on a live queue. create_address reads routingType only. "
                                            + "create_divert needs at least address and forwardingAddress; a divert "
                                            + "created this way persists across broker restarts and will not appear "
                                            + "in the broker's own configuration, and there is no update_divert — "
                                            + "change one by deleting it and creating the replacement."),
                            Param.note(
                                    "kind.fanout",
                                    "A command targets the cluster and fans out to every live node. The result "
                                            + "is a per-node list: a node already in the requested state reports "
                                            + "ALREADY, and one that was not live reports SKIPPED_NOT_LIVE rather "
                                            + "than a failure. Re-running after a partial application converges."),
                            Param.note("dryRun", DRY_RUN),
                            Param.note("confirm", CONFIRM))),
            new Entry(
                    "broker_config",
                    Posture.READ,
                    "A cluster's declared broker configuration, its drift per node, its XML fragment or its applies.",
                    List.of(Param.values(
                            "kind",
                            List.of("declaration", "drift", "xml", "applies"),
                            "Default declaration. drift is the last stored evaluation per node; it "
                                    + "compares every node with the declaration, not nodes with each other "
                                    + "(that is config_diff)."))),
            new Entry(
                    "broker_config_change",
                    Posture.MUTATE,
                    "Declare a cluster's broker configuration, or apply it to every live node canary-first.",
                    List.of(
                            Param.values("op", List.of("declare", "apply"), null),
                            Param.shape(
                                    "document",
                                    "{ version: 1, addresses: [{ name, routingTypes, queues: [{ name, routingType, "
                                            + "filter, durable, maxConsumers, purgeOnNoConsumers, exclusive, "
                                            + "nonDestructive, ringSize }] }], addressSettings: [{ match, "
                                            + "values: { <key>: value } }], securitySettings: [{ match, "
                                            + "permissions: { <permission>: [role] } }], diverts: [{ name, address, "
                                            + "forwardingAddress, filter, exclusive, routingType }] }",
                                    "declare only: the same shape broker_config kind=declaration returns. Keys "
                                            + "come from the address-setting catalogue; an unknown key is refused, "
                                            + "never ignored. Alternatively send xml, a broker.xml <core> fragment; "
                                            + "unsupported elements are listed and not applied. declare saves a "
                                            + "revision and applies nothing."),
                            Param.note(
                                    "op.apply",
                                    "A dry run returns the plan: steps per live node with before and after, the "
                                            + "hazards with their identifiers and class, the canary and planHash. "
                                            + "The real run must send that planHash as expectedPlanHash and every "
                                            + "High hazard id in acknowledge (comma-separated). It applies to the "
                                            + "canary, reads it back, then continues node by node and halts on the "
                                            + "first failure; the rest report NOT_ATTEMPTED, nothing is rolled "
                                            + "back, re-running converges. Studio never destroys a queue or "
                                            + "address this way and removes only settings it applied unless "
                                            + "removeUndeclared=true."),
                            Param.note(
                                    "nodeIds",
                                    "Comma-separated node ids to target; omit for every live node. The canary "
                                            + "is the first targeted live node by name; choosing another is a "
                                            + "UI-only option."),
                            Param.note("dryRun", DRY_RUN),
                            Param.note("confirm", "Required to turn dryRun off; must equal the cluster's name."),
                            Param.note("override", OVERRIDE))),
            new Entry(
                    "connection_action",
                    Posture.MUTATE,
                    "Close a connection, a session, a consumer's connection, or an address's consumers.",
                    List.of(
                            Param.values("kind", names(ConnectionCloseKind.values()), null),
                            Param.note(
                                    "target",
                                    "The connection, session or consumer id from list_resources, or the address "
                                            + "for address_consumers. Ids are node-local: nodeId is required for "
                                            + "every kind but address_consumers, and comes from the same row."),
                            Param.note(
                                    "kind.ephemeral",
                                    "A close is not idempotent and is never retried. A target that has already "
                                            + "gone answers alreadyGone=true, which is a success — the requested "
                                            + "state holds. Do not re-issue it: the identifier may since have been "
                                            + "reissued to a different application."),
                            Param.note("dryRun", DRY_RUN),
                            Param.note(
                                    "confirm",
                                    "The client id or remote address the preview reported, or the address for "
                                            + "address_consumers — never the opaque connection id. Preview first "
                                            + "to learn it."),
                            Param.note("override", OVERRIDE))),
            new Entry(
                    "send_message",
                    Posture.MUTATE,
                    "Enqueue one message. Adds, never removes.",
                    List.of(
                            Param.values("type", List.of("3 (text)", "4 (bytes)"), "Default 3."),
                            Param.note("durable", "Default true."),
                            Param.note("dryRun", DRY_RUN))),
            new Entry(
                    "alert_rule",
                    Posture.MUTATE,
                    "List, create, update or delete alert rules.",
                    List.of(
                            Param.values("op", List.of("list", "create", "update", "delete"), "Default list."),
                            Param.shape(
                                    "rule",
                                    "{ name, kind, metric, comparator, threshold, stateCondition, forSeconds, "
                                            + "severity, scope, enabled }",
                                    "Required for create; a partial body is merged on update."),
                            Param.note("confirm", "The rule name, to delete."))),
            new Entry(
                    "studio_setting",
                    Posture.MUTATE,
                    "Read or change an operational setting: scrape cadence, rate limit, retention, bulk cap.",
                    List.of(
                            Param.values("op", List.of("get", "set"), "Default get."),
                            Param.note("key", "Omit on get for every setting."))));

    static List<Entry> entries() {
        return ENTRIES;
    }

    static List<String> toolNames() {
        return ENTRIES.stream().map(Entry::name).toList();
    }

    /** One tool's detail, or empty when the topic names nothing registered. */
    static Entry find(String tool) {
        return ENTRIES.stream()
                .filter(e -> e.name().equalsIgnoreCase(tool))
                .findFirst()
                .orElse(null);
    }

    /**
     * The compact index: what exists, grouped by posture, without any parameter
     * detail. This is what a model reads to choose a tool; it reads {@link #find}
     * only once it has chosen.
     */
    static Map<String, List<Map<String, String>>> index() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Posture posture : Posture.values()) {
            out.put(
                    posture == Posture.READ ? "read" : "mutate",
                    ENTRIES.stream()
                            .filter(e -> e.posture() == posture)
                            .map(e -> Map.of("tool", e.name(), "summary", e.summary()))
                            .toList());
        }
        return out;
    }

    /**
     * The {@code instructions} sent at initialisation, generated rather than
     * retyped. Under a host that searches tools instead of dumping {@code
     * tools/list}, this is the only text guaranteed to be read, so it names every
     * tool and where the detail is.
     */
    static String instructions() {
        String reads = ENTRIES.stream()
                .filter(e -> e.posture() == Posture.READ)
                .map(Entry::name)
                .collect(Collectors.joining(", "));
        String mutations = ENTRIES.stream()
                .filter(e -> e.posture() == Posture.MUTATE)
                .map(Entry::name)
                .collect(Collectors.joining(", "));
        return "Artemis Studio: cluster-wide management and observability for Apache ActiveMQ Artemis brokers. "
                + "Read tools: " + reads + ". "
                + "Mutating tools: " + mutations + " — all default to dryRun=true, and a real destructive run "
                + "needs `confirm` to equal the subject's name. "
                + "Call " + HELP_TOOL + " for the accepted values and JSON body shapes the tool schemas leave "
                + "out; the schemas are deliberately terse and " + HELP_TOOL + " is the complete reference. "
                + "Resources mirror the same detail for hosts that read them: studio://clusters (the source of "
                + "cluster ids this key can see), studio://permissions (what it may do), studio://tools, "
                + "cluster://{id}/topology, cluster://{id}/capabilities, "
                + "cluster://{id}/nodes/{nodeId}/settings. "
                + "Start from studio://clusters or " + HELP_TOOL + ".";
    }

    private static List<String> names(Enum<?>[] values) {
        return Stream.of(values).map(e -> e.name().toLowerCase(Locale.ROOT)).toList();
    }
}
