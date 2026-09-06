# Tasks

Unchecked on purpose — this change is proposed, not applied. Revise this list
freely as the design is refined; a stale task list is worse than an edited one.

## Groundwork

- [ ] Confirm every Artemis management signature via `ctx7` — `createQueue`,
      `destroyQueue`, `createAddress`, `deleteAddress`, `updateQueue`, queue
      `pause` / `resume` / `resetMessageCounter`, and the `Paused` attribute.
      Record the exact parameter types in `design.md`. Do not carry signatures
      from memory into code (project rule; the repo has been bitten before)
- [ ] Confirm which queue configuration fields Artemis accepts on `updateQueue`
      for a live queue, and pin D7's mutable set to that answer

## Broker layer

- [ ] `broker/QueueLifecycleOperations.java` — one `exec` per operation, same
      shape as `MessageOperations` (never a dry-run and an act in one POST)
- [ ] Extend `BrokerMBeans` only if an object name shape is missing; reuse
      `queue(...)` / `address(...)`
- [ ] Classify a management refusal: authorization refusal vs argument refusal vs
      transport failure (D5 depends on telling them apart)

## Service layer

- [ ] `service/QueueLifecycleService.java` — `requireCluster` with the new
      permission, resolve live nodes from topology, fan out, collect per-node
      outcome, one `AuditService.begin/succeed/fail` per command (D4)
- [ ] `LifecycleOutcome` per-node record (D2), including the `ALREADY` semantics
      and the configuration comparison on create (Risks)
- [ ] Delete estimate: sum `MessageCount` across target nodes, check against the
      bulk cap, honour `override` (D6)
- [ ] Address delete refuses while queues are bound, naming them (D8)

## Capability model

- [ ] `CapabilityProbe` — stop inferring `managementWrite` from a read; make it
      `UNKNOWN` until a write is attempted, `AVAILABLE` on success, `UNAVAILABLE`
      with a `broker.xml` snippet on an authorization refusal (D5)
- [ ] Persist the assessment so it survives a probe that makes no write
- [ ] Verify no existing screen regresses when a connection reports `UNKNOWN`

## API

- [ ] `web/QueueLifecycleController.java` — create / delete / update / pause /
      resume / reset-counter for queues, create / delete for addresses; every
      mutating route takes `?dryRun` and, where the cap applies, `?override`
- [ ] DTOs under `web/dto/` with `@Schema`, per ADR-0019
- [ ] Regenerate `openapi.json` (snapshot test) and `web/src/api/schema.d.ts`

## Permissions

- [ ] `queue:create`, `queue:delete`, `queue:update`, `queue:pause` in
      `security/Permissions.java` and its `catalogue()`
- [ ] Confirm the role editor renders them with no client change

## MCP

- [ ] `queue_lifecycle` tool in `mcp/McpTuningTools.java` — kind enum, `dryRun`
      default true, `confirm` must equal the target name for a destructive kind,
      body in `McpErrors.guard`, delegating to `QueueLifecycleService`
- [ ] `destructiveHint` / `idempotentHint` set per kind, `openWorldHint = false`
- [ ] Keep the tool-count and response-size budget the `mcp-server` spec sets

## Frontend

- [ ] "New queue" action on `web/src/queues/QueuesView.tsx`
- [ ] Pause / resume / edit / delete on `web/src/queues/QueueDetailDrawer.tsx`
- [ ] Hooks in `web/src/api/client.ts`; DTOs generated, never hand-written

### The per-node outcome component

- [ ] One shared component in `web/src/shared/` rendering the per-node outcome,
      used for both the preview and the result so the two are comparable
- [ ] Node state carried in text; colour only where something is wrong, per the
      house rule in `web/src/theme.css` that a healthy view is near-monochrome
- [ ] A new semantic token only if an existing `--as-*` does not fit; never a raw
      colour literal in the component
- [ ] Tabular figures on the per-node counts so a difference between nodes is
      visible without reading digits
- [ ] A partial outcome is apparent before any row is read

### The create-queue form — the product's first substantial form

