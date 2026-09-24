## Context

The decisions are recorded in ADR-0105 to ADR-0108. This file is the working design: how
the pieces fit, what was verified against the libraries, and the thresholds the UI uses.

Library facts come from mantine.dev/llms.txt (Mantine 9.6.1) and ctx7 (TanStack Virtual).

**Mantine**
- `Splitter` and `Splitter.Pane` exist, with the WAI-ARIA window-splitter keys, pane sizes
  in % or px, controlled `sizes`, `collapsible` panes and a `splitterRef`. A pane must be a
  direct child, and nothing is persisted.
- `Menu`:
  - Disabled items are skipped by the keyboard; only `[data-menu-item]:not([data-disabled])`
    is navigable.
  - `Menu.ContextMenu` documents no keyboard trigger and sets `user-select: none`.
  - Items are `role="menuitem"` and support type-ahead.
- `Spotlight.Root` takes a controlled `query` and `onQueryChange`. Actions support
  `disabled`, and `spotlight.open()` opens it.
- `useHotkeys` has no key sequences.

**TanStack Virtual**
- `rangeExtractor(range)` with `defaultRangeExtractor` keeps chosen indices rendered.
- `scrollToIndex(i, { align: 'auto' })`.
- `scrollPaddingStart` offsets a sticky header.

## Goals / Non-Goals

**Goals**
- One keyboard-complete path to every per-row verb, from any grid.
- Per-row verbs from every owning feature, with no new cross-feature imports.
- Navigation that keeps context: view across clusters, title, breadcrumb, recents, links,
  search.
- Seeing which broker node a queue's backlog and traffic are on, with trends, beside the
  flow graph.

**Non-Goals**
- Saved views (a separate roadmap item).
- A correct cluster gauge total.
- Per-client history.
- Multi-select context menus. Bulk stays in the selection bar.
- Context menus on non-virtual admin tables.

## Decisions

**D1 — Slots, not a new contribution field (ADR-0105).** Action and link slots reuse the
slot machinery: enabled filtering, order, plugin namespacing and guarding.
`SlotContribution` gains an optional `section` from `ACTION_SECTIONS`.

**D2 — One anchored menu per grid.**
- `ui/AnchoredMenu` renders a controlled `Menu` whose `Menu.Target` is a portalled,
  fixed-position, zero-size span.
  - For the pointer it is moved to the pointer's coordinates.
  - For the keyboard, and for the Actions button, it is moved to the bottom of the
    trigger's rect.
- Items render only while open, and the menu closes on grid scroll.
- `returnFocus` is off. The opener's element is re-focused on close unless a dialog took
  over.

**D3 — Blocked items.**
- `ui/ActionMenuItem` renders a `Menu.Item` with `aria-disabled="true"` and a dimmed style
  from tokens (never `disabled`). The short reason is shown under the label and linked by
  `aria-describedby`.
- Its click calls `onExplain`. The host shows `ui/CapabilityReason` (the same content as
  the `CapabilityGate` popover) in a small dialog.
- An `uncertain` verdict stays enabled and says "not yet proven on this connection".

**D4 — Action host (`kernel/actions/ActionHost`).**
- It is mounted in `ClusterLayout` and exposes `open`, `explain` and `announce` through
  context.
- `open(Component, props, { restoreFocus })` stores an entry `{ opened: false }` and flips
  it to `true` on the next frame. That is what lets `onEnterTransitionEnd` fire, which is
  where dialogs take their dry-run preview.
- The dialog's `onClose` flips it to `false`. After the exit transition (200 ms) the entry
  is removed and `restoreFocus()` runs, unless `location.href` changed.
- The dialog props passed are `{ opened, onClose }` plus the caller's props.

**D5 — Targets.**
- `QueueTarget { queueName, address?, routingType?, snapshot?: QueueView }`
- `AddressTarget { address }`
- `ClientTarget { nodeId, nodeName, id, snapshot? }` for connection, session, consumer and
  producer (with a `kind`)
- `MessageTarget { queueName, messageId, node?, snapshot? }`
- `DivertTarget { name, snapshot? }`

