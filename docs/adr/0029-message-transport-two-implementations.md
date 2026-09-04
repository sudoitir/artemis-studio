# ADR-0029: `MessageTransport`, two implementations — Core for read/write fidelity, Jolokia for the rest

- **Status**: accepted
- **Date**: 2026-09-04
- **Deciders**: Mahdi Amirabdollahi

## Context

ADR-0002 said the broker-client interface would be "extracted then, from two
real implementations, not guessed up front", where *then* is Phase 4. ADR-0021
made Phase 3 message operations Jolokia-only and disclosed the consequence per
message: the management channel stringifies bodies and truncates oversized
values at `management-message-attribute-size-limit`, so binary payloads are
unusable and the detail panel shows the `broker.xml` snippet that raises the
limit. That disclosure was always a promissory note against the Core client.

Now the Core client exists (ADR-0026). The question is how much of the message
surface gets a Core path.

## Decision

**Supersede ADR-0021.** We will extract `broker/MessageTransport` with two real
implementations:

- **`JolokiaMessageTransport`** — a thin adapter over the existing
  `MessageBrowser` and `MessageOperations`, unchanged. It is the fallback and
  stays the well-tested path.
- **`CoreMessageTransport`** — `browse` via a JMS `QueueBrowser` (real property
  types, real byte bodies via base64, no truncation) and `send` with typed
  `setXProperty` and a `BytesMessage` when the request says the body is base64.

**Only browse and send get a Core path.** Move / retry / delete / expire / purge
are management operations addressed by id or selector; they carry no payload and
have no fidelity dimension, so a Core implementation would be a second way to
invoke the identical broker operation. They stay on `MessageOperations`. The
interface covers exactly the fidelity surface.

- **Selection.** `MessageService.transportFor(clusterId)` returns
  `CoreMessageTransport` when `CoreSubscriptionManager.verdictFor(clusterId)` is
  `Connected`, else `JolokiaMessageTransport` — reusing ADR-0026's cached verdict,
  opening no connection on the read path.
- **Deep-page honesty.** A `QueueBrowser` has no server-side offset, so a page
  past `MessageBrowser.BROKER_PAGE_CAP` (200) is served over Jolokia instead, and
  every browse/detail response carries `transport` (`CORE` / `JOLOKIA`) — the
  channel that *actually* served it. A silent slow path breaks non-negotiable #1
  as surely as a silent lossy one breaks #5.
- **Disclosure.** `MessageDetailView` gains `bodyEncoding` (`TEXT` / `BASE64`),
  `contentType`, and `transport`; `bodyTruncated` is always false on Core. The
  UI shows a "via Core" / "via Jolokia" badge and a base64/binary notice.
- **The safety layer is untouched.** Audit, the per-node call limiter, `?dryRun`,
  the bulk cap and typed confirmation all wrap `MessageService` *above* the
  transport swap, so they apply identically regardless of channel.
- **Internal representation.** `BrowsedMessage.body` stays a `String` (base64
  when the encoding is `BASE64`) rather than widening to `byte[]` — the observable
  contract at the API boundary ("bytes with an encoding indicator") is met
  without the ripple through every call site and test. The design doc's D9
  proposed `byte[]`; this is the smaller equivalent.

## Consequences

- Faithful binary read and typed-property read/write when a Core connection
  exists; honest fallback and honest disclosure otherwise.
- Two transport implementations to keep in step, but the mutation surface is not
  duplicated.
- The Core browse walks the queue locally to page (no server offset); capped at
  the broker page size, past which it is Jolokia's problem.
- ADR-0021 is marked superseded; its decision text is not edited.

## Alternatives considered

- **A full Core transport including mutations** — doubles the mutation surface
  for no fidelity gain and a second thing to keep in step.
- **Widen `BrowsedMessage.body` to `byte[]`** — the design's first form; a much
  larger diff (DTO, mappers, every test) for the same observable behaviour.
- **No interface, a branch in `MessageService`** — ADR-0002 explicitly wanted
  the interface once two implementations existed.
