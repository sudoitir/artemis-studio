package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.platform.broker.OutboundMessages.Outbound;
import io.github.sudoitir.artemisstudio.platform.broker.OutboundMessages.Provenance;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.activemq.artemis.api.core.ActiveMQAddressFullException;
import org.apache.activemq.artemis.api.core.ActiveMQDuplicateIdException;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;

/**
 * One run's relay from a queue on one node to a queue on another (ADR-0097, transfer design D1 and
 * D2), one batch at a time. It owns a transacted session on each node, so the Core client types
 * stay in this module and a feature drives the relay only through {@link #relay}.
 *
 * <p>A batch is received, each message rebuilt ({@link OutboundMessages}) as it arrives, sent to the
 * target queue's FQQN and
 * committed on the target; only then is it acknowledged on the source. A <em>staged</em> link
 * receives destructively from a staging queue; a <em>browse</em> link reads the source queue and
 * leaves it as it is, and the caller keeps its own record of what it copied.
 *
 * <p>When the target refuses a commit because one of the batch's duplicate ids arrived before (a
 * batch repeated after Studio stopped between the two commits), it delivers none of the batch. The
 * link rolls both sides back and relays the next batch's worth of messages one per transaction, where
 * a refusal means only that one message had already arrived.
 *
 * <p>Not thread-safe: one run drives it.
 */
public final class RelayLink implements AutoCloseable {

    private static final long FIRST_WAIT_MS = 1000;
    private static final long NEXT_WAIT_MS = 200;

    /** What the caller decides around a batch: which messages it wants, and what happens once the target has them. */
    public interface Hooks {

        /** Whether this source message is part of the selection. */
        default boolean selected(long sourceId) {
            return true;
        }

        /** The ids among {@code sourceIds} already relayed by an earlier attempt, to skip. */
        default Set<Long> alreadyRelayed(List<Long> sourceIds) {
            return Set.of();
        }

        /**
         * These source messages are about to be committed on the target. Once the commit is sent,
         * Studio may not learn whether it succeeded, so a caller that must know records them here.
         */
        default void beforeTargetCommit(List<Long> sourceIds) {}

        /**
         * The target has committed these source messages and the source has not yet acknowledged
         * them. Throwing leaves them unacknowledged on the source.
         */
        default void afterTargetCommit(List<Long> sourceIds) {}
    }

    public enum Outcome {
        /** Messages were relayed, or skipped as already relayed. */
        RELAYED,
        /** The source gave no message within the wait. */
        EMPTY,
        /** The target refused the batch for want of room; nothing of it was delivered or acknowledged. */
        ADDRESS_FULL
    }

    /**
     * @param delivered messages the target committed in this batch
     * @param duplicates messages the target refused as already delivered, now acknowledged
     * @param bytes body bytes of the delivered messages
     * @param sourceIds the source ids of the delivered and duplicate messages
     */
    public record Batch(Outcome outcome, int delivered, int duplicates, long bytes, List<Long> sourceIds) {}

    /**
     * What to relay: from {@code sourceQueue} (a staging queue when {@code staged}, else the source
     * queue browsed with {@code browseFilter}, which may be null) to {@code targetQueue} on {@code
     * targetAddress}, with {@code provenance} naming where the messages came from.
     */
    public record Route(
            String sourceQueue,
            boolean staged,
            String browseFilter,
            String targetAddress,
            String targetQueue,
            Provenance provenance) {}

    private final CoreRelay.Session source;
    private final CoreRelay.Session target;
    private final Route route;
    private ClientConsumer consumer;
    /** Messages still to be relayed one per transaction after a duplicate refusal. */
    private int single;

    RelayLink(CoreRelay.Session source, CoreRelay.Session target, Route route) {
        this.source = source;
        this.target = target;
        this.route = route;
    }

    /**
     * Relay at most {@code max} selected messages.
     *
     * @throws BrokerConnectionException when either node fails mid-batch: nothing of the batch was
     *     acknowledged on the source, and the caller closes this link
     * @throws IOException when a large message cannot be spooled, for want of disk
     */
    public Batch relay(int max, Hooks hooks) throws IOException {
        List<Received> received = receive(max, hooks);
        try {
            if (received.isEmpty()) {
                return new Batch(Outcome.EMPTY, 0, 0, 0, List.of());
            }
            List<Long> ids =
                    received.stream().map(r -> r.message().getMessageID()).toList();
            Set<Long> skip = hooks.alreadyRelayed(ids);
            List<Received> fresh = new ArrayList<>();
            for (Received r : received) {
                if (skip.contains(r.message().getMessageID())) {
                    acknowledge(r.message());
                } else {
                    fresh.add(r);
                }
            }
            if (fresh.isEmpty()) {
                commitSource();
                return new Batch(Outcome.RELAYED, 0, 0, 0, List.of());
            }
            return single > 0 ? oneByOne(fresh, hooks) : together(fresh, hooks);
        } catch (ActiveMQException e) {
            rollbackQuietly();
            throw failed(e);
        } finally {
            received.forEach(r -> r.outbound().close());
        }
    }

