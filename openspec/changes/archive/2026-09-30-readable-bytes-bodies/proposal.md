## Why

Many producers send JSON or XML as a Core bytes message. Studio reads such a body as opaque binary, so
the message view shows a hex dump, the queue's body column shows base64, a reader without clear access
gets the whole body withheld, and the SQL console can neither read nor search it. The payload is text;
Studio should treat it as text, without the operator having to do anything.

Separately, the cluster's Remove button sits in the header above every view of the cluster, one click
from an irreversible action on every screen. It belongs with the cluster's other settings.

## What Changes

- A bytes message whose body is valid, printable UTF-8 is read as a text body, once, where Studio reads
  it from the broker. Every consumer of that body (message view, queue body column, SQL console, message
  index, request-reply, MCP) sees text. The interface does not announce the decoding; the message type
  still reads `bytes`.
- A gzip- or deflate-compressed body whose content is text is decompressed under a hard size cap, and its
  format is labelled with the compression (e.g. `JSON · gzip`). A body over the cap, or one that is not
  text once decompressed, stays binary.
- Only a genuinely binary body is withheld from a reader without clear access. A decoded body is masked
  field by field, like any text body.
- The message index stores decoded bodies as text, so word, phrase and JSON-path search find them.
- The message view gains a **Tree** presentation for JSON: collapsible, searchable, keyboard-navigable,
  with a per-field "copy path" that yields the SQL console's JSON-path form.
- **BREAKING (UI)**: the cluster's Remove action moves from the cluster header to a "Remove cluster"
  section in the cluster's settings. The header no longer offers it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `message-operations`: format detection covers text carried by bytes messages and compressed text; the
  formatted body gains a tree presentation.
- `data-governance`: only a genuinely binary body is withheld; decoded text is masked like text.
- `message-index`: decoded bodies are stored as text and are searchable.
- `cluster-registration`: removal is offered from the cluster's settings, not from its header.

## Impact

- Backend: `platform/broker` (a body decoder used by `CoreMessageTransport.toBrowsed`, the one mapper
  shared by browse and capture), message DTOs (`bodyCompression`), `feature/sql` (index row and
  governance decide binary by encoding, not message type), one new Liquibase changeset in
  `feature/sql`. Message transfer and the plugin messaging API keep relaying the original bytes
  unchanged.
- Frontend: `features/messages` (payload detection, detail panel, new JSON tree), `features/clusters`
  (header, new settings section), regenerated `kernel/api/schema.d.ts`.
- ADR: bytes bodies are decoded once, at the read boundary.
