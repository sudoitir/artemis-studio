## 1. Measure the broker before anything is built

- [x] 1.1 Against the pinned 2.56 broker on `just dev-up`, over Jolokia as the broker's management user, establish and record: whether `ActiveMQServerControl` exposes bridge creation and destruction, their exact operation signatures, and whether the configuration is a JSON document like `createDivert`'s.
- [x] 1.2 Record exactly which attributes `BridgeControl` exposes for a bridge created that way — this is the whole of what a read-back can compare, and it decides what `createVerified` can report as `ALREADY` versus `FAILED`.
- [x] 1.3 Restart the node and re-read. Record whether a management-created bridge survives, the way ADR-0065 recorded the same question for diverts. Leave the broker as it was found.
- [x] 1.4 If 1.1 or 1.2 fails, take the fallback in design.md Decision 4 — declared bridges, `broker.xml` export and drift, with live bridge apply refused and the configuration shown — and narrow the scope of tasks 4 and 5 accordingly before continuing. Tell the user which branch is being taken.

## 2. Decision record

- [x] 2.1 Write `docs/adr/0090-routing-builder-is-a-canvas-over-the-declaration.md`: why the canvas edits the declaration and not the broker, why it is a mode of the one configuration screen (ADR-0087) rather than a screen of its own, and how authoring-versus-previewing satisfies `operator-ui`'s "a previewed mutation is exactly what is submitted". Reference 0056, 0067, 0071, 0074, 0080, 0082, 0087.
- [x] 2.2 Write `docs/adr/0091-bridges-are-declared-and-applied-over-the-management-api.md`, carrying the measurement from task 1 verbatim in its Context, in the style of ADR-0065. State why the objection behind the superseded prohibition is answered by the declaration, the per-node drift report and the export. Record the negative result honestly if task 1.4 was taken.
- [x] 2.3 Mark the superseded requirement's origin: add the "superseded by 0091" note to ADR-0065's neighbours only where a decision actually changed — do not edit an accepted ADR's decision text.
- [x] 2.4 Write `docs/adr/0092-bridge-credentials-are-vaulted-never-declared.md`: the six disclosure paths and what closes each.
- [x] 2.5 Add all three entries to `docs/adr/README.md`.

## 3. Shared graph layout moves to `ui/`

- [x] 3.1 Move `elkInstance()` and `runLayout(ElkNode)` out of `web/src/features/flow/useFlowLayout.ts` into `web/src/ui/graph/elk.ts`, unchanged — worker when `Worker` exists, bundled build in Node and tests, one instance created once. Keep the CommonJS `constructorOf` handling and its comment.
- [x] 3.2 Update `features/flow/useFlowLayout.ts` to import from there. `flowLayout.ts` and everything else in `features/flow` stay put.
- [x] 3.3 `npm run lint` and the existing flow layout tests pass with no other change. This task lands on its own so a failure here is isolated.

## 4. Bridges in the declaration — model, XML, validation

- [x] 4.1 `feature/brokerconfig/BrokerConfigDocument`: add `TransformerDecl { className, properties }` and `BridgeDecl` — name, queue name, forwarding address, filter, transformer, static connectors or discovery group name, HA, retry interval and multiplier, max retry interval, initial connect attempts, reconnect attempts, duplicate detection, confirmation window size, producer window size, min large message size, check period, connection TTL, routing type, concurrency, client id, credential reference. Give it a `sameAs(other)` mirroring `DivertDecl`'s, and an `effectiveRoutingType()` documenting the broker's default.
- [x] 4.2 Point `DivertDecl`'s transformer fields at `TransformerDecl` without changing what the document serialises, so existing revisions read back unchanged. Correct `DivertDecl.sameAs` to include the transformer: task 1 measured that the broker reports `TransformerClassName` and `TransformerProperties` in full, so the existing exclusion and its comment are wrong.
- [x] 4.3 `BrokerXmlCodec`: parse and write `<bridges><bridge>`, JDK StAX only, namespace-agnostic, reusing the existing `<transformer>` handling. An unrecognised child element is reported in `Unsupported` by path, never dropped silently.
- [x] 4.4 `BrokerConfigValidator`: per-field bridge validation — name against `LifecycleRequests.MANAGEMENT_NAME`, queue name required, exactly one of static connectors and discovery group, numeric ranges, routing type against the broker's set. Each failure names its field.
- [x] 4.5 Test: a document with a bridge round-trips through `BrokerXmlCodec` unchanged, including transformer properties and a filter containing `<`, `&` and a quote; a bridge naming both connector sources and one naming neither are each refused by field.

## 5. Bridges on the broker — operations, plan, apply, drift

