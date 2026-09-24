## Context

See proposal.md for why this change exists. The current state that shapes the approach:

- **Delivery is already durable.**
  - `AlertEvaluator` writes one `alert_delivery` row per (rule, channel, tick). The
    payload is JSON listing every transition.
  - `AlertDispatcher` claims due rows with `FOR UPDATE SKIP LOCKED` and hands them to
    the `NotificationSender` whose `kind()` matches.
  - A sender reports `ok`, `retryable(+Retry-After)` or `permanent`, and never throws.
  - A channel's secret is AES-GCM in `notification_channel.secret_ct`, and its
    non-secret part is `config` jsonb.
  - `ck_notification_channel_kind` allows only `WEBHOOK` and `SLACK`.
  - `ck_alert_rule_state_condition` allows only the five baseline conditions, so a `CONFIG_DRIFT` rule, which the API accepts, is refused by the database. Found while verifying this change end to end, and widened in the same changeset.
- **Transitions carry opaque subject keys** (`node:<uuid>`, `queue:<name>`, `cluster`,
  `studio`), and the payload carries no cluster.
- **State conditions owned by other modules** come through `AlertSignalSource`
  (`CONFIG_DRIFT` is the precedent), and `AlertRuleService.STATE_CONDITIONS` lists what
  a rule may name.
- **`feature/brokerconfig` already reads configuration** in one batched POST per node
  (`ConfigReader`). It reads for diffing, not reviewing. Its drift job runs under
  `ClusterLock.Scope.CONFIG_DRIFT`.
- **Verified against Artemis 2.44 sources** (`artemis-server`, `artemis-core-client`):
  - `HAPolicy` is one of: `Primary Only`, `Replication Primary w/quorum voting`,
    `Replication Backup w/quorum voting`, `Replication Primary w/lock manager`,
    `Replication Backup w/lock manager`, `Shared Store Primary`, `Shared Store Backup`,
    `Colocated`.
  - `QuorumManager` skips the vote and returns its decision immediately when
    `maxClusterSize <= 1`. It logs AMQ221083.
  - `QuorumVoteServerConnect` needs `size/2` votes for a size of 2 or less, and
    `size/2 + 1` above that.
  - `ClusterConnectionControl` (`component=cluster-connections,name=*`) exposes `Name`,
    `Address`, `NodeID`, `DuplicateDetection`, `MessageLoadBalancingType`, `MaxHops`,
    `StaticConnectors`, `DiscoveryGroupName`, `RetryInterval`, `Nodes` (node id →
    address) and `Started`.
  - `ActiveMQServerControl` exposes `ConnectorsAsJSON`, `AcceptorsAsJSON`,
    `PersistenceEnabled`, `SecurityEnabled`, `MaxDiskUsage`, `SharedStore`,
    `FailoverOnServerShutdown`, `Clustered`, `ClusterConnectionNames` and `Version`.
  - `redistribution-delay` defaults to `-1`, and `max-disk-usage` to `90`.
  - **Not exposed**: `network-check-list`, `vote-on-replication-failure`,
    `quorum-size` and `check-for-active-server`.

## Goals / Non-Goals

**Goals:**
- Email, Teams and PagerDuty channels, with the delivery machinery that exists
  (durable, batched per tick, retried, never one row per subject).
- A notification an on-call engineer can act on without opening Studio first.
- A channel screen that is finished: edit, disable, test before save, delivery
  health, a delivery log, and typed delete.
- A setup review whose every finding is:
  - evidenced per node;
  - explained in operator terms;
  - paired with a copyable fix;
  - honest about what the management API cannot see.

**Non-Goals:**
- **Per-channel routing rules** (severity filters, schedules, quiet hours). Routing
  stays "which channels a rule is bound to".
- **Applying fixes from the review.** A few fixes are address settings that
  `brokerconfig` can already apply. The review links to that screen, and never writes
  to a broker itself.
- **Reading `broker.xml` from disk.** Studio has no file access to brokers, and must
  not need it (the management API is the only channel, per non-negotiable 1).
  Anything not exposed is disclosed as unassessed.
- **OAuth for SMTP, and Slack/Teams apps with bot tokens.** Webhooks only.

## Decisions

### D1. One sender per kind, one shared formatter

`AlertMessage` is parsed once from the payload JSON: the rule, severity, cluster, link,
and transitions with readable subject labels. `AlertMessageFormatter` renders it as a
title, a plain-text body, and a small HTML body. Slack, Teams, email and PagerDuty
summaries all come from it, so the four channels cannot drift apart in what they say.
Slack moves onto it too. Its wire format is unchanged, but its subject text becomes
readable.

