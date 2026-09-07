# ADR-0056: Every view is bounded, and the topology canvas degrades by level of detail

- **Status**: accepted
- **Date**: 2026-09-06
- **Deciders**: maintainers

## Context

Studio has a virtualised grid (ADR-0020) with paging, sorting, sort announcement,
node attribution and a reveal panel for clipped cells. Three views use it: queues,
resources and messages. `operator-ui` already requires new tabular views to use it.

The rest of the product does not, and the gap is not a disagreement — those views
predate the requirement:

- `events` and `audit` render a whole page of rows into a Mantine `<Table>`; events
  additionally merges a live buffer of up to 500 rows on top of it.
- `dlq` renders every address, every queue beneath it, and every per-node row of
  every queue, with no paging at any level.
- `rr/FlowsTable` renders whatever one page returned and offers no way to reach the
  next, so a busy cluster's flows are silently truncated at the page size.
- `topology` hands every node to React Flow at every zoom level.

None of this is visible on the two-node dev pair every contributor runs, and all of
it is visible on the clusters this product exists for. The failure is not a crash;
it is a console that becomes slow and then unusable at exactly the cluster size
where an operator most needs it, and — in the flows case — one that omits data
without saying so.

## Decision

**We will bound what every view renders**, so that the work a screen does is a
function of what the operator can see rather than of how large the cluster is. A
view listing rows either presents a bounded page with a way to reach the rest, or
renders only the rows in the viewport, or both.

**We will reuse `VirtualTable` rather than introduce a second table.** For `events`
and `audit` this is adoption of an existing requirement, not new design.

**We will make every bound visible.** An operator must never be unable to tell
whether they are looking at everything. A truncated flows list gets a pager; a
reduced-detail topology says it has been reduced.

**The topology canvas will degrade by level of detail**, because a graph cannot be
paged. Above a node threshold it collapses each live/backup pair into a single
node and drops edge labels; node internals render only above a zoom threshold; and
React Flow renders only visible elements.

## Consequences

- The DLQ view stops showing every per-node row at once. Per-node detail moves
  behind an expansion, which is one more interaction for the operator who wanted all
  of it — and the only version of the view that survives a cluster with hundreds of
  dead-lettered queues.
- Collapsing a live/backup pair hides real structure at high node counts. It is
  mitigated by stating that detail was reduced rather than reducing it silently; an
  operator who cannot tell what they are being shown is worse off than one looking
  at a slow graph.
- The node threshold and zoom threshold are constants chosen by judgement, not
  measurement. They are in one place and expected to move once someone runs this
  against a genuinely large cluster.
- `events` keeps its live buffer and its existing cap; virtualising the rows does
  not change how many are held, only how many are in the DOM.

## Alternatives considered

**Leave it and revisit when someone complains.** The complaint arrives during an
incident on the largest cluster in the estate, which is the worst possible moment
to discover it.

**Cap the response server-side and drop the rest.** Bounds the DOM and loses data
without telling anyone — the specific failure `rr/FlowsTable` already has.

**Virtualise the topology graph by viewport alone.** Necessary and not sufficient:
the layout pass itself is over every node, and a hundred nodes drawn at full detail
are illegible whether or not they are all in the DOM.

**A separate "large cluster" mode the operator opts into.** Two code paths, one of
which is exercised only by the users least able to tolerate a bug in it.
