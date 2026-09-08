# ADR-0064: A console query is submitted by POST and streamed by reference

- **Status**: accepted
- **Date**: 2026-09-07
- **Deciders**: Artemis Studio maintainers

## Context

The SQL Console executes every query — static and tailed — over one SSE endpoint,
`GET /api/v1/clusters/{id}/sql/stream?sql=…`. The browser's `EventSource` can only issue a GET,
so the query text is a query parameter. That was the shortest path to a streaming result and it
has two consequences that are not acceptable in an audited console.

**The query text is written to every log on the path.** A console query carries the values an
operator is searching for: an order number, a correlation id, a customer reference, whatever
they pasted from the incident. Those are application data. A request URL is recorded by
reverse proxies, ingress controllers, and load balancers, in files with a different retention
policy and a different audience from Studio's own audit table — which was carefully designed to
be the place this information lives.

**A long query cannot be run.** Proxies cap URL length well below what a real query can reach,
and the failure is a 414 or a silent truncation rather than anything the console can explain.

The console already refuses to auto-reconnect a dropped query stream, on the grounds that a
query fans out across brokers and is audited and must not run twice without being asked
(`SqlConsoleView`, the `disconnected` state). That reasoning applies equally to how the query is
submitted in the first place: a request that mutates an audit trail and issues broker reads
belongs in a body, not in a URL that anything on the path may replay from a log.

## Decision

We will **submit a query with `POST` and open its stream by reference.**

**D1 — Two steps, one query.** `POST /api/v1/clusters/{id}/sql/query` carries the query text in
its body, performs the parse, validation, planning and cost gate that already happen, and
returns a short-lived reference. `GET /api/v1/clusters/{id}/sql/stream?queryId=…` opens the SSE
stream for it. The URL now carries an opaque id and nothing an operator typed.

**D2 — The reference is short-lived and scoped to its caller.** It is valid only for the session
that created it, only until it is opened, and only for a short window. A reference is not a
capability to be passed around; it is a handle on a query the caller just submitted.

**D3 — Refusals still travel as they do now.** A cost refusal or a validation error is answered
by the POST directly, as a `ProblemDetail`, which is a better place for it than the `failed`
frame the stream currently uses — that frame exists because an `EventSource` cannot read a
non-200 body, and with the POST doing the refusing, most refusals never reach the stream at all.
The `failed` frame stays for what genuinely fails mid-execution.

**D4 — Audit is written by the POST.** It already is, at the start of execution. Moving
submission to a POST does not change when an audit record appears; it changes which request
carries the text being recorded, and moves that text out of the transport that leaks it.

## Consequences

- The console makes two requests where it made one, and the second can fail independently. A
  reference that is never opened expires and its query never runs — which is correct, and means
  the plan and the cost gate must not have side effects beyond the audit record they already
  write.
- This departs from the pattern of ADR-0003 and ADR-0027, where a stream is opened by a plain
  GET. That is still right for the multiplexed cluster stream, whose URL carries a cluster id
  and nothing else. It is wrong here, and the difference is that this stream's parameters are
  operator-supplied data rather than a resource identifier.
- Query text no longer appears in access logs, so reconstructing what someone ran during an
  incident is done from Studio's audit trail, which records it deliberately, rather than from
  an nginx log, which recorded it by accident.
- A shareable query is unaffected: it is shareable because the text is in the console's own URL
  in the browser (`?q=`), which never leaves the client. Only the transport to the server
  changes.

## Alternatives considered

- **Leave it and accept the leak.** Free, and it means the product's own audit design is
  undermined by its transport. The console is the one screen where operator-typed application
  data is routine.
- **Encrypt or hash the query in the URL.** Solves the log leak, not the length limit, and adds
  a key to manage so that a URL can carry something a body carries for nothing.
- **`fetch` with a streaming body reader instead of `EventSource`.** Would allow a POST that
  streams directly, in one request. It gives up `EventSource`'s reconnection and `Last-Event-ID`
  handling, which the app relies on elsewhere, in exchange for removing a request the client
  makes once per query.
