package io.github.sudoitir.artemisstudio.domain.brokerconfig;

/**
 * One reason a declaration cannot be applied. {@code path} is the field the form
 * should focus, in the shape {@code addressSettings[2].values.maxSizeBytes}.
 */
public record Violation(String path, String message) {}
