# ADR-0036: Notification delivery is a durable Postgres queue, batched per rule per tick

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

`006-alerting.sql` scaffolded `notification_channel` (`name`, `kind` —
`WEBHOOK`/`SLACK`/`EMAIL`, `config JSONB`) but nothing joins it to `alert_rule`,
nothing records a delivery attempt, and a channel's secret (a Slack webhook URL
*is* its credential) had no encryption path — unlike `broker_credential`, which
has used AES-GCM since ADR-0009. Studio has no outbound-webhook code today; the
only precedent for calling out over HTTP is `BrokerClientFactory`'s
`RestClient` recipe, built for per-node Jolokia calls with their own
credentials, rate limiter, and TLS bundles — none of which a Slack/webhook call
should share.

README's "Notification channels — webhook, Slack" and "Built-in critical
alerts" both imply delivery must survive more than the happy path: a receiver
can be down, rate-limit, or revoke a webhook, and Studio itself can restart
mid-retry.

## Decision

We will add `alert_rule_channel` (routing), `alert_delivery` (a durable retry
queue and ledger), and `alert_firing` (append-only start/resolve history,
since `alert_state` alone is overwritten in place), and give
`notification_channel` `secret_ct`/`secret_nonce` columns.

- **One `alert_delivery` row per `(rule, channel)` per evaluation tick**, never
  one row per firing subject. Its payload lists every transition that tick
  produced for the rule. A rule matching 200 queues that all cross at once
  produces one notification per bound channel, not 200 — the storm guard, with
  no grouping engine and no cap to configure.
- **`AlertDispatcher`** (`@Scheduled`, alongside `RrDeadlineSweep`) claims due
  rows with `SELECT ... FOR UPDATE SKIP LOCKED`, which costs nothing at
  today's single-instance scale and is already correct for the
  multi-instance seam ADR-0015 left open. Backoff on failure is exponential
  with jitter — the same shape as `broker.core.Backoff`, reimplemented
  stateless in `scheduler/AlertBackoff` because a delivery's attempt count
  lives on the row (`alert_delivery.attempts`), not in an in-memory map keyed
  by node. Five failed attempts and a delivery goes `DEAD`, not retried
  forever.
- **Two channel kinds only: `SLACK` and `WEBHOOK`.** `EMAIL` is dropped from
  the CHECK — no README task asks for it, and it would need an SMTP dependency,
  a config surface, and its own test path for zero requested behavior.
  - **Slack**: POST to the incoming-webhook URL with a `{text, blocks}` Block
    Kit body. The channel's secret *is* the webhook URL. A `404 no_team`
    (revoked webhook) goes `DEAD` immediately, never retried; a `429` honours
    `Retry-After`.
  - **Webhook**: signed per the Standard Webhooks spec —
    `webhook-id`/`webhook-timestamp`/`webhook-signature: v1,<base64
    HMAC-SHA256>` over `"{id}.{timestamp}.{body}"`. `webhook-id` is the
    delivery row's own id, giving a receiver a free idempotency key across our
    retries of that row. Hand-rolled with `javax.crypto.Mac` (~10 lines); the
    `standard-webhooks` library is a dependency for less code than it saves.
- **`SecretVault` (ADR-0009) gains an overload taking an opaque AAD string**
  instead of always `clusterId + "|" + kind` — a channel has no cluster.
  Callers pass `channelId + "|" + kind`. This generalizes the AAD's *shape*
  from "always a cluster" to "the entity that owns this secret"; the cipher,
  key source, and "AAD binds ciphertext to its row" principle are unchanged, so
  this is a clarification of ADR-0009, not a supersession.
- **Channels stay global, not per-cluster.** One Slack workspace commonly
  serves several clusters; routing is expressed by which rules bind to which
  channel, not by a channel's own scope.
- **Audit draws the line at operator intent.** Rule CRUD, channel CRUD, and the
  manual "send test" action write `audit_event` via the existing
  `AuditService` pattern, in the same transaction as the command. Firings and
  deliveries do not — they are machine-generated, would drown the operator
  audit log, and have their own tables (`alert_firing`, `alert_delivery`) as
  record.

## Consequences

- A receiver outage during a Studio restart resumes retrying automatically —
  no lost notification, no duplicate spam beyond the batching already in
  place.
- `alert_delivery.payload` duplicates data already in `alert_firing`; the
  dispatcher only ever reads its own table, so there is exactly one writer and
  one reader per table and no risk of the two drifting into an inconsistent
  read.
- Holding a single short DB transaction across a claimed batch's HTTP round
  trips is an accepted simplification at today's single-instance, low-volume
  scale; it costs throughput, not correctness, if ever run with multiple
  dispatcher instances.
- An `EMAIL` channel needs a new changeset re-widening the CHECK plus its own
  ADR for the SMTP choice — a small, well-understood reopening, not a design
  debt.

## Alternatives considered

- **One `alert_delivery` row per `(firing subject, channel)`.** Rejected — the
  storm case (many subjects crossing at once) would produce as many
  notifications as subjects, which is worse than the alert being useful.
- **A nullable `cluster_id` on `notification_channel`** to keep
  `SecretVault`'s AAD shape unchanged. Rejected — it forces every channel into
  a fake per-cluster identity to satisfy an implementation detail of the vault,
  when generalizing the vault's AAD parameter is the smaller, more honest
  change.
- **spring-retry or resilience4j** for dispatcher backoff. Rejected — neither
  is a project dependency, and the existing `Backoff` shape is ~20 lines to
  reproduce statelessly; adding a library here would violate "prefer
  established libraries only when they reduce complexity," not increase it.
- **An `EMAIL` channel via Spring Mail.** Rejected for this phase — no README
  task requests it, and it is a straightforward addition later behind its own
  ADR.