**D6 — Roving grid (ADR-0106).**
- `ui/useRovingGrid` is a pure model: `{ rowKey, col }` plus the key-to-move reducer, with
  unit tests.
- `VirtualTable` renders `tabIndex=0` only on the active cell, and on the first
  header/body cell when none is active.
- Arrow keys update the model, `scrollToIndex(i, { align: 'auto' })`, then focus the cell
  with `preventScroll`.
- A cell's focus target is its single `[data-grid-widget]` or `a, button, input`
  descendant, else the cell.
- The `rangeExtractor` merges the active index into `defaultRangeExtractor`.
- `scrollPaddingStart` is the measured header height.

**D7 — Copy.** Ctrl/Cmd+C with an empty selection on a focused cell copies `data-full`,
then calls `onAnnounce` if provided.

**D8 — Title and crumb (ADR-0107).**
- `kernel/shell/pageTitle.ts` is a tiny external store with `set(part, value)` for
  `cluster`, `view` and `resource`, and `useDocumentTitle` is applied in `RootLayout`.
- `useCurrentView()` returns the nav contribution whose path is the longest prefix of the
  path after `/clusters/<id>/`.

**D9 — Palette.**
- Moves to compound `Spotlight.Root` with a controlled query, debounced to 200 ms for the
  sources.
- Sources are mounted always, as today, but receive `query` and `opened`, and must not fetch
  while closed.
- `QueuePalette` calls `useQueues(clusterId, { q, size: 8 })` with
  `enabled: opened && q.length >= 2` and no refetch interval.

**D10 — Shortcuts.**
- `kernel/keyboard/useKeySequences` is one `keydown` listener on `document`, with a pending
  `g` that times out after 1.2 s.
- Letters come from `NavContribution.hotkey`: t topology, f flow, h consumer health,
  m metrics, l alerts, r requests, q queues, d DLQ, x transfers, s SQL, a addresses,
  c consumers, n connections, p producers, o routing, k configuration, e events, b bulk,
  u audit.
- Ignored:
  - editable targets;
  - any modifier except Shift for `?`;
  - `isComposing` and `defaultPrevented`;
  - `closest('[role=dialog],[role=menu],[role=listbox]')`.
- Non-Latin keys fall back to `event.code`: `KeyQ` becomes `q`, and `Slash` with Shift
  becomes `?`.
- The on/off flag is stored in `localStorage` `as:shortcuts` and defaults to on.

**D11 — Imbalance statements (flow).** All are computed over nodes that answered. A node
share that is unknown is stated as such and excluded from the percentages.
- **Concentration**: one node holds 75% or more of a backlog of at least 100 messages, in
  a cluster with two or more serving nodes. Stated as "N% of the backlog is on <node>".
- **Stranded**: a node has a backlog and 0 consumers while another node has consumers.
  Stated as "<node> holds X messages and has no consumer; the consumers are on <nodes>".
- **Skew**: a node's share of messages in exceeds its share of messages out by 40
  percentage points or more, at a total rate of at least 1 msg/s. Stated as "<node>
  receives N% of messages in but delivers M% of messages out".
- **All clear**: nothing above applies. Stated as "Balanced across N nodes", with no colour.

**D12 — Metrics split.**
- `splitBy=NODE` is allowed only with `subjectType=QUEUE`, returns at most 16 node entries,
  and widens the step so that nodes × buckets ≤ 2,000.
- Node names come from `ClusterDirectory`. An active node with no rows is
  `sampled: false` with empty series.
- The per-node SQL groups by `node_id` over the same rows as the total.

## Risks / Trade-offs

- **Roving focus changes every grid's keyboard behaviour.** Mitigations: unit-tested
  model, updated screen tests, and tabbable widgets preserved inside cells.
- **An anchored menu with a fake target could mis-position near viewport edges.**
  Mitigation: floating-ui flip and shift (Mantine defaults) and `position="bottom-start"`.
- **Per-node Flow payloads grow with node count.** They are opt-in, and only the Split
  layout asks for them.
