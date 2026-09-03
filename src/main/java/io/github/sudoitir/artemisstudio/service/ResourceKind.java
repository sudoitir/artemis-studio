package io.github.sudoitir.artemisstudio.service;

/** The live-through resource lists and their Artemis management op names. */
public enum ResourceKind {
    ADDRESSES("listAddresses"),
    CONSUMERS("listConsumers"),
    SESSIONS("listSessions"),
    CONNECTIONS("listConnections"),
    PRODUCERS("listProducers");

    private final String op;

    ResourceKind(String op) {
        this.op = op;
    }

    public String op() {
        return op;
    }
}