- [ ] Address field reuses `web/src/queues/AddressPicker.tsx` — it already
      suggests from the broker's own list while allowing free text for an address
      that does not exist yet, which is exactly the create case
- [ ] Visible label on every field; no placeholder-as-label
- [ ] Validate on blur, message beside its field, not only on submit
- [ ] Submit either enabled-and-validating or disabled-with-a-visible-reason —
      never silently disabled
- [ ] Filter and routing type presented as immutable with the reason on edit,
      visually distinct from a disabled field (D7)
- [ ] Advanced configuration behind a disclosure; the fields that identify the
      queue come first
- [ ] Autofocus the first invalid field on a rejected submit

### Destructive flows

- [ ] Confirmations reuse `web/src/shared/ConfirmByTyping.tsx` — no fourth
      hand-rolled copy; while here, replace the inline copy in
      `web/src/clusters/AddManagementUrl.tsx` with it
- [ ] The confirmation names the resource, the target nodes and the message count
      that will be destroyed; an unavailable estimate is stated, not omitted
- [ ] The initiating control is busy while in flight and cannot be re-submitted
- [ ] Outcome announced through an `aria-live` region, following the pattern in
      `web/src/clusters/RegisterCluster.tsx`
- [ ] Failure copy states cause and next action; no bare "something went wrong"
- [ ] Modal focus: enters on open, trapped while open, escape closes, focus
      returns to the trigger

### Capability gating

- [ ] Permission-dependent controls gate on `web/src/auth/useCan.ts`, not a
      hand-rolled grant check — and only to disable-with-a-reason, never to hide;
      the server remains the enforcement point
- [ ] Lifecycle actions visible-and-disabled with the reason and the `broker.xml`
      snippet when the capability is `UNAVAILABLE` (non-negotiable #5)
- [ ] Enabled with the uncertainty stated when it is `UNKNOWN` (D5) — absence of
      evidence must not block the operator
- [ ] The explanation is keyboard-reachable, not hover-only

### Empty and error states

- [ ] The queues view's empty state teaches what a queue is and offers to create
      one where permitted
- [ ] Filtered-empty is distinguishable from empty and offers to clear the filter
- [ ] A view empty because a node was unreachable says so rather than showing an
      absence as a fact

## Tests

- [ ] `QueueLifecycleServiceTest`: fan-out to live nodes only; `ALREADY` on a
      matching create; `FAILED` with the difference on a mismatching create;
      partial failure returns per-node detail and records the command as failed
- [ ] Dry-run makes no mutating broker call, for every kind
- [ ] Delete over the cap without `override` is refused; with `override` proceeds
- [ ] `CapabilityProbeTest`: `UNKNOWN` before any write; `UNAVAILABLE` only on an
      authorization refusal, not on a bad argument
- [ ] Authorization: each new permission is required; a caller without a grant
      gets not-found, not access-denied
- [ ] MCP: dry-run default, confirm mismatch refused, permission inherited
      (extend `McpDryRunIntegrationTest` / `McpAuthorizationIntegrationTest`)
- [ ] Frontend: create form validation on blur, per-node preview render, partial
      outcome distinguishable from complete, disabled-with-reason when the
      capability is unavailable and enabled-with-uncertainty when it is unknown
- [ ] Frontend queries by role and accessible name, per the existing harness in
      `web/src/test/render.tsx` — not by class or test id
- [ ] Keyboard-only pass over the destructive flow, asserted: focus enters the
      dialog, escape closes it, focus returns to the trigger
- [ ] Contrast of any new status treatment verified in both colour schemes, not
      inferred from one

## Process

- [ ] ADR-0049 — cluster-wide topology mutation; index row in `docs/adr/README.md`
- [ ] Consider `.claude/rules/frontend.md`: the repo has no frontend rule file, and
      the `operator-ui` contract is the natural seed for one
- [ ] CHANGELOG `[Unreleased]` — Added, plus a note on the `managementWrite`
      reporting change under Changed
- [ ] README roadmap row
- [ ] `./mvnw verify` and `just verify-web` green
- [ ] `openspec validate 01-queue-and-address-lifecycle --strict`
- [ ] Archive this change
