/**
 * The approval gate's engine (ADR-0179, ADR-0180, ADR-0181): {@code OperationGate} itself, the held
 * operations Studio keeps for whichever provider is installed, the approver rules, the decision and the
 * run that happens exactly once, the jobs that expire, recover and sweep, break-glass, and the HTTP API.
 * The contract it implements is {@code kernel.gate}.
 */
@ApplicationModule(
        displayName = "Approvals",
        allowedDependencies = {
            "kernel.audit",
            "kernel.core",
            "kernel.gate",
            "kernel.inbox",
            "kernel.jobs",
            "kernel.lifecycle",
            "kernel.plugin",
            "kernel.replica",
            "kernel.security",
            "kernel.settings",
            "kernel.stream"
        })
package io.github.sudoitir.artemisstudio.kernel.approval;

import org.springframework.modulith.ApplicationModule;