- [x] 5.1 `feature/queues/BridgeOperations`, beside `DivertOperations`: move `listBridges` here, and add create, destroy and `createVerified`. The document uses hyphenated broker.xml key names and a nested `transformer-configuration` (task 1). Read back the thirteen attributes `BridgeControl` reports: `ALREADY` when an identical bridge was present; `FAILED` naming the differing fields when one of that name differs; `FAILED` when the broker accepted the request and deployed nothing, naming the transformer class when one was declared.
- [x] 5.1a Account for concurrency in `BridgeOperations`: a bridge declared with a concurrency above one deploys as `<name>-0` through `<name>-(N-1)`, while a concurrency of one or unset deploys the bare `<name>`; `destroyBridge` takes the declared name and removes every instance. Without this the read-back reports a healthy concurrent bridge as failed. Test both shapes.
- [x] 5.2 Update `feature/routing/RoutingService` and `feature/flow/ClientSampler` to read bridges from the new home. No behaviour change.
- [x] 5.3 `Plan.HazardKind` (not `HazardClass`, which is the severity enum): add bridge replacement, bridge removal, bridge creation and an unverifiable transformer, classified High/High/Medium/Medium, each with the wording the plan will show.
- [x] 5.4 `BrokerConfigPlanner`: emit bridge steps with `SECTION:key:OP` ids, ordered after diverts and before nothing; removals in reverse, before the queues and addresses they depend on are removed. A changed bridge plans as a removal followed by a creation.
- [x] 5.5 `BrokerConfigApplyService`: apply bridge steps through `BridgeOperations`, canary-first, halting on the first failure like every other step, and recording ownership so "remove only what we applied" covers bridges.
- [x] 5.6 `BrokerConfigDriftService`: compare declared bridges against each node. Report `started` and `connected` as observed runtime state, separate from configuration drift — a matching but disconnected bridge is a fault, not drift. Compare the transformer class and properties like any other field; they are reported in full. Distinguish matches, differs and not-reported-by-the-broker, and claim nothing about the ten fields `BridgeControl` does not expose (design.md Decision 4a).
- [x] 5.7 Liquibase: `db/changelog/feature/brokerconfig/changes/0003-owned-item-bridge.sql` drops and recreates `ck_broker_config_owned_item_kind` including `BRIDGE`, with a `--rollback`. Never edit the baseline.
- [x] 5.8 Test: plan ordering puts a bridge's creation after its queue's and its removal before it; a changed bridge plans as remove-then-create with the hazard attached; drift separates not-connected from divergent.

## 6. Bridge credentials

- [x] 6.1 Store a bridge's user and password in the existing `SecretVault`; `BridgeDecl` carries only the reference. Apply resolves it at the moment of the broker call.
- [x] 6.2 Confirm `AuditParamsFilter` withholds it, and that the diff between two revisions states that the credential changed without showing either value.
- [x] 6.3 `BrokerXmlCodec.write` emits a placeholder naming the credential to supply, never the secret. `BrokerXmlSnippets` gains `forBridge(...)` on the same StAX discipline as `forDivert`.
- [x] 6.4 Test, one per disclosure path (design.md Decision 5): the stored document, a stored revision, a revision diff, an audit row's parameters, the MCP tool response, and the exported XML each carry no password. The broker exposes neither `User` nor `Password` on `BridgeControl` (task 1), so every path that could leak is one Studio would have created.

## 7. Connector names, MCP and the generated API

- [x] 7.1 `platform/broker`: read the `ConnectorsAsJSON` attribute (an attribute, not an operation — task 1) in the existing batched shape, so the bridge editor can offer a node's connector names. Where the read is unavailable, report it as unknown rather than as empty.
- [x] 7.2 Expose bridges and transformer properties through the existing declaration, plan, diff and drift view records — no new route.
- [x] 7.3 `feature/brokerconfig/mcp`: bridges and transformer properties reachable through the existing configuration tools, with the credential withheld.
- [x] 7.4 Regenerate `web/src/kernel/api/schema.d.ts` from the OpenAPI snapshot.

## 8. The canvas

