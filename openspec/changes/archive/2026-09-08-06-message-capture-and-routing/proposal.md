## Why

Studio's three answers to "what messages went through here" are three copies of the same
sampler, and they share its one defect. `SqlTailPoller` re-browses a queue against a
high-water mark; `MessageIndexCapture` is that poller with the database as its sink;
`RrSampler` browses page 1 of every request-reply address. A browse can only report what
was still sitting on the queue at the instant it ran, so **a queue that drains faster than
the poll interval is invisible to all three**.

The practical consequence is that the message index produces rows for `ExpiryQueue` and
almost nothing else — because messages *sit* on an expiry queue. On a healthy `ORDER.IN`
they do not. An operator who opts into indexing a busy queue, waits, and finds it empty
concludes the feature is broken. It is not broken; it is sampling, exactly as ADR-0060
says it is. But ADR-0060's honesty obligation was written on the assumption that the
alternative was unavailable, and it no longer is.

ADR-0060 named divert-based capture "the only complete answer" and deferred it over a
single objection: an orphaned divert would leave a broker "quietly copying production
traffic into a queue nobody is draining". A bounded tap settles that objection.

**A ring queue bounds the cost even if nothing ever drains it.** `ring-size` plus
`address-full-policy=DROP` means the capture queue overwrites its oldest entries and the
broker never blocks, never pages, and never grows. `expiry-delay` on the same address,
with `auto-create-expiry-resources=false`, bounds it in age as well, so an abandoned tap
holds at most `ring-size` messages and none older than the delay.

Change 04 assumed a second bound — that a divert created over the management API does not
survive a broker restart. Measured against Artemis 2.56.0, it does, and so do the address
setting and the security setting ([ADR-0065](../../../docs/adr/0065-runtime-broker-configuration-persists-across-restarts.md)).
Nothing removes an abandoned tap for us, which is why removal is always an explicit act and
why the expiry bound is part of the tap rather than an optional extra.

Capture is therefore now safe in a way it was not when ADR-0060 was written, and the
sampler's blind spot is no longer a cost the product has to keep paying.

## What Changes

**A new capability, `message-capture`.** An operator opts a queue into capture. Studio
installs, on every live node, a non-exclusive divert from the source address into a
Studio-owned ring-bounded capture queue, drains that queue with a Core consumer, and fans
the result out to three sinks that previously each did their own sampling: the SQL
Console's live tail, the message index, and request-reply correlation. One tap, three
consumers — generalising ADR-0060 D4 rather than adding a fourth reading mechanism.

Capture is **opt-in per queue and never a side effect**, exactly as the index already is.
It is **address-scoped**: a divert copies at address routing, before multicast fan-out, so
for a multicast address capture records what was routed to the address and states plainly
that it cannot say which subscription queues received it.

**A new capability, `routing-management`**, absorbed wholesale from change
`04-divert-and-bridge-management`, which this change deletes. Capture needs a divert client;
building it twice would be the waste. Read views for diverts and bridges, bridges read-only
permanently, and the runtime-versus-configured persistence flag that was 04's real feature.

**Sampling is retained, not removed.** A queue with no capture subscription is still tailed
and still indexed by browse. Capture raises the ceiling; it does not remove the floor.

**Postgres full-text over the message index.** `MATCH (body) AGAINST ('terms')` compiles to
`to_tsvector('simple', body) @@ websearch_to_tsquery(...)` against a functional GIN index,
with `ORDER BY match_rank`. Quoted phrases, exclusions and ranking — the part of a search
engine operators actually reach for — with no second datastore.Substring search is unchanged: the existing trigram index
already serves both `LIKE` and `ILIKE`.

**Query execution moves off the URL.** Query text currently travels in an `EventSource`
GET, so body predicates land in every proxy access log between the operator and Studio, and
a long query hits a URL length limit. Execution becomes `POST` then stream.

**The per-cluster Postgres advisory lock**, pulled forward from the roadmap. Two Studio
instances reconciling capture would race on divert create and destroy. Capture is the first
feature that cannot be correct without it.

**BREAKING** — `message_index` rows gain provenance (`SAMPLED` / `CAPTURED`) and the
original address. Existing rows are backfilled as `SAMPLED`, which is what they are. A
result set that previously claimed one thing about completeness now says which of two
things it means.

**One new permission**, `capture:write`. Capture mutates the broker, so `settings:write` —
which gates index subscriptions today — is the wrong authority for it.

## Capabilities

### New Capabilities

- `message-capture`: opting a queue into complete capture; the divert-and-ring-queue tap,
  its per-node lifecycle across failover and restart, its bounded cost, its cleanup
  guarantees, the loss it reports when it cannot keep up, and the fact that its payload is
  a complete copy of production traffic rather than a sample.
- `routing-management`: cross-node read views of diverts and bridges, and the disclosure
  that a runtime-created divert is one broker restart from disappearing.

### Modified Capabilities

- `message-index`: populated by capture as well as by sampling, so a row now carries its
  provenance and the index may claim completeness for a captured, covered window — which
  ADR-0059 D2 explicitly forbade when sampling was the only source. Coverage becomes
  per-node. Retention gains a size bound alongside the time bound.
- `sql-console`: full-text predicates and relevance ordering; provenance and per-node
  coverage stated per result; execution no longer carries query text in the URL; a running
  query is cancellable from the interface, which the capability already required and the
  screen never offered.
- `request-reply-tracing`: correlation observes captured messages where an address is
  captured, and states that temporary reply queues remain notification-derived because a
  queue that does not yet exist cannot be diverted.
- `authorization`: the `capture:write` permission.
- `broker-capabilities`: capture requires management write, divert management, and the
  authority to set a security setting on the capture address; each unavailability is named
  with the `broker.xml` that would grant it.
- `cross-node-resource-views`: diverts and bridges join the six existing views.
- `operator-ui`: capture subscription management, the routing view, and the console's
  result-meta, history, export and tail-pause behaviour.
- `mcp-server`: capture state and the routing view exposed as tools.

## Impact

- **Studio now mutates broker routing configuration.** Every previous broker write was
  scoped to messages or to a queue's lifecycle. This one changes how messages are routed,
  and it does so on a schedule rather than on an operator's click, because a tap must be
  re-asserted after failover. The reconciler's blast radius is the thing to get right.
- **Studio's database now holds a complete copy of captured traffic**, not a sample.
  ADR-0059 already treats indexed payload as retained data; capture changes the volume and
  the completeness, not the kind. It is why capture stays opt-in, why the capture address
  gets its own broker security setting, and why retention grows a size bound.
- **A second copy of production payload exists on the broker.** Studio installs a
  `security-setting` restricting it, and refuses to capture at all if it cannot — creating
  an unguarded copy would be worse than not capturing.
- Code: new `broker/routing/` and `broker/capture/`; `sql/` planner, catalogue, executors
  and tail; `persist/` writer, coverage and partition maintenance; `service/` request-reply;
  `web/` controllers and DTOs; `web/src/sql/` and a new routing screen.
- Schema: one new changeset. New `message_capture_node`; `message_index` and
  `message_index_subscription` gain columns; two new indexes.
- ADRs: 0062 (capture; supersedes 0059 and 0060), 0063 (Postgres full-text), 0064
  (POST-then-stream), 0065 (management-created configuration persists across restarts —
  measured, and correcting an assumption 0062 and change 04 both rested on).
- Deletes `openspec/changes/04-divert-and-bridge-management/`, absorbed here in full.
