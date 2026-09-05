## 1. Slice 0 — clean base and broker surface spike

- [x] 1.1 Branch `v1-hardening` and commit the existing 9-file working tree, so the four slices start from a clean base
- [x] 1.2 Bring up the dev stack (`just dev-up`): primary `:8161` broker="primary", backup `:8261` broker="backup"
- [x] 1.3 **Q1** — read the broker MBean on both sides; record whether a passive backup answers management reads at all and how much of the surface it exposes
- [x] 1.4 **Q2** — capture the full attribute list and operation catalogue from both sides (`/list`)
- [x] 1.5 **Q3** — exec `getAddressSettingsAsJSON("#")` and record whether `slowConsumerThreshold`, `-CheckPeriod` and `-Policy` are actually returned, or only `slowConsumerThresholdMeasurementUnit` as the Phase-0 capture suggests
- [x] 1.6 **Q4** — record whether `getRolesAsJSON` and the acceptor MBeans (`component=acceptors,*`) exist and answer
- [x] 1.7 **Q5** — check `010-broker-events.sql`'s type CHECK constraint against `CONSUMER_SLOW`
- [x] 1.8 Write `## 14. v1.0 surface checks` in `docs/broker-management-notes.md` with a verdict table, in the style of the existing "Phase N surface checks" sections
- [x] 1.9 Record the consequences of the verdicts: whether native slow-consumer state is UNKNOWN (Q3), whether the `securitySettings` / `acceptors` diff sections exist (Q4), whether a new changeset is needed (Q5), and whether the pair diff degrades to a stated-limitation mode (Q1)

## 2. Slice 1 — topology view

- [x] 2.1 `layout.ts`: emit a `pair` group node per `LogicalNodeView` with the two endpoints as children (`parentId`, `extent: 'parent'`), geometry derived from the existing `COL_W` / `LIVE_Y` / `BACKUP_Y`; keep the function pure
- [x] 2.2 `layout.ts`: move `axisStatus` off the canvas-level `TopologyLayout` onto each group's data; keep a cluster-level roll-up for the screen-reader summary
- [x] 2.3 `layout.ts`: export the mark vocabulary (or a legend shape) so the legend and the node marks cannot drift apart
- [x] 2.4 Extend `layout.test.ts`: one group per logical node; children carry `parentId`; split-brain puts both endpoints above the group axis; a lone unmanaged endpoint still produces a group
- [x] 2.5 `TopologyGraph.module.css`: delete `.axis` / `.axisNote`; add `.group`, `.groupAxis`, `.groupId`
- [x] 2.6 `TopologyGraph.module.css`: marks by shape at 9–10px — `live` filled disc, `standby` hollow ring (1.5px), `behind` half-filled, `unmanaged` dashed ring; `down` and `split-brain` keep amber/red
- [x] 2.7 `TopologyGraph.module.css`: `:focus-visible` outline on `.node`; `var(--mantine-font-family-monospace)` on `.badge` and the NodeID; `block-size: clamp(420px, 60vh, 720px)` replacing the fixed 460px
- [x] 2.8 `TopologyCanvas.tsx`: register the `pair` node type; `fitViewOptions={{ padding: 0.15, maxZoom: 1 }}`; `<Controls showInteractive={false} />`
- [x] 2.9 `TopologyCanvas.tsx`: re-fit via `useReactFlow().fitView()` keyed on the logical-node ids, so a failover does not leave a stale viewport
- [x] 2.10 `TopologyCanvas.tsx`: static legend row beneath the canvas (four marks, two edge styles, the axis) — not a floating `<Panel>`
- [x] 2.11 `TopologyCanvas.tsx`: empty state for a cluster with no nodes — "No nodes yet. Studio learns the topology from the first broker it reaches." plus the add-a-URL action
- [x] 2.12 React context carrying the optional `onAddManagementUrl(endpointId)` handler; `RegisterCanvas` passes none and the CTA renders as plain text there
- [x] 2.13 `TopologyGraph.tsx`: wire the real `AddManagementUrl` modal (`web/src/clusters/AddManagementUrl.tsx`, currently imported nowhere) to the unmanaged card's button; copy becomes "Add a management URL"
- [x] 2.14 `TopologyView.tsx`: replace the bare `<Loader size="sm">` with a skeleton occupying the canvas frame
- [x] 2.15 Update `RegisterCanvas.tsx` and `examples.ts` for the group change (shared canvas)
- [x] 2.16 Verify the failover-animation claim in the browser (`docker compose -f deploy/compose/compose.dev.yaml stop artemis-primary`); then either move the `transition: transform` to `.react-flow__node` or delete the claim at `TopologyGraph.tsx:13`
- [x] 2.17 `npm --prefix web test -- layout` and `npm --prefix web run lint` pass

