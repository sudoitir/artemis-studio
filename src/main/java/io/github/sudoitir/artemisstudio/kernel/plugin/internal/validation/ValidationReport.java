package io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.util.List;

/**
 * The outcome of {@link PluginValidator#validate}. {@code descriptor} is {@code null} when
 * {@code plugin.json} itself could not be read (a violation says why); nothing further is
 * checked in that case.
 */
public record ValidationReport(
        PluginDescriptor descriptor, List<Violation> violations, List<ChangesetInfo> changesets) {

    public ValidationReport {
        violations = List.copyOf(violations);
        changesets = changesets == null ? List.of() : List.copyOf(changesets);
    }

    public ValidationReport(PluginDescriptor descriptor, List<Violation> violations) {
        this(descriptor, violations, List.of());
    }

    public boolean valid() {
        return violations.stream().noneMatch(v -> v.severity() == Violation.Severity.ERROR);
    }

    public List<Violation> errors() {
        return violations.stream()
                .filter(v -> v.severity() == Violation.Severity.ERROR)
                .toList();
    }

    public List<Violation> warnings() {
        return violations.stream()
                .filter(v -> v.severity() == Violation.Severity.WARNING)
                .toList();
    }
}
