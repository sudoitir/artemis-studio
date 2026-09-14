## Context

See proposal.md (Why) and ADR-0075 (the binding decision). Constraints that shape the design:

- **No shared message type across paths.**
  - Live reads produce `platform.broker.MessageBrowser.BrowsedMessage`, from Jolokia `decodeRow` or Core `toBrowsed`.
  - The SQL feature flattens it into `feature.sql.QueryResult.Row`, built separately by `BrokerQueryExecutor`, `CaptureConsumer` and `IndexQueryExecutor`.
  - Request-reply stores a `bodyPreview` string in `rr_event.detail`.
- **Module edges** (Spring Modulith plus ArchUnit): `sql → messages`, `rr → sql`. `platform.broker` and `kernel.audit` cannot import a feature or another platform module.
- **Permissions** are strings. `PermissionResolver.can(clusterId, perm)` walks global → environment → cluster, and wildcards cover everything.
- **Settings** are global `SettingDef`s of kind DURATION, INT or CRON.
- `SecretVault.encrypt(aad, plaintext)` / `decrypt(aad, ciphertext, nonce)` already provides AES-GCM with AAD.
- **`message_index`** is range-partitioned, with trigram and full-text GIN indexes over `body` (ADR-0063). Its capture writes are batched (ADR-0062 D7) and must never block producers (ADR-0066).

## Goals / Non-Goals

**Goals:**
- One policy decision per message, reused by every egress and persistence path.
- Compile-time impossibility of emitting ungoverned content from web and MCP.
- No raw sensitive value in Studio's Postgres, including the audit trail.
- Bounded classification cost.

**Non-Goals:**
- **Content search and predicate-based deletion.** That is the roadmap item "C · Compliance tooling".
- **Audit export and retention controls.** That is "C · Audit export".
- **Per-environment or per-cluster rules.** Rules are global with an address filter.
- **XML path rules.** XML and text bodies get detectors only.
- **Classifying binary formats such as protobuf or Avro.** They are withheld instead.
- **A wildcard-exempt permission.** Recorded as deferred in ADR-0075.
- **Key rotation for sealed originals.** Retention bounds the lifetime of sealed data, and vault rotation is its own concern.

## Decisions

### D1. Module and public API

`platform.governance` is a required module with `allowedDependencies = kernel.core, kernel.plugin, kernel.security, kernel.audit, kernel.settings, kernel.jobs`. The `messages`, `sql`, `rr` and `events` features add `platform.governance` to their allowed dependencies.

The public types sit in the module's root package:

```java
record GovernContext(UUID clusterId, String address, boolean clearAccess)
interface ContentPolicy {
  GovernContext context(UUID clusterId, String address);          // resolves clearAccess via PermissionResolver
  GovernedMessage govern(GovernContext ctx, MessageContent content);
  String governText(String text);                                  // detectors only, for free text
  int version();
}
record MessageContent(Map<String,String> headers, Map<String,Object> properties,
                      String body, BodyEncoding encoding, String contentType, boolean bodyTruncated)
record GovernedMessage(Map<String,String> headers, Map<String,Object> properties, String body,
                       List<Redaction> redactions, List<Withheld> withheld,
                       Map<String,String> sealable, int policyVersion)
record Redaction(Location location, String path, DataClass dataClass, Action action, boolean clear)
record Withheld(Location location, String reason, String settingKey)
enum Location { HEADER, PROPERTY, BODY }
enum DataClass { CREDENTIAL, PAN, IBAN, EMAIL, PHONE, NATIONAL_ID, PERSONAL }
enum Action { DROP, PARTIAL, REDACT, CLEAR }
```

`MessageContent` is a neutral input. Callers adapt `BrowsedMessage` or `Row` into it, so governance never depends on `feature.sql`. `platform.broker` is not a dependency, and governance defines its own `BodyEncoding` mapping from a `boolean base64`.

**Alternative rejected:** overloads per source type. That would force governance to depend on `feature.sql`, reversing the module edge.

### D2. Evaluation order

For each header and property:

1. Collect the rules matching `(address, location, name)`. An explicit rule wins over a detector.
2. If none match, run the value detectors unless a dismissal exception covers `(address, path, class)`.
3. Apply the action for the resulting class. A `CLEAR` override from an exception or rule leaves the value as it is.

