## Context

See `proposal.md` — Why. Design-relevant current state, verified in the tree:

- **Topology.** `TopologyGraph.module.css:12-22` positions `.axis` as
  `position:absolute; inset-block-start:50%` on the 460px wrapper — a DOM overlay at a
  fixed y. The nodes live in React Flow's transformed pane at `LIVE_Y=40` /
  `BACKUP_Y=200` (`layout.ts:22-24`), rescaled by `fitView` and moved by every pan and
  zoom. The axis that carries the whole identity grammar therefore lands between the two
  rows only by coincidence, and slides off on the first interaction. That is the root
  cause of "grouping is hard to parse". `theme.css:28-31` records that green is
  deliberately not in the palette — "health is the absence of colour"; `--as-node-live`
  and `--as-node-backup` are two greys, indistinguishable at 8px.
  `web/src/clusters/AddManagementUrl.tsx` is fully built and imported nowhere.
- **Payloads.** There is no `CodeHighlightAdapterProvider` anywhere in the app, so every
  existing `<CodeHighlight language="…">` — including the `broker.xml` snippet in the
  truncation alert at `MessageDetailPanel.tsx:197-210` — renders unhighlighted today.
- **Alerting.** `AlertEvaluator.conditionFor` dispatches on `rule.getMetric()`, which is
  free text; `MetricSeriesRepository.latestRateBySubject` already carries ADR-0033's
  restart-safe never-negative clamp. A new derived metric therefore needs no migration
  and no CHECK-constraint edit.
- **Config diff.** Jackson 3 (`tools.jackson`) is already a dependency. `DlqService`
  (`DlqService.java:68-74`) is the house pattern for "we could not learn this, so we
  infer nothing" — `settingsAvailable = false` rather than a guessed default.

## Goals / Non-Goals

**Goals**

- Attach the topology grouping to the things it groups, so the grammar survives
  interaction — and spend the slice's one bold move there, leaving the rest quiet.
- Make a message body legible without ever asserting a format that did not parse.
- Detect a slow consumer with the definition that does not page at 3am: consumers
  attached, backlog present, acks near zero.
- Make config drift between a pair visible, with expected differences separated from
  drift so a clean pair reads as clean.

**Non-Goals**

- Re-theming the topology view, or superseding the monochrome-health grammar. Shape
  replaces brightness as the discriminator; the palette does not change.
- Backend parsing of arbitrary message payloads. Detection is client-side; no DTO change
  and no OpenAPI regeneration for slice 2.
- Per-consumer attribution from Studio's own derivation — the broker's consumer listing
  carries no per-consumer ack counter. See the spec requirement that states the limit.
- Editing any released Liquibase changeset.

## Decisions

### D1 — Topology: a React Flow group node per logical node, not a repositioned overlay

Replace the full-width divider band with a group node per `LogicalNodeView`; the two
endpoint nodes become its children (`parentId`, `extent: 'parent'`), the axis becomes a
hairline inside the group at its mid-line, and the shared `NodeID` sits in monospace on
the group's edge.

*Why:* the group lives in the same transformed pane as the nodes, so pan and zoom cannot
separate them. It also gives split-brain a natural reading — two boxes above the line
*inside one group* — instead of a colour change on a detached rule. One change answers
two of the five weak points.

*Alternatives:* (a) keep the overlay and recompute its y from the viewport transform on
every `onMove` — restores correctness but leaves the band unattached to any pair and adds
a React-state-per-frame path; (b) draw the axis as a React Flow edge between the two
endpoints — transforms correctly but carries no group identity and degenerates for a lone
endpoint.

`layout.ts` stays a **pure function** (it is unit-tested): it emits the group nodes and
their geometry from the existing `COL_W` / `LIVE_Y` / `BACKUP_Y` constants, and no
callback or React state enters it. The `onAddManagementUrl(endpointId)` handler travels
by React context instead — which also lets `RegisterCanvas`'s example cards supply no
handler, so the CTA renders as plain text there rather than as a dead button.

`TopologyLayout.axisStatus` moves from a canvas-level field onto each group's data:
replication state is per pair, not per cluster. A cluster-level roll-up is kept for the
screen-reader summary.

### D2 — Topology: `fitView` `maxZoom: 1`

