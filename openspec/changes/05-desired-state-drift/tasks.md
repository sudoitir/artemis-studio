# Tasks

Unchecked on purpose — proposed, not applied. This change depends on change 01 and
should not begin until it is archived. Expect substantial revision after further
brainstorming; the open questions in `proposal.md` are real.

## Prerequisite

- [ ] Confirm `01-queue-and-address-lifecycle` is archived and its per-node outcome
      type and lifecycle service are available to call

## Persistence

- [ ] Liquibase changeset for the declaration — a new changeset only, never an edit
      to a released one
- [ ] Column ordering per non-negotiable #7; storage parameters only if the table
      proves high-churn
- [ ] Keep the declaration entirely separate from `queue_snapshot`; deleting the
      snapshot cache must never affect a declaration

## Declaration

- [ ] Declare queues and addresses per cluster: name, address, routing type,
      durability, and the pinned configuration values
- [ ] Import a declaration from a cluster's current state (D3)
- [ ] Re-import shows what changed since the last import
- [ ] Per-cluster setting for whether undeclared resources are reported, with
      exclusion patterns (D2)

## Comparison

- [ ] Evaluate drift on a schedule registered through the existing dynamic-schedule
      mechanism, its cadence a settings-registry key (D6)
- [ ] Read effective configuration through the same reader `broker-config-diff`
      uses — one truth per node
- [ ] Classify missing (naming the nodes), unexpected, and divergent (D2)
- [ ] A node that is not live is reported as not evaluated, never as missing
      everything

## Fixing

- [ ] A fix action composes the corresponding lifecycle command and delegates to
      change 01's service — no broker call, no audit action, no dry-run, and no cap
      of its own (D4)
- [ ] Assert in a test that this change contributes no broker write path

## Alerting

- [ ] Drift as a condition the existing alert rules can fire on, reusing debounce
      and channel delivery unchanged

## API and frontend

- [ ] Routes for reading and editing a declaration and reading the drift report
- [ ] DTOs with `@Schema`; regenerate `openapi.json` and `web/src/api/schema.d.ts`
- [ ] `/clusters/$clusterId/drift` screen: a summary that answers "is this cluster
      correct" before any finding is read — per-kind counts and when the comparison
      last ran
- [ ] A matching cluster renders as a resolved state, never as an empty table
- [ ] An incomplete comparison is stated in the summary, not omitted
- [ ] Findings grouped by kind, ordered within a kind by likelihood of action
- [ ] A configuration difference shows declared beside observed, not prose
- [ ] Each fixable finding offers the change-01 action with its ordinary preview and
      confirmation; unavailable actions state the reason instead of vanishing
- [ ] Wording never implies the report applies itself
- [ ] Link to and from the config-diff screen; state plainly how the two differ
- [ ] Surface when a declaration was last reviewed, and offer its age as a possible
      cause of a full report
- [ ] Regenerate-from-cluster reachable from the report, showing its effect before
      replacing the declaration
- [ ] The declaration editor follows the `operator-ui` form contract — visible
      labels, blur-time validation, no silently disabled submit
- [ ] Drift counts use tabular figures; kind is conveyed in text, colour only where
      something is wrong

Meets the `operator-ui` contract from change 01; the above is what is specific to
this change.

## Permissions

- [ ] `desired-state:write` in `security/Permissions.java` and `catalogue()`
- [ ] Applying a fix requires the change-01 permission for the operation it
      performs, not the declaration permission

## Tests

- [ ] Missing on a subset of nodes names exactly those nodes
- [ ] A node that is not live is not reported as missing
- [ ] Unexpected is silent until enabled, and respects exclusions
- [ ] A fix produces the same audit event a manual lifecycle command would
- [ ] Nothing in the system applies a fix without an explicit action — a test that
      fails if a scheduled reconciliation is ever introduced (D1)
- [ ] Deleting the snapshot cache leaves declarations intact
- [ ] Frontend: a matching cluster renders as resolved, not as an empty table
- [ ] Frontend: an unevaluated node appears in the summary
- [ ] Frontend: a finding the operator cannot fix shows the reason rather than
      omitting the action
- [ ] Frontend: no control anywhere offers automatic resolution
- [ ] Keyboard-only pass over the report and one fix flow

## Process

- [ ] ADR-0053 — desired state is advisory, never auto-reconciled; index row
- [ ] Drift evaluation cadence added to the settings registry with label and hint
- [ ] Commit message — `feat(...)`, body written for someone upgrading (.claude/rules/05-commits.md)
- [ ] README roadmap row
- [ ] `./mvnw verify` and `just verify-web` green
- [ ] `openspec validate 05-desired-state-drift --strict`
- [ ] Archive this change
