# Tasks

Layered so the product works end to end at the end of every group. See `design.md`.

## 1. Groundwork

- [x] 1.1 Measure M1–M6 against the dev pair and record them in `docs/broker-management-notes.md` §15
- [x] 1.2 Write ADR-0067 and its index row
- [x] 1.3 Remove `openspec/changes/05-desired-state-drift/` after confirming every requirement in its delta appears in `specs/broker-configuration/spec.md`

## 2. Domain (pure, no Spring)

- [x] 2.1 `domain/brokerconfig/`: `BrokerConfigDocument` and the four declaration records, Jackson-serialisable with `version: 1`
- [x] 2.2 `AddressSettingKey` catalogue (xml name, json name, type, allowed values, hazard class) covering every key M1 read back; `PermissionType` in the 13-arm order
- [x] 2.3 `BrokerConfigValidator`: catalogue membership, types, enums, ranges, `pageSizeBytes < maxSizeBytes` against the merged page size, unique names, routing-type consistency, `${…}` rejection, referential checks
- [x] 2.4 `BrokerXmlCodec`: StAX parse listing unsupported elements by path; escaped write of the four sections; round-trip test with a placeholder-named fixture
- [x] 2.5 `BrokerConfigPlanner`: ordered diff-driven steps, `ALREADY`, divert replace pair, `DIVERGENT_QUEUE` finding, removals only for owned items, never a queue/address removal, hazard classification with stable ids, `planHash`
- [x] 2.6 Unit tests for 2.3–2.5

## 3. Persistence

- [x] 3.1 `024-broker-configuration.sql`: five tables, column order per non-negotiable #7, storage parameters on `broker_config_node_state`, `--rollback`
- [x] 3.2 Entities (Lombok, `@JdbcTypeCode(SqlTypes.JSON)`), repositories; `./mvnw verify` validates the schema

## 4. Broker operations and services

- [x] 4.1 `broker/brokerconfig/BrokerConfigOperations`: add/remove address setting (JSON arm), add/remove security setting (13-arm), `readObserved` as one batch per node, reusing `DivertOperations` and `QueueLifecycleOperations`
- [x] 4.2 `ManagementRefusal`: classify `AMQ229012` (divert absent) and the address-setting parse/validation refusals as `ARGUMENT`
- [x] 4.3 `BrokerConfigService`: get, save with `expectedRevision` (409 `stale-revision`), mode, revisions, import/export, adopt; `EDIT_BROKER_CONFIG` audit
- [x] 4.4 `BrokerConfigApplyService`: plan (dry run, audited) and apply (lock, plan-hash, acknowledgements, step cap, canary → verify → halt, ownership, audit, SSE `config`, capability evidence)
- [x] 4.5 `BrokerConfigDriftService` + `DynamicSchedules` registration + settings keys `config.drift-interval`, `config.apply-step-cap`
- [x] 4.6 `StateCondition` `CONFIG_DRIFT` + rule template
- [x] 4.7 `Permissions.CONFIG_WRITE` / `CONFIG_APPLY` + catalogue rows; built-in role seeds unchanged, as for `queue:create`, `divert:write` and `capture:write` (ADMIN's `*` grants both; a custom role grants them to operators)
- [x] 4.8 Service tests (Postgres + `@MockitoBean BrokerConnections`): canary success continues; canary failure → all `NOT_ATTEMPTED`; verify `MISMATCH` halts; acknowledgement enforced; lock conflict; audit row shape; drift states

## 5. HTTP

- [x] 5.1 `BrokerConfigController` + `BrokerConfigViews` / `BrokerConfigRequests` with `@Schema`; problem types
- [x] 5.2 Authorization tests on the pattern of `QueueLifecycleAuthorizationTest`
- [x] 5.3 `OpenApiSnapshotTest` → `web/openapi.json` → `npm --prefix web run gen:api`

## 6. MCP

- [x] 6.1 `broker_config` (read) and `broker_config_change` (mutate) with catalogue entries and `McpViews` projections; `McpToolSchemaBudgetTest` green
- [x] 6.2 Correct the stale restart sentence in the routing requirement of `mcp-server`

## 7. Frontend

- [x] 7.1 Hooks in `api/client.ts`; `config` SSE topic in `stream.ts`
- [x] 7.2 Route `configuration` (+ `configuration/apply`) in `router.tsx`, `NAV_ITEMS` entry
- [x] 7.3 `ConfigurationView`: header (revision, mode in words, actions), Declared tab with per-section tables and drift column in words, teaching empty state
- [x] 7.4 Editors in a Drawer (address setting, security setting, divert, address + queues) on the house form pattern; advanced keys behind a disclosure; hazard notes beside fields
- [x] 7.5 Import XML (paste-and-preview: recognised / unsupported / errors) and Export XML
- [x] 7.6 Apply route: Plan → Confirm → Result with `OutcomeSummary`, High hazards acknowledged individually, `ConfirmByTyping` with the cluster name, `aria-live`, four outcomes, halt wording
- [x] 7.7 Drift tab (resolved state first, findings grouped, declared beside observed, evaluate now, link to config diff) and History tab
- [x] 7.8 `CONFIG_MANAGED` disables apply with the reason; `gateFor` on `config:apply` / `config:write`
- [x] 7.9 Tests: validation on blur + focus first invalid; unsupported elements listed; keyboard-only apply pass; acknowledgement gate; halted result; config-managed reason; drift resolved state

## 8. Docs and process

- [x] 8.1 `site/src/guide/broker-configuration.md` + sidebar entries (en/zh/fa); MCP guide tool table corrected and extended
- [x] 8.2 README roadmap rows (en/fa/zh); architecture note if services are listed
- [x] 8.3 `just fmt`; `./mvnw verify`; `just verify-web`; `openspec validate 07-broker-configuration --strict`
- [x] 8.4 Commit on the conventions in `.claude/rules/05-commits.md` (delivered as a patch plus the commit message in `broker-configuration-commit.md`, per the request — no commit made here); archive the change