### D2. Payload enrichment is additive

`AlertEvaluator.enqueueDelivery` adds these fields:
- `event: "alert.transitions"` and `version: 2`;
- `clusterId` and `clusterName`;
- `firedCount` and `resolvedCount`;
- per transition, `subjectLabel` and `at`.

It also adds `studioUrl` when `artemis-studio.alerting.public-url` (`ARTEMIS_STUDIO_PUBLIC_URL`) is set. That is a deploy-time property, because runtime settings have no string kind. Existing fields keep their
names and meaning, so a receiver written against the old payload keeps working. The
label is resolved once, at enqueue time: `node:<uuid>` becomes the node name,
`queue:<name>` becomes `queue <name>`, `studio` becomes `Studio host`, and `cluster`
becomes the cluster name. A retried delivery therefore says what was true when it
fired.

### D3. PagerDuty: one event per transition, idempotent retries

A delivery row carries every transition of a tick. The PagerDuty sender posts one
Events v2 event per transition:
- `trigger` for FIRED and `resolve` for RESOLVED;
- `dedup_key = sha256("artemis-studio|" + ruleId + "|" + subject)` in hex (PagerDuty
  caps it at 255 characters, and a queue name can be long);
- `payload.summary` is capped at 1024 characters;
- `severity` maps CRITICAL → `critical`, WARNING → `warning`, and anything else →
  `info`.

A retry re-sends the whole row. PagerDuty deduplicates a `trigger` on an open key and
ignores a `resolve` on a resolved one, so re-sending already-accepted events changes
nothing. That is why partial progress needs no ledger. The sender stops at the first
failure and classifies the row by it:
- 400 → permanent (the event is malformed, and retrying will not fix it);
- 429 → retryable, honouring `Retry-After`;
- 5xx or I/O → retryable.

The endpoint defaults to `https://events.pagerduty.com/v2/enqueue`, and is set
per channel for the EU region or a compatible receiver. The routing key is the
channel's secret.

### D4. Teams: an Adaptive Card through a webhook URL

The secret is the webhook URL, as for Slack. The body is
`{"type":"message","attachments":[{"contentType":"application/vnd.microsoft.card.adaptive","content":<card>}]}`.
Both Power Automate Workflows webhooks and the retiring Office 365 connector webhooks
accept this shape. The card has:
- a title with the severity in words (colour is only redundant emphasis);
- a FactSet with the cluster and rule;
- one line per transition;
- an `Action.OpenUrl` button when a Studio link exists.

400, 401, 403, 404 and 410 are permanent: the flow was deleted or the URL is wrong.
429 honours `Retry-After`.

### D5. Email: Jakarta Mail, one short-lived sender per delivery

Configuration: `host`, `port`, `security` (`STARTTLS` | `TLS` | `NONE`), `username`,
`from`, `to[]` and `subjectPrefix`. The SMTP password is the secret.
- **STARTTLS is required, not opportunistic.** `mail.smtp.starttls.required=true`
  and `ssl.checkserveridentity=true`, so a downgrade fails instead of sending in
  clear.
- Connection, read and write timeouts come from `alerting.email-timeout`.
- Addresses are parsed strictly when the channel is saved. The subject is stripped of
  CR/LF, so a rule name cannot inject headers.
- The HTML part escapes every value.
- **Classification:**
  - authentication failure → permanent;
  - every recipient rejected → permanent;
  - connect or I/O failure → retryable.

A `JavaMailSenderImpl` is built per send. It is cheap next to an SMTP handshake, and
it keeps no state that could leak one channel's credentials into another's session.

### D6. Channel API

- `NotificationChannelView` gains `boundRuleCount` and a nullable `health`:
  - `lastState`, `lastAttemptAt`, `lastError`;
  - `pending`, `failedLast24h` and `sentLast24h`.

  One grouped query computes it for all channels, so the list stays O(1) queries.
- `GET /channels/{id}/deliveries?limit=` returns the newest deliveries first (at most
  100), each with the rule name, state, attempts, last error, times and a one-line
  summary.
- `POST /channels/{id}/deliveries/{seq}/retry` resets a `DEAD` delivery to `PENDING`
  with its attempts cleared. It is audited (`RETRY_NOTIFICATION_DELIVERY`), and 409 is
  returned for a delivery that is not dead.