## 3. Slice 2 — payload inspection helpers

- [x] 3.1 Add `shiki` to `web/package.json`
- [x] 3.2 `main.tsx`: `CodeHighlightAdapterProvider` with `createShikiAdapter(loadShiki)`, `loadShiki` using a dynamic `import('shiki')`; `langs: ['json','xml','yaml','sql','properties']`, `themes: []`
- [x] 3.3 Confirm against `vite build`'s chunk report that the entry chunk did not grow, and that the existing `broker.xml` snippets now highlight
- [x] 3.4 `web/src/messages/payload.ts`: detection in order — declared content type (`contentType` on the DTO or `_AMQ_CONTENT_TYPE` / `contentType` / `content_type` property) wins; TEXT structural probe (`{`/`[` → `JSON.parse`; `<` → `DOMParser` **with `parsererror` checked**); BASE64 magic bytes (gzip `1F 8B`, zip `50 4B 03 04`, Java-serialized `AC ED 00 05`, Avro `4F 62 6A 01`); else TEXT
- [x] 3.5 `payload.ts`: formatting — JSON via `JSON.stringify(JSON.parse(x), null, 2)`; XML via a recursive indent walk over the `Document` already parsed in detection
- [x] 3.6 `payload.ts`: truncation path — a `bodyTruncated` body that fails to parse reports "can't format: the broker truncated this body", never "not JSON"
- [x] 3.7 Measure `JSON.parse` + Shiki on synthetic 1 / 5 / 20 MB bodies, then fix the three named size ceilings (detect-prefix, pretty-print, highlight) from the measurements
- [x] 3.8 `web/src/messages/HexDump.tsx`: hex + ASCII dump of the first N bytes for binary bodies; never `TextDecoder` a binary body into the code block
- [x] 3.9 `payload.test.ts`: declared type wins over a conflicting body; valid JSON; truncated JSON reports truncated not "not JSON"; XML with a `parsererror`; each of the four magic-byte kinds; empty body; a body over each size ceiling
- [x] 3.10 `MessageDetailPanel.tsx`: format badge next to "Body", formatted/raw toggle, copy and download; above-ceiling fallback to plain `<pre>` with "Formatting is off for a body this size"
- [x] 3.11 `MessageDetailPanel.tsx`: map the JMS type int to a name, reading the constants off `org.apache.activemq.artemis.api.core.Message` in the `artemis-jakarta-client` jar
- [x] 3.12 `npm --prefix web test -- payload`, `npm --prefix web run build` and `npm --prefix web run lint` pass

## 4. Slice 3 — slow-consumer detection

- [x] 4.1 `CapabilityProbe`: read the slow-consumer fields from `getAddressSettingsAsJSON` and report native detection three-state — configured / off / **UNKNOWN** when the threshold is not exposed (per spike Q3)
- [x] 4.2 `BrokerXmlSnippets.forSlowConsumerDetection()`, following the existing `NOTIFICATIONS_SECURITY_SETTING` / `NOTIFICATION_PLUGIN` pattern
- [x] 4.3 `EventStreamPublisher.topicFor`: `case "CONSUMER_SLOW" -> "consumers"`, preserving `_AMQ_ConsumerName` attribution
- [x] 4.4 If spike Q5 showed the CHECK rejects `CONSUMER_SLOW`: add a **new** changeset (never edit `010-broker-events.sql`)
- [x] 4.5 Resolve the paused-queue scope explicitly — either add `paused` to `queue_snapshot` (new changeset + scrape field) or state in the rule UI that paused queues fire; do not leave it silent
- [x] 4.6 `domain/alerting/SlowConsumerCondition.java`: metric `ackRatePerConsumer`; universe = `(node, queue)` with `consumerCount > 0` **and** `messageCount > 0` from `QueueSnapshotRepository`
- [x] 4.7 `SlowConsumerCondition`: value = `messagesAcked` rate ÷ `consumerCount`, taking the rate from `MetricSeriesRepository.latestRateBySubject` over the existing 2 × tier-B window — reuse, do not reimplement; fewer than two samples → absent, not zero
- [x] 4.8 `SlowConsumerCondition`: subject keys follow `GaugeCondition`'s convention (`queue:<name>`, `node:<id>/queue:<name>` when node-scoped)
- [x] 4.9 `AlertEvaluator.conditionFor`: select `SlowConsumerCondition` ahead of the gauge and rate checks; evaluate in the same tier-B scheduler pass as `RateCondition`
- [x] 4.10 `SlowConsumerConditionTest`: consumers but no backlog → not in universe; backlog but no consumers → not in universe; both + low rate → active; both + high rate → in universe, not active; counter reset → clamped, no firing; one sample in window → absent
- [x] 4.11 `web/src/alerts/*`: metric label, a prefilled "New slow-consumer rule" template (no seeded rule), and the `(node, queue)` attribution limit stated in the firing view
- [x] 4.12 `./mvnw test -Dtest=SlowConsumerConditionTest` passes

