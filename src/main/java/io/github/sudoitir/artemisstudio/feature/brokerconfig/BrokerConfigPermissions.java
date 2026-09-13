package io.github.sudoitir.artemisstudio.feature.brokerconfig;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class BrokerConfigPermissions {

    /**
     * Edit a cluster's declared configuration (ADR-0067). Changes nothing on a broker.
     */
    public static final String CONFIG_WRITE = "config:write";

    /**
     * Apply a declaration to brokers: create and update only, never destroy a queue or address (ADR-0067 D6).
     */
    public static final String CONFIG_APPLY = "config:apply";

    private BrokerConfigPermissions() {}
}
