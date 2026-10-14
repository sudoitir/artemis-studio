package io.github.sudoitir.rahdar.feature.setupreview;

/**
 * A rule the review could not evaluate for a subject, and why — stated so that the absence of a
 * finding is never read as a pass.
 */
public record NotAssessed(String code, String subject, String reason) {}
