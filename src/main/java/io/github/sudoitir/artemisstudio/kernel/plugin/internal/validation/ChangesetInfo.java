package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

/**
 * One changeset in the plugin's own Liquibase changelog, as found by the DB-free pre-flight
 * ({@link LiquibasePreflight}). {@code reversible} feeds the "Irreversible: rollback won't be
 * available" warning shown at review time (design.md §8); it says nothing about whether the
 * changeset has actually run.
 */
public record ChangesetInfo(String id, String author, boolean reversible) {}
