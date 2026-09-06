## Why

An operator declared a request address and a set of reply addresses, sent traffic,
and saw nothing. The Flows tab said `0 flows`, which reads identically whether
nothing was sent, nothing could be browsed, or every request was consumed faster
than the sampler ticks — three situations with three different answers.

Investigating it found that the silence was real, and that the time handling
underneath it was wrong.

**Tracing could fail completely without saying so.**

- If no node had a reachable Core endpoint, the sampler's loop was simply empty:
  nothing browsed, nothing logged, nothing to find.
- Browsing hardcoded `(address, address, "ANYCAST")`. A multicast address, or an
  anycast queue named differently from its address, failed on every tick into a
  throttled warning nobody reads.
- `samplePerMin` was stored, rendered in the table, and **never read**. The screen
  offered a control that did nothing.

**And four clocks were being treated as one.** A traced exchange involves the
broker's clock, the producer's, the consumer's and Studio's, and the codebase had
no notion of any of them differing:

- a flow's deadline came from the absolute `JMSExpiration` — the *producer's*
  clock — and was compared against Studio's, so a producer ten minutes fast meant
  no flow ever timed out, and ten minutes slow meant every flow timed out at once;
- latency was the gap between two sample ticks, quantised to the sample interval,
  with nothing disclosing that;
- notification-path and sampler-path observations used different clocks in the
  same flow.

## What Changes

**Tracing explains itself.** A new diagnostics view — over HTTP, in the UI, and
through MCP — reports what the sampler actually did on its last tick per traced
address (nodes sampled, nodes skipped **with the reason**, messages browsed,
observations emitted, last error) plus the reasons an operator may be seeing no
flows, ranked most-likely-first and each carrying its remedy. The Flows tab shows
it in place of `0 flows`; the Requests tab gains a Status column.

**The sampler stops failing quietly.** It resolves the queue name and routing type
from the last scrape instead of assuming them, reports an address with no queue as
a stated reason, names the missing Core endpoint, and honours `samplePerMin` —
disclosing when the requested rate is faster than the global sampler interval can
deliver.

**Time is measured, corrected, disclosed and alerted.** Studio measures each
broker's clock offset from the timestamp Jolokia already puts on every response
(no extra call), normalises broker time onto its own timeline at the three
boundaries where foreign time enters, records forward-only producer and consumer
skew, states how each latency was measured (`OBSERVED` with its error bar, or
`MESSAGE_TIMESTAMPS`), infers a wrong clock on Studio's own host from estate-wide
corroboration, and raises a `CLOCK_SKEW` alert.

## Impact

- **Modified specs**: `request-reply-tracing` (diagnostics, sampler behaviour,
  latency provenance), `alerting` (a fifth state condition),
  `broker-connectivity` (clock offset measurement), `mcp-server` (a discriminator
  value and two result fields).
- **New ADR-0053**.
- Schema: `020-clock-skew-and-latency-source.sql`.
- New endpoint `GET /api/v1/clusters/{id}/rr/diagnostics`, at `cluster:read`.
- MCP: `trace_request_reply` gains `mode=diagnostics`; `cluster_health`'s result
  gains `asOf` and `clock`. **No new tool and no new parameter** — the listing
  budget is unchanged (ADR-0045/0050).
