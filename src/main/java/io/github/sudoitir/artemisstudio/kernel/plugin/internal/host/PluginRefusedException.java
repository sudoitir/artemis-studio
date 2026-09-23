package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@link PluginHost#plan(String)}/{@link PluginHost#activate(String, String)} when the
 * jar itself is invalid ({@link io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.PluginValidator}
 * re-run at plan time, per design.md §3) or the activation would violate a rule {@code plan}
 * enforces synchronously — a vendor mismatch, a downgrade, or (activate only) missing
 * {@code requires} or the connection budget. Every violation names what is wrong and, where one
 * exists, what the caller must do instead.
 */
public final class PluginRefusedException extends RuntimeException {

    private final List<Violation> violations;

    public PluginRefusedException(List<Violation> violations) {
        super(violations.stream().map(Violation::message).collect(Collectors.joining("; ")));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
