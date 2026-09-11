package io.github.sudoitir.artemisstudio.domain.brokerconfig;

/**
 * How much a step can hurt. {@code HIGH} hazards must be acknowledged one by one before
 * a real run (ADR-0067 D7); the other two are stated so the operator reads them, and
 * gate nothing.
 */
public enum HazardClass {
    LOW,
    MEDIUM,
    HIGH
}