**Body:**
- A JSON content type, or a body that parses as a JSON object or array: walk the leaves with the existing Jackson `ObjectMapper`, apply the `BODY_PATH` rules (simple dotted path with `[*]`), then run the detectors on the string leaves. Re-serialise.
- Text or XML: run the detectors over the text with regex replacement.
- Base64 or undetectable: withheld.
- Bytes beyond the scan limit: truncated and withheld with `settingKey = governance.scan-limit`.

**With clear access,** values remain as they were and `Redaction.clear = true`. `CREDENTIAL` is still dropped, and withheld content is shown.

**Sealing:** `sealable` maps `location:path` to the original for every non-credential redaction. It is filled only when the caller asks for sealing (persistence paths).

### D3. Detectors

These are hand-written, with no dependency:

| Class | Pattern and check |
| --- | --- |
| PAN | `\b(?:\d[ -]?){13,19}\b`, digits stripped, then Luhn |
| IBAN | `\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b`, then mod-97 |
| EMAIL | conservative RFC-5322-lite regex |
| PHONE | E.164 `\+\d{8,15}`, plus separated groups of at least 10 digits |
| CREDENTIAL | `(?i)bearer\s+[A-Za-z0-9._~+/-]+=*`, and JWT `eyJ[\w-]+\.[\w-]+\.[\w-]*` |

`NATIONAL_ID` and `PERSONAL` come from rules only, because formats vary by country.

**Alternatives:** commons-validator (a new dependency for about 20 lines), or ML/NER-based detection (heavy, and nondeterministic for audit evidence).

### D4. Rules, exceptions and findings persistence

These live in the `db/changelog/platform/governance/` changesets. Columns are ordered by the padding rule.

| Table | Columns |
| --- | --- |
| `governance_rule` | `updated_at timestamptz`, `address_pattern text NULL`, `target text` (HEADER, PROPERTY, BODY_PATH), `selector text`, `data_class text`, `action text NULL`, `id uuid`, `builtin boolean`, `enabled boolean`, `is_exception boolean` |
| `classification_finding` | `first_seen_at`, `last_seen_at`, `hit_count bigint`, `address text`, `location text`, `field_path text`, `data_class text`, `status text` (OPEN, CONFIRMED, DISMISSED), `id uuid`; unique `(address, location, field_path, data_class)` |
| `governance_policy` | single row: `updated_at`, `version integer` |

- A dismissal is a rule with `is_exception = true` and `action = CLEAR`.
- Built-in credential rules are seeded by a changeset with fixed UUIDs.
- The policy is cached in memory as an immutable snapshot. Every rule write bumps `version` in the same transaction and publishes an application event, and the snapshot reloads on that event.

**Findings** are aggregated in a bounded in-memory map keyed by the unique tuple (at most 10k keys; beyond that, new keys are dropped and counted). A `ScheduledJob` flushes them every 30 seconds with one `INSERT … ON CONFLICT DO UPDATE` batch. There is no write per message.

### D5. Choke points

| Path | Change |
| --- | --- |
| `MessageService.browse` / `detail` → `toSummary` / `toDetail` | Adapt → `govern` → views take `GovernedMessage`. `bodyPreview` is taken from the governed body. The views gain `redactions` and `withheld`. MCP `browse_messages` reuses `detail`, so it inherits this. |
| `SqlStreamController` `Session.row` / `done` via `SqlViewMapper.toView` | Takes `(Row, GovernedMessage)`. The planner computes a per-cluster `GovernContext` once per query. |
| `MessageIndexWriter.observe` / `capturedBatch` | Govern with sealing. Bind the masked body and props, `sealed` / `sealed_nonce` from `SecretVault.encrypt("governance:message_index:" + clusterId + ":" + nodeId + ":" + messageId + ":" + observedAt, json(sealable))`, and `policy_version`. |
| `IndexQueryExecutor.toRow` | Re-govern the stored row under the current policy. With clear access, and when the row has a seal, decrypt it and restore the originals before governing with clear. |
| `RrCorrelator.capturedPayload` | Govern the preview with sealing into `rr_event.detail` (`bodyPreview` masked, `sealed`, `nonce` base64, `policyVersion`). |
| `RequestReplyService.eventViews` | Require `message:read` for detail, otherwise omit it with a reason. Unseal for clear. |
| `EventViews.props` | `governText` over each string value. |
| `AuditService.begin` | `kernel.audit` declares `interface AuditParamsFilter { Map<String,?> filter(Map<String,?> params); }`, with a no-op default bean (`@ConditionalOnMissingBean`). Governance implements it: string values go through `governText`, and SQL or selector literals compared with classified names are masked (regex over `name <op> 'literal'`). |
| `MessageBrowser.browse` | Invalid-filter message becomes `"Invalid message filter"`. |

