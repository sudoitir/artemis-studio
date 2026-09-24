package io.github.sudoitir.artemisstudio.feature.setupreview;

/**
 * How much a finding costs (ADR-0106). Fixed per rule code, never computed, so the same mistake
 * always reads the same way.
 */
public enum Severity {
    /** Data can be lost, duplicated or diverge under a foreseeable event (a partition, a restart). */
    CRITICAL,
    /** Messages can be stranded, or service degraded. */
    WARNING,
    /** A hardening step. Never alerts. */
    INFO
}