- `POST /channels/test` takes `{channelId?, kind, config, secret?}`:
  - a blank secret with a `channelId` means "use the stored secret";
  - it sends synchronously, and is audited `TEST_NOTIFICATION_CHANNEL`;
  - it returns `{delivered, permanent, error, durationMs}` with status 200, whatever
    the outcome. A failed test is a result, not a server error.

  `POST /channels/{id}/test` returns the same result. This replaces the 502 that a refused test answered with (`NotificationDeliveryException`): a receiver refusing a test is the answer the operator asked for, not a failure of Studio's request.
- Validation is per kind, on create, update and test:
  - URLs must be `http(s)` with a host;
  - an email channel needs a host, a port in 1–65535, a valid `from` and at least
    one valid `to`;
  - a PagerDuty routing key must be 32 characters (when the endpoint is the default).

  A rejection names its field.

### D7. Setup review: read, evaluate, persist, and alert from the table

- **The reader** (`SetupReader`) makes one batched POST per manageable node:
  1. `readAll` of the broker MBean;
  2. `exec getAddressSettingsAsJSON("#")`;
  3. `readAll` of the pattern `<broker>,component=cluster-connections,name=*`.

  The request goes through the per-node limiter, like every other Jolokia call. An
  entry that fails (no cluster connections, an ACL) turns into `null` for that part
  only, and the rules that need it report as not assessed.
- **The catalogue** (`SetupRules`) is pure. Its input is a `ClusterSnapshot`: the
  per-node reads plus Studio's topology (logical nodes, endpoints, live state). Its
  output is `Finding`s, each with a `code`, `category`, `severity`, `subject`, `title`,
  `impact`, `evidence[]`, `recommendation`, `snippet`, and `alsoAssessed` caveats. The
  catalogue is table-tested without Spring.
- **Persistence** uses three tables:
  - `setup_review`: one row per cluster, holding the last run's time, the nodes read
    and the nodes unreachable with reasons.
  - `setup_finding`: PK `(cluster_id, code, subject)`, with the rendered finding as
    jsonb and `first_seen_at` / `last_seen_at`.
  - `setup_finding_acceptance`: PK `(cluster_id, code, subject)`, with reason,
    actor, created and expiry.
- **What a run updates.** A run replaces findings only for the subjects it could
  evaluate:
  - a node that answered, for its node-scoped findings;
  - `cluster`, when every live, manageable node answered.

  A finding about an unreachable node is kept and marked stale (`last_seen_at` older
  than the run). It is never silently resolved.
- **`SETUP_RISK`.** `SetupRiskSignal` implements `AlertSignalSource`:
  - the universe is every current finding at warning or critical;
  - active means not accepted, or accepted with an elapsed expiry;
  - the subject is `setup:<code>:<subject>`.

  A finding that disappears leaves the universe, so the evaluator's existing orphan
  handling resolves it. A stale finding stays in the universe, so it stays firing.
  `AlertRuleService` adds `SETUP_RISK` to its closed set, and the rule form offers it
  as a template.
- **Scheduling.** The `setup-review` job runs at `setupreview.interval` (default
  `PT15M`) under the new `ClusterLock.Scope.SETUP_REVIEW`, so two instances never
  review the same cluster at once. `POST .../setup-review/run` runs one cluster now.
  It returns the fresh review. If the last run was under `setupreview.min-interval`
  (default `PT30S`) ago, or another run holds the lock, it returns the current review
  with a `notice` stating why, and status 200. After a run the module publishes
  topic `setup-review`.
- **Permissions.** Reading needs `cluster:read` (the same as config diff), and
  running a review does too, since it is a read. Accepting a risk, or revoking an
  acceptance, needs `alert:write`, because it silences an alert. Both are audited
  (`ACCEPT_SETUP_RISK`, `REVOKE_SETUP_RISK`).

### D8. The rule catalogue (v1)