React Flow 12's default `fitView` `maxZoom` is 2, so a two-node cluster is scaled 2× to
fill the 460px frame. That, not the layout constants, is the actual cause of "unbalanced
at wide viewports", and capping it is the single largest perceived-quality fix in the
slice. Paired with `padding: 0.15`, a `<Controls />` cluster (`showInteractive={false}` —
nodes are not draggable), a re-fit keyed on the logical-node ids so a failover does not
leave a stale viewport, and `block-size: clamp(420px, 60vh, 720px)` in place of the fixed
460px.

### D3 — Topology: marks by shape, monochrome kept

`live` filled disc, `standby` hollow ring (1.5px), `behind` half-filled, `unmanaged`
dashed ring, raised to 9–10px so fill-versus-ring survives at size. `down` and
`split-brain` keep amber and red — colour still enters only when something is wrong.

*Why not new colour tokens:* `theme.css:28-31` records the grammar deliberately. A shape
difference fixes the real defect (two greys 8px apart) without superseding a recorded
decision or sweeping four other views' tokens. This is why the slice needs no ADR.

The legend is a static row beneath the canvas, not a floating `<Panel>` that covers nodes
at small sizes, and the mark vocabulary is exported from `layout.ts` so legend and nodes
cannot drift apart.

### D4 — Verify the failover-animation claim before shipping either the claim or the fix

`TopologyGraph.tsx:13` says "Failover animates the promoted box across the axis", and
`.node` carries `transition: transform 240ms`. But React Flow sets `transform` on **its
own** node wrapper, not on the inner `.node` div — so the promotion animation likely never
happens, while the `data-offset` `translateX(18px)` does. Confirm in the browser by
stopping the primary in the dev stack, then either move the transition to
`.react-flow__node` or delete the claim. Do not ship a comment that describes behaviour
the code does not have. This is a check, not a foregone conclusion in either direction.

### D5 — Payload detection: client-side, declared type first, never overclaim

The body is already in the browser; sending it back for classification buys nothing.
Order:

1. A declared type — `contentType` on the DTO, or an `_AMQ_CONTENT_TYPE` / `contentType`
   / `content_type` string property. The producer said what it is; that beats any sniff.
2. `bodyEncoding === 'TEXT'` → trim; first non-whitespace `{` or `[` → `JSON.parse`;
   `<` → `DOMParser`, **checking the document for `parsererror`** (DOMParser reports
   failure in the document, not by throwing).
3. `bodyEncoding === 'BASE64'` → `atob` to bytes, match a short magic-byte table:
   `1F 8B` gzip, `50 4B 03 04` zip, `AC ED 00 05` Java-serialized, `4F 62 6A 01` Avro.
   These are the containers a broker operator actually meets; a general file-type library
   covers neither of the last two.
4. Otherwise text.

The detection is self-verifying by construction: JSON is only claimed when `JSON.parse`
returned. Formatting is `JSON.stringify(JSON.parse(x), null, 2)` for JSON and a recursive
indent walk over the `Document` already parsed in step 2 for XML — `XMLSerializer` does
not indent, and adding a dependency for thirty lines is not worth it.

### D6 — Payload: truncation is a distinct outcome, not a parse failure

The existing `bodyTruncated` flag means a JSON body will fail `JSON.parse`. The panel must
say *"can't format: the broker truncated this body"* and point at the existing
`management-message-attribute-size-limit` alert — never "not JSON". Getting this wrong
turns a size problem into a false malformed-payload report, which is the more expensive
error: it sends an operator to debug a producer that is fine.

### D7 — Payload: three measured size ceilings

`CodeHighlight` tokenising a multi-MB body locks the main thread. Three named ceilings:
detect on the first N KB only; pretty-print below one ceiling; highlight below a lower
one; above both, plain `<pre>` with *"Formatting is off for a body this size"* plus copy
and download. **The numbers are measured, not guessed** — time `JSON.parse` + Shiki on a
synthetic 1 / 5 / 20 MB body and fix the constants from that. Binary renders as a hex +
ASCII dump of the first N bytes; never `TextDecoder` a binary body into the code block,
which produces mojibake that looks like corruption.

### D8 — The highlight adapter belongs in `main.tsx`, lazily

Wrap the app in `CodeHighlightAdapterProvider` with `createShikiAdapter(loadShiki)`,
`loadShiki` using a dynamic `import('shiki')` so it stays out of the entry chunk;
`langs: ['json','xml','yaml','sql','properties']`, `themes: []`. Confirm against
`vite build`'s chunk report that the entry chunk did not grow. Without this the
pretty-printer produces correctly-indented but still unhighlighted text — and the
existing `broker.xml` snippets start highlighting as a free side effect.

