# Tasks

Unchecked on purpose — this change is proposed, not applied. Revise freely as the
design is refined.

## Groundwork

- [ ] Confirm the Artemis management signatures via `ctx7` — `closeConnectionWithID`,
      `closeSessionWithID`, `closeConsumerConnectionsForAddress`, and whatever
      `listConnections` / `listSessions` field carries the client id, remote address
      and in-flight count. Record them in `design.md`; do not code from memory
- [ ] Confirm what the broker returns when the id does not exist — it decides how
      `ALREADY_GONE` is detected (D2)

## Broker layer

- [ ] `broker/ConnectionOperations.java` — one `exec` per operation, `MessageOperations`
      shape
- [ ] A pre-close read that snapshots client id, remote address, user, session and
      consumer counts, and in-flight message count where available (D3)

## Service layer

- [ ] `service/ConnectionControlService.java` — `requireCluster` with
      `connection:close`, node-targeted close for connection and session, cluster
      fan-out for the address-scoped close (D1)
- [ ] `CLOSED` / `ALREADY_GONE` outcome, both success (D2)
- [ ] Address-scoped close: per-node dry-run count, bulk cap, `override` (D6)
- [ ] `AuditService.begin/succeed/fail` carrying the pre-close snapshot, not the id
      alone

## API

- [ ] Routes on the existing cross-node resource controller, node-scoped for the
      by-id closes and cluster-scoped for the address close
- [ ] `?dryRun` on the address-scoped close; `?override` where the cap applies
- [ ] DTOs with `@Schema`; regenerate `openapi.json` and `web/src/api/schema.d.ts`

## Permissions

- [ ] `connection:close` in `security/Permissions.java` and `catalogue()`
- [ ] Assert no message permission implies it

## MCP

- [ ] `connection_action` tool in `mcp/McpTuningTools.java` — kind enum,
      `destructiveHint = true`, `idempotentHint = false`, dry-run default, confirm
      against the client id, body in `McpErrors.guard`
- [ ] Delegate to `ConnectionControlService`; no authz or audit logic in `mcp/`

## Frontend

Meets the `operator-ui` contract from change 01; only what is specific to this
change is listed.

- [ ] Row action menu on the connections / sessions / consumers `ResourceView`
- [ ] Confirmation reuses `web/src/shared/ConfirmByTyping.tsx`, tokened on the
      client id or remote address — never the opaque connection id (D3)
- [ ] The confirmation shows user, node, session and consumer counts, and the
      in-flight message consequence (D4)
- [ ] An action on a row whose connection has since gone reports as completed, in
      the success position, not as an error notification
- [ ] The affected row reconciles after a close without a manual reload
- [ ] Data age is visible on these views, since the action depends on freshness
- [ ] Link the action from wherever slow-consumer detection is surfaced — the
      finding and the verb one click apart
- [ ] The row action is keyboard-reachable; the menu trigger has an accessible
      name, not an icon alone
- [ ] Outcome announced through an `aria-live` region
- [ ] The address-scoped close reuses change 01's per-node outcome component

## Tests

- [ ] `ConnectionControlServiceTest`: close by id; already-gone is success; the
      audit row carries the pre-close snapshot
- [ ] Address-scoped close: dry-run makes no mutating call; over-cap refused
      without `override`
- [ ] Authorization: `connection:close` required; missing grant yields not-found,
      not access-denied; no message permission grants it
- [ ] MCP: dry-run default, confirm mismatch refused
- [ ] Frontend: stale-row close renders as completed in the success position;
      confirmation is tokened on the client id, not the connection id
- [ ] Frontend: the confirmation states the in-flight message consequence
- [ ] Keyboard-only pass over the row action and its confirmation

## Process

- [ ] ADR-0050 — connection identifiers are node-local and ephemeral; index row
- [ ] Commit message — `feat(...)`, body written for someone upgrading (.claude/rules/05-commits.md)
- [ ] README roadmap row
- [ ] `./mvnw verify` and `just verify-web` green
- [ ] `openspec validate 02-connection-and-consumer-control --strict`
- [ ] Archive this change
