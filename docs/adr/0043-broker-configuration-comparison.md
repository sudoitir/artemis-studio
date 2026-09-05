# ADR-0043: Broker configuration is compared by a classified pointer diff, not a diff library

- **Status**: accepted
- **Date**: 2026-09-05
- **Deciders**: Mahdi Amirabdollahi

## Context

Configuration drift between a primary and its backup — a different
`journal-directory`, a missing `security-setting`, a `max-size-bytes` only one side
enforces — is silent until failover, and expensive exactly then. Nothing in Studio
compared two nodes' configuration.

The slice-0 surface check (`docs/broker-management-notes.md` §14) established the
ground truth this rests on:

- A **passive backup answers in full**: the same 90 broker attributes and 78
  operations as the primary, set difference empty in both directions. The feature
  needs no capability gate.
- Of the 25 attributes that differ on a healthy pair, **22 are runtime counters**
  reading zero on the passive side, and the rest are `Active`, `Backup`, `HAPolicy`
  and `Name`. A naive diff of that pair reports 25 differences, none of which is a
  problem.
- `getAddressSettingsAsJSON` resolves the *effective* settings for any match string,
  including one the node hosts no address for — so the backup answers for the
  primary's addresses. But **no operation enumerates the configured match patterns**,
  so the compared set has to come from somewhere else.

Two failure modes were therefore designed against, not discovered later: a report in
which runtime counters look like drift, and a report in which the *absence* of a
key means "unreachable" but reads as "removed".

## Decision

**We will flatten each side to JSON-Pointer-keyed values with Jackson and compare
the two key sets three ways, then classify every key.**

1. **A hand-rolled pointer diff, not a diff library.** Jackson is already a
   dependency. Each side becomes `Map<pointer, value>`; keys present on both compare
   `SAME`/`DIFFERENT`, keys on one side become `ONLY_IN_LEFT`/`ONLY_IN_RIGHT`.

2. **Address settings, security settings and acceptors are keyed by their own
   identity** — `match` for address settings, `name` for roles and acceptors —
   never by array index. Two nodes returning the same settings in a different order
   report no drift. An element with no identity field falls back to its index so it
   is still shown rather than dropped.

3. **Classification, not filtering.** An allowlist of broker attributes drives the
   Configuration section; everything else — a runtime counter, or an attribute a
   future Artemis adds — lands in a visible Unclassified section, marked as such.
   Nothing returned by either node disappears from the response.

4. **Expected differences are their own class**, distinct from drift: `Name`,
   `NodeID`, `HAPolicy`, the node-local journal/bindings/paging/large-message
   directories, and an acceptor's `host` and `port`.

5. **Never a half-diff.** If either side's read fails, the response marks that side
   unavailable with the classified reason and reports no per-key drift at all. The
   same applies if a passive node answers with a genuinely smaller attribute set.

6. **The compared match set comes from the `queue_snapshot` cache**, not from a
   broker read: `#` plus the cluster's known addresses, capped at 25, with `#`
   always included and the cap disclosed in the response.

## Consequences

- A healthy pair reads as healthy. That is the whole point: an operator who learns
  to ignore six false positives will ignore the seventh entry too.
- **One batched Jolokia POST per node** (non-negotiable #1), through
  `NodeCallLimiter`, following `DlqService`. Taking the match set from the cache
  rather than from `AddressNames` is what keeps it to one — and a passive backup
  reports no addresses anyway, so the cache is the better source in both cases.
- **The allowlist will go stale** as Artemis adds attributes. By construction that
  degrades presentation, not correctness: a new attribute appears under
  Unclassified rather than vanishing or being miscounted as drift.
- **The expected list encodes a judgement** — that two nodes may legitimately differ
  in name, HA role and local paths. If a deployment requires identical paths, those
  differences are reported as expected and the operator must notice them under that
  heading. The class is visible, not suppressed, so the information is there.
- Read-only, at `CLUSTER_READ`, and **deliberately unaudited**: only mutating calls
  write an `audit_event` (ADR-0023).
- The `securitySettings` and `acceptors` sections exist because spike Q4 proved both
  read paths answer on both sides. Acceptors come from the `AcceptorsAsJSON`
  attribute rather than the `component=acceptors,*` MBean search — it batches with
  everything else, where the search costs a second round trip and returns only the
  queried node's own acceptor.

## Alternatives considered

- **A generic JSON-diff library.** Solves the structural half, which was never the
  hard half, and actively adds index-based array noise that the `match` keying
  exists to avoid. It cannot know a message counter is not configuration.
- **A denylist of runtime counters.** Silently admits every attribute a future
  Artemis adds into the drift count.
- **An allowlist that filters rather than classifies.** Silently drops new
  configuration — the operator is never told something was not compared.
- **Suppressing expected differences entirely.** Cheaper to render, and it hides
  a real signal: a `Name` collision or an unexpected `HAPolicy` is worth seeing.
- **Comparing raw `broker.xml`.** Studio has no file access, and the effective
  configuration is what actually governs the broker's behaviour anyway.