The JMS type integer is mapped to a name by reading the constants off
`org.apache.activemq.artemis.api.core.Message` in the `artemis-jakarta-client` jar, not
from memory.

### D9 — Slow consumers: two authorities, the broker wins when configured

Artemis has `slow-consumer-threshold`, `-check-period` and `-policy` (`NOTIFY`|`KILL`)
per address-setting and emits `CONSUMER_SLOW` on `activemq.notifications` — confirmed in
`CoreNotificationType` (`docs/broker-management-notes.md:325`). The broker sees every
consumer; Studio sees a sampled queue. So the broker is the truth source when configured,
and Studio's derivation is the fallback for brokers where native detection is off. This
layering is ADR-0044.

`EventStreamPublisher.topicFor` gains `case "CONSUMER_SLOW" -> "consumers"`.
`BrokerXmlSnippets.forSlowConsumerDetection()` follows the existing
`NOTIFICATIONS_SECURITY_SETTING` / `NOTIFICATION_PLUGIN` pattern.

### D10 — Slow consumers: a new `AlertCondition`, no schema change

`SlowConsumerCondition implements AlertCondition`, metric `ackRatePerConsumer`, selected
in `conditionFor` ahead of the gauge and rate checks. Universe = `(node, queue)` subjects
with **`consumerCount > 0` AND `messageCount > 0`** from `QueueSnapshotRepository`. Both
guards matter: no consumers is not slow, and no backlog with a zero ack rate is just
idle. That triple — consumers attached, backlog present, acks near zero — is why this
beats a plain `messagesAcked < X` rate rule, which pages on every quiet queue at 3am.

Value = `messagesAcked` rate ÷ `consumerCount`, taking the rate from
`MetricSeriesRepository.latestRateBySubject` over the existing 2 × tier-B window —
**reused, not reimplemented**, so ADR-0033's clamp applies and a broker restart cannot
produce a spurious firing. Fewer than two samples → subject absent, not zero, matching
`RateCondition`. Subject keys follow `GaugeCondition`'s convention exactly:
`queue:<name>`, or `node:<id>/queue:<name>` when node-scoped. Evaluated in the same
scheduler pass as `RateCondition` (tier B), since it shares that data source.

No seeded rule: Phase 7 seeds `SPLIT_BRAIN` / `NODE_DOWN` / `REPLICATION_BEHIND` because
those are universally wrong conditions. A slow-consumer threshold is workload-specific and
any seeded value would be wrong for everyone — a prefilled template ships in the existing
rule UI instead.

### D11 — Config diff: Jackson pointer-flatten, no diff library

Flatten each side to `Map<String,String>` keyed by JSON Pointer with Jackson (already a
dependency), then a three-way key comparison → `SAME` / `DIFFERENT` / `ONLY_IN_LEFT` /
`ONLY_IN_RIGHT`.

*Why not a generic JSON-diff library:* the hard part here is semantic — classifying a
broker attribute as configuration versus a runtime counter, and keying address settings
by `match` rather than array index. A generic differ solves neither and actively adds
index-based array noise. This rationale is ADR-0043.

Sections in order: `broker` (config attributes), `addressSettings`, `securitySettings`,
`acceptors` — the last two conditional on spike Q4.

### D12 — Config diff: classification, not filtering; expected differences are their own class

A denylist of runtime counters silently admits every attribute a future Artemis adds; an
allowlist silently drops new config. So: an allowlist drives the **Configuration**
section, and everything else lands in a collapsed **Unclassified** section marked as such.
Nothing disappears without the operator being told — the same ethos as "no silently
missing buttons".

Expected differences are a third class, not a suppression. The dev stack proves the point:
the primary is `broker="primary"` and the backup `broker="backup"`, so `<name>` differs by
design; likewise node-local paths, NodeID and acceptor host names. Rendered as
**Expected**, visually distinct from **Drift**, a pair with zero real drift shows a clean
diff instead of six false positives. This distinction is the substance of ADR-0043.

### D13 — Config diff: never a half-diff

`GET /api/v1/clusters/{id}/config-diff?left={nodeId}&right={nodeId}` → `ConfigDiffView`.
`left` omitted defaults to the two endpoints of the selected node's `LogicalNodeView`.
Permission `CLUSTER_READ`; no audit event (read-only). Both reads go through
`NodeCallLimiter.acquire(nodeId)`, one batched Jolokia POST per node, following
`DlqService` exactly.

