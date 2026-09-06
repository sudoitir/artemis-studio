# Tasks

Unchecked on purpose — proposed, not applied. Revise freely as the design is
refined.

## Groundwork

- [ ] Confirm via `ctx7` which Artemis message headers are broker-owned and refused
      or overwritten on send, and how the dead-letter origin headers are named.
      Pin D2's carried/dropped split to that answer
- [ ] Determine, per transport, when a browsed body is complete versus rendered or
      truncated — D6 depends on being able to tell
- [ ] Check whether request-reply payload capture already records that a payload was
      truncated by the capture cap; if not, add it as a prerequisite task here

## Service layer

- [ ] `service/MessageReplayService.java` composing a replay and delegating the send
      to `MessageService.send` — one send path, one audit shape (D1)
- [ ] Header composition: carry application properties, content type and correlation
      id; drop broker-owned and dead-letter bookkeeping (D2)
- [ ] Provenance header — original message id, audit event id, replay depth (D3)
- [ ] Replay depth read from the incoming message, incremented, refused above the
      ceiling; the ceiling is an operational setting in the registry (D4)
- [ ] Refuse a replay whose fidelity cannot be guaranteed, with the reason and the
      `broker.xml` snippet where it applies (D6)
- [ ] Bulk replay: dry-run estimate, ADR-0022 cap, `override`, per-message outcome
      keyed by original id (D7)

## Sources

- [ ] Replay from a browsed message
- [ ] Replay from a DLQ entry, including one whose original queue no longer holds it
- [ ] Replay from a captured request-reply payload
- [ ] A truncated captured payload is not replayable and says why

## API

- [ ] Replay routes alongside the existing message actions; `?dryRun` and
      `?override` on the bulk form
- [ ] DTOs with `@Schema`; regenerate `openapi.json` and `web/src/api/schema.d.ts`
- [ ] Assert no new permission is introduced — `message:send` plus `message:read`

## MCP

- [ ] `replay_message` tool in `mcp/McpTuningTools.java` — dry-run default, confirm
      on the bulk form, `destructiveHint = false` but `idempotentHint = false`
- [ ] Delegate to the replay service; no authz or audit logic in `mcp/`

## Frontend

Meets the `operator-ui` contract from change 01; only what is specific to this
change is listed.

- [ ] Replay action in `web/src/messages/MessagesView.tsx`, the DLQ view, and the
      request-reply flow detail
- [ ] Replay preview showing destination, body and carried headers, with
      broker-assigned values visually distinguished from carried ones
- [ ] Destination is an explicit field starting at the original address — a
      redirect is a visible edit, not a hidden option
- [ ] A body too large to render shows a marked excerpt, never a silent truncation
- [ ] The confirmation states that a replay is a new message, that ordering is not
      preserved, and that a duplicate is possible
- [ ] A refusal names which of the three reasons applies and its remedy, rendered
      where the action was taken rather than as a generic notification
- [ ] Replay provenance marked wherever messages are listed, linking to the origin
      and showing depth
- [ ] Bulk replay preview follows `web/src/messages/BulkActionPreview.tsx` and reuses
      `web/src/shared/ConfirmByTyping.tsx`
- [ ] Reuse the existing code/JSON rendering (`@mantine/code-highlight`) for the
      body rather than a new viewer
- [ ] Outcome announced through an `aria-live` region; per-message bulk outcome
      reuses change 01's outcome component shape

## Tests

- [ ] Broker-owned headers are not carried; application headers are
- [ ] Provenance header present, depth increments, ceiling refuses
- [ ] Binary body over a Jolokia-only connection is refused with the snippet
- [ ] Truncated captured payload is refused
- [ ] Bulk replay: dry-run mutates nothing; over-cap refused without `override`;
      partial failure reports per original id
- [ ] Authorization: `message:send` required; no new permission exists
- [ ] MCP: dry-run default and confirm behaviour
- [ ] Frontend: the preview distinguishes carried from assigned values; a
      too-large body renders as a marked excerpt
- [ ] Frontend: each refusal reason renders its own remedy, not a shared generic
      message
- [ ] Frontend: a replayed message is marked as such in the message list

## Process

- [ ] ADR-0051 — a replay is a new send with provenance; index row
- [ ] Replay-depth ceiling added to the settings registry with a label and hint
- [ ] Commit message — `feat(...)`, body written for someone upgrading (.claude/rules/05-commits.md)
- [ ] README roadmap — tick "Message replay from a captured payload"
- [ ] `./mvnw verify` and `just verify-web` green
- [ ] `openspec validate 03-message-replay-from-payload --strict`
- [ ] Archive this change