- [x] 8.1 `features/brokerconfig/routing/routingGraph.ts` — pure: declaration plus observed node state to nodes and edges, each carrying declared-and-observed, declared-not-applied, or observed-not-declared. No origin claim anywhere (ADR-0065 D2).
- [x] 8.2 `features/brokerconfig/routing/routingLayout.ts` — ELK layered options, node sizes per kind, and a layout signature over structure only, modelled on `flowLayout.ts`.
- [x] 8.3 `features/brokerconfig/routing/RoutingCanvas.tsx` — React Flow over `ui/graph/elk.ts`. Node types for address, queue, divert, bridge and remote target. Nothing animates on its own.
- [x] 8.4 `RoutingNodes.tsx` and `RoutingEdge.tsx` — semantic `--as-*` tokens only, logical properties only, state carried in words with colour as redundant emphasis. Add new tokens only if no existing one fits.
- [x] 8.5 `TransformerFields.tsx` — class name plus an editable property table (`KeyValueList` is a read-only `<dl>` and cannot host inputs; it is still reused for the read side in the inspector), with the statement that the class cannot be verified against each broker's classpath before apply. The field stays enabled (ADR-0049 D5).
- [x] 8.6 `BridgeEditor.tsx` — identity fields first, the rest behind the disclosure pattern `DivertEditor` already uses; the connector picker from 7.1 falling back to free text with the uncertainty stated; validate on blur against the same rules the server enforces; autofocus the first invalid field on a rejected submit.
- [x] 8.7 Amend `DivertEditor.tsx` to use `TransformerFields`, closing the existing `transformerProperties: item?.transformerProperties ?? {}` gap.
- [x] 8.8 `RoutingInspector.tsx` — the selected element: what it does, which of the three states it is in, and its edit and remove actions, opening the same drawer editors the Declared tab opens.
- [x] 8.9 Direct manipulation: dragging between two addresses opens `DivertEditor` prefilled; dragging from a queue to a remote target opens `BridgeEditor` prefilled. Nothing is written until the document is saved.
- [x] 8.10 Commit through `useSaveDocument` — "Save as revision N+1", against the revision the document was read from. Apply stays `ReviewApplyDrawer`, untouched.
- [x] 8.11 Add the tab to `ConfigurationView.tsx`'s `Tabs` (`ModeControl.tsx` is the apply-mode popover, not the view switch) and put the anchor and selection in `ConfigurationSearch` so a view can be shared and restored.
- [x] 8.12 Bound the graph above roughly 150 elements to a region anchored on an operator-chosen address, stating the bound and how much is outside it (`operator-ui`, ADR-0056). Use `onlyRenderVisibleElements` within the region.
- [x] 8.13 Link to the builder from `feature/routing`'s divert and bridge tables by path — a router link, not an import, so no new boundary edge is introduced.

## 9. Frontend tests

- [x] 9.1 Query by role and accessible name only, through `src/test/render.tsx`. No class or test-id selectors.
- [x] 9.2 A keyboard-only pass over the canvas: focus enters, arrow keys move between elements, Enter opens the inspector, Escape returns focus to where it entered, and each element's accessible name states what it is and what it connects.
- [x] 9.3 A declared-but-unapplied element is readable as text with colour removed; an observed-not-declared element states that and makes no origin claim.
- [x] 9.4 The bounded view states its bound and what is outside it rather than silently truncating.
- [x] 9.5 Transformer properties round-trip through `DivertEditor` and `BridgeEditor`, and the reachability statement is present and keyboard-reachable.
- [x] 9.6 The existing configuration journey test still passes against the Declared tab unchanged, proving the non-graph equivalent is intact.
- [x] 9.7 Verify contrast at 4.5:1 or better for every new text token, measuring the light scheme independently of the dark one.

## 10. Close out

