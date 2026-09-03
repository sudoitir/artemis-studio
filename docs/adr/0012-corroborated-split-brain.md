# ADR-0012: Split-brain detection requires corroborated evidence

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0002](0002-broker-transport-and-capability-model.md) — the
  "two `true` in a pair raises a critical split-brain alert" decision bullet.

## Context

ADR-0002 and `CLAUDE.md` non-negotiable #4 say: *"Poll `Active` on every node;
two `true` in a pair raises a critical split-brain alert."* Phase 0 then measured
a real failover on the dev pair at **~0.6 s** from quorum vote to
`AMQ221007: Server is now active`, and confirmed `Started` — not `Active` — is
the health signal.

The Phase 1 refresh reads each node in its own HTTP call, a few seconds apart.
During any planned failover or failback there is a window where one node's
sample still says `Active=true` and the other node's later sample also says
`Active=true`, purely from **sampling skew** — the two were never live at the
same instant. The rule as written fires `CRITICAL` on routine maintenance. A
monitoring tool that cries wolf during planned failover gets its alerts muted,
and is then worse than not having the check.

## Decision

Split-brain is flagged only on **corroborated** evidence. The refresh loop
carries a monotonic cycle counter; every node row records the `observed_cycle`
of its last successful read.

- **`SUSPECTED`**: two endpoints sharing one `NodeID` both report `Active=true`,
  both stamped with the current cycle.
- **`CRITICAL`**: the `SUSPECTED` condition held on the current cycle **and** the
  immediately preceding one.
- Readings drawn from two different cycles never, by themselves, raise the flag.

A first sighting surfaces in the API and UI as *suspected* (amber, "checking"),
not *critical* (red, pageable). Worst-case real detection is ~2 cycles (~10 s at
the tier-A interval) — well inside human response time.

`ReplicaSync=false` on a `Backup=true` node remains a separate, lower-severity
"replication desynced" signal, unchanged.

## Consequences

- Planned failover and failback no longer produce a false `CRITICAL`.
- Detection of a genuine split-brain is delayed by one cycle. Accepted: a real
  split-brain persists until an operator acts, so it will still be true next
  cycle.
- The evaluator holds one cycle of cross-run state in memory. On a process
  restart mid-split-brain the status momentarily drops to `SUSPECTED` and
  re-escalates on the next cycle. Acceptable; Phase 2's scheduler-ownership model
  will revisit where this state lives.
- `broker_node` gains an `observed_cycle` column (changeset `008`).

## Alternatives considered

- **Fire `CRITICAL` on the first dual-`Active` sighting (ADR-0002 as written).**
  False-alarms on every planned failover. Rejected.
- **Single batched read of both nodes in one request.** Reduces but does not
  eliminate skew (the broker still samples each MBean at a slightly different
  moment), and it couples the split-brain check to a multi-node batch shape the
  per-node scrape model does not have. Rejected as insufficient on its own.
- **A time-window instead of a cycle counter** (e.g. "both within 500 ms").
  Equivalent in spirit but more fragile across clock skew and GC pauses; the
  cycle counter is exact and already needed for the refresh loop.
