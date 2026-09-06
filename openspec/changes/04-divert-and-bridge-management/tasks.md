# Tasks

Unchecked on purpose — proposed, not applied. Revise freely as the design is
refined; the create/delete half may not survive that discussion.

## Groundwork

- [ ] Confirm via `ctx7` the Artemis management operations for listing diverts and
      bridges, and whether their return shape fits `BrokerListOps.fetch` or needs a
      different read path
- [ ] Confirm `createDivert` / `destroyDivert` signatures and whether any clean
      update operation exists — D4 depends on the answer
- [ ] Confirm that the effective configuration `broker-config-diff` already reads
      contains configured diverts, so the comparison has one source of truth

## Read path

- [ ] Divert and bridge listing per node, merged cross-node, on the existing
      aggregation pattern
- [ ] Classify each divert: configured-and-running, runtime-only, or
      configured-but-not-running (D1)
- [ ] Bridge rows carry running state; no mutation is offered (D2)

## Write path — diverts only

- [ ] `broker/DivertOperations.java` — create and destroy, one `exec` each
- [ ] `service/RoutingManagementService.java` — `requireCluster(divert:write)`,
      fan-out to live nodes, per-node outcome borrowed from `queue-lifecycle` (D5),
      audit one event per command
- [ ] `?dryRun` naming the target nodes and making no mutating call
- [ ] Generate the `broker.xml` snippet from the submitted values (D3)
- [ ] No update operation; a change is delete then create (D4)

## API

- [ ] Routes for the divert and bridge views and the divert mutations
- [ ] DTOs with `@Schema`; regenerate `openapi.json` and `web/src/api/schema.d.ts`

## Permissions

- [ ] `divert:write` in `security/Permissions.java` and `catalogue()`
- [ ] Reading diverts and bridges requires only `cluster:read`

## MCP

- [ ] Extend the read-only resource listing tool to cover diverts and bridges rather
      than adding new read tools — keep within the tool-count budget
- [ ] `divert_action` mutating tool: dry-run default, confirm on the divert name,
      result states the runtime-only consequence in words, not only a flag

## Frontend

Meets the `operator-ui` contract from change 01; only what is specific to this
change is listed.

- [ ] `/clusters/$clusterId/diverts` route reusing the `ResourceView` and
      `VirtualTable` machinery, not a new table
- [ ] Runtime-only and configured-but-not-running markers in the list, in text,
      not colour alone, and persistent rather than shown once at creation (D1)
- [ ] Wording names the event — "lost when this broker restarts" — never
      "temporary", which implies the product cleans it up
- [ ] The marker is selectable and leads to the configuration that would make the
      divert permanent; keyboard-reachable, not hover-only
- [ ] Create form states the impermanence with the action, not in a dismissible
      notice, and shows the generated snippet before the control arms (D3)
- [ ] Explicitly NOT `web/src/app/useDismissedNotice.ts` for the impermanence
      statement or the runtime-only marker — a dismissible notice is the one shape
      this requirement rules out
- [ ] Snippet copyable in one action, rendered with `@mantine/code-highlight`
- [ ] Source-to-forwarding direction legible at a glance; exclusivity
      distinguishable in text
- [ ] Filter by address, in the URL like every other view's filter state
- [ ] Empty state teaches what a divert is; a cluster with none is not an error
- [ ] Delete reuses `web/src/shared/ConfirmByTyping.tsx`; create/delete outcomes
      reuse change 01's per-node outcome component
- [ ] Link from a runtime-only divert to the config-diff screen for that cluster
- [ ] Bridges render with no mutating control at all — not a disabled one, since
      no such operation exists to explain (D2)

## Tests

- [ ] Classification: each of the three states is produced from the right
      combination of running and configured presence
- [ ] Create fan-out reports per node; dry-run mutates nothing
- [ ] The generated snippet round-trips the submitted values
- [ ] Authorization: `divert:write` required to mutate, `cluster:read` to view
- [ ] Frontend: the runtime-only marker is present in the list, not only at
      creation, and is conveyed in text
- [ ] Frontend: the create form's impermanence statement is not dismissible
- [ ] Frontend: bridge rows expose no mutating control
- [ ] Keyboard-only pass over the marker, its explanation, and the create form

## Process

- [ ] ADR-0052 — a runtime divert is not a configured divert; index row
- [ ] Commit message — `feat(...)`, body written for someone upgrading (.claude/rules/05-commits.md)
- [ ] README roadmap row
- [ ] `./mvnw verify` and `just verify-web` green
- [ ] `openspec validate 04-divert-and-bridge-management --strict`
- [ ] Archive this change
