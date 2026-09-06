package io.github.sudoitir.artemisstudio.broker;

/**
 * A management operation the broker refused for a reason that is not a connection
 * failure. Telling these apart is what {@code managementWrite} honesty depends on
 * (ADR-0049 D5): an {@link Kind#AUTHORIZATION} refusal is evidence the connection
 * cannot write, and every other kind is evidence about the <em>request</em>, which
 * must never disable the capability.
 *
 * <p>The codes are the ones Artemis 2.44 actually returns, measured over Jolokia
 * during this change's groundwork and recorded in ADR-0049 / the change's
 * {@code design.md}. Authorization is <em>not</em> represented here: on the
 * console's Jolokia endpoint it arrives as HTTP 401/403 and is already classified
 * as {@link BrokerConnectionException.Kind#UNAUTHORIZED} by the client.
 */
public class ManagementRefusal extends RuntimeException {

    public enum Kind {
        /**
         * The cluster is already in the requested state — the queue exists, or the
         * thing being removed is already gone. A success, not a failure (D2).
         */
        ALREADY,
        /**
         * The broker rejected an argument: an invalid filter, or a field that cannot
         * change on a live queue. The operator's input is wrong, the connection is fine.
         */
        ARGUMENT,
        /** An address still has queues bound to it (D8). The refusal names them. */
        BOUND_QUEUES
    }

    private final Kind kind;

    public ManagementRefusal(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    // ---- Artemis error codes, measured on 2.44.0 -----------------------

    /** Queue already exists on the address. */
    static final String QUEUE_EXISTS = "AMQ229019";
    /** Queue does not exist. */
    static final String QUEUE_ABSENT = "AMQ229017";
    /** Address does not exist. */
    static final String ADDRESS_ABSENT = "AMQ229203";
    /** Address already exists. */
    static final String ADDRESS_EXISTS = "AMQ229204";
    /** Address still has bindings — the force-free delete refuses. */
    static final String ADDRESS_HAS_BINDINGS = "AMQ229205";
    /** Routing type cannot be changed on a live queue. */
    static final String ROUTING_TYPE_IMMUTABLE = "AMQ229211";
    /** Invalid message filter. */
    static final String INVALID_FILTER = "AMQ229020";

    /**
     * Classify a failed Jolokia response, or return {@code null} when the error is
     * not one of the known management refusals and should be treated as a
     * connection-level failure instead.
     */
    static ManagementRefusal classify(String error, String operation) {
        if (error == null) {
            return null;
        }
        if (error.contains(QUEUE_ABSENT) || error.contains(ADDRESS_ABSENT)) {
            return new ManagementRefusal(Kind.ALREADY, "Already absent: " + error);
        }
        if (error.contains(QUEUE_EXISTS) || error.contains(ADDRESS_EXISTS)) {
            return new ManagementRefusal(Kind.ALREADY, "Already present: " + error);
        }
        if (error.contains(ADDRESS_HAS_BINDINGS)) {
            return new ManagementRefusal(Kind.BOUND_QUEUES, error);
        }
        if (error.contains(ROUTING_TYPE_IMMUTABLE)) {
            return new ManagementRefusal(
                    Kind.ARGUMENT,
                    "A queue's routing type cannot be changed on a live queue. " + "Recreate the queue instead.");
        }
        if (error.contains(INVALID_FILTER)) {
            return new ManagementRefusal(Kind.ARGUMENT, "Invalid message filter.");
        }
        return null;
    }
}
