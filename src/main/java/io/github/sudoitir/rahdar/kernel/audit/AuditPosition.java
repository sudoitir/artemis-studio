package io.github.sudoitir.rahdar.kernel.audit;

import io.github.sudoitir.rahdar.kernel.plugin.PluginApi;

/**
 * A record's place in commit order: the writing transaction's id and a sequence value. Records are
 * returned only once every transaction that could precede them has ended, so reading after a
 * position never skips one. A record that changes (a pending one given its outcome) takes a later
 * position. Store the last position in the same transaction as whatever the records caused.
 */
@PluginApi
public record AuditPosition(long txid, long seq) implements Comparable<AuditPosition> {

    public static final AuditPosition START = new AuditPosition(0, 0);

    @Override
    public int compareTo(AuditPosition other) {
        int byTx = Long.compare(txid, other.txid);
        return byTx != 0 ? byTx : Long.compare(seq, other.seq);
    }
}
