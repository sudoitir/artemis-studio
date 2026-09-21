package io.github.sudoitir.artemisstudio.feature.bulk;

/** Where a bulk run is. The last five are terminal. */
public enum BulkRunStatus {
    PREVIEWED,
    RUNNING,
    /** Every queue succeeded. */
    SUCCEEDED,
    /** Some queues succeeded and some did not. */
    PARTIAL,
    /** No queue succeeded. */
    FAILED,
    /** The operator stopped it. */
    STOPPED,
    /** Studio stopped while it ran. Never resumed. */
    INTERRUPTED
}
