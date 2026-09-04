package io.github.sudoitir.artemisstudio.service;

/**
 * A destructive message operation whose dry-run count exceeds the server-enforced
 * {@code safety.bulk-cap} (ADR-0021). Mapped to {@code 422} with problem type
 * {@code bulk-cap-exceeded} and properties {@code affectedCount} / {@code cap}.
 * The caller can retry with {@code ?override=true} behind the UI's typed
 * confirmation.
 */
public class BulkCapExceededException extends RuntimeException {

    private final long affectedCount;
    private final long cap;

    public BulkCapExceededException(long affectedCount, long cap) {
        super("This would affect " + affectedCount + " messages, over the safety cap of " + cap
                + ". Confirm to override.");
        this.affectedCount = affectedCount;
        this.cap = cap;
    }

    public long affectedCount() {
        return affectedCount;
    }

    public long cap() {
        return cap;
    }
}
