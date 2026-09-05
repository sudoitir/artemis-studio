## Why

Phases 0–8 have shipped, and three of the four items left on the README roadmap for
v1.0 — payload inspection helpers, slow-consumer detection, broker config diff across a
pair — share one theme with the fourth (topology polish): **Studio already holds the
data an operator needs, and does not make it legible.** A message body renders as an
undifferentiated wall of text; a consumer that is attached but not draining is invisible
even though every input to spot it is collected; config drift between a primary and its
backup is silent until failover, when it is expensive; and the topology view's
identity-axis grammar — position encodes HA role, colour is reserved for faults — does
not read, because the axis is a DOM overlay detached from the nodes it groups and the
status marks are two greys 8px apart.

This is one combined change covering four slices. That departs from
`.claude/rules/00-workflow.md`'s "one change at a time" — a deliberate, recorded
decision: the slices are independent in code but share a single verification gate, a
single release, and one README/CHANGELOG edit, and splitting them into four proposals
would multiply process cost without reducing risk. The departure is recorded here rather
than left implicit.

## What Changes

**Slice 0 — spike before design.** A broker surface check against the dev stack
(primary `:8161`, backup `:8261`), recorded as a new `## 14. v1.0 surface checks`
section with a verdict table in `docs/broker-management-notes.md`. Three assumptions the
config-diff and slow-consumer slices rest on are unverified: whether a passive backup
answers management reads at all and how much surface it exposes; whether
`slowConsumerThreshold` is actually returned by `getAddressSettingsAsJSON` (the Phase-0
capture shows only `slowConsumerThresholdMeasurementUnit`); whether
`getRolesAsJSON` and acceptor MBeans answer; and whether `broker_event`'s type CHECK
constraint accepts `CONSUMER_SLOW`. **Slice 4 is not designed before this runs.**

**Slice 1 — topology view.** The pair axis becomes a React Flow group node per logical
node with the two endpoints as children, so the grouping transforms with the nodes
instead of sliding off on the first pan. Status marks are distinguished by **shape**
(filled disc / hollow ring / half-filled / dashed ring), monochrome preserved —
`theme.css` records that health is the absence of colour, and this change does not
supersede that grammar. `fitView` gets `maxZoom: 1` (React Flow 12 defaults to 2, which
is why a two-node cluster looks unbalanced), zoom controls, a static legend, a real
empty state, keyboard focus rings, and a responsive height. The unmanaged-endpoint
call to action stops being a permanently-disabled button and opens the already-built
`AddManagementUrl` modal, which is currently imported nowhere.

**Slice 2 — payload inspection helpers.** Client-side format detection with a strict
precedence: a declared content type wins, structural probing is the fallback, and Studio
**never claims a format that did not parse**. Pretty-printing and syntax highlighting
for JSON/XML, a hex+ASCII dump for binary, and the JMS type int rendered as a word. A
truncated body reports *"can't format: the broker truncated this body"*, never "not
JSON" — turning a size problem into a false malformed-payload report is the specific
failure this guards against. Three measured size ceilings (detect / pretty-print /
highlight) degrade to a plain `<pre>` with copy and download. `main.tsx` gains the
`CodeHighlightAdapterProvider` that is absent today — so every existing
`<CodeHighlight>`, including the `broker.xml` truncation snippet, starts highlighting as
a side effect.

**Slice 3 — slow-consumer detection**, two authorities layered, the broker's own
detection being the truth source when configured. Studio surfaces the broker's
`slow-consumer-threshold` / `-check-period` / `-policy` settings and routes its
`CONSUMER_SLOW` notification to a `consumers` SSE topic; where the threshold is not
exposed, native detection state is reported **UNKNOWN**, not "off", with the enabling
`broker.xml` snippet. Studio's own fallback is a new `ackRatePerConsumer`
`AlertCondition` over `(node, queue)` subjects that have **both** consumers attached and
a non-zero backlog — the guard that keeps a quiet queue from paging at 3am. No schema
change: `AlertEvaluator.conditionFor` dispatches on free-text `rule.getMetric()`. No
seeded rule either — a slow-consumer threshold is workload-specific — a prefilled rule
template ships instead.