    /** The whole batch in one target transaction. */
    private Batch together(List<Received> messages, Hooks hooks) throws ActiveMQException {
        long bytes = 0;
        try {
            for (Received r : messages) {
                bytes += OutboundMessages.size(r.message());
                target.send(
                        route.targetAddress(), route.targetQueue(), r.outbound().message());
            }
            hooks.beforeTargetCommit(
                    messages.stream().map(r -> r.message().getMessageID()).toList());
            target.commit();
        } catch (ActiveMQException e) {
            rollbackQuietly();
            if (duplicate(e)) {
                // Some of these arrived before: find which, one message per transaction.
                single = Math.max(messages.size(), 1);
                return new Batch(Outcome.RELAYED, 0, 0, 0, List.of());
            }
            if (full(e)) {
                return new Batch(Outcome.ADDRESS_FULL, 0, 0, 0, List.of());
            }
            throw e;
        }
        List<Long> ids = messages.stream().map(r -> r.message().getMessageID()).toList();
        hooks.afterTargetCommit(ids);
        for (Received r : messages) {
            acknowledge(r.message());
        }
        commitSource();
        return new Batch(Outcome.RELAYED, messages.size(), 0, bytes, ids);
    }

    /** Each message in its own target transaction, after a duplicate refusal. */
    private Batch oneByOne(List<Received> messages, Hooks hooks) throws ActiveMQException {
        int delivered = 0;
        int duplicates = 0;
        long bytes = 0;
        boolean full = false;
        List<ClientMessage> arrived = new ArrayList<>();
        for (Received r : messages) {
            ClientMessage m = r.message();
            try {
                target.send(
                        route.targetAddress(), route.targetQueue(), r.outbound().message());
                hooks.beforeTargetCommit(List.of(m.getMessageID()));
                target.commit();
                delivered++;
                bytes += OutboundMessages.size(m);
            } catch (ActiveMQException e) {
                target.rollback();
                if (full(e)) {
                    full = true;
                    break;
                }
                if (!duplicate(e)) {
                    throw e;
                }
                duplicates++;
            }
            arrived.add(m);
            single--;
        }
        List<Long> ids = arrived.stream().map(ClientMessage::getMessageID).toList();
        if (!ids.isEmpty()) {
            hooks.afterTargetCommit(ids);
        }
        // Acknowledge exactly the messages the target holds; the rest go back to the source.
        for (ClientMessage m : arrived) {
            acknowledge(m);
        }
        commitSource();
        if (full) {
            giveBack();
        }
        return new Batch(
                full && ids.isEmpty() ? Outcome.ADDRESS_FULL : Outcome.RELAYED, delivered, duplicates, bytes, ids);
    }

    /** A received message and what will be sent for it. */
    private record Received(ClientMessage message, Outbound outbound) {}

    /**
     * Receive up to {@code max} selected messages, building each one's outbound as it arrives: a large
     * message's body streams in behind it and is cut off by the next receive, so it is spooled first.
     */
    private List<Received> receive(int max, Hooks hooks) throws IOException {
        List<Received> out = new ArrayList<>();
        try {
            if (consumer == null) {
                reopen();
            }
            while (out.size() < max) {
                ClientMessage m = consumer.receive(out.isEmpty() ? FIRST_WAIT_MS : NEXT_WAIT_MS);
                if (m == null) {
                    break;
                }
                if (!hooks.selected(m.getMessageID())) {
                    continue;
                }
                out.add(new Received(m, OutboundMessages.from(m, route.provenance())));
            }
            return out;
        } catch (ActiveMQException e) {
            out.forEach(r -> r.outbound().close());
            rollbackQuietly();
            throw failed(e);
        } catch (IOException | RuntimeException e) {
            out.forEach(r -> r.outbound().close());
            rollbackQuietly();
            throw e;
        }
    }

    /** A new consumer: after a rollback a browse starts again from the head of the queue. */
    private void reopen() throws ActiveMQException {
        if (consumer != null) {
            consumer.close();
        }
        consumer = route.staged()
                ? source.receiver(route.sourceQueue())
                : source.browser(route.sourceQueue(), route.browseFilter());
    }

    private void acknowledge(ClientMessage m) throws ActiveMQException {
        if (route.staged()) {
            source.acknowledge(m);
        }
    }

    private void commitSource() throws ActiveMQException {
        if (route.staged()) {
            source.commit();
        }
    }

    /** Return the received but unacknowledged messages: to the staging queue, or to a new browse. */
    private void giveBack() throws ActiveMQException {
        if (route.staged()) {
            source.rollback();
        } else {
            reopen();
        }
    }

    /** Roll both sides back; a browse starts again from the head, so nothing it read is lost. */
    private void rollbackQuietly() {
        try {
            target.rollback();
        } catch (ActiveMQException | RuntimeException e) {
            // The session is going away; the broker rolls it back when it closes.
        }
        try {
            giveBack();
        } catch (ActiveMQException | RuntimeException e) {
            // As above.
        }
    }

    private static boolean duplicate(ActiveMQException e) {
        return e instanceof ActiveMQDuplicateIdException || e.getType() == ActiveMQExceptionType.DUPLICATE_ID_REJECTED;
    }

    private static boolean full(ActiveMQException e) {
        return e instanceof ActiveMQAddressFullException || e.getType() == ActiveMQExceptionType.ADDRESS_FULL;
    }

    private static BrokerConnectionException failed(ActiveMQException e) {
        return new BrokerConnectionException(
                BrokerConnectionException.Kind.UNREACHABLE, "The relay failed: " + e.getMessage(), e);
    }

    @Override
    public void close() {
        try {
            if (consumer != null) {
                consumer.close();
            }
        } catch (ActiveMQException | RuntimeException e) {
            // Closing the session below closes it too.
        }
        source.close();
        target.close();
    }
}
