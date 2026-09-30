## Context

`CoreMessageTransport.toBrowsed` is the one mapper from a Core/JMS message to a `BrowsedMessage`; both
browse (`CoreMessageTransport` L73, L116) and capture (`CaptureConsumer.read`) use it. A `BytesMessage`
body becomes base64 with `BodyEncoding.BASE64`. Everything downstream keys off that:

- `PolicyEngine` withholds a base64 body from non-clear readers (L67, L190).
- `SqlGovernance.content(Row)` (L102) and `MessageIndexRemasker` (L112) decide "binary" by
  `messageType == 4`, and `IndexQueryExecutor` (L238) and the FTS index (`0001-baseline.sql` L128)
  exclude `message_type = 4` from word search.
- `web/src/features/messages/payload.ts` only magic-sniffs a base64 body, so the drawer hex-dumps it.

Message transfer (`OutboundMessages.from`) copies body buffers verbatim and the plugin messaging API
(`MessageConversion`) hands plugins raw bytes; both must stay byte-faithful.

## Goals / Non-Goals

**Goals:** text carried in bytes is text everywhere, decided once; gzip/deflate text unwrapped safely;
governance masks it; the index stores and searches it; a JSON tree view; Remove moves to Settings.

**Non-Goals:** other charsets (UTF-16, Latin-1), Protobuf/Avro decoding (queued change
30-schema-detection), rewriting stored index rows beyond fixing the binary flag, decoding in transfer or
the plugin API, zip archives, Java deserialization (never).

## Decisions

### D1. Decode once, at the read boundary (`platform/broker/BodyDecoder`)
A small final class with one static method, `decode(byte[] raw) → Decoded(String text, Compression)`,
where `text == null` means binary and `Compression` is `NONE | GZIP | DEFLATE`. `toBrowsed` calls it for
a `BytesMessage`: text → `BodyEncoding.TEXT`, binary → base64 as today. `BrowsedMessage` gains
`Compression bodyCompression`. `size` stays the raw byte length (what the broker holds).

Alternatives: decode in the browser only (rejected: governance, index, SQL, MCP and request-reply stay
blind); decode per consumer (rejected: five sets of rules that drift).

### D2. What counts as text
Strict UTF-8 via `CharsetDecoder` with `CodingErrorAction.REPORT` for malformed and unmappable input
(rejects overlong forms and lone surrogates), then reject any C0 control other than `\t \n \r` and DEL.
A leading BOM is kept in the string, so the text re-encodes to the same bytes and Download stays
faithful. Binary that happens to pass is shown as text: nothing is lost, and Protobuf/Avro almost always
contain control bytes.

### D3. Compression, bounded
If the bytes start with gzip magic `1f 8b`, inflate with `GZIPInputStream`; if they start with a valid
zlib header (CMF `0x78`, `(CMF*256+FLG) % 31 == 0`), with `InflaterInputStream`. Read through a fixed
buffer with a hard output ceiling of **16 MiB** (the largest `body_cap_bytes` capture allows). Reaching
the ceiling, a corrupt stream, or non-text output → binary (the gzip label and hex dump the UI already
has). One level only: the decompressed text is not inspected for further compression. The ceiling is a
constant, not a setting.

### D4. Never fail a read
`decode` catches everything (`IOException`, `RuntimeException`) and returns binary. A browse or a
capture never fails because of a body.

### D5. Governance: no special case
A decoded body arrives as `TEXT`, so `PolicyEngine` applies the text path: detectors, JSON-path rules,
scan limit, sealing. `MessageContent.base64` stays the one "binary" signal. `SqlGovernance.content(Row)`
and `MessageIndexRemasker` switch from `messageType == 4` to the row's stored binary flag.

### D6. Index storage: decoded text, plus a binary flag
New changeset `0005-body-encoding.sql`:
- `ALTER TABLE message_index ADD COLUMN body_compression text` and
  `ADD COLUMN body_base64 boolean NOT NULL DEFAULT false` (boolean last, per the column-order rule;
  a partitioned parent propagates to partitions).
- `UPDATE message_index SET body_base64 = true WHERE message_type = 4` (rows captured before this
  change stored base64; they age out with retention).
- Drop `ix_message_index_body_fts` and recreate it on the parent with
  `WHERE body IS NOT NULL AND NOT body_base64`; `IndexQueryExecutor` uses the same predicate.

`QueryResult.Row` gains `bodyBase64` and `bodyCompression`; the writer, the executor's row mapper,
`SqlGovernance.withContent`, and `SqlViews.RowView` carry them. Stored bodies stay the masked copy;
originals stay sealed. Bytes are not stored: search, masking and the trigram/FTS indexes need text,
and a UTF-8 round trip is lossless.

### D7. DTOs
`MessageSummaryView`, `MessageDetailView` and `SqlViews.RowView` gain `bodyCompression` (`null` when
none). `schema.d.ts` is regenerated.

### D8. Frontend
- `payload.ts`: `PayloadInput.bodyCompression`; a detected text format carries it into the label
  (`JSON · gzip`, `XML · deflate`, `text · gzip`). A base64 body is now only ever true binary; the magic
  table stays.
- `MessageDetailPanel.tsx`: the Formatted/Raw control gains **Tree** when the payload is parsed JSON
  (`format === 'json' && formatted !== null`). `JsonTree.tsx` renders with Mantine `Tree`
  (`useTree`, expanded state; children rendered only when expanded), a search input that filters by key or
  value and expands matching ancestors, text-only labels, an object/array summary (`{3}`, `[12]`), and a
  per-node "Copy path" button producing `body->>'a.b.0.c'`. A key containing `.` or `'` cannot be written
  in that form, so that node offers no copy action.
- `MessagePredicate.jsonPath` steps into arrays by numeric segment (Postgres `#>>` already does), so a
  copied path works against both sources.
- The queue body column, `ResultGrid` and `FlowDetail` read `body`/`bodyPreview` and need no change.

### D9. Remove → Settings
`RemoveCluster` leaves `AddManagementUrl.tsx` for `RemoveClusterSection.tsx`, contributed to
`settings.sections` in the `cluster` group, ordered last. Inline, not a modal: consequence text,
`ConfirmByTyping` with the cluster name, pending state on the button, a failure alert with cause and next
action, an `aria-live` region, disabled with a reason when `useCan` denies cluster removal. On success:
notification, then navigate to the app root. `ClusterHeader` loses the button and the modal; the
`RegisterSection` hint points at the new section.

## Risks / Trade-offs

- **Printable binary shown as text** → harmless: Download is unchanged; the type still reads bytes.
- **Non-clear readers now see bytes bodies (masked)** → deliberate; same rules as text bodies; covered by
  a governance test with a secret in a bytes-JSON body.
- **Governance and index cost grow** (more bodies scanned and indexed) → bounded by the scan limit and
  `body_cap_bytes`; measured on a 200-message browse before and after.
- **Gzip Download returns decompressed text**, not the compressed bytes → acceptable for an inspection
  view; transfer and the broker copy keep the original.
- **Old index rows** keep base64 bodies (flagged binary) until retention drops them → no conversion, per
  the no-backward-compatibility decision.

## Migration Plan

Breaking only in the UI (Remove moves). The changeset runs on start-up; no manual step.
