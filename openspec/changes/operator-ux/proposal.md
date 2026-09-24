## Why

Roadmap B names "Operator UX: row context menus, navigation enhancements, and flow-split
monitoring views". Behind the line are three things an on-call operator runs into.

**Acting on one row needs a pointer and a detour.**
- Grid rows cannot be focused or activated from the keyboard.
- A queue's verbs are reached through its drawer.
- The close actions on the resource grids mount their dialog inside a virtualized cell.
  When the close succeeds, the refetch removes the row and the result disappears with it.
- No feature can offer a verb on another feature's row. The queue row cannot say "Browse
  messages", "Purge…", "Transfer…" or "Show in Flow".

**Navigation stops at the sidebar and ⌘K.**
- Switching cluster drops the view.
- The palette never sees what is typed, lists views without checking permission, and
  filters instead of opening a queue.
- No tab says what it holds.
- Resources do not link to each other.
- Flow's "Open its connections" searches a field that never matches.
- There is no shortcut help.

**Flow cannot say which node a problem is on.** Every figure is summed across broker nodes,
though every sample is stored per node. The graph, its table and any trend are three
separate places.

## What Changes

- **Row action menus.**
  - Every resource grid (queues, addresses, consumers, sessions, connections, producers,
    diverts, messages, consumer health) offers one menu per row. It opens by right-click, by
    a visible Actions button, or by Shift+F10 or the ContextMenu key.
  - Items are grouped as Open, Copy, Operate and Destroy.
  - Items come from whichever enabled feature owns them: queues, messages, bulk, transfer,
    flow, metrics, resources and routing.
  - A blocked item stays in the menu, reachable, with its reason and the `broker.xml` that
    enables it.
  - Destructive items open the existing previewed, typed confirmation. The outcome outlives
    the row.
- **The data grid is one tab stop** with arrow-key navigation. Enter opens a row, Space
  selects it, and a clipped value can be copied from the keyboard.
- **Cross-links.**
  - Queue, address, session and connection names in other views are links to the exact
    resource.
  - A link to a queue that is not on the loaded page still opens it.
  - The resource filters match the identifiers other views link with.
- **Navigation aids.**
  - Switching cluster keeps the view.
  - Every page has a title and a breadcrumb.
  - The palette has a Search button. It searches queues as you type, offers searches of
    live resources without touching brokers, lists recents, and shows views the operator
    cannot open as disabled with the reason.
- **Keyboard shortcuts.**
  - `g` then a letter goes to a view; `?` lists shortcuts; `/` focuses the filter.
  - A personal setting turns single-key shortcuts off.
- **Flow Split.**
  - A third layout puts the graph beside a resizable monitoring pane that follows the
    selection. The selection is now in the address.
  - The pane breaks the selection's backlog, rates and consumers down per broker node, and
    states any imbalance in words.
  - For a queue, it adds per-node trends contributed by Metrics.
- **Metrics** can split a queue's series by node. The Metrics view offers the split.

## Capabilities

### New Capabilities
- None. Each change extends a capability that already owns the surface.

### Modified Capabilities
- `operator-ui`:
  - the row action menu, the one-tab-stop grid, cross-links, the title and breadcrumb, the
    cluster switch keeping the view, the palette's search and permissions, and single-key
    shortcuts;
  - a one-queue bulk run is confirmed by the queue's name.
- `flow-visualization`: the Split layout, the per-node breakdown and imbalance statements,
  exact-resource links and navigate-only menus, keyboard activation of the table, and the
  selection in the address.
- `metrics`: a queue's series broken down per node.
- `cross-node-resource-views`: filter fields that match what other views link with, and a
  row's close outcome that survives the row.
- `plugin-runtime`: action-slot entries are namespaced and sectioned, and link slots are
  built-in only.

## Impact

- **Backend**
  - `platform/scrape/MetricSamples` gains per-node reads.
  - `feature/metrics`: `splitBy=NODE`, and a dependency on `platform.clusters`.
  - `feature/flow`: opt-in `byNode`, with new nullable DTO lists.
  - `feature/resources`: wider filter fields.
  - The OpenAPI snapshot and `schema.d.ts` are regenerated.
- **Kernel (web)**
  - Action and link slots, the action host, and the resource-actions renderer.
  - The title/crumb store and breadcrumb, the current-view hook, recents, and the keyboard
    engine and help.
  - Palette `query`/`opened`, `NavContribution.hotkey`, `SlotContribution.section`, and the
    `flow.selection.panels` slot.
  - Plugin validation.
- **UI (web)**
  - `VirtualTable` gains roving focus, a `label`, and `rowMenu`.
  - `AnchoredMenu`, `ActionMenuItem` and `CapabilityReason` are new.
- **Features**: queues, messages, bulk, transfer, flow, metrics, triage, resources, routing,
  clusters and settings.
- **ADRs**: 0105 (row actions), 0106 (roving grid), 0107 (navigation and shortcuts) and
  0108 (per-node series and flow breakdown).
- **Not in this change**:
  - a correct cluster-scope gauge total (ADR-0108 D4, filed);
  - fine-step rate under-count (filed);
  - `?event=` deep links for audit and events, which have no single-event read (filed);
  - per-client history;
  - MCP tools for the per-node reads.
