# Rule: how work happens here

## OpenSpec for every feature

No feature or behaviour change is implemented without an approved OpenSpec change.

1. `/opsx:propose` — create `openspec/changes/<name>/` with `proposal.md`,
   `tasks.md`, `design.md` (when it has design weight), and `specs/` deltas.
2. `/opsx:apply` — implement the checklist in `tasks.md`.
3. `/opsx:archive` — move the change to `openspec/changes/archive/` and merge its
   deltas into `openspec/specs/`.

`openspec/specs/` is the living source of truth. `openspec/changes/` is in-flight
work only — one change at a time. `/opsx:explore` is for no-stakes thinking first.

Bug fixes and pure refactors do not need a proposal. Anything that changes what
the product does, does.

## ADRs for every significant decision

`docs/adr/`, English, Nygard style (`docs/adr/000-template.md`). Adding a
technology, changing a pattern, or departing from a recorded convention needs a
new sequential ADR before or with the change. Never edit an accepted ADR's
decision — supersede it and mark the old one superseded with a link.

OpenSpec proposals reference the ADRs they depend on.

## Library facts via ctx7, never memory

Any question about a library, framework, SDK, or CLI — including ones you think
you know — goes through the `ctx7` CLI:

```
npx ctx7@latest library "<name>" "<what to look up>"
npx ctx7@latest docs "<id>" "<what to look up>"
```

Max 3 calls per question. One concept per `docs` call. Training data on versions
and API signatures is stale; this project has already been bitten by it
(Testcontainers 2.x artifact rename, Boot 4 per-module auto-configuration).
