> Runs alongside `03-message-replay-from-payload` (itself parked) by explicit maintainer
> OK on 2026-09-14: the two touch no shared module, spec requirement or table.

## Why

Operators can see brokers (Topology) and flat lists of connections, consumers, producers,
diverts and bridges, but nothing answers the question an incident actually asks: *which
application sends where, through which address, divert, bridge or cluster hop, into which
queue, consumed by whom — at what rate, and where is it stuck?* Today that answer is
assembled by hand from five screens and a `broker.xml`. It is the roadmap item
"D · Flow visualization", and the data to answer it is already within reach: queue rates
are stored, client lists and routing objects are readable.

## What Changes

- **A Flow screen** per cluster: one graph of producers → addresses → queues → consumers,
  with diverts, bridges, cluster redistribution, wildcard matches, dead-letter/expiry
  routes and temporary queues as optional layers, and a table twin carrying the same rows.
- **Bounded by construction.** It opens on the busiest paths (default 40) and says how many
  it left out; selecting any client, address or queue focuses its neighbourhood. Lens,
  focus, ranking, grouping and layers live in the URL.
- **Live, honest rates.** Edge thickness and moving dots encode msg/s. Every rate names
  its source and age; an unknown rate reads "measuring…" or "not counted by broker", never
  zero. Motion stops under reduced motion, on Pause, and in a hidden tab.
- **Demand-driven client sampling.** Per-client rates do not exist today. While at least
  one operator has a Flow view open on a cluster, Studio reads producers and consumers
  in one batched request per node per sweep,
  under the per-node ceiling. When nobody is looking, it stops. The latest sample is a
  disposable cache shared by every Studio instance.
- **Faults in words.** Backlog with no consumer, a stalled consumer, a bridge not
  connected, a divert or bridge present on only some nodes, an unreachable node, a denied
  management permission (with the `broker.xml` fix), and truncated sampling.
- **Read-only.** Flow issues no mutation; its inspector links to the existing,
  confirmed close actions.
- New `flow` stream topic; new operational settings for the sampler; new tables owned by
  the `flow` module.

## Capabilities

### New Capabilities
- `flow-visualization`: the Flow graph and table — what they draw, how they are bounded
  and focused, how rates, freshness, faults and unavailable data are stated, how client
  activity is sampled on demand, and how motion and keyboard access behave.

### Modified Capabilities
- `realtime-stream`: the recognised and signal topics gain `flow`, emitted only when a
  client-activity sweep changed a cluster's persisted flow state.
- `scrape-scheduling`: the tiered schedule gains a demand-driven client-activity schedule
  that runs only while a cluster's flow is observed.
- `operator-ui`: content that moves on its own can be paused and honours reduced motion.

## Impact

- **Backend**: new module `feature/flow` (sampler, graph assembly, controller, three tables
  under `db/changelog/feature/flow/`); `kernel/stream/SseHub` gains a per-topic subscriber
  query; `platform/clusters/ClusterLock` gains a scope; `platform/scrape/MetricSamples`
  gains a latest-rate-with-timestamp read; `feature/routing/RoutingService` is read.
- **API**: `GET /api/v1/clusters/{id}/flow`; SSE topic `flow`.
- **Frontend**: new feature `web/src/features/flow`; new dependency `elkjs`; new
  `--as-flow-*` tokens.
- **Broker load**: bounded and only while observed — one batched POST per serving node per
  sweep (default 15 s), row-capped, through the existing limiter.
- **ADRs**: 0080 (ELK layered layout and SVG motion), 0081 (demand-driven client sampling).
  Depends on 0015, 0025, 0056, 0069, 0070, 0074, 0076.
- **Not in scope**: image export, an MCP tool, per-client rate history, a request-reply
  overlay, federation and AMQP broker connections.
