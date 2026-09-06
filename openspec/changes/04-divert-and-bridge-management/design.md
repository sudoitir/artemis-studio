## Context

See `proposal.md` — Why. Only the shaping constraints are recorded here.

What exists and is reused:

```
BrokerListOps.fetch(client, op, options, page, size)   the one list path behind
                                                       all six resource views
ResourceView (web/src/resources/)                      the one screen behind them
ConfigDiffController / broker-config-diff              effective config per node
queue-lifecycle (change 01)                            fan-out + per-node outcome
```

Constraints that follow:

- **`BrokerListOps` expects a broker operation returning `{data, count}`.** Whether
  the Artemis divert and bridge listing operations have that shape decides whether
  this is a configuration read or a new list path. Confirmed at apply time, not
  assumed.
- **Config-diff already reads effective configuration per node.** The
  runtime-versus-configured comparison should read configuration through that, not
  through a second reader with its own idea of what "effective" means.
- **A divert is per-node.** Same fan-out problem as a queue, same answer.

## Goals / Non-Goals

**Goals**

- Make the middle of the routing path visible, at the same quality as its ends.
- Make a runtime-only divert impossible to mistake for a configured one.
- Reuse the list, screen and fan-out machinery rather than growing a parallel set.

**Non-Goals**

- No bridge or cluster-connection mutation. See D2.
- No `broker.xml` writing. Studio shows the snippet; a human or a configuration
  system applies it.
- No divert update. See D4.

## Decisions

### D1 — Runtime-only is a first-class state, not a footnote

A divert has, from Studio's point of view, three states:

- **configured and running** — present in the effective configuration and in the
  running broker. Normal.
- **runtime-only** — running, absent from configuration. Disappears on restart.
- **configured but not running** — in configuration, absent from the broker.
  Usually means the configuration was changed and not reloaded, or the broker
  failed to apply it.

All three appear in the list with a distinct marker. The third is arguably the more
valuable finding and comes free from the same comparison.

Studio does not need to have created a divert to classify it. The comparison is
between the running broker and its own effective configuration, so a divert created
by anyone through any tool is classified correctly.

### D2 — Bridges are read-only, permanently

Rejected: create/delete for bridges, for symmetry with diverts.

A bridge is part of how a cluster is wired to another broker. Creating one at
runtime from a console makes a topology change that is invisible to whatever
manages the cluster's configuration, cannot be reviewed, and is undone by a
restart. Symmetry is not a reason to ship an operation whose safe use case is
empty.

Read-only bridges still pay for themselves: "is the bridge running, and what is its
state" is a question operators ask constantly and currently cannot answer here.

### D3 — Creation states the persistence consequence before it happens

The create form states, before the action arms, that the divert will not survive a
restart, and shows the `broker.xml` snippet that would make it permanent. The
snippet is generated from the same values the operator entered, so it is
copy-pasteable rather than a template to fill in again.

This is the mechanism non-negotiable #5 already defines, pointed at a different
question. Getting the wording right matters more than the code: "temporary" is not
sufficient, because it implies Studio will clean it up. "Will be lost when this
broker restarts" is.

### D4 — No divert update

Artemis's management surface for changing an existing divert is limited and varies
by version. Rather than expose a partial update whose accepted field set is a
version-dependent surprise, a change is delete-then-create — two explicit steps,
each confirmed, each audited, with no illusion of atomicity.

If the Artemis API turns out to support a clean update, this decision is worth
revisiting; it is recorded as a question in `tasks.md` rather than settled from
memory.

### D5 — Fan-out and per-node outcome are borrowed, not reinvented

Create and delete apply to every live node and report per node, exactly as change
01 defines. If 01 has landed, this change uses its outcome type directly. If it has
not, this change defines the same shape and 01 adopts it — but the two must not end
up with two different per-node result vocabularies.

## Risks

- **The honesty wording fails.** The main risk in the change. Mitigated by making
  the runtime-only marker a state in the list rather than a one-time warning at
  creation, so it stays visible long after the operator who created it has left.
- **Comparison false positives.** A divert may be legitimately absent from the
  effective configuration a node reports depending on how it was defined. The
  comparison must be built on the same effective-configuration source config-diff
  uses, and disagreements are a bug in the comparison, not a finding to show.
- **Scope creep into configuration management.** Studio shows a snippet and stops.
  Anything further is change 05's territory or a different product.

## Open for refinement

The open question from `proposal.md` — whether create and delete belong here at all
— is a live design question, not a formality. D4 is also likely to move once the
Artemis API is checked. Revise this file with `tasks.md` and the spec deltas.