| Code | Severity | Fires when | Fix offered |
|---|---|---|---|
| `HA_SINGLE_PAIR_QUORUM` | critical | Replication with quorum voting, and one primary (NodeID) in the cluster | `<manager>` lock-manager snippet, or add two pairs; mentions `network-check-list` as a mitigation Studio cannot see |
| `HA_TWO_PRIMARY_QUORUM` | warning | Replication with quorum voting and exactly two primaries | Add a third pair, or the lock manager |
| `HA_BACKUP_MISSING` | warning | A primary whose policy expects a backup, and no endpoint shares its NodeID as a backup | Start or register the backup |
| `HA_POLICY_MISMATCH` | critical | Two endpoints of one NodeID with incompatible families (replication, shared store, lock manager), or both primary or both backup policies | Matching `<ha-policy>` pair |
| `HA_NONE` | info | A node with `Primary Only` in a cluster of two or more logical nodes | Add a backup |
| `CLUSTER_NOT_CLUSTERED` | warning | Two or more logical nodes, and a node reports `Clustered=false` or no cluster connection | `<cluster-connection>` snippet |
| `CLUSTER_CONNECTION_STOPPED` | warning | A cluster connection with `Started=false` | Check broker log |
| `CLUSTER_MEMBERSHIP_INCOMPLETE` | warning | A live node's cluster connection does not see a NodeID that Studio sees live | Static connectors snippet; the UDP-in-cloud note |
| `CLUSTER_LOOPBACK_CONNECTOR` | warning | A clustered node's connector advertises `localhost`, `127.*`, `0.0.0.0` or `::1` | Connector with a routable host |
| `CLUSTER_LOAD_BALANCING_OFF` | warning | `MessageLoadBalancingType=OFF` with two or more logical nodes | `ON_DEMAND` with redistribution |
| `CLUSTER_STRANDED_MESSAGES` | warning | Clustered, `ON_DEMAND` or `OFF_WITH_REDISTRIBUTION`, and `#` `redistributionDelay < 0` | `<redistribution-delay>0</…>` (appliable in Broker configuration) |
| `CLUSTER_MAX_HOPS_ZERO` | warning | `MaxHops=0` with two or more logical nodes | `<max-hops>1</max-hops>` |
| `CLUSTER_VERSION_SKEW` | warning | Live nodes report different `Version`s | Upgrade note |
| `CLUSTER_NO_DUPLICATE_DETECTION` | info | A cluster connection with `DuplicateDetection=false` | `<use-duplicate-detection>true</…>` |
| `DURABILITY_PERSISTENCE_OFF` | critical | `PersistenceEnabled=false` | `<persistence-enabled>true</…>` |
| `DURABILITY_DISK_UNBOUNDED` | warning | `MaxDiskUsage` below 0, or 100 and above | `<max-disk-usage>90</…>` |
| `MESSAGES_NO_DLA` | warning | `#` has no dead-letter address and a finite `maxDeliveryAttempts` | `<dead-letter-address>DLQ</…>` |
| `MESSAGES_INFINITE_REDELIVERY` | warning | `#` has `maxDeliveryAttempts=-1` | A finite attempts value and a DLA |
| `MESSAGES_NO_EXPIRY_ADDRESS` | info | `#` has no expiry address | `<expiry-address>ExpiryQueue</…>` |
| `MESSAGES_DROP_WHEN_FULL` | warning | `#` has `addressFullMessagePolicy=DROP` | `PAGE` or `BLOCK` |
| `SECURITY_DISABLED` | warning | `SecurityEnabled=false` | `<security-enabled>true</…>` |
| `SECURITY_PLAINTEXT_ACCEPTOR` | info | An acceptor without `sslEnabled=true` | `sslEnabled=true;keyStorePath=…` |

- **Severity is fixed per code.** Critical means "loses or duplicates data, or
  diverges, under a foreseeable event". Warning means "degrades or strands messages".
  Info means "a hardening step".
- **Subject granularity.** Node-scoped codes (`node:<id>`) track per node.
  Cluster-scoped codes (`cluster`) are one finding, with the per-node evidence
  inside it.
- **Primary counting for quorum.** The count is the number of distinct NodeIDs among
  the cluster's endpoints, unioned with the NodeIDs in every cluster connection's
  `Nodes` map. The finding states both counts, so an operator can see when Studio has
  registered only part of the cluster.

## Risks / Trade-offs

- **[A Studio cluster registered with only some of its members]** Primary counts would
  under-count. → The union with the cluster connections' `Nodes` corrects it. The
  evidence names both sources.
- **[Operators already mitigate with `network-check-list`]** A critical finding they
  have handled. → The finding says Studio cannot see that setting. Accepting it as a
  known risk, with a reason, is the path, and the acceptance is audited.
- **[Webhook URLs reach internal hosts (SSRF)]** Configuring a channel needs
  `alert:write`, and the URL is the operator's intent. → Scheme and host are validated,
  and redirects are not followed (the notification client never enables them).
  Restricting destinations is left to egress policy, and recorded in ADR-0105.
- **[SMTP servers that are slow or tarpit]** → Timeouts are bounded by
  `alerting.email-timeout`, and the dispatcher's batch is bounded. One slow channel
  delays, but does not block, the others' next ticks.
- **[Catalogue false positives]** → Every rule has unit tests over the verified
  attribute shapes. An unknown `HAPolicy` string is not assessed, never guessed at.
