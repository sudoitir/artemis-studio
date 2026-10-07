package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * A position in the held-operation events: the writing transaction's id and the event's sequence.
 * Events are returned only once every transaction that could precede them has ended, so reading
 * after a cursor never skips one. Store it in the same transaction as whatever the events caused.
 */
@PluginApi
public record EventCursor(long txid, long seq) implements Comparable<EventCursor> {

    public static final EventCursor START = new EventCursor(0, 0);

    @Override
    public int compareTo(EventCursor other) {
        int byTx = Long.compare(txid, other.txid);
        return byTx != 0 ? byTx : Long.compare(seq, other.seq);
    }
}
