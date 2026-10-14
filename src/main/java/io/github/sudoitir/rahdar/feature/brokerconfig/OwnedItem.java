package io.github.sudoitir.rahdar.feature.brokerconfig;

/**
 * An item Rahdar itself applied (ADR-0067 D6): the only kind an apply may remove
 * without the operator opting into removing what it does not own.
 */
public record OwnedItem(Plan.Section section, String key) {}