**Slice 4 — broker config diff across a node pair.**
`GET /api/v1/clusters/{id}/config-diff?left=&right=`, defaulting to the two endpoints of
a logical node. Each side is flattened to JSON-Pointer-keyed values with Jackson (already
a dependency) and compared three ways. Address settings are keyed by their `match`
string, never by array index, so reordering is not drift. **Classification, not
filtering**: an allowlist drives a Configuration section and everything else lands in a
visible, collapsed Unclassified section — nothing disappears without the operator being
told. Expected differences (`<name>`, node-local paths, NodeID, acceptor host names) are
their own class, visually distinct from drift. If either side is unreachable, or the
backup's management surface is reduced, Studio says so instead of rendering a half-diff.

**BREAKING**: none.

## Capabilities

### New Capabilities
- `broker-config-diff`: comparing two nodes' broker configuration — what counts as
  configuration versus a runtime counter, three-way key comparison, expected-difference
  classification, the unavailable-side and reduced-surface cases, and the diff screen.

### Modified Capabilities
- `cluster-topology`: the topology graph's presentation becomes specified behaviour —
  pair grouping that transforms with the nodes, marks distinguished by shape, an
  actionable add-a-management-URL flow for unmanaged endpoints, an empty state, and
  keyboard focus.
- `message-operations`: a browsed message's body gains detected-format reporting,
  formatting, and a binary dump; a truncated body reports truncation rather than a
  format failure; the message type is reported as a name, not an integer.
- `alerting`: a new derived `ackRatePerConsumer` metric with its own subject universe
  (consumers attached **and** backlog present) and its documented `(node, queue)`
  attribution limit; a rule template rather than a seeded built-in.
- `broker-events`: `CONSUMER_SLOW` is routed to a `consumers` stream topic and carries
  the broker's own per-consumer attribution.
- `broker-capabilities`: slow-consumer detection is reported three-state (configured /
  off / unknown-because-not-exposed) with its enabling `broker.xml` snippet.
- `broker-connectivity`: a config read for the diff is one batched POST per node under
  the per-node rate limiter, like every other multi-attribute read.

## Impact

- **Backend**: new `broker/ConfigReader`, `domain/config/ConfigDiff`,
  `service/ConfigDiffService`, `web/ConfigDiffController`, `web/dto/ConfigViews`; new
  `domain/alerting/SlowConsumerCondition`; edits to `service/AlertEvaluator`,
  `sse/EventStreamPublisher`, `broker/BrokerXmlSnippets`, `broker/CapabilityProbe`;
  `web/openapi.json` regenerated. A new Liquibase changeset **only if** spike Q5 shows
  `broker_event`'s type CHECK rejects `CONSUMER_SLOW` — `010-broker-events.sql` is
  released and is never edited.
- **Frontend**: `web/src/topology/*` reworked (`layout.ts` stays a pure, unit-tested
  function); `web/src/clusters/{RegisterCanvas,examples}` follow the shared canvas; new
  `web/src/messages/{payload.ts,HexDump.tsx}` and a reworked `MessageDetailPanel`; new
  `web/src/config/ConfigDiffView` plus a route and nav item; `main.tsx` gains the
  highlight adapter; `web/src/alerts/*` gains the metric label and rule template;
  `schema.d.ts` regenerated.
- **Dependencies**: `shiki` (frontend, loaded by dynamic `import()` so it stays out of
  the entry chunk — verified against `vite build`'s chunk report). No new backend
  dependency: Jackson 3 already ships.
- **ADRs**: **ADR-0043** broker configuration comparison (what counts as configuration,
  why a hand-rolled pointer diff over a diff library, how an expected difference is
  distinguished from drift); **ADR-0044** slow-consumer detection (the two authorities
  and why the broker wins when configured). No ADR for the topology slice — it is polish
  within the recorded grammar, not a departure from it.
- **Docs**: `docs/broker-management-notes.md` `## 14. v1.0 surface checks`; three
  README roadmap rows removed; `CHANGELOG.md` `## [Unreleased]` gains `Added` and
  `Fixed` bullets written for someone upgrading.
- **Process**: this change covers four slices at once, departing from
  `.claude/rules/00-workflow.md`'s one-change-at-a-time rule, by the user's explicit
  decision.