## 5. Slice 4 — broker config diff across a node pair

- [ ] 5.1 Write **ADR-0043** (broker configuration comparison: what counts as configuration, why a hand-rolled pointer diff over a diff library, how an expected difference is distinguished from drift) and **ADR-0044** (slow-consumer detection: the two authorities, and why the broker wins when configured)
- [ ] 5.2 `broker/ConfigReader.java`: one batched Jolokia POST per node through `NodeCallLimiter.acquire(nodeId)`, following `DlqService`; sections per the spike Q4 verdict
- [ ] 5.3 `domain/config/ConfigDiff.java`: Jackson pointer-flatten each side to `Map<String,String>`, three-way key comparison → `SAME` / `DIFFERENT` / `ONLY_IN_LEFT` / `ONLY_IN_RIGHT`
- [ ] 5.4 `ConfigDiff`: key address settings by their `match` string, never by array index
- [ ] 5.5 `ConfigDiff`: allowlist drives the **Configuration** section; every other key lands in a collapsed **Unclassified** section, marked as such — nothing is dropped
- [ ] 5.6 `ConfigDiff`: an **Expected** class for `<name>`, node-local paths, NodeID and acceptor host names, distinct from **Drift**
- [ ] 5.7 `service/ConfigDiffService.java`: `left` omitted defaults to the two endpoints of the selected node's `LogicalNodeView`; either side's `BrokerConnectionException` marks that side unavailable with the classified reason — never a half-diff
- [ ] 5.8 `ConfigDiffService`: detect a passive backup (`Active: false`) with a reduced surface and say so instead of rendering the diff; cap compared `match` patterns, always compare `#`, and report "compared N of M address settings"
- [ ] 5.9 `web/ConfigDiffController.java` + `web/dto/ConfigViews.java`: `GET /api/v1/clusters/{id}/config-diff?left=&right=`, permission `CLUSTER_READ`, no audit event
- [ ] 5.10 `ConfigDiffTest`: `SAME` / `DIFFERENT` / `ONLY_IN_*`; address-settings reordering is not drift; `<name>` classifies as Expected; an unknown attribute lands in Unclassified
- [ ] 5.11 Regenerate `web/openapi.json` and `web/src/api/schema.d.ts` (`npm run gen:api`)
- [ ] 5.12 `web/src/config/ConfigDiffView.tsx`: two node pickers, section accordions, three-column rows (key · left · right) with the status as a word, not colour alone; unavailable-side and passive-backup states rendered as statements
- [ ] 5.13 New route `clusters/$clusterId/config-diff` in `web/src/router.tsx` following the existing `createRoute` pattern, plus a `NAV_ITEMS` entry in `web/src/app/navItems.ts`
- [ ] 5.14 `./mvnw test -Dtest=ConfigDiffTest` passes
- [ ] 5.15 Live check against the dev stack with a session cookie: `GET /api/v1/clusters/{id}/config-diff` for a pair, and with one side stopped

## 6. Close-out

- [ ] 6.1 Remove the three completed rows from the README Roadmap tables — Slow-consumer detection, Payload inspection helpers, Broker config diff across a pair
- [ ] 6.2 `CHANGELOG.md` `## [Unreleased]`: `Added` bullets for the three features and a `Fixed` bullet for the topology work, written for someone upgrading
- [ ] 6.3 `just fmt` clean
- [ ] 6.4 `just verify` (= verify-api + verify-web) passes, output pasted
- [ ] 6.5 `./mvnw verify` passes with Docker (Testcontainers + Liquibase), output pasted
