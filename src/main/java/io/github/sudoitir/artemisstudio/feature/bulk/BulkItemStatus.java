package io.github.sudoitir.artemisstudio.feature.bulk;

/** One queue's place in a bulk run. */
public enum BulkItemStatus {
    PENDING,
    /** Refused at preview, with the reason; never acted on. */
    REFUSED,
    RUNNING,
    SUCCEEDED,
    /** Applied on some nodes and failed on others. */
    PARTIAL,
    FAILED,
    /** Not acted on because an earlier queue failed and the run does not continue past failures. */
    SKIPPED,
    /** Not acted on because the operator stopped the run, or Studio stopped before reaching it. */
    CANCELLED,
    /** In flight when Studio stopped: what the broker did is not known. */
    UNKNOWN;

    /** Counted as succeeded, failed or skipped in the run's progress. */
    public boolean succeeded() {
        return this == SUCCEEDED;
    }

    public boolean failed() {
        return this == FAILED || this == PARTIAL || this == UNKNOWN;
    }

    public boolean skipped() {
        return this == REFUSED || this == SKIPPED || this == CANCELLED;
    }
}