If either side throws `BrokerConnectionException`, return the view with that side marked
unavailable and the classified reason — never a partial comparison in which the
unreachable side's absent keys read as removals. This mirrors `DlqService`'s
`settingsAvailable = false` precedent.

Separately, a passive backup's reduced management surface (spike Q1) would make most keys
come back `ONLY_IN_LEFT` — catastrophic-looking drift that is just an inactive broker.
Detect it (`Active: false`) and say so plainly instead of rendering the diff. Many
addresses: cap the compared `match` patterns, always compare `#`, and report "compared N
of M address settings" — never a silent truncation.

### D14 — Sequencing: spike before design of slice 4

Slice 0's broker surface check runs first and is recorded as `## 14. v1.0 surface checks`
in `docs/broker-management-notes.md` with a verdict table, in the style of the existing
"Phase N surface checks" sections. Three of slice 4's assumptions and one of slice 3's are
unverified, and two of them can change the shape of the feature:

- **Q1** a passive backup's management surface. If thin, the diff becomes "primary vs
  primary across the cluster, pair-diff only when the backup answers" — said in the UI,
  not half-rendered.
- **Q3** whether `slowConsumerThreshold` is returned at all. The Phase-0 capture in
  `src/test/resources/jolokia/address-settings.json` shows only
  `slowConsumerThresholdMeasurementUnit`. If the threshold is not exposed, native
  detection state is **UNKNOWN**, not "off" — guessing would violate non-negotiable #5.
- **Q4** whether `getRolesAsJSON` and acceptor MBeans answer — determines whether the
  `securitySettings` and `acceptors` sections exist.
- **Q5** whether `broker_event`'s type CHECK accepts `CONSUMER_SLOW`. A new changeset only
  if it does not; `010-broker-events.sql` is released and is never edited.

### D15 — Process: one change, four slices, recorded

`.claude/rules/00-workflow.md` says one change at a time. This is four. The departure is
the user's explicit call, made with the trade-off stated, and is recorded in
`proposal.md` rather than left implicit. The slices are independent in code and share one
verification gate and one release.

## Risks / Trade-offs

- **The group-node rewrite breaks `RegisterCanvas`'s example cards** (shared canvas) →
  slice 1's task list changes `RegisterCanvas.tsx` and `examples.ts` in the same pass, and
  `layout.test.ts` covers the lone-unmanaged-endpoint case that only the examples exercise.
- **Shiki lands in the entry chunk** and the app's first paint regresses → dynamic
  `import()` plus an explicit check of `vite build`'s chunk report in the verification
  step, not an assumption.
- **The size ceilings are wrong** and either a usable body is refused formatting or a
  large one still janks → measure on 1 / 5 / 20 MB synthetic bodies before fixing the
  constants; the ceilings are named constants, tunable without touching detection.
- **A paused queue is correctly "slow" but operationally expected.** `queue_snapshot` has
  no `paused` column, so `SlowConsumerCondition` will fire on a paused queue with a
  backlog and consumers. Resolve explicitly during slice 3 — either add `paused` to the
  snapshot (a new changeset plus the scrape field) or state in the rule UI that paused
  queues fire. Do not ship it silently mis-scoped.
- **A `CONSUMER_SLOW` value the CHECK constraint rejects** would make event persistence
  throw at runtime rather than at build → spike Q5 answers it before any code is written,
  and a new changeset is the fix if needed.
- **The config allowlist goes stale** as Artemis adds attributes → by construction, a new
  attribute lands in the visible Unclassified section rather than disappearing; staleness
  degrades presentation, not correctness.
- **Four slices in one change** raises the cost of a revert and widens the review surface
  → each slice is independently testable and the verification gate is per-slice as well as
  whole-repo.

## Migration Plan

No data migration and no breaking change. A new Liquibase changeset is added **only** if
spike Q5 shows `broker_event`'s type CHECK rejects `CONSUMER_SLOW`; it is additive and
appended, never an edit to `010-broker-events.sql`. Rollback is the ordinary image
rollback — the config-diff endpoint and the new alert metric are additive, and a
`ackRatePerConsumer` rule created by an operator is inert on an older image (its metric
matches no condition) rather than harmful.

## Open Questions

- The exact numeric values of the three payload size ceilings — deferred to measurement
  in slice 2. They are named constants; the specs constrain the behaviour, not the values.
- Whether the `securitySettings` and `acceptors` diff sections exist at all — answered by
  spike Q4 in slice 0, before slice 4's design is finalised.
