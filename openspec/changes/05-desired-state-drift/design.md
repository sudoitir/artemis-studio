## Context

See `proposal.md` — Why, including the dependency on change 01.

What exists and is reused:

```
queue-lifecycle (change 01)     fan-out, per-node outcome, dry-run, cap, audit
broker-config-diff              effective configuration per node
queue_snapshot                  broker-derived cache, disposable by design
alerting                        rule → debounce → channel delivery
studio-settings registry        per-key group/label/hint/default
```

Constraints that follow:

- **`queue_snapshot` is a disposable cache.** The declaration is not, and the two
  must live in different tables with different lifecycles. Deleting the cache to
  force a rescrape must never touch a declaration.
- **Change 01 owns every mutation.** This change contributes no broker write path
  of its own. If it needs one, that is a signal the boundary is wrong.
- **The comparison must read the same effective configuration `broker-config-diff`
  reads**, or the two features will report different truths about one node.
- **Non-negotiable #7 governs the new table's column order** and its storage
  parameters if it turns out to be high-churn. The declaration is low-churn; the
  drift result may not be.

## Goals / Non-Goals

**Goals**

- Detect the uniform-but-wrong cluster that node-to-node comparison cannot see.
- Contribute zero new safety mechanisms — every fix goes through change 01's.
- Make the declaration cheap to start from, so it is actually adopted.

**Non-Goals**

- No reconciliation loop, ever. See D1.
- No declaration of diverts, address settings, or security settings. Later, if the
  shape earns it.
- No replacement for `broker.xml` or for whatever manages it. See D5.
- No new notification path. Alerting already has one.

## Decisions

### D1 — Advisory only: the system never reconciles on its own

Rejected: a scheduled reconciler that applies the declaration.

The argument for it is real — drift that nobody looks at is drift that is not
fixed. It is rejected anyway, on three grounds:

- **The blast radius is unbounded and automatic.** A declaration edited wrongly, or
  a topology read that transiently reports a node as missing a queue, becomes a
  create or destroy on production with no human in the path.
- **Every safety mechanism in this product assumes a human.** Dry-run exists to be
  read. The bulk cap exists to be overridden deliberately. Typed confirmation
  exists to slow someone down. A reconciler runs past all three by construction.
- **It changes what the product is.** An observability and management tool that a
  team trusts because it does nothing they did not ask for becomes a controller
  that acts on its own. That is a different trust relationship and it is not the
  one this codebase has been earning.

Drift is reported, alerted on, and fixed by an operator who clicks the fix and
confirms it. The fix routes through change 01, so it is dry-run-able, capped,
confirmed, and audited exactly like a manual create.

This decision is the reason the change exists in this shape; if it is reversed, the
whole design should be rebuilt around the safety a reconciler would need, not
patched.

### D2 — Three drift kinds, and "unexpected" is opt-in

`missing` and `divergent` are always reported. `unexpected` is a per-cluster
setting, off by default, with exclusion patterns.

Auto-created queues are normal on many deployments. A report that lists four
hundred unexpected queues is not a report anyone reads, and a feature nobody reads
is worse than absent because it looks like coverage.

### D3 — The declaration is generated from reality, then edited

The first declaration for a cluster is an import of its current state. Nobody
hand-writes a hundred entries, and a feature that requires them to will not be
adopted.

The import is deliberately a snapshot-and-edit, not a subscription: importing again
later shows what changed since, which is itself the drift report from a different
angle.

### D4 — Fixing drift is change 01's operation, wholly

A fix action composes the corresponding lifecycle command and hands it over. It
does not have its own broker call, its own audit action type, its own dry-run, or
its own cap.

The audit trail therefore shows a queue create, with the drift report as its
context — not a separate "drift applied" event that would have to be correlated
with the create it caused.

### D5 — The declaration is intent, not truth

`broker.xml` and whatever manages it remain authoritative for what a broker is
configured to do. The declaration is Studio's record of what an operator expects,
and drift means "these two disagree", not "the broker is wrong".

Stated explicitly in the spec because the alternative reading — that editing the
declaration is how you change a cluster — is an easy and expensive mistake to make.

### D6 — Drift evaluation is scheduled, drift action is not

The comparison itself runs on a schedule, through the existing settings-driven
dynamic schedule mechanism, so its cadence is an operator setting like every other.
Only the comparison is scheduled. Nothing that follows from it is.

## Risks

- **The reconciler gets added later "just for missing queues".** The most likely way
  this design fails. D1 is written to be the thing someone has to argue against
  explicitly, in an ADR, rather than something they can slip past in a task.
- **Declaration rot.** A declaration nobody updates produces drift reports that are
  all false and get ignored. Mitigated by re-import showing changes, and by the
  report distinguishing a declaration that has not been touched in a long time.
- **Duplicate truth with config-diff.** Mitigated by sharing the
  effective-configuration reader (Context) and by linking the two screens rather
  than reimplementing either.
- **Divergent is only as good as change 01's update surface.** If change 01 ends up
  able to update very little, `divergent` findings will mostly have no fix action.
  Flagged as an open question rather than designed around prematurely.

## Open for refinement

Per-cluster versus per-environment declarations, file import/export for version
control review, and whether `divergent` ships at all in the first version are open.
Revise this file, `tasks.md` and the spec deltas together.
