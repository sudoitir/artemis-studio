# ADR-0196: Heavy-request permits and limits are counted across replicas in PostgreSQL

- **Status**: accepted
- **Date**: 2026-10-13
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0144](0144-password-policy-lockout-and-sign-in-limits.md) (the failed sign-in throttle is no longer per instance), [ADR-0136](0136-token-rotation-lifetime-cap-limits-and-usage.md) (the in-flight cap per token), [ADR-0076](0076-rate-limit-every-jolokia-request-inside-the-client.md) (the per-node rate is shared between replicas)

## Context

With replicas (ADR-0152) a limit kept in a replica's memory is multiplied by the number of replicas. An
operator limited to two SQL console queries could run two on each; five failed sign-ins allowed five on each;
a token's in-flight cap and a user's five plugin uploads an hour held per replica; and several replicas together
called a broker node faster than its configured rate. None of these limits protects what it is for in a
replicated deployment.

Separately, endpoints whose cost grows with the data they cover (an audit export, a history page, a bulk or
transfer preview, the flow graph) had no common bound. Only the SQL console limited its time, its rows and its
concurrency, and said which it reached.

## Decision

1. **`HeavyRequests` gives every heavy endpoint three bounds.** A per-user concurrency cap, a time bound and a
   row cap, declared as an `Endpoint` by the code that serves it. A request that is refused is HTTP 429 with the
   problem type `too-many-heavy-requests`, naming the endpoint and the limit and saying whether it was the user's
   own or the installation's. A read the database stops at its time bound before it has a page to return is HTTP
   503 with `heavy-request-timed-out`. A request that can return what it has read, such as an export, stops at
   the bound and says which one: the file ends with a mark naming `ROWS` or `TIME`, and the audit event of the
   export records it as partial with the bound.
2. **Permits are rows of an UNLOGGED table, `heavy_permit`.** A claim is one `INSERT ... ON CONFLICT DO UPDATE ...
   WHERE expired` over `generate_series(1, cap)`, so two replicas never take the same slot. A permit is released
   when the request ends and lapses at its time bound plus 30 s, so a replica that dies does not hold a user's
   limit. An installation-wide cap of a quarter of the connection pool (at least one) bounds all heavy requests
   together, so they cannot take every connection from ordinary ones. The SQL console uses it in place of its
   per-replica map.
3. **Heavy history reads run on the read replica when there is one.** Audit history, event history, alert
   history and the audit export go through `ReadRouting` and say in the response (`mayLag`) when the replica
   served them. The UI shows a short notice.
4. **History is paged by key beyond the first rows.** A page number reaches the first 10,000 rows, with a total;
   a response carries a `next` cursor, passed as `after`, which reads the same however deep and has no total.
   A page number beyond 10,000 rows is refused with a message naming the cursor.
5. **The other per-user limits are counted in PostgreSQL too**, in UNLOGGED tables written with
   `INSERT ... ON CONFLICT DO UPDATE`: the failed sign-in throttle (`login_attempt`), the plugin upload limit
   (`upload_window`) and a token's in-flight cap (`request_in_flight`). Each row lapses at a time it carries and
   housekeeping removes what nobody came back to (ADR-0134). The sign-in throttle is written in its own
   transaction, so a failed sign-in that rolls its request back stays counted.
6. **The per-node call limiter divides its rate by the number of ready replicas** instead of counting in the
   database: a round trip per Jolokia call would cost more than it saves. Each replica takes the rate divided by
   the ready replicas, rounded up and at least one, read from the replica registry at most every five seconds.
   Broker calls are counted in `studio.broker.calls`, tagged by kind, and `BrokerListOps.readAll` sends its
   reads in batches of 200.

Kept as they are, because they are already shared or need no sharing: the caches invalidated through the bus,
`api_request_window`, ShedLock, advisory locks, leases and the `SKIP LOCKED` queues.

## Consequences

- A limit means what it says in a replicated deployment, and survives a restart.
- A heavy request costs a few small statements for its permit. They are the cost of the guarantee and are
  cheaper than the work they guard.
- A sign-in costs a read, and a failed one two writes. The throttle is no longer lost on a restart, and a
  database that cannot be reached cannot throttle: the sign-in fails with it.
- UNLOGGED tables are emptied by a crash of the database, which drops the permits and counters with it; the
  limits start again from zero, which is the safe direction for a permit and a short reprieve for a throttle.
- A node's rate is shared in whole calls per second, so a rate below the number of ready replicas lets each
  replica call once a second and the node can see more than its configured rate. The default is 20.
- The new bounds on the audit export, the events and the history make these screens refuse a page number beyond
  10,000 rows. An operator narrows the time range, or an API client pages by `after`.

## Alternatives considered

- **Per-replica counters with a limit divided by the replica count.** Cheap, but a user whose requests all reach
  one replica gets a fraction of their limit, and a replica count that changes moves every limit.
- **Redis or another store.** A second system for what Postgres, which Studio already needs, does in one
  statement.
- **Advisory locks for permits.** They are released when a session ends, which looks like the right lease, but
  they hold a connection for the whole request and are invisible in a table an operator can query.
- **A shared counter for the node rate.** One round trip per management call; rejected in the design as costing
  more than it saves.
- **Offset paging with a larger cap.** Cost grows with the offset; a cursor does not.
