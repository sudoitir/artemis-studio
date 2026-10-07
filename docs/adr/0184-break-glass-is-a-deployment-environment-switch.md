# ADR-0184: Break-glass is a deployment-environment switch only

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

Once an approval provider is armed, the gate fails closed
([ADR-0179](0179-an-approval-gate-in-the-service-layer-asks-one-provider.md)): a provider that
cannot start, hangs or throws blocks every gated operation, including the plugin administration
that would remove it. Some escape is needed, but any switch reachable from inside Studio is also a
way for a requester to skip approval. Any Spring property can be set from the database through
`studio_config_property` ([ADR-0047](0047-two-configuration-planes.md)), and plugins share the
database role ([ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)).

## Decision

1. **The switch is `artemis-studio.gate.break-glass=<reason>`, accepted only from OS environment
   or JVM system-property sources.** A value from `studio_config_property` or any other source fails
   startup.
2. **While it is set,** every gated operation runs and is audited `GATE_BYPASSED`, the shell shows a
   red banner, a WARN line is logged at boot and every hour, and every approver gets an inbox item.
3. **Held requests stay held,** because running one needs the provider's `checkRun`.
4. **Studio has no other off switch.** Settings in the provider's namespace carry the
   `GATE_INTEGRITY` trait, so changing them is itself held.

## Consequences

- A broken provider is recoverable by whoever controls the deployment, with no database edit.
- Bypassing approval needs a redeploy, which is visible, logged and announced, and is not open to
  a Studio user however privileged.
- An operator must remember to unset it; the banner and the hourly log line make that hard to miss.
- Held requests cannot be pushed through during break-glass; they wait for the provider.

## Alternatives considered

- **No break-glass.** Rejected: recovering from a broken provider would need a database edit.
- **A Studio setting or an admin action.** Rejected: anyone who can change settings could skip
  approval, and the database plane can set any property.
- **Run held requests too while bypassed.** Rejected: they were held under a policy the provider
  must still confirm.
