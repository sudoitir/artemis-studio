# ADR-0148: Mutating requests are idempotent on request, through a persisted, user-scoped key

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/09-public-api-contract`

## Context

Scripts and clients retry a `POST` whose answer they never saw, and the retry runs the operation twice:
two transfers, two purges, two rule creations. There are 109 mutating mappings and no support for
retrying safely. Bulk and transfer guard execution with a preview id and plan hash, but everything else
relies on the caller not retrying. A retry happens most often around a restart or a timeout, which is
exactly when an in-memory guard is gone.

## Decision

- **`Idempotency-Key` on any `POST`, `PUT`, `PATCH` or `DELETE` under `/api/v1`.** An `IdempotencyFilter`
  after security handles a request that carries the header (1 to 255 printable ASCII); a request without
  it is unchanged. Plugin-gateway requests pass through, because the plugin owns the header there.
  Multipart uploads with a key are refused with 400 `idempotency-unsupported`, so a client never assumes a
  guarantee it does not have.
- **The scope is the caller's user id.** Tokens and sessions of one user share a namespace and different
  users never do.
- **The fingerprint is SHA-256 over method, path, sorted query and body.** `dryRun=true` and the real
  run therefore differ.
- **The record is persisted** (`idempotency_record`, primary key `user_id, idem_key`) and claimed with
  `INSERT ... ON CONFLICT DO UPDATE` that only overwrites a row that is no longer live (finished more than
  24 hours ago, or pending past a 10 minute lease), so an expired key is absent and a claim abandoned by a
  crash is taken over atomically:
  - claimed: run, and store status, content type, the `Location` and `ETag` headers and body on 2xx to
    4xx; on 5xx, an exception, 401, 403, 429 or a body over 8 MiB delete the row so the retry runs;
  - existing and finished with the same fingerprint: replay status, body and those headers (never a
    cookie) with `Idempotent-Replayed: true`, running nothing;
  - existing with another fingerprint: 422 `idempotency-key-reused`;
  - existing and still running: 409 `idempotency-in-progress` with `Retry-After: 1`.
- **Retention is 24 hours**, purged by an `IdempotencyStore` `ManagedStore` (ADR-0134), so it appears on the
  data-lifecycle page like every other store.
- The header is documented on every mutating operation.

## Consequences

- A client can retry any mutation after a timeout or a restart and get the first result.
- The filter buffers JSON request and response bodies. Bodies here are small and bulk bodies are capped
  (ADR-0022); multipart is excluded.
- A mutation that runs longer than the 10 minute lease can run twice if it is retried with its key after the
  lease; long work is asynchronous (202 plus a run id).
- A stored 4xx is replayed too, so a corrected request needs a new key. That is the meaning of "same key,
  same result"; clients mint a key per logical attempt.
- A table that grows with use, bounded by the 24 hour retention.

## Alternatives considered

- **In memory.** Loses the guarantee across a restart, which is when clients retry.
- **Per-endpoint opt-in.** The requirement is every mutating endpoint, and an opt-in list drifts.
- **Server-minted operation ids.** Needs a round trip before the first attempt and changes every client.
