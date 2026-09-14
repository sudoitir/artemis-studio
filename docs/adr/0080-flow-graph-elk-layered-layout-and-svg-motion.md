# ADR-0080: Flow graph uses ELK layered layout and budgeted SVG motion

- **Status**: accepted
- **Date**: 2026-09-14
- **Deciders**: maintainers

## Context

The Flow screen (`openspec/changes/flow-visualization`) draws up to 200 nodes in fixed
columns (producers, addresses, queues, consumers, remote) with edges that cross, fan out and
occasionally loop through diverts. The topology graph's hand-written layout
(`features/clusters/layout.ts`) places a known, small structure. It has no crossing
minimisation and does not scale to this. The screen must also show live flow as motion
without letting motion become the only carrier of meaning, without restarting animations on
every 15-second refresh, and with a cost bounded independent of cluster size (ADR-0056).

## Decision

We will add `elkjs` (exact version pinned) and lay out the flow graph with ELK's `layered`
algorithm: direction right, one layer per column via `layerChoiceConstraint`, model order
seeded from the previous layout, run in a web worker, re-run only when the set of drawn
nodes changes.

We will animate flow with SVG `<animateMotion>` dots inside a custom React Flow edge: rate
maps to one of five logarithmic speed buckets and one to four dots; the edge is memoised on
its bucket; a global budget caps dots at 400, removed from the lowest-rate edges first.
Motion is paused through `SVGSVGElement.pauseAnimations()` on Pause, when the view is hidden
or off-screen, and is not rendered at all under reduced motion. Every animated edge also
carries its rate as width and text.

## Consequences

- One new frontend dependency, isolated to `features/flow`, loaded only with that route.
- Rate-only refreshes never move nodes, so operators keep their mental map.
- Layout is asynchronous; the view must hold a placeholder while the worker runs.
- SMIL is a mature, widely supported browser feature but not a React idiom. The memoisation on bucket is what
  keeps it from restarting, and a test must guard it.
- A future graph elsewhere (routing builder, lineage) can reuse the same layout worker; it
  is not generalised now.

## Alternatives considered

- **Hand layout** (as topology): no crossing minimisation, which breaks down beyond a few dozen nodes.
- **`@dagrejs/dagre`**: smaller, but no per-node layer constraints and weaker ordering control.
- **d3-sankey**: the natural "flow" chart, but it cannot represent cycles and brings no
  interaction model; React Flow is already in the stack.
- **CSS dash animation (`animated` edges)**: cheaper, but reads as selection rather than flow and
  collides with the dashed divert style.
- **Canvas particle overlay**: best raw throughput, but a second rendering system with no
  per-edge accessibility.
