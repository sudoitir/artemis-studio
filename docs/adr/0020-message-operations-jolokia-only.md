# ADR-0020: Phase 3 message operations are Jolokia-only; truncation is disclosed per message

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 3 adds the first operations that read and change messages on a broker:
browse, send, move, retry, DLQ replay, delete, expire, purge. ADR-0002 already
settled the transport question — Jolokia is the primary channel, the Core client
is "the second channel, added in Phase 4 … The client interface is extracted
then, from two real implementations, not guessed up front." So Phase 3 has one
transport and no reason to invent an abstraction over it.

`docs/broker-management-notes.md` §8 and the Phase 3 spike (§11) record two
Jolokia limits that matter here:

- **Bodies are stringified.** `browse()` / `sendMessage()` carry the body as
  text; binary bodies are not faithful. Faithful binary I/O is a Phase 4 Core
  feature.
- **The broker truncates returned message data** at the
  `management-message-attribute-size-limit` address-setting (default 256 bytes on
  the versions we target). The spike confirmed the signal: any truncated string
  value comes back with a literal **`, + <N> more`** suffix appended
  (a 4000-char body was returned as 256 visible chars + `, + 3744 more`). It also
  confirmed the limit itself is **not exposed anywhere over Jolokia** — not in
  `getAddressSettingsAsJSON`, not on any broker MBean attribute or operation.

A browse UI that shows a clipped body without saying so is lying to the operator
— a direct hit on non-negotiable #5.

## Decision

We will implement Phase 3 message operations as one concrete Jolokia code path,
and disclose truncation per message rather than as a probed capability.

- **No transport interface.** `broker/MessageBrowser` and
  `broker/MessageOperations` take a `JolokiaBrokerClient` and build
  `JolokiaRequest`s directly. When the Core client arrives in Phase 4 the
  interface is extracted from the two real implementations, per ADR-0002.
- **Every operation is one batched Jolokia POST** via
  `JolokiaBrokerClient.batch(...)` — its first production use. Browse is
  `browse(page, size, filter)` + `MessageCount` in one array. A by-filter dry run
  is one `countMessages` POST; the execution that follows is a **separate** POST.
  A dry run and the act it previews are never in the same POST.
- **`browse(int page, int pageSize, java.lang.String filter)`** — the broker
  pages, 1-based, and caps a page at `managementBrowsePageSize` (200). Studio
  passes the operator's page/size/filter straight through; it does not slice
  Studio-side and does not need a `browse.max-rows` guard.
- **Operations target one node.** A backup holds no messages; every message
  endpoint takes `?node=`, defaulting to the message-holding live endpoint of the
  logical node serving that queue, and echoes which node answered. The queue
  MBean name is built from `BrokerMBeans.queue(...)` with `address` /
  `routingType` read from the cached `queue_snapshot` row.
- **Truncation is disclosed per message, not as a capability.** Every browsed
  message carries `bodyTruncated`, set when a value ends in `, + <N> more`; the
  single-message read carries the observed approximate limit
  (`visibleLength − suffixLength`). The browse detail panel shows a notice naming
  that limit and the `<address-setting>` `broker.xml` snippet that raises it, and
  states that faithful binary I/O arrives with the Core client in Phase 4.
  `BrokerXmlSnippets.forMessageBodyLimit()` supplies the snippet text.

  We do **not** add a `MESSAGE_BODY_FULL` capability. The limit cannot be probed,
  so a connection-level capability could only ever be "unknown until we happen to
  browse a big message" — weaker and more confusing than an always-present
  per-message flag. `MESSAGE_IO` stays the gate for the whole feature; its reason
  string states that the Jolokia channel carries bodies as text and that
  faithful binary I/O is Phase 4.
- **`BrokerCapabilities` is unchanged** — still the four classes from ADR-0002.
  No mapper change, no spec change to `broker-capabilities`.

## Consequences

- The message feature ships against the broker setup our users already run, with
  zero broker changes required to browse — and it tells the truth when a body is
  clipped, with the exact fix, on the message where it happened.
- `JolokiaBrokerClient.batch()` moves from tested-but-unused to a load-bearing
  path; its per-entry status handling is now exercised in production.
- `browse(page, size, filter)` is the reach limit: a queue deeper than
  `managementBrowsePageSize` can only be inspected past that point by filtering.
  Operations (move/delete/retry) are by-filter or by-id, so this constrains
  *inspection*, not *action*.
- Operators who need faithful binary bodies wait for Phase 4. Surfaced in the UI,
  not hidden.
- A single-message "detail" view is a scoped browse (`browse(1, N, filter)`
  scanned for the `messageID`) — there is no richer call; detail shows the same
  still-truncated `text`, just isolated.
- An invalid filter comes back as `AMQ229020 … IllegalStateException`, HTTP 500
  from the broker; the service maps it to a **400 invalid-value** problem so the
  operator sees "bad filter", not a broker error.

## Alternatives considered

- **A `MessageChannel` interface with a Jolokia implementation now.** Rejected —
  ADR-0002 explicitly defers the interface to Phase 4 so it is shaped by two real
  implementations. One implementation cannot shape a good interface.
- **A `MESSAGE_BODY_FULL` capability, probed from the address-setting.** Rejected
  — the spike proved the limit is not exposed over Jolokia by any means, so the
  probe is impossible.
- **A `MESSAGE_BODY_FULL` capability, *observed* (UNKNOWN until a browse sees a
  truncated value, then UNAVAILABLE).** Considered and rejected as more machinery
  than it earns: it never reaches a trustworthy `AVAILABLE` over Jolokia, it
  duplicates the per-message `bodyTruncated` flag at the connection level, and it
  adds a fifth `CapabilityAssessment` plus a mapper mapping plus a spec delta for
  a roll-up the detail-panel notice already conveys. The per-message flag plus
  the `broker.xml` snippet in the panel satisfy non-negotiable #5 without it.
- **Fetch full bodies by paging `browse` under the limit.** Rejected — the limit
  truncates each attribute, not the row count; you cannot page around it.
- **Show the truncated body with no marker.** Rejected outright — non-negotiable
  #5.
