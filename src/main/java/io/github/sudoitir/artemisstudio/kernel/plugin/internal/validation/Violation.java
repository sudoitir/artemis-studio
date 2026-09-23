package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

/**
 * One thing wrong with a plugin jar (design.md §3). {@code message} says what is wrong in plain
 * words; {@code authorFix} says what the author must change. A {@link Severity#WARNING} does not
 * block install — used only for a {@code since}/{@code until} check that could not be evaluated
 * because the running Studio version is unknown.
 */
public record Violation(String code, String message, String authorFix, Severity severity) {

    public Violation(String code, String message, String authorFix) {
        this(code, message, authorFix, Severity.ERROR);
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}
