## Context

See `proposal.md` and [ADR-0067](../../../docs/adr/0067-broker-configuration-declared-applied-canary-first.md).
The measurements this design rests on are `docs/broker-management-notes.md` §15 (M1–M6).

What exists and is reused:

```
QueueLifecycleService / LifecycleOutcome     outcome vocabulary, audit shape (ADR-0049)
QueueLifecycleOperations, DivertOperations   createAddress, createQueue, createDivert, destroyDivert
CaptureTap                                   the measured addAddressSettings / addSecuritySettings arms
ConfigReader                                 the effective-configuration read, one batch per node
ServingNodes                                 live member per logical node from observed topology
NodeCallLimiter, ClusterLock, DynamicSchedules, SettingsService registry
AuditService.begin / finish                  one event per command, node detail in outcome_detail
ClusterAccessGuard, Permissions.catalogue()  scope walk, permission catalogue
McpToolCatalog, McpArgs, McpErrors, McpViews the MCP surface and its budget test
web: VirtualTable, ConfirmByTyping, OutcomeSummary, CapabilityGate/gateFor, useCan,
     CodeHighlight (xml), the house form pattern (CreateQueueForm)
```

Constraints that follow:

- **At most two batched reads per node per plan, per verification, per evaluation** —
  one for the names and the declared matches, a second for the per-item MBeans those
  names identify. Never a call per declared item. The limiter is acquired before
  every POST.
- **`addAddressSettings` replaces** (M2). Any preview that shows only the declared keys
  is lying by omission.
- **The broker ignores unknown keys** (M1). Studio's validation is the only guard.
- **A second `createDivert` succeeds and changes nothing** (M4). Existence is decided by
  the read, never by the write's return.
- **Backups do not show runtime settings until active** (M3). They are not targets and
  are never reported as missing.
- **Non-negotiable #7** governs the changeset; the per-node state table is high-churn.

## Goals / Non-Goals

**Goals**

- One declared shape per cluster; every live node measured against it.
- Applying to N nodes is one previewed, confirmed, audited command that cannot take a
  whole cluster down at once.
- The broker.xml fragment becomes an export of the same model, in both directions.
- Zero new mechanism for queues and addresses: their steps call the existing operations.

**Non-Goals**

- No reconciliation loop, ever (D8).
- No `broker.xml` write, no `reloadConfigurationFile` (D9).
- No static `<core>` settings (decided out of scope).
- No bridge or cluster-connection mutation (the routing spec forbids it).
- No destruction of a queue or address through an apply (D6).

## Decisions

The twelve decisions are recorded in ADR-0067 D1–D12; this file adds the engineering
detail behind them.

### The document

```
BrokerConfigDocument { version: 1, addresses[], addressSettings[], securitySettings[], diverts[] }
AddressDecl          { name, routingTypes: [ANYCAST|MULTICAST], queues: [QueueDecl] }
QueueDecl            { name, routingType, filter?, durable, maxConsumers?, purgeOnNoConsumers?,
                       exclusive?, lastValue?, nonDestructive?, consumersBeforeDispatch?,
                       delayBeforeDispatch?, ringSize?, autoDelete? }   -- what CreateQueueRequest accepts
AddressSettingDecl   { match, values: { <AddressSettingKey.jsonName>: value } }   -- only declared keys
SecuritySettingDecl  { match, permissions: { <PermissionType>: [role] } }
DivertDecl           { name, address, forwardingAddress, filter?, exclusive, routingType?,
                       transformerClassName?, transformerProperties? }
```

`AddressSettingKey` (D10) is the catalogue: `xmlName`, `jsonName`, `type`
(BOOLEAN | LONG | INT | DOUBLE | STRING | enum), allowed values, `HazardClass`. It covers
the 49 keys M1 read back plus the three the broker accepts and does not echo when unset.
`PermissionType` lists the twelve permission arms in the order the 13-String
`addSecuritySettings` takes them.

### The plan

`BrokerConfigPlanner.plan(document, observedByNode, ownedItems, options) → Plan`:

1. Validate (`BrokerConfigValidator`): catalogue membership, types, enums, ranges,
   `pageSizeBytes < maxSizeBytes` against the *merged* page size of the match (M1),
   unique names, routing-type consistency, unresolved `${…}`, referential checks
   (DLQ/expiry addresses, divert forwarding addresses, queue addresses — declared or
   observed on every live node).
2. Per live node, in order — addresses → queues → address settings → security settings
   → diverts, then removals in reverse: compare declared with observed; emit
   `ADD | REPLACE | REMOVE` steps with `before` / `after`; `ALREADY` where equal. A
   divert that exists with different properties is a `REMOVE` + `ADD` pair
   (`DIVERT_REPLACE`). A queue that exists with different configuration is not a step:
   it is a `DIVERGENT_QUEUE` finding with a link to the queue's own edit flow (M6, and
   ADR-0049's replace semantics on `updateQueue`).
3. Classify hazards (the table in the capability spec). High hazards carry stable ids
   (`<HAZARD>:<node>:<section>:<key>`) so an acknowledgement names one thing.
4. Compute `planHash` over the ordered step list (not the hazards, not the timestamps) so
   the real run can refuse when the cluster moved since the preview.

### The run

`BrokerConfigApplyService.apply(clusterId, request)`:

```
requireCluster(config:apply) → ClusterLock.runIfHeld else 409 apply-in-progress
plan again → planHash mismatch → 409 plan-changed
missing High acknowledgement → 422 hazard-not-acknowledged (ids listed)
steps > config.apply-step-cap and !override → 422 (cap and count stated)
audit.begin(APPLY_BROKER_CONFIG, dryRun=false, params)
for node in [canary] + rest:
    for step in node.steps:  exec (limiter) → APPLIED | FAILED → halt
    read back (one batch) → VERIFIED | MISMATCH (→ halt) | UNVERIFIABLE
    MANAGEMENT_ACCESS acknowledged → the read back must succeed, else halt
    record owned items for APPLIED steps
audit.finish(event, anyFailed, appliedSteps, summary, node×step detail)
publish SSE `config` after commit; evaluate drift for touched nodes
```

Outcome per (node, step): `WOULD_APPLY | APPLIED | ALREADY | FAILED | NOT_ATTEMPTED |
SKIPPED_NOT_LIVE`; per step `verified: VERIFIED | UNVERIFIABLE | MISMATCH`.
Outcome per apply: `DRY_RUN | APPLIED | HALTED | FAILED`.

### Drift

`BrokerConfigDriftService.evaluate(clusterId)`: one observed read per live node;
findings `MISSING | DIVERGENT | UNDECLARED | UNVERIFIABLE`, each naming the node; the
node's state `IN_SYNC | DRIFTED | NOT_EVALUATED | UNREACHABLE`; persisted in
`broker_config_node_state`. `UNDECLARED` only when the cluster enables it, minus the
exclusion patterns. Scheduled through `DynamicSchedules` with
`DynamicTriggers.fixedDelay(settings::configDriftInterval)` under `ClusterLock.runIfHeld`.
`StateCondition` gains `CONFIG_DRIFT`, subjects = drifted nodes.

### Import / export

`BrokerXmlCodec` uses the JDK StAX reader and writer — no new dependency. `parse` walks
`<core>` (or a bare fragment wrapped in a synthetic root), recognises `<addresses>`,
`<address-settings>`, `<security-settings>`, `<diverts>`, and lists every other element
under `<core>` — and every unknown child *inside* a recognised section — as unsupported
with its path. Values of the form `${…}` are errors naming the element. `write` emits the
four sections with the catalogue's XML names, escaped.

### Persistence

`024-broker-configuration.sql`: `broker_config_declaration` (one per cluster: apply mode,
undeclared reporting, current revision), `broker_config_revision` (append-only document),
`broker_config_apply` (plan + outcome per command, dry runs flagged),
`broker_config_node_state` (per-node evaluation; `fillfactor = 80`,
`autovacuum_vacuum_scale_factor = 0.05`), `broker_config_owned_item`. Entities with
Lombok; `@JdbcTypeCode(SqlTypes.JSON)`; intent-named mutators.

### HTTP

`/api/v1/clusters/{clusterId}/config` — `GET`, `PUT` (`expectedRevision`),
`PATCH /mode`, `GET /revisions[/{n}]`, `POST /import-xml`, `GET /export-xml`,
`POST /adopt`, `GET /drift`, `POST /drift/evaluate`, `POST /apply?dryRun&override`,
`GET /applies[/{id}]`. Problem types: `stale-revision`, `plan-changed`,
`apply-in-progress`, `hazard-not-acknowledged`, `config-invalid` (with `fieldErrors`).

### MCP

`broker_config(clusterId, kind = declaration | drift | xml | applies)` and
`broker_config_change(clusterId, op = declare | apply, document?, xml?, nodeIds?,
removeUndeclared?, acknowledge?, dryRun = true, confirm, override)`. Both in
`McpToolCatalog.ENTRIES`; results are `McpViews` projections with a one-sentence message.

### Frontend

A new cluster section, *Configuration*: `tab = declared | drift | history` in the URL;
editors in a Drawer following the house form pattern; Import XML as paste-and-preview in
a monospace `Textarea`; export shown with `CodeHighlight language="xml"` and a
`CopyButton`; a dedicated `configuration/apply` route with Plan → Confirm → Result, the
generic `OutcomeSummary` for both preview and result, `ConfirmByTyping` with the cluster
name, High hazards acknowledged individually; apply disabled-with-reason in
`CONFIG_MANAGED` and behind `gateFor(can('config:apply'), …, managementWrite)`.

## Risks / Trade-offs

- **The reconciler gets added later.** D8 is written to be argued against in an ADR.
- **Replace semantics produce hazard noise on sparse declarations.** Adopt seeds full
  entries; the hazard text says what to declare to keep a value.
- **Ownership is Studio's own record.** Pre-existing runtime items are reported, never
  removed without the opt-in. Stated in the UI.
- **Catalogue rot.** Measured on 2.44.0 and recorded; an unknown key fails loudly.
- **Canary-then-halt leaves a cluster one node ahead on failure.** Deliberate; re-run
  converges; the result says so in words.
- **`getAddressSettingsAsJSON` reports the merged entry, not the literal one.** Drift and
  verification compare declared keys against the merged read at the match string, which
  is what the broker enforces for addresses under it (M5).

## Migration Plan

No data migration: a cluster with no declaration shows the teaching empty state. The
changeset adds tables only. Change 05's folder is removed; nothing was applied from it.
Existing Studio-created diverts are not retroactively owned.
