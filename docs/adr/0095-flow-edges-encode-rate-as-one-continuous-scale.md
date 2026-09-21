# ADR-0095: Flow edges encode rate as one continuous scale for width, speed and density

- **Status**: accepted
- **Date**: 2026-09-21
- **Deciders**: maintainers

## Context

ADR-0080 mapped an edge's rate to motion through five logarithmic speed buckets, and to width
through four coarse tiers (unknown 1.25px, idle 1px, light 1.75px, busy 3px from 50 msg/s,
drawn in a brighter colour). The two channels came from different functions with different
breakpoints, so they could disagree: a 40 msg/s and a 5 msg/s edge had the same width but
different speeds. Every edge from 50 msg/s to 50,000 msg/s looked the same once motion was
paused or off, which is what reduced motion gives an operator. A 3px maximum was also too
thin for a weight to read at a glance. The rate label always carried the exact number, but
the picture did not rank edges on its own.

## Decision

We will derive every rate channel of an edge from one normalised value,
`s = sqrt(min(rate, 1000) / 1000)`, with `s = 0` when the rate is unknown or not positive
(`features/flow/edgeEncoding.ts`):

- **Width** is `2 + 8·s` px: a 2px floor and a 10px cap at 1000 msg/s.
- **Dot crossing time** is `6 − 4.2·s` seconds, rounded to 0.1 s so a refresh that barely
  moves the rate keeps the memoised dots and their running SMIL animation.
- **Dots wanted** are `1 + round(3·s)` for a positive rate and 0 otherwise. The 400-dot
  global budget and its busiest-first allocation from ADR-0080 are unchanged.

The scale is square-root, not linear or logarithmic: linear hides everything below about
100 msg/s, and a log scale compresses the busy end, where an operator most needs to see
the difference.

Width is the only weight channel. There is no separate "busy" colour: every healthy edge is
drawn in the same monochrome edge token, and colour still enters only for a fault. A small
line state (`unknown`, `idle`, `stale`, `flowing`) now sets only the dash pattern. Unknown
and idle both sit at the 2px floor and differ only by dash, and the legend says so.

The width reaches the SVG as a `--edge-width` custom property, and the CSS derives every
stroke from it. The inner stroke of a bridge or cluster-hop double line is 0.35 of the
outer one, so the double line keeps its proportions. The stroke width transitions over
400 ms, because rates refresh every 15 s. Reduced motion removes that transition, as it
does every other one.

Dots scale with the line, at radius `max(2.6, 0.4·width)`, and carry a surface-coloured
ring. The dot token on the edge token measures only 1.5:1 in the dark scheme. The ring puts
the dot at 9.4:1 against the surface in dark and 21:1 in light, so no new token is needed.
The edge itself stays at 6.1:1 (dark) and 5.1:1 (light) on the canvas, which is above the
3:1 floor for graphical marks.

The legend draws real reference lines with the same function, at 1, 100 and 1000+ msg/s,
and says in words that line width and dot speed both follow throughput on a square-root
scale capped at 1000 msg/s. The reference rates are 1 and 100, not 1 and 50, because 1 and
50 msg/s are only 1.5px apart, and reference weights must differ by at least 1.8px to be
told apart at a glance.

The rest of ADR-0080 stands: the ELK layout, the SMIL dots, the budget, pausing, and "never
the only carrier".

## Consequences

- Width and speed can no longer disagree, and the picture ranks edges even with motion off.
- Weight is monochrome, so it is colour-blind safe by construction. The exact rate is still
  in the label and the inspector.
- Every edge above 1000 msg/s looks the same. The cap is deliberate, so a single firehose
  cannot flatten every other edge to the floor, and the label carries the exact rate.
- A 10px edge crowds more than a 3px one. The ELK spacing is unchanged: React Flow draws
  each edge as a bezier between node handles rather than along ELK's routes, and the
  in-layer node gap (22px) already clears two 10px edges into adjacent nodes. Edges that
  leave one handle converge on it whatever the spacing. Revisit this if the operators find
  dense fan-outs unreadable.
- A continuous crossing time can restart an edge's dot animation whenever its rounded time
  changes, which is at most once per 15-second refresh.

## Alternatives considered

- **Keep the buckets and add more width tiers**: this still quantises, and two functions
  still have to be kept in step.
- **A logarithmic scale**: it spreads the low rates well, but it compresses 100–1000 msg/s,
  the range where an operator most needs to see a difference.
- **Colour or opacity for rate**: a healthy view stays near-monochrome (non-negotiable #6,
  ADR-0080), and colour is reserved for faults.
- **No cap**: one very busy edge would set the scale, and every other edge would sit on the
  floor.
