package io.github.sudoitir.artemisstudio.broker.core;

/**
 * Turns a stored {@code broker_node.core_url} — which discovery fills from the
 * broker-advertised {@code <connector>}, usually a bare {@code host:port} — into a
 * URL the Core client can dial.
 */
public final class CoreUrl {

    private CoreUrl() {}

    /**
     * @return {@code null} when {@code coreUrl} is null or blank; the value
     *     unchanged when it already carries a scheme; otherwise {@code tcp://} +
     *     the value.
     */
    public static String dialable(String coreUrl) {
        if (coreUrl == null || coreUrl.isBlank()) {
            return null;
        }
        String trimmed = coreUrl.trim();
        return trimmed.contains("://") ? trimmed : "tcp://" + trimmed;
    }
}