**Compile enforcement:** the view constructors and index-writer bind methods take `GovernedMessage`. An ArchUnit rule in `BoundaryRulesTest` states that no class in `..web..` or `..mcp..` calls `BrowsedMessage.body()`, `bodyPreview()` or `*Properties()`, or `QueryResult.Row.body()` or `properties()`.

### D6. SQL predicate guard

After parsing, `QueryPlanner` walks the predicate's column references. For each target cluster where the caller lacks `message:clear`, a `props->>'name'` or header column that matches an enabled non-exception rule for any target address, or a detector-only class, refuses the query with `CostRefusedException`-style semantics. The refusal is a new `GovernanceRefusedException` naming the field. Body predicates are evaluated in `MessagePredicate` against the governed body for such callers, and the broker pushdown of body predicates does not exist today. On the index source, a clear caller gets a warning when the predicate names a field masked at rest.

### D7. Re-mask job

`GovernanceRemaskJob` is a `ScheduledJob` running every minute. For each table:

```sql
UPDATE … SET … WHERE ctid IN (SELECT ctid … WHERE policy_version < :v LIMIT 500)
```

It is implemented as select, govern in Java, batch update, and repeats until a per-run time budget of 10 seconds is spent. It needs originals: it decrypts the seal and merges it with the stored masked values, so a newly classified field becomes masked and sealed. A value that was clear and is newly classified is sealed from the stored clear value.

Progress is `SELECT count(*) WHERE policy_version < :v`, served capped by `/api/v1/governance/remask`.

### D8. API

`/api/v1/governance`:

| Endpoint | Permission |
| --- | --- |
| `GET/POST /rules`, `PUT/DELETE /rules/{id}` | `governance:read` / `governance:write` |
| `GET /findings?status=` | `governance:read` |
| `POST /findings/{id}/confirm`, `POST /findings/{id}/dismiss` | `governance:write` |
| `GET /remask` | `governance:read` |

Every write goes through `AuditService`.

### D9. Frontend

- `web/src/features/governance/` has `feature.ts` (route `/governance` in the admin nav group, permission `governance:read`), `api.ts`, `RulesPanel.tsx`, `FindingsInbox.tsx` and `RemaskProgress.tsx`.
- `web/src/ui/RedactedValue.tsx` and `WithheldNotice.tsx` take the generated DTO descriptors. They are used by `MessageDetailPanel`, the SQL grid cell renderer and `FlowDetail`.
- Tokens come from `--as-*` only. The UI skill `ui-ux-pro-max` is consulted before each UI task.

## Risks / Trade-offs

| Risk | Mitigation |
| --- | --- |
| Classification on the capture drain slows it until the ring drops messages. | A scan limit, precompiled patterns, and a single pass per string. A benchmark test over 10k × 4 KiB JSON messages asserts throughput. Loss is still measured and reported. |
| False positives: a 16-digit order id that passes Luhn gets masked. | The inbox dismissal exception. Operators with `message:clear` still see the value. |
| Egress re-governing of stored rows has to rebuild originals for clear callers. | It decrypts only for clear callers, bounded by the query row cap. |
| Regex-based literal masking in audit SQL misses exotic quoting. | The SQL console's own AST provides literals for SQL-console audit params. The regex fallback covers selectors, and the detectors run over the whole string anyway. |
| Vault key loss makes sealed originals unrecoverable. | Documented. Masked data stays readable, and retention bounds the loss. |
| A `*` administrator sees clear PII. | A recorded trade-off (ADR-0075). The guide shows a separation-of-duties role recipe. |

## Migration Plan

- The changesets only add tables and nullable columns. Existing rows get `policy_version = 0`, so the re-mask job governs them after deploy. The guide states that until it converges, rows indexed before the upgrade are protected on egress only.
- Rollback: disable nothing, since governance is a required module. Revert the release. The added columns are ignored by older code.
- Deployments relying on VIEWER to read request-reply payloads must grant `message:read`. This is stated in the commit body as BREAKING.
