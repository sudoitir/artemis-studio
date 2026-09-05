# Changelog

All notable changes to Artemis Studio are recorded here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is CalVer
`YYYY.MM.PATCH` — see [`.claude/rules/10-release.md`](.claude/rules/10-release.md).

Images are published to Docker Hub as
[`sudoit1/artemis-studio`](https://hub.docker.com/r/sudoit1/artemis-studio).
There is no stable release yet: the moving tag is `:dev` and GitHub Releases are
marked as pre-releases.

## [Unreleased]

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
