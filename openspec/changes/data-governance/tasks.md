## 1. Decision and scaffolding

- [x] 1.1 ADR-0075 accepted and linked from `docs/adr/README.md`
- [x] 1.2 Create `platform/governance` module: `package-info.java` (allowedDependencies per design D1), `GovernanceModule` descriptor (required; permissions `message:clear`, `governance:read`, `governance:write`; setting key `governance.scan-limit`; api prefix `/api/v1/governance`), registered in `app/StudioFeatures` (a platform module is scanned by the application, so it has no `@FeatureModule` class)
- [x] 1.3 `GovernanceSettings` contribution: `governance.scan-limit` INT, default 262144
- [x] 1.4 Liquibase `db/changelog/platform/governance/` with `governance_rule`, `classification_finding`, `governance_policy`, seeded built-in CREDENTIAL rules; included from the master changelog
- [x] 1.5 `GovernanceModuleTest` (`@ApplicationModuleTest`); `ModularityTest` and `BoundaryRulesTest` green

## 2. Policy engine (phase 1 core)

- [x] 2.1 Public types `DataClass`, `Action`, `Location`, `Redaction`, `Withheld`, `MessageContent`, `GovernedMessage`, `GovernContext`, `ContentPolicy`
- [x] 2.2 Detectors (PAN+Luhn, IBAN+mod-97, email, phone, bearer/JWT) with unit tests for positives and negatives
- [x] 2.3 Rule matching (address pattern, header/property name glob, case-insensitive) and precedence (rule over detector, exception over detector) with unit tests
- [x] 2.4 Policy snapshot loaded from the DB, reloaded on rule-change event; `version()` from `governance_policy`
- [x] 2.5 `govern` for headers and properties: DROP/PARTIAL/REDACT/CLEAR, clear-access semantics (credentials always dropped), `sealable` collection; `governText` for free text
- [x] 2.6 `context(clusterId, address)` resolving `message:clear` via `PermissionResolver`; `VIEW_CLEAR` audit helper recording classes and counts only

## 3. Egress: messages, MCP, events, audit (phase 1)

- [x] 3.1 `MessageService` browse/detail govern each message; `MessageSummaryView`/`MessageDetailView` built from `GovernedMessage` with `redactions` and `withheld`; `VIEW_CLEAR` audited when clear values served
- [x] 3.2 MCP `browse_messages` returns governed detail; MCP view carries redaction markers; test that `Authorization` never appears
- [x] 3.3 `EventViews.props` values passed through `governText`
- [x] 3.4 `kernel.audit` `AuditParamsFilter` SPI with no-op default, applied in `AuditService.begin`; governance implementation masking detected values and literals compared with classified names; tests
- [x] 3.5 `MessageBrowser.browse` invalid-filter error no longer echoes the selector; test updated
- [ ] 3.6 ArchUnit rule: `..web..`/`..mcp..` do not call raw body/property accessors of `BrowsedMessage` and `QueryResult.Row`

## 4. Governance API (phase 1)

- [x] 4.1 Rules endpoints (list/create/update/delete) with permission checks, built-in delete refused, disable audited, version bump in the same transaction
- [x] 4.2 Integration test: rule CRUD permissions and audit rows
- [x] 4.3 Regenerate `web/src/kernel/api/schema.d.ts`

## 5. Frontend: redacted values and rules (phase 1)

- [x] 5.1 Load `ui-ux-pro-max`; `ui/RedactedValue.tsx` and `ui/WithheldNotice.tsx` using `--as-*` tokens, class in words, keyboard-reachable explanation; tests by role/name
- [x] 5.2 `MessageDetailPanel` renders governed properties/body with `RedactedValue`/`WithheldNotice`; copy/download state masked content
- [x] 5.3 `features/governance` feature (`feature.ts`, `api.ts`, `FEATURE_IDS`, admin nav, `featureView`) with `RulesPanel`: list, create/edit form (labels, blur validation), built-ins not deletable, enable toggle, disabled-with-reason without `governance:write`
- [x] 5.4 Frontend tests for `RulesPanel` gating and form validation

## 6. Body classification and withheld content (phase 2)

- [x] 6.1 JSON body walk with `BODY_PATH` rules and leaf detectors; text/XML detector pass; base64/unknown withheld; scan-limit truncation withheld with setting key; unit tests
- [ ] 6.2 Findings aggregator (bounded in-memory map) and flush `ScheduledJob` with batched upsert; test that no per-message write occurs
- [ ] 6.3 Findings endpoints: list, confirm (creates rule), dismiss (creates exception); audited; integration test
- [ ] 6.4 `FindingsInbox` UI: table, confirm/dismiss with four outcomes, empty and filtered-empty teaching states, aria-live announcement, keyboard pass test (load `ui-ux-pro-max` first)

## 7. SQL console governance (phase 2)

- [x] 7.1 SQL row views (stream rows, tail rows, done result) built from `GovernedMessage` per cluster context; `clearFields` added to the query audit row
- [x] 7.2 SQL audit text masked via the governance filter (literals compared with classified fields)
- [x] 7.3 Predicate guard: refuse predicates on classified headers/properties without `message:clear`, naming the field; body scan predicates evaluated over governed body; tests
- [x] 7.4 SQL grid cells and export use governed values; export notice states masked content (load `ui-ux-pro-max` first)

## 8. At rest (phase 3)

- [ ] 8.1 Changesets adding `sealed bytea`, `sealed_nonce bytea`, `policy_version integer` to `message_index` (feature/sql) and a policy version handling for `rr_event.detail`
- [ ] 8.2 `MessageIndexWriter.observe`/`capturedBatch` store governed masked content with sealed originals and policy version
- [ ] 8.3 `IndexQueryExecutor` re-governs stored rows under the current policy and unseals for clear callers; index-source warning for predicates on fields masked at rest
- [ ] 8.4 `RrCorrelator` stores governed payload preview with seal; `RequestReplyService` flow detail requires `message:read` for payloads (omitted with reason otherwise) and unseals for clear callers
- [ ] 8.5 `GovernanceRemaskJob` batching rows with `policy_version < current`; `GET /governance/remask` progress
- [ ] 8.6 Testcontainers ITs: stored row masked + sealed; full-text search for a stored email finds nothing; unseal only with `message:clear`; credential never stored; re-mask converges after a new rule
- [ ] 8.7 `FlowDetail` renders governed payloads and the omitted-for-permission state; `RemaskProgress` on the governance screen; capture and index subscription copy states masked storage (load `ui-ux-pro-max` first)

## 9. Documentation and verification

- [ ] 9.1 Site guide `site/src/guide/data-governance.md` (classes, built-ins, inbox, clear permission, separation-of-duties role recipe, at-rest behaviour, upgrade note) linked from the guide sidebar
- [ ] 9.2 README roadmap item ticked; `docs/architecture.md` governance section
- [ ] 9.3 `./mvnw verify` and `just verify-web` green
- [ ] 9.4 Live check on the dev stack: send a message with `Authorization` and a test card number; browse as viewer and as admin; run an index query; inspect `message_index` in the DB shell
