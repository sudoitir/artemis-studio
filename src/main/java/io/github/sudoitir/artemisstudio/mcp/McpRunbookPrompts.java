package io.github.sudoitir.artemisstudio.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

/**
 * Runbooks as prompts (ADR-0045): the order to do things in, which a tool
 * description cannot carry.
 *
 * <p>Every one of these is stateless and orchestrating only — it returns text and
 * calls nothing. That is not an implementation shortcut: a prompt that acted would
 * be a mutation with no {@code confirm} gate and no audit attribution of its own,
 * which is precisely what the safety model forbids. {@code before_you_purge} is the
 * clearest case — it prescribes the sequence and then stops at asking the human,
 * rather than ever prescribing a confirmed run.
 */
@Component
public class McpRunbookPrompts {

    @McpPrompt(name = "triage_cluster", description = "The order to investigate a cluster that looks unhealthy.")
    public McpSchema.GetPromptResult triageCluster(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId) {
        String target = target(clusterId);
        return prompt("Triage runbook", """
                Triage %s in this order, and stop as soon as one step explains the symptom.

                1. cluster_health. A splitBrain of CRITICAL is the whole answer — stop and \
                escalate; two nodes are accepting writes and the journals are diverging. \
                SUSPECTED during a failover is normal for a few seconds.
                2. If alertsVisible is false, this key cannot see alerts. Say so rather than \
                reporting that nothing is firing.
                3. replicationBehind means the backup cannot take over cleanly yet. That is a \
                risk, not an outage.
                4. list_resources kind=queues, sorted by depth. A deep queue with zero consumers \
                is a stuck consumer, not a broker fault.
                5. diagnose_queue on the worst queue. Its trend separates a backlog that is \
                draining from one that is growing.
                6. activity_log source=broker_events for what the broker itself reported around \
                the time the symptom started.

                Report what you found and what you did not check. Do not run any mutating tool \
                as part of triage.""".formatted(target));
    }

    @McpPrompt(
            name = "investigate_queue",
            description = "How to work out why one queue is deep, and what would actually fix it.")
    public McpSchema.GetPromptResult investigateQueue(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId,
            @McpArg(name = "queue", description = "Queue name", required = false) String queue) {
        return prompt("Queue investigation runbook", """
                Investigate %s on %s.

                1. diagnose_queue. Read trend before depth: a deep queue that is draining needs \
                patience, not intervention.
                2. Zero consumers with a growing depth is a consumer-side problem. The broker is \
                working. Fixing it here — by purging — destroys the evidence and the messages.
                3. list_resources kind=consumers filtered to this queue, to see whether consumers \
                are attached but not acknowledging.
                4. browse_messages for a few headers. Check whether they are all one type, one \
                correlation id, or all expired.
                5. Only read a body (message_body) once the headers have narrowed it to a specific \
                message worth looking at.
                6. activity_log source=broker_events filtered to the address, for consumer \
                connect/disconnect churn.

                State the cause before proposing an action. If the cause is consumer-side, say so \
                and stop — no broker-side action fixes it.""".formatted(
                        queue == null || queue.isBlank() ? "the queue" : queue, target(clusterId)));
    }

    @McpPrompt(
            name = "before_you_purge",
            description = "The checks to run before any destructive queue operation, and where to stop.")
    public McpSchema.GetPromptResult beforeYouPurge(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId,
            @McpArg(name = "queue", description = "Queue name", required = false) String queue) {
        return prompt("Pre-purge checklist", """
                Purging discards messages permanently. There is no undo and no recovery from the \
                broker side. Before proposing one on %s:

                1. queue_action with the defaults. dryRun is true unless you set it false, so this \
                returns the count that would be affected and changes nothing.
                2. diagnose_queue. If consumers are attached and the depth is falling, the queue is \
                draining on its own — purging is the wrong action.
                3. browse_messages. Look at what is actually there. Messages that a fixed consumer \
                would process are messages a purge would destroy.
                4. Check whether the queue is a DLQ. Purging a DLQ discards the record of what \
                already failed.
                5. If the dry run reports overCap, the count is above the configured safety cap. \
                That is a signal about scale, not an obstacle to route around.

                Then stop. Report the count, what the messages appear to be, and your \
                recommendation — and ask the operator to confirm. Do not call queue_action with \
                dryRun=false on your own initiative; the confirm argument exists so that a human \
                decision is what unlocks a destructive run, and supplying it yourself defeats it.""".formatted(subject(queue, clusterId)));
    }

    @McpPrompt(
            name = "tune_scrape_load",
            description = "How to decide whether Studio's polling is the load on the broker, and what to change.")
    public McpSchema.GetPromptResult tuneScrapeLoad(
            @McpArg(name = "clusterId", description = "Cluster id", required = false) String clusterId) {
        return prompt("Scrape tuning runbook", """
                Work out whether %s is under load from Studio itself before changing anything.

                1. studio_setting op=get. Read the current scrape intervals, the per-node rate \
                limit and the bulk cap together — they interact.
                2. Studio batches its reads: one management call per node per tier, not one per \
                queue. A cluster with thousands of queues costs the same per tick as a small one, \
                so queue count alone is not evidence.
                3. metric_series on messageCount for the cluster over 6h. If broker load tracks \
                message volume rather than a fixed cadence, Studio is not the cause.
                4. If it is the cause, raise tier-c first — the full queue walk is the expensive \
                one and the least time-sensitive. Leave tier-a alone: it is what detects \
                split-brain, and slowing it delays the one alert that matters most.
                5. Lower the per-node rate limit only if the broker is rejecting calls. It \
                throttles Studio, so it makes every screen slower.

                Say what you would change and what it costs before changing it.""".formatted(target(clusterId)));
    }

    private static String target(String clusterId) {
        return clusterId == null || clusterId.isBlank() ? "the cluster" : "cluster " + clusterId;
    }

    private static String subject(String queue, String clusterId) {
        String q = queue == null || queue.isBlank() ? "the queue" : "queue " + queue;
        return q + " on " + target(clusterId);
    }

    private static McpSchema.GetPromptResult prompt(String description, String text) {
        return new McpSchema.GetPromptResult(
                description,
                List.of(new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text))));
    }
}
