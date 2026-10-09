# ADR-0179: An approval gate in the service layer asks one plugin provider, and Studio owns held operations

- **Status**: accepted. The requester's reason and the approver count are amended by [ADR-0187](0187-the-approval-gate-keeps-two-approvers-and-asks-for-no-reason.md).
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

Some installations need a second person to agree before a destructive, bulk, settings or
access-control operation runs (four eyes). Studio has no shared operations layer: purges, deletes,
moves, transfers, settings and access changes are mutations in separate services, reached from
REST, MCP tools and the CLI (which uses REST), and each writes its own audit row
([ADR-0078](0078-audit-row-commits-before-the-broker-call.md)). A check bolted onto one entry point
leaves the others open.

Who may approve what, for how long and with what reason is policy, and policies differ per
installation. Studio already lets plugins answer questions it asks on its own path, such as
sign-in ([ADR-0156](0156-plugins-sign-users-in-through-declared-credential-providers.md)). Plugins
share Studio's database role ([ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)),
so nothing a plugin stores can be trusted to protect what Studio later runs.

Some checks need the HTTP request (step-up for plugin lifecycle and user changes, ADR-0103) and
cannot be repeated when an approved operation runs later. `202` already means "started" for bulk
runs, transfers, plugin activation and key rotation.

## Decision

1. **The gate is an explicit call in the service layer.** Every gated service method authorizes
   exactly as before, returns at once on a dry run (never gated), and then calls
   `gate.run(operation, () -> doIt(...))`. Each such method carries `@Gated("<type>")`, and an
   ArchUnit test checks that every `@Gated` method calls `OperationGate.run` and that every type has
   a registered `GatedOperation` bean. Request-bound checks (step-up, the installer tier) move to
   the controllers; services keep only permission and state checks, which are safe to repeat.
   `gate.run` is never called inside a transaction.
2. **The contract is a new module, `kernel.gate`, published as `@PluginApi`.** It holds only types:
   `OperationGate`, `GatedOperation<P extends Record>` (type, version, typed parameters, traits,
   mode, scope, summary, display rows, redacted paths, effect estimate, replay), `Operation`,
   `Effect`, the `ApprovalProvider` SPI and its sealed `GateDecision` (`Allow`, `Hold`, `Deny`),
   `HeldOperations` (a provider's read view), `GateContext`, `GateScope`, `GateTicket` and the
   exceptions. It depends on the core, and on the plugin contract only for the `@PluginApi`
   marker. The engine lives in a separate module that depends on it. Plugins register their own
   types as `<pluginId>:<name>`.
3. **One provider, declared and armed from its install status.** A plugin declares
   `approvalProvider: {approverPermission}` in its descriptor, naming a permission it declares
   itself; activation refuses a second provider. Each gated call reads, with one indexed query,
   whether a provider's install row is in a desired-active status. There is no cache, so arming is
   never stale.
4. **Fail closed once armed.** Not armed: the action runs exactly as before. Armed but the provider
   is not attached on this replica, slow (over `gate.decide-timeout`, 3 s by default) or throwing:
   `503 approval-unavailable`. The provider runs on a virtual thread inside the plugin's context.
5. **The provider decides; Studio owns the held operation.** `decide` answers `Allow(policyRef)`,
   `Hold(policyRef, ttl, reasonRequired, approverHint)` or `Deny(reason)`, in PREVIEW or SUBMIT
   mode. Allow runs the action with the provider and policy on its audit row; Deny is audited and
   returns `403 operation-denied`; Hold writes a held operation Studio stores and answers
   `202` with `X-Studio-Held-Operation` and a `Location`, or `422 approval-reason-required`
   when the policy wants a reason none was given. MCP maps the same outcomes to result text or a
   tool error.
6. **Contract.VERSION becomes 12.** Every plugin is rebuilt against it.

## Consequences

- Every entry point is covered by construction, and the coverage test plus a REST and MCP parity
  test keep any of about 40 call sites from slipping through.
- An installation without a provider behaves exactly as before, at the cost of one sub-millisecond
  indexed read per gated call, which happens at human rate.
- A broken provider blocks gated administration; recovery is break-glass
  ([ADR-0184](0184-break-glass-is-a-deployment-environment-switch.md)).
- Services that held their own transaction or step-up checks are restructured: `SettingsService`
  becomes a non-transactional facade over a transactional writer, and step-up moves to controllers.
- Held state survives a broken or removed provider, because Studio keeps it
  ([ADR-0180](0180-held-operations-are-sealed-claimed-once-and-replayed-as-the-requester.md)).
- Every plugin must be rebuilt for contract 12.

## Alternatives considered

- **An aspect around service methods.** Rejected: it has to rebuild typed parameters from method
  arguments, which breaks silently when a signature changes, and it cannot express "authorize
  first, dry runs never".
- **A filter on REST and MCP.** Rejected: it gates entry points, not operations, and every new entry
  point would need the same work.
- **Held state owned by the provider.** Rejected: execution integrity, cancellation on removal and
  the requester's view must survive a broken or removed provider.
- **Arming from a cached flag.** Rejected: a cache can only add a window in which a removed or
  failed provider leaves operations ungated.
