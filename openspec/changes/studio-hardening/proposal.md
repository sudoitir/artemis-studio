## Why

A field report shows a production Studio being OOM-killed every 2–4 hours. It was holding
about 13,000 OS threads, and it also contributed to exhausting the host's file table. The
root cause is in source: every Jolokia call builds a new JDK `HttpClient`, and each one keeps
a selector thread and an epoll descriptor until a GC happens to collect it.

Two audits prompted by that report found more of the same class, where the product's
non-negotiables are broken in practice:
- Studio sends broker traffic the per-node limiter never sees, and walks whole queues to
  count a page.
- Capture acknowledges broker messages that never reached Postgres, and reports `ACTIVE`
  while recording nothing.
- Creating a divert reports success for diverts the broker never deployed.

Studio must never be the reason a broker is under pressure, and must never lose operator
data silently.

## What Changes

- **Bounded client resources.** One HTTP client per TLS bundle, reused for every broker call
  and shut down on timeout change, certificate reload and shutdown. A failed Core
  subscription start releases its connection factory. The shipped default heap goes from
  `MaxRAMPercentage=75` to `50`.
- **Every broker request is rate-limited.** The per-node ceiling counts HTTP requests, not
  operations, and applies to every path: registration, capability probes, multi-step
  commands, capture installation, and by-id message operations.
- **Bounded Core browse.** A browse reads at most the requested page. The total comes from
  one management read, or is reported as unavailable, never guessed and never shown as 0.
  The request-reply sampler reads a bounded sample.
- **By-id message operations are batched.** A partial failure reports how many messages were
  already acted on, in the response and in the audit row.
- **Shared list views are coalesced.** Concurrent requests and stream refetches for a node's
  connections, sessions and consumers cost one broker call.
- **Sampled index tails are bounded.** They still index the backlog already on a queue, but
  walk it over several ticks with a bounded number of pages per tick, never the whole
  backlog in one tick.
- **Capture never acknowledges an unstored message.** When Postgres is unavailable, capture
  pauses and backs off, the bounded capture queue holds the backlog, and anything past the
  bound is counted as loss and shown. Unreadable messages and rate-cap drops are counted,
  never skipped silently.
- **Audit rows commit before the broker call.** The row commits in its own transaction and
  the outcome commits after, so a crash mid-fan-out can never leave a changed broker with
  no audit record.
  **BREAKING (process):** this replaces non-negotiable #3's "same transaction" wording.
- **Full capture correctness.**
  - Footprint, loss, retention and delete match captured rows by address.
  - A tap whose divert disappeared, or whose connection died, is reinstalled.
  - Bounds edits reach the broker.
  - The capture queue is bounded in bytes as well as count.
  - Capture never taps its own queues.
  - Permanent errors are `FAILED`, not retried forever.
  - `DEGRADED` clears after a clean interval.
  - Delete stops draining before rows are removed.
- **BREAKING:** capture's broker settings are scoped per Studio instance, and the capture
  broker role has no default. Capture is refused, with the remedy, until it is configured.
- **Divert create and delete correctness.**
  - The broker's deployment is verified, so a duplicate reports `ALREADY` or `FAILED` rather
    than `APPLIED`.
  - A preflight refuses a missing forwarding address, source equal to forwarding, and loops,
    and warns about an exclusive divert shadowing capture.
  - The capture prefix is refused server-side.
  - Requests are validated.
  - Generated `broker.xml` is escaped.
- **UI guards.**
  - Divert: validation on blur, a frozen preview that is exactly what gets created, and four
    rendered outcomes.
  - Capture: a dry run with nodes, bounds and `broker.xml`, typed confirmation to arm it,
    bounds inputs, mode-aware copy, and loss stated as unavailable rather than 0.
  - Per-node outcomes are announced to assistive technology.
- **Orderly shutdown.** Background jobs stop before broker calls do, and the broker-event
  writer re-queues a failed batch and flushes on shutdown.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `broker-connectivity`: broker HTTP clients are bounded, shared resources.
- `scrape-scheduling`: the per-node ceiling counts every HTTP request on every path.
- `message-operations`: bounded browse with an honest total; partial by-id outcomes;
  audit timing; rate limiting of every request.
- `audit-log`: the pending row commits before the broker call; the outcome commits after.
- `core-transport`: capture sessions cannot starve operator sessions; connection factories
  are released on a failed start.
- `message-capture`:
  - acknowledge only after storage;
  - backpressure within bounds;
  - byte-bounded queue;
  - per-instance objects;
  - reinstall on drift;
  - bounds edits applied;
  - loss honesty;
  - permanent-error state;
  - dry run;
  - required broker role;
  - fix the "ceases on broker restart" contradiction.
- `message-index`: indexing a queue's backlog is spread across ticks with a bounded broker
  cost per tick.
- `routing-management`: verified deployment, preflight refusals, validation, reserved capture
  prefix, escaped configuration.
- `operator-ui`: divert preview freeze and validation; capture arming, dry run and bounds;
  announced outcomes.
- `operational-health`: shutdown stops jobs before broker calls and flushes buffered writes;
  thread and request metrics.

## Impact

- **Backend:**
  - `platform/broker`: `BrokerClientFactory`, `JolokiaBrokerClient`, `NodeCallLimiter`,
    `CoreMessageTransport`, `MessageOperations`, `CorePool`, `CoreConnectionFactory`,
    `CoreSubscriptionManager`, `BrokerXmlSnippets`.
  - `platform/clusters/BrokerCommands`, `platform/scrape/ScrapeScheduler`,
    `kernel/audit/AuditService`, `kernel/jobs`, `kernel/core/ShutdownPhases`.
  - `feature/sql` capture and index classes.
  - `feature/queues` and `feature/routing` divert paths, `feature/messages`,
    `feature/resources/PagedListService`, `feature/rr/RrSampler`,
    `feature/events/BrokerEventWriter`.
- **Schema:** new changesets adding capture footprint counters to `message_capture_node`,
  lowering the ring-size ceiling, and any index the address match needs.
- **API:**
  - Browse `total` becomes nullable, with a reason.
  - By-id operations return a partial outcome.
  - Capture create takes `?dryRun`.
  - Divert and capture requests return 400 with per-field errors instead of silently
    clamping.
  - Generated `schema.d.ts` is regenerated.
- **Frontend:** `features/routing/DivertActions`, `RoutingView`,
  `features/sql/IndexSubscriptions`, `ui/NodeOutcomeSummary`, message browse and operation
  views.
- **Deploy:** `Dockerfile`, `deploy/compose/.env.example`, `compose.prod.yaml`
  (`MaxRAMPercentage=50`).
- **Configuration:** `artemis-studio.capture.broker-role` becomes required for capture; new
  `artemis-studio.capture.max-ring-bytes`.
- **Docs:**
  - ADR-0076 limiter per request;
  - ADR-0077 capture acknowledgement and backpressure;
  - ADR-0078 audit row own transaction (supersedes the transaction wording of the audit
    decision);
  - ADR-0079 per-instance capture objects and the reserved prefix;
  - `CLAUDE.md` non-negotiable #3 reworded.
