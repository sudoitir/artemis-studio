# ADR-0148: Text carried in a bytes message is decoded once, where Studio reads it

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Artemis Studio maintainers

## Context

Producers commonly send JSON or XML as a Core `BytesMessage`. Studio read every bytes body as opaque
binary: base64 over Core (ADR-0029), nothing at all over Jolokia, which returns only a `BodyPreview`
array for a bytes message. Every consumer then treated the payload as binary. The message view showed a
hex dump, the queue's body column showed base64, the content policy withheld the whole body from
readers without clear access (ADR-0075), and the message index left it out of full-text search by
testing `message_type <> 4` (ADR-0063). The payload was text the whole time.

## Decision

We will decide whether a body is text once, where Studio reads it from the broker, in
`platform/broker/BodyDecoder`. It is used by `CoreMessageTransport.toBrowsed` (browse and capture share
it) and by the Jolokia browse's `BodyPreview` path.

- **Text** is strict UTF-8 (malformed and unmappable input rejected) with no control characters other
  than tab, line feed and carriage return. It becomes an ordinary `TEXT` body. The message type still
  reads bytes.
- **Compressed text**: a body with a gzip or zlib header is inflated, one level, to at most 2 MiB. If the
  result is text, it is the body, and `bodyCompression` names `gzip` or `deflate`. The cap bounds a page of
  200 decompression bombs to 400 MiB; it is also where the message view stops formatting.
- **Anything else is binary** and stays base64, including anything that fails. Decoding never fails a read.
- **Over Jolokia**, the preview is cut at the address's `management-message-attribute-size-limit` with no
  marker. The browse POST also reads `getAddressSettingsAsJSON(address)` (still one batched call per
  node), so a preview at the limit is reported as truncated. A UTF-8 sequence split by the cut is dropped.
- **Binary is a property of the body, not of the message type.** The index stores decoded text and a
  `body_base64` flag; governance, the remasker and full-text search test that flag.
- **Relaying stays byte-faithful.** Transfer (`OutboundMessages`) and the plugin messaging API are not
  touched.

## Consequences

- Readers without clear access now see bytes bodies that are text, masked field by field, instead of
  "withheld". This is deliberate: the same rules and scan limit as any text body apply.
- More bodies are scanned by governance and indexed for full-text search. Both costs are bounded by
  the scan limit and `body_cap_bytes`.
- Binary that happens to be printable UTF-8 is shown as text. Nothing is lost: Download is unchanged, and
  the type still reads bytes.
- Downloading a compressed body gives the decompressed text. The broker copy and every relay keep the
  original bytes.
- Rows indexed before this change keep their stored form (marked `body_base64`) until retention drops them.

## Alternatives considered

- **Decode in the browser only.** Governance, the index, SQL, request-reply and tool access would stay
  blind to the text.
- **Decode in each consumer.** Five sets of rules that drift apart.
- **Store the original bytes in the index as well.** Search, masking and the trigram and full-text indexes
  all need text. A strict UTF-8 round trip is lossless, so the bytes add storage and nothing else.
