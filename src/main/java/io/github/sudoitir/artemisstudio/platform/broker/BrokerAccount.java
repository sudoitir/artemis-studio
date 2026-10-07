package io.github.sudoitir.artemisstudio.platform.broker;

/**
 * The two broker accounts a cluster holds (ADR-0026): the one Jolokia management calls
 * authenticate with, and the one Core connections do, which falls back to the management account
 * when none is stored.
 */
public enum BrokerAccount {
    MANAGEMENT,
    CORE
}