- [x] 10.1 A real-broker test beside `BrokerConfigApplyRealBrokerTest`: declare a bridge, apply, read it back, change it, remove it, asserting the per-node outcomes and the hazard at each step.
- [x] 10.2 Regenerate `docs/modules/`.
- [x] 10.3 Update `docs/architecture.md` where it maps the configuration screen and the declared sections.
- [x] 10.4 Tick **B · Routing builder** in the `README.md` roadmap, and update the Broker configuration bullet to say bridges are declarable.
- [x] 10.5 `just fmt` then `just verify` — Spotless, Liquibase against Testcontainers Postgres, `ModularityTest`, `BoundaryRulesTest`, `SchemaOwnershipTest`, `AuditCoverageTest`, backend and frontend suites all green.
- [ ] 10.6 End-to-end on `just dev-up`: compose a divert with a transformer and two properties on the canvas, save the revision, apply canary-first, confirm drift closes; then declare a bridge to the second broker, apply, and confirm messages forward; then change it and confirm the plan shows a removal and a creation with the hazard.
- [ ] 10.7 Confirm the scrape issued no additional broker request, and that the only new read is the connector-name read on the configuration screen (non-negotiable #1).

## Notes from apply

### Task 1 — the measurement (2026-09-20)

Run against `artemis-studio-dev-artemis-primary-1` over Jolokia as the broker's management
user, then the broker was returned to the state it was found in (only `DLQ`, `ExpiryQueue`
and `$sys.mqtt.sessions` remain).

**Version.** `Version` reads **2.44.0**. `deploy/compose/compose.dev.yaml` pins
`apache/activemq-artemis:2.44.0`, while `CLAUDE.md` and ADR-0065 both say the project pins
2.56.0 and ADR-0065's own measurement names a container (`artemis-secondary`) that this
compose file does not define. The discrepancy is recorded, not resolved here.

**1.1 — the operations exist.** `ActiveMQServerControl` exposes `createBridge` in five
overloads, including the single-document `createBridge(java.lang.String)` that mirrors
`createDivert(java.lang.String)`, plus `destroyBridge(java.lang.String)`. There is no
`updateBridge`, which confirms that a change must be planned as a destroy and a create.
`addConnector(String,String)` and `removeConnector(String)` also exist. `updateDivert` exists
but stays unused, by the existing decision in `routing-management`.

**The document uses broker.xml's hyphenated key names**, like `divertConfig` already does:
`name`, `queue-name`, `forwarding-address`, `filter-string`, `static-connectors` (array),
`discovery-group-name`, `ha`, `use-duplicate-detection`, `retry-interval`,
`retry-interval-multiplier`, `max-retry-interval`, `initial-connect-attempts`,
`reconnect-attempts`, `confirmation-window-size`, `producer-window-size`,
`min-large-message-size`, `check-period`, `connection-ttl`, `routing-type`, `concurrency`,
`client-id`, `user`, `password`.

**A transformer is a nested object**, on bridges and diverts alike:
`"transformer-configuration": { "class-name": ..., "properties": { ... } }`. Three other
shapes were tried — `transformerConfiguration`/`className`, a flat `transformer-class-name`,
and a nested `transformer` — and all three **deployed the bridge with the transformer
silently dropped**, returning 200.

**An unknown key is accepted and silently ignored.** A camelCase document
(`queueName`/`forwardingAddress`/`staticConnectors`) returned 200 and created nothing at all.
This is the same failure mode `DivertOperations.createVerified` already exists for, and it
makes a read-back mandatory rather than defensive.

**1.2 — what `BridgeControl` reports.** Twenty attributes: `Name`, `QueueName`,
`ForwardingAddress`, `FilterString`, `DiscoveryGroupName`, `StaticConnectors`, `HA`,
`UseDuplicateDetection`, `RetryInterval`, `RetryIntervalMultiplier`, `MaxRetryInterval`,
`ReconnectAttempts`, `TransformerClassName`, `TransformerProperties`,
`TransformerPropertiesAsJSON`, `Started`, `Connected`, `MessagesAcknowledged`,
`MessagesPendingAcknowledgement`, `Metrics`.

Those are what a read-back can compare. **Not reported, and therefore not verifiable:**
`confirmation-window-size`, `producer-window-size`, `min-large-message-size`, `check-period`,
`connection-ttl`, `routing-type`, `concurrency`, `client-id`, `initial-connect-attempts`, and
— importantly for ADR-0092 — `user` and `password`, which the broker never hands back.

**Concurrency changes the object name.** `concurrency: 2` deploys two MBeans named
`<name>-0` and `<name>-1`; `concurrency: 1` and an unset concurrency both deploy one MBean at
the bare `<name>`. `destroyBridge` takes the **declared** name and removes every instance;
called with an already-absent name it returns 200 and does nothing. The read-back must expect
`<name>` for concurrency of at most one and `<name>-0 … <name>-(N-1)` above that, or it will
report a healthy bridge as failed.

**Transformer properties are reported in full, on bridges and on diverts.** A divert created
with `{"k":"v"}` reads back `TransformerProperties = {"k": "v"}`; a bridge created with
`{"a":"1"}` reads back `{"a":"1"}`. This contradicts the assumption in `BrokerConfigDocument`
(*"transformer excluded: the broker does not report it fully"*) and the drift exclusion
written into this change's `broker-configuration` delta. See the open item below.

**1.3 — it all survives a restart.** After `docker restart` and an `Uptime` of 4.459 seconds,
every bridge and divert was still present, with transformer class and properties intact.
ADR-0065's finding for diverts holds for bridges too.

**1.4 — no fallback needed.** Both 1.1 and 1.2 succeeded, so the change proceeds on the full
branch: bridges are declared and applied over the management API, with a read-back.

### Task 2.3 — nothing to supersede in `docs/adr/`

The prohibition on mutating bridges lived only in `openspec/specs/routing-management/spec.md`.
No accepted ADR's Decision section ever carried it — the ADRs that mention bridges (0081) do
so only as something the flow graph draws. So no accepted decision text is edited. The
supersession is recorded in ADR-0091 and in this change's `routing-management` REMOVED block,
which is where it belongs.

### Review pass (2026-09-20)

Two real bugs, both fixed:

- `SchemaBaselineDiffTest` had no `DELIBERATE` pattern for the `BRIDGE`-widened
  `ck_broker_config_owned_item_kind` check, so `just verify` failed outright.
  Added `"CREATE TABLE broker_config_owned_item "` alongside the other post-rebaseline
  patterns.
- `BrokerConfigService.bridgeCredentials` (the `GET /bridge-credentials` read) was gated on
  `BrokerConfigPermissions.CONFIG_WRITE` instead of `Permissions.CLUSTER_READ`, unlike every
  sibling read in this service (`get`, `revisions`, `revision`, `connectors`). This made the
  endpoint 404 for a read-only grant and failed `ClusterScopeAuthorizationTest`. Changed to
  `CLUSTER_READ` — it returns references and usernames only, never a password, so read access
  is the correct gate; `setBridgeCredential`/`forgetBridgeCredential` correctly keep
  `CONFIG_WRITE`.

Everything else reviewed — the six ADR-0092 disclosure paths, the concurrency-suffix
read-back, the `sameAs`/`differencesFrom` exclusion of the ten unreported fields, the second-
write-path check, entity placement, colour tokens and logical CSS, and each `openspec/changes/
routing-builder/specs/*` requirement against its implementation — held up. `just fmt`,
`./mvnw verify` (Docker/Testcontainers), and the frontend's `lint` / `typecheck` / `build` /
`vitest run` are all green after the two fixes above.

### The screenshot pass (2026-09-21)

Captured with `web/scripts/ui-review.ts` (new, `npm run ui-review`, output gitignored) against
the seeded demo stack, then against the working tree through Vite with `STUDIO=...:5174`,
because the container image was built before these fixes and photographing it would have shown
the old code.

**Contrast, measured rather than eyeballed, in both schemes.** Playwright's `colorScheme`
alone does not switch the app - Mantine keys off its own storage - so the first run
photographed the dark scheme twice under two filenames. Forcing
`mantine-color-scheme-value` fixed it, and the harness now does that so it cannot quietly
claim to have checked a scheme it did not render.

| Token | Dark | Light |
| --- | --- | --- |
| `declared, not applied` (attention) | 8.44:1 | 6.67:1 (`#8f4a00`) |
| `declared and observed` (dimmed) | 5.32:1 | 5.1:1 |

Both clear the 4.5:1 floor. No new token was introduced; the canvas reuses existing ones.

**Fixed: the canvas ran off the bottom of the screen.** `block-size` was
`calc(100dvh - 260px)`, but the chrome above it - cluster header, health banner, revision
strip, tab row - is about 466px on a cluster that has anything worth showing in a banner, and
every one of those is conditional, so no fixed subtraction is right. The wrapper now measures
its own top edge into `--as-routing-top` on mount and on resize, and `FitOnLayout` re-fits on a
`ResizeObserver` so the fit is not computed against a box that is about to change.

**Not a defect, twice.** A capture run against a cluster with no declaration photographs
nothing, because the screen is correctly the first-run adoption offer; the harness now adopts
first. And "tabbing never reaches a canvas node" was the harness giving up after 12 tabs with
about twenty sidebar links in front of it - at 40 it reaches one, and the focus ring is real.

**Confirmed working by looking:** direction reads left to right without cross-referencing;
state is carried in words with colour only on an element that is not doing what it was
declared to do, so the healthy view is near-monochrome; the empty state teaches and names the
action; the inspector states that everything on the canvas is also on the Declared & live tab.

**Left alone, deliberately.** Long names still truncate ambiguously
(`ORDERS.events.analy...` beside `ORDERS.events.audit`); the full name is in the element's
accessible name, its `title`, and the inspector. Widening the nodes enough to fix it would
cost more graph per screen than it buys, and the right fix is probably a middle-truncation
that keeps the distinguishing tail. Filed here rather than done.

### Not done

10.6 and 10.7 are unticked and were not performed. The end-to-end walkthrough on a live stack
(compose a transformer divert on the canvas, apply canary-first, watch drift close, then
declare and change a bridge) and the confirmation that the scrape issued no extra broker
request are both real verification steps that this change claims but has not carried out. The
automated equivalents that do exist and do pass are `BridgeLifecycleRealBrokerTest` against a
real 2.44.0 broker and the full `./mvnw verify` and frontend suites.
