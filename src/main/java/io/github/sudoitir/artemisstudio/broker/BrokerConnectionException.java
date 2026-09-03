package io.github.sudoitir.artemisstudio.broker;

/**
 * A classified broker connection failure. The {@link Kind} maps to a stable
 * {@code type} URI in the API's {@code ProblemDetail} so the frontend can switch
 * on the class of failure rather than parse a message.
 */
public class BrokerConnectionException extends RuntimeException {

    public enum Kind {
        /** Nothing answered — DNS failure, connection refused, or timeout. */
        UNREACHABLE("Nothing answered at this address."),
        /** The broker rejected the credentials (HTTP 401 or 403). */
        UNAUTHORIZED("The broker rejected these credentials."),
        /** A Jolokia agent answered, but no Artemis broker MBean is registered on it. */
        NOT_ARTEMIS("This is a Jolokia agent, but no Artemis broker is registered on it."),
        /** No Jolokia agent at this path (HTTP 404). */
        WRONG_PATH("No Jolokia agent here. The Artemis console usually serves it at /console/jolokia."),
        /** The TLS handshake to an HTTPS broker failed. */
        TLS_FAILED("The TLS connection to the broker could not be established."),
        /** The broker answered but the response was not valid Jolokia JSON. */
        BAD_RESPONSE("The broker answered, but not with a Jolokia response.");

        private final String defaultMessage;

        Kind(String defaultMessage) {
            this.defaultMessage = defaultMessage;
        }

        public String defaultMessage() {
            return defaultMessage;
        }
    }

    private final Kind kind;

    public BrokerConnectionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public BrokerConnectionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static BrokerConnectionException of(Kind kind) {
        return new BrokerConnectionException(kind, kind.defaultMessage());
    }

    public Kind kind() {
        return kind;
    }
}
