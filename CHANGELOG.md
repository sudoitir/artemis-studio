# Changelog

All notable changes to Artemis Studio are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is CalVer
`YYYY.MM.PATCH` — see [`.claude/rules/10-release.md`](.claude/rules/10-release.md).

Images are published to Docker Hub as
[`sudoit1/artemis-studio`](https://hub.docker.com/r/sudoit1/artemis-studio).
There is no stable release yet: the moving tag is `:dev` and GitHub Releases are
marked as pre-releases.

## [Unreleased]

### Breaking

- **An expectation's reply address is now a set of patterns.** The request-reply
  expectation API field `replyAddress` (a single string) is replaced by
  `replyAddresses` (an array of strings) on the create, update and read payloads.
  Any client posting `replyAddress` must send `replyAddresses: ["<the old value>"]`
  instead, or `[]` where it previously sent nothing. Stored expectations migrate
  themselves — an existing reply address becomes a one-entry set — so no operator
  action is required in the UI.

### Added

- **One expectation can trace reply queues you cannot list in advance.** Reply
  addresses are now a set, and each entry may be a pattern: `orders.reply.*` covers
  a reply queue per responder, including ones created after you declared it. This is
  what makes tracing work against a deployment whose reply queue is named after the
  broker node or the client host, where any fixed list is stale as soon as something
  is redeployed. `*` matches any run of characters and matching is anchored at both
  ends; Artemis's `#` is not a wildcard here. The form shows what a pattern currently
  resolves to as you type it, and says so when it matches nothing yet — which is
  normal, not an error.
- **A completed flow records which reply queue answered it.** When more than one
  reply address is in play, the flow takes its reply destination from the reply that
  joined it, so you can see which responder served a given exchange.
- **A separate broker account for the Core connection.** Settings can now rotate the
  Core-protocol credentials independently of the management (Jolokia) ones. Set these
  when your management account is also the broker's `<cluster-user>`: Artemis reserves
  that account for inter-node traffic and refuses it over Core with `AMQ229099`, which
  previously left the notification subscription failing with no way to fix it short of
  re-registering the cluster.

- **Pick an address instead of typing it.** The request and reply address fields on the
  Requests screen now suggest the cluster's own addresses as you type, each with its
  routing type, current depth and how many nodes carry it — enough to tell a request
  queue from a reply queue without leaving the form. You can still type a name that does
  not exist yet; the field says it matched nothing rather than refusing it. The
  suggestions can be narrowed to anycast or multicast.

### Changed

- **The broker-capabilities notice can be dismissed.** It stays dismissed for the rest of
  your session and comes back when you sign out, when someone else signs in, or when a
  *different* capability starts falling short — so waving away a known gap never hides a
  new one.

### Fixed

- **Every node serving a traced address is now sampled.** Studio browsed only the
  first active node of a cluster, so in a multi-primary cluster the request and reply
  traffic on the other nodes was never read and the correlation identity that only
  browsing supplies was missing for most exchanges.
- **A reply consumed faster than the sampler ticks is no longer missed.** A delivery
  on a declared reply address now counts as a reply observation, alongside the
  existing browse. It carries no correlation id, so it completes a flow only where
  sampling already identified the request — coverage, not a replacement for browsing.
- **Request-reply sampling failures are reported.** A failure was swallowed at debug
  level, so a correctly-configured-looking expectation produced no flows and said
  nothing about why. Failures now log a warning naming the expectation and the node,
  rate-limited so a node that is down for an hour does not flood the log.
- **Capabilities are assessed against a live node.** Studio probed whichever node sorted
  first by name. On a cluster whose backup sorts before its primary that meant probing a
  passive backup, which registers no acceptor and no address MBeans — so Studio reported
  "CORE acceptor not found" and "activemq.notifications address not found" about a broker
  where both were present.
- **A Jolokia agent that labels its JSON `text/plain` is understood.** The agent bundled
  with Artemis 2.39 answers a valid Jolokia response with
  `Content-Type: text/plain;charset=utf-8` where 2.44 sends `application/json`. Studio's
  client only accepted the JSON content types, so every response from the older broker
  failed to convert and a healthy cluster was reported as "the broker answered, but not
  with a Jolokia response".
- **The MCP client configuration example is valid.** It omitted `"type": "http"`, which
  clients reject.

- **A broker that refuses the connection now says so.** Registering a cluster against an
  Artemis console that rejects the credentials reported *"The broker answered, but not with
  a Jolokia response"* — a message that sent operators looking for a proxy or a CORS problem
  that was not there. Studio now classifies the refusal from the HTTP status rather than
  from an exception subclass, and repeats what the broker itself said: the
  `Hawtio-Forbidden-Reason` header the Artemis console sets on its bare 403, and any
  `WWW-Authenticate` challenge.
- **A management URL pointing at the console instead of the agent is named as such.** Studio
  no longer follows redirects to the console's login page and then reports the resulting
  HTML as a bad Jolokia response; a redirect is reported as the wrong path, with the
  location the broker sent. A seed typed as `host:port/console` is completed to
  `/console/jolokia` rather than left to fail.
- **Tracing an already-traced request address returns a conflict, not a server error.**
  Adding the same request address twice failed with an HTTP 500 whose body said nothing;
  it now returns 409 naming the address, and the Requests screen shows that message. The
  enable/disable switch and the remove button on that screen also report their failures
  instead of appearing to do nothing.
- **The config diff table is readable again.** Long acceptor values no longer take the whole
  row and squeeze the key and status columns to one character per line; wide values scroll
  inside the section, and a key too long for its column is revealed on hover.

## [2026.09.3] — 2026-09-06

### Added

- **Settings changes no longer need a restart, and there are far more of them.** The
  Settings screen now covers 25 operational keys instead of 5: broker connect/read
  timeouts, the request-reply deadline, payload capture cap, sweep and sampler
  cadences, alerting dispatch interval, retry attempts and backoff, the broker-event
  flush interval and buffer size, the SSE heartbeat, the bulk-operation safety cap,
  and every retention window plus the cron each reaper runs on. All apply on the next
  use or the next fire — none require a restart, and `Reset` puts a key back to the
  packaged default.
- **Deploy-time configuration can live in Postgres.** A new `studio_config_property`
  table is read during startup and contributes to the application's configuration,
  keyed by application / profile / label. It ships empty, and an unreachable or
  not-yet-migrated database is a warning rather than a failed start. Set
  `ARTEMIS_STUDIO_CONFIG_ENCRYPT_KEY` to store a value as `{cipher}…` and have it
  decrypted at startup. This is a **different key** from
  `ARTEMIS_STUDIO_SECRET_KEY`, which still exclusively seals broker credentials — do
  not set them to the same value. See [ADR-0047](docs/adr/0047-two-configuration-planes.md).
- `POST /actuator/refresh` re-reads configuration into the running application. It
  requires the `settings:write` permission. Note that it does **not** rebind most
  components — for anything an operator changes, use Settings, which does.

### Changed

- The Settings screen is now generated from the server's own description of each key,
  so its labels and hints cannot drift from what the settings actually do.

### Fixed

- **Changing a setting is now recorded in the audit trail**, with the old and new
  value, in the same transaction as the change. Settings writes were previously the
  one mutating path that wrote no audit event.
- **`artemis-studio.rr.sweep-interval` now does something.** The request-reply
  deadline sweep and the sampler both hardcoded a 5-second cadence and ignored the
  configured value entirely. If you had set this property and observed no effect,
  that was why — it now applies, so check the value you set.
- The Settings screen no longer claims scrape cadence changes "take effect on
  restart". They have applied immediately since
  [ADR-0025](docs/adr/0025-live-scrape-cadence-scheduling-configurer.md); only the
  caption was out of date.
- `safety.bulk-cap`, `events.retention-hours` and `events.buffer-size` were reachable
  over the API but missing from the Settings screen. They are now shown.

### Security

- `POST /actuator/refresh` is gated on `settings:write`. Other non-health actuator
  endpoints (`/actuator/prometheus`, `/actuator/metrics`, `/actuator/info`) remain
  reachable without authentication, as before — bind Studio behind a proxy if that
  matters to you.

## [2026.09.2] — 2026-09-06

### Security

- **Cluster-scoped reads now honour your grants.** Six read endpoints — the cross-node
  queue grid, the addresses / consumers / sessions / connections / producers views, the
  metrics timeseries, the audit trail, and the broker event history — did not check
  whether the caller held a grant on the cluster they addressed. Any signed-in user
  could read any registered cluster's queues, metrics, audit trail, and events by
  putting its id in the URL, regardless of the roles they had been given. They are now
  checked like every other cluster-addressed read, and a cluster you hold no grant on
  answers `404` rather than revealing that it exists.

  **After upgrading, users whose grants are scoped to specific clusters or environments
  will lose access to data they could previously see.** That is the fix working. If
  someone genuinely needs cross-cluster visibility, grant them the role at global scope
  (Administration → Users). Users holding a global grant are unaffected.

## [2026.09.1] — 2026-09-05

### Added

- **Slow-consumer detection.** A consumer that is attached but not draining is now
  visible two ways. Studio surfaces the broker's own `CONSUMER_SLOW` notification on
  the `consumers` event topic — the only source that can name the individual consumer
  — and, for brokers where native detection is not configured, a new
  `ackRatePerConsumer` alert metric you can threshold yourself. It only evaluates
  queues that have consumers attached, a non-zero backlog, and are not paused, so it
  does not page on idle queues. No rule is created for you: the threshold is
  workload-specific, so the rule form offers a prefilled template instead.
  Studio's own detection resolves to a queue on a node, never to one consumer, and
  says so.
- **Broker configuration diff across a pair.** A new *Config diff* view per cluster
  compares two nodes' effective configuration — broker attributes, address settings,
  security settings and acceptors. Differences that are correct by design (a broker's
  name, its HA policy, node-local paths, acceptor host and port) are shown as
  *expected*, and runtime counters as *unclassified*, so a healthy primary/backup pair
  reports no drift instead of two dozen false positives. Nothing is hidden: every key
  either node returns appears somewhere. If either node cannot be read, Studio says so
  and shows no comparison rather than a half-diff in which the unreachable node's keys
  look deleted. Read-only, at the same permission as the topology view.
- **Payload inspection in the message detail panel.** A browsed message's body now
  reports its detected format, and JSON and XML are pretty-printed and syntax
  highlighted, with a formatted/raw toggle plus copy and download. Binary bodies are
  shown as a hex + ASCII dump instead of being decoded into mojibake, and gzip, zip,
  Java-serialized and Avro payloads are named. A body the broker truncated says so,
  rather than reporting your payload as malformed. Very large bodies are shown
  unformatted with a note, so the panel never blocks on a multi-megabyte message.
- Syntax highlighting now works throughout the app. No highlighter was ever mounted,
  so every `broker.xml` snippet Studio shows — including the one next to a truncated
  message — rendered as plain text. It is loaded lazily, so first paint is unaffected.
- The capability list gains a **slow-consumer detection** row. On brokers that do not
  expose `slow-consumer-threshold` over management — which is all of them today —
  it reports *unknown* rather than guessing, with the `broker.xml` to enable it.
- A message's type now reads `text` or `bytes` rather than `type 3`.

### Fixed

- **Topology view.** The band carrying each pair's shared NodeID was drawn at a fixed
  position over the canvas rather than attached to the nodes it grouped, so it lined up
  only by coincidence and slid out of place on the first pan or zoom. Each logical node
  is now a real group containing its endpoints. Split-brain reads as two boxes above
  one group's line instead of a colour change on a detached rule.
- Topology status marks are told apart by shape — filled disc, hollow ring, half-filled,
  dashed ring — instead of by two shades of grey eight pixels apart.
- A two-node cluster is no longer magnified to fill the frame at wide viewport sizes.
- The topology canvas gains zoom controls, a legend, a keyboard focus indicator, a
  loading placeholder the size of the graph, and an explanation when a cluster has no
  nodes yet.
- The "add a management URL" prompt on a discovered-but-unreachable node was a
  permanently disabled button. It now opens the dialog that adds the URL.
- The topology graph re-fits after a failover instead of leaving a stale viewport.

### Changed

- `queue_snapshot` gains a `paused` column so paused queues can be excluded from
  slow-consumer detection. Applied automatically on startup; no action needed.

## [2026.09.0] — 2026-09-05

### Added

- Automated release pipeline. Every push to `main` builds a multi-arch
  (`amd64` + `arm64`) image, pushes it to Docker Hub as `:<version>`, `:<YYYY.MM>`
  and `:dev`, cuts a git tag, and creates a GitHub pre-release with the runnable
  jar and its SHA-256 checksum attached.
- `CHANGELOG.md` and `.claude/rules/10-release.md` documenting the CalVer scheme
  and the merge-time changelog obligation.

### Changed

- `just up` / `just down` now run the **prod** stack against the published image
  (previously the dev build). The dev stack — Postgres plus a real Artemis
  primary/backup pair, built locally — moved to `just dev-up` / `just dev-down`.
  `just prod-up` / `just prod-down` are removed.
- `just up` runs `just setup` first: it generates `deploy/compose/.env` with a
  random `SECRET_KEY` / `DB_PASSWORD` on first run and pins `STUDIO_IMAGE` to the
  newest published release tag (`:dev` until one exists), so a clean clone comes
  up with a single command.
- The published image is `sudoit1/artemis-studio` on Docker Hub. The previous
  `ghcr.io/sudoitir/artemis-studio` reference is dropped — CI only ever built that
  tag and threw it away.
