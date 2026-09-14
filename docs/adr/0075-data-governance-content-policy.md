# ADR-0075: Data governance is a content policy enforced at typed choke points

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainer

## Context

Studio reads broker messages and hands their headers, properties and bodies to people, API clients and assistants. It also stores them in the message index (ADR-0062, ADR-0063) and in request-reply payloads. Nothing masks any of it:

- a `VIEWER` sees card numbers;
- the index full-text-searches raw bodies;
- SQL literals land in `audit_event.params`;
- an `Authorization` header is shown like any other string.

Installations under GDPR or PCI DSS need sensitive values masked on every way out, not stored in clear in Studio's own database, visible in clear only by grant, and every clear view evidenced.

Content leaves Studio through at least eight paths:

- browse and message detail;
- MCP `browse_messages`;
- the SQL stream, for broker, index and tail;
- index and capture writes;
- request-reply payloads;
- broker event properties;
- audit parameters;
- the UI's export.

No single message type is shared by all of them. A single "mask in one place" hook either sits too early, before the originals needed for clear access and sealing are captured, or misses paths.

## Decision

1. **A required platform module, `platform.governance`, owns the policy.**
   - **Data classes** are a fixed set, each with a default action:
     - `CREDENTIAL` → drop;
     - `PAN` → partial, last four digits kept;
     - `IBAN` → partial;
     - `EMAIL`, `PHONE`, `NATIONAL_ID`, `PERSONAL` → redact.
   - **Rules** are global, optionally narrowed by address pattern. A rule targets a header, a property name glob, or a JSON body path.
   - **Detectors** (Luhn, mod-97, regex, bearer/JWT) run over property and body values. A detection with no matching rule is masked and recorded as a finding to review, never merely suggested.
   - **Uninspectable content** (a non-text body, or bytes beyond the scan limit) is withheld with a stated reason.
2. **Typed choke points.** `ContentPolicy.govern(context, message)` returns a `GovernedMessage`: the values to emit, plus descriptors of what was redacted and what was withheld. Every mapper that serialises or persists message content accepts only a `GovernedMessage`, so an ungoverned path does not compile. An ArchUnit rule forbids web and MCP packages from reading raw body and property accessors.
3. **Clear access is a permission, covered by wildcards.** `message:clear`, at the cluster's scope, shows sealable classes in clear. `CREDENTIAL` values are never shown. Serving clear content is audited per query as classes and counts. We accept that `*` and `message:*` include it rather than change kernel grant semantics. An installation that separates duties grants administrators a custom role without the wildcard.
4. **Masked at rest, original sealed.**
   - Stored rows hold the masked form, so the trigram and full-text indexes and `body->>` queries never see a sensitive value.
   - The originals of sealable values are encrypted with the existing `SecretVault` (AES-GCM). The AAD binds the blob to its table and row. `CREDENTIAL` values are never sealed.
   - Each stored row records the policy version it was masked under.
5. **A policy change is enforced twice.** On the way out, stored rows are re-governed at once. At rest, a background job re-masks rows below the current version in bounded batches and reports progress. Retention bounds its work.
6. **Audit parameters are filtered by a kernel SPI.** `kernel.audit` defines `AuditParamsFilter`, and governance implements it. The audit module never imports a feature or platform module.
7. **Constraint on message replay.** Replay (`03-message-replay-from-payload`) replays from unsealed originals and requires `message:clear`. It refuses a message whose credentials were dropped or whose content was withheld, because it cannot replay that message faithfully.

## Consequences

- Every new path that emits message content has to go through the policy to compile. That friction is intended.
- Classification costs CPU on every governed message. The scan limit bounds it, and it must be measured on the capture drain path, which must keep pace (ADR-0066).
- Search over the index cannot find a masked value. Operators with clear access who need that search query the broker source instead, and the planner says so.
- Dropping credentials is irreversible. A message that relied on one cannot be replayed from Studio.
- Losing the vault key makes sealed originals unrecoverable. The masked rows stay readable.
- Detectors produce false positives. The inbox makes dismissing one an explicit, audited act, rather than leaving data unmasked by default.

## Alternatives considered

- **Mask in the broker mappers.** One hook, but the originals are gone before clear access or sealing can use them. Broker-side selectors on masked fields would still act as an oracle, and stored rows would still need a separate path.
- **Jackson serialisation filter with `@Sensitive` fields.** No cluster, address or principal context. It misses persistence, the SQL stream and audit text, and a new DTO field leaks until someone annotates it.
- **Irreversible masking at rest.** Simpler and stronger, but clear access and replay become impossible for anything already stored.
- **Encrypt whole rows at rest.** It either disables full-text search for everyone or indexes decrypted text, which defeats the purpose.
- **Suggest-only classification.** Personal data flows in clear until a human reviews it, which is indefensible under PCI DSS.
- **A sensitive permission exempt from wildcards.** Stronger separation of duties, but it changes kernel grant semantics for one permission. That was deferred: see decision 3.
