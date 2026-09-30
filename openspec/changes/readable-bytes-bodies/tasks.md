## 1. Decode at the read boundary

- [x] 1.1 `BodyDecoderTest` (red first): UTF-8 JSON, BOM round trip, invalid/overlong UTF-8, lone surrogate, NUL, other C0, gzip JSON, deflate JSON, gzip of binary, corrupt/truncated gzip, 1 GB-of-zeros gzip stops at the ceiling, nested gzip stays gzip text-free, empty
- [x] 1.2 `platform/broker/BodyDecoder` + `Compression` enum (D1–D4)
- [x] 1.3 `BrowsedMessage.bodyCompression`; `CoreMessageTransport.toBrowsed` uses the decoder; `size` from raw length; fix every `new BrowsedMessage(` site
- [x] 1.5 Jolokia browse reads a bytes message's `BodyPreview` through the decoder, flags a preview cut at the address's `management-message-attribute-size-limit` as truncated (the same batched POST reads the address settings); unit test with fixtures
- [x] 1.4 `CoreMessageTransportTest`: a bytes-JSON message browses as TEXT, a gzip-JSON one as TEXT + GZIP, a binary one stays BASE64; a moved bytes message keeps its bytes (existing transfer test or one assertion)

## 2. Messages API and governance

- [x] 2.1 `bodyCompression` on `MessageDetailView` (the summary's preview needs no label), mapped in `MessageService`
- [x] 2.2 Governance test: a bytes-JSON body with a masking rule is masked for a non-clear reader, not withheld; clear reader sees the original

## 3. Message index

- [x] 3.1 Changeset `0005-body-encoding.sql` (D6)
- [x] 3.2 `QueryResult.Row` gains `bodyBase64`, `bodyCompression`; `CaptureConsumer.toRow`, `MessageIndexWriter`, `IndexQueryExecutor` (select + FTS predicate), `MessageIndexRemasker`, `SqlGovernance`, live-broker row mapping (the SQL console's drawer reads compression from the message detail, so `RowView` is unchanged)
- [x] 3.3 `MessagePredicate.jsonPath` steps into arrays by numeric segment (+ test)
- [x] 3.4 Index IT: a captured bytes-JSON message is found by `body->>'status'` and by MATCH

## 4. Frontend body views

- [x] 4.1 Regenerate `web/src/kernel/api/schema.d.ts`
- [x] 4.2 `payload.ts` compression label (+ `payload.test.ts`)
- [x] 4.3 `JsonTree.tsx` (Mantine `Tree`, ctx7 for the API) + Tree option in `MessageDetailPanel`; tests: collapse/expand, search, copy path, keys rendered as text, no copy for dotted keys

## 5. Remove → Settings

- [x] 5.1 `RemoveClusterSection.tsx` + contribution in `features/clusters/feature.ts`; delete from `ClusterHeader.tsx` and `AddManagementUrl.tsx`; update the `RegisterSection` hint
- [x] 5.2 Tests: typed confirm arms the button, failure shows cause, success navigates away, keyboard-only pass, header has no Remove

## 6. Decision record and verification

- [ ] 6.1 ADR 0147 "Bytes bodies are decoded once, at the read boundary"
- [ ] 6.2 `just fmt`, then `just verify` once
- [ ] 6.3 Run Studio on its own compose project: send bytes-JSON, gzip-JSON and binary over Core; check queue → Messages (body column, drawer Formatted/Tree/Raw), SQL console search, Settings → Remove cluster; light and dark screenshots; stop the stack and delete its volumes
- [ ] 6.5 Security review of the branch diff with the `security-review` skill; fix every finding before the PR
- [ ] 6.4 Browse timing for 200 × 64 KiB bytes-JSON messages before and after, noted in the PR
