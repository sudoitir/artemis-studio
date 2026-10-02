## Context

See proposal.md for why. The current state that shapes the approach:

- `web/src/theme.ts` holds one Mantine theme (a `pine` primary, Mantine's default spacing and type). `web/src/theme.css` holds the semantic `--as-*` colour layer, with dark as the base and light as the override. The named font (Inter) is never loaded.
- `web/src/ui/VirtualTable.tsx` is the only shared grid: CSS-grid rows (ADR-0020), roving focus (ADR-0108), row menu (ADR-0107), fit and resize (ADR-0116), exported by the SDK. About 40 other files render Mantine `<Table>` directly.
- The fit bug: `measure()` reads `cell.scrollWidth`, which is never smaller than the cell's current, stretched width. The first fit therefore locks every free column at its `1fr` share + 2 px, the grid's minimum inline size exceeds its container, and it scrolls sideways and never shrinks. jsdom tests mock `scrollWidth` and cannot see this.
- At 1280 px the content box beside the 264 px navigation is about 960 px wide.
- CSP (`SecurityConfig`): `script-src 'self' 'wasm-unsafe-eval'`, `font-src 'self' data:`. No inline scripts.
- The module boundaries (eslint-plugin-boundaries, ADR-0074): `ui/` may import only `ui/` and the API schema; `app/` may import anything.

## Goals / Non-Goals

**Goals:**
- One token scale that every component reads.
- One table whose columns fit at 1280–1920 px, verified in a real browser.
- One set of page parts every page is built from.
- AA contrast, visible focus, keyboard operation and reduced motion everywhere.
- No layout shift.

**Non-Goals:**
- Named or shared table views; the table state shape is ready for them.
- Phone and tablet layouts.
- Server-side changes beyond serving the new static file and the contract version.

## Decisions

### Tokens: three layers, one source per value
- `theme.ts` holds the primitives:
  - 10-step colour tuples (graphite, which also feeds Mantine's `gray` and `dark`; cobalt as primary; amber; signal);
  - the type scale, spacing, radius (xs–xl), shadows;
  - `other: { density, motion, z, layout }`.
- A `cssVariablesResolver`, passed to `MantineProvider`, emits the numbers that CSS and JS both need: row heights, cell padding, durations, z-indexes, navigation widths.
- `theme.css` keeps the semantic `--as-*` layer. It adds:
  - density (`:root[data-density]`), focus ring, easing, sticky shadow, skeleton;
  - the xyflow control and minimap variables;
  - a reduced-motion rule that zeroes every duration.
- Components read only `--as-*` and `--mantine-*` variables. There are no Mantine colour-name props, no raw colours and no px values apart from 1 px hairlines.
- `autoContrast` replaces the Button-only label-contrast hack.
- *Rejected:* moving the whole colour layer into TypeScript. That rewrites measured CSS for no product gain.

### Visual identity: hyperlegible data
- **Typefaces.** Atkinson Hyperlegible Next for UI text and identifiers, Atkinson Hyperlegible Mono for code and message bodies.
  - They separate 0/O, 1/l/I and rn/m, which matters for queue names and message IDs.
  - Numbers use the tabular figures of Next.
  - Identifiers stay in Next because Mono is 15–25% wider.
- **Shipping the fonts.**
  - They are installed as pinned npm packages (`@fontsource-variable/*`) and bundled by Vite into the jar, so they are served by Studio, never by a CDN.
  - The Latin files are preloaded.
  - The fallback faces carry metric overrides, so the font swap does not shift layout.
- **Colour.** Near-monochrome graphite surfaces, one cobalt accent, amber and signal red for state. Health is the quiet default, stated in words.
- *Rejected:* keeping Inter and pine. The user chose a new identity, and Inter was never loaded anyway.

### Colour scheme and density before first paint
- `web/public/boot-prefs.js` is a static, same-origin script, so it is CSP-safe. It runs before React and:
  - resolves the colour scheme from `mantine-color-scheme-value` (`light`, `dark` or `auto` only, resolved through `matchMedia`);
  - applies the density from `as:density`.
- `MantineProvider` uses `defaultColorScheme="auto"`.
- The density hook reads storage synchronously (`getInitialValueInEffect: false`). A density change calls `virtualizer.measure()`, because the virtualizer does not re-measure when `estimateSize` changes.
- A backend test asserts that the script is served as JavaScript and not as the SPA fallback.
- *Rejected:* Mantine's inline `ColorSchemeScript`, which the CSP blocks.

### One data table, two renderers
- **`DataTable`**, in `web/src/ui/table/`, takes one column model and renders it one of two ways:
  - **grid:** the default. Interactive, always virtualised, CSS-grid tracks, roving focus, row menu, selection, resize. It keeps ADR-0020, 0107 and 0108.
  - **static:** a native `<table>` for small read-only sets, whose cells hold natively focusable controls. Above 200 rows it switches to the grid automatically.
- `DescriptionList` (`<dl>`) replaces key/value tables.
- *Rejected:*
  - a native table for the interactive grid: rows taken out of flow for virtualisation break table layout;
  - subgrid: absolutely positioned rows do not take part in track sizing;
  - TanStack's column sizing: it races the DOM, as ADR-0116 found.

### Column model
- **`Column<T>` fields:** `id`, `header`, `accessor` (the plain value used for copy, reveal, measurement and title), an optional `cell` renderer, `kind`, `priority`, optional `min`/`max` in ch, `grow`, `wrap` (static only), `sortKey`, `description`.
- **`kind`** sets the bounds and the font:

  | Kind | Bounds | Font | Overflow |
  |---|---|---|---|
  | text | 12–48 ch | Next | end ellipsis |
  | identifier | 16–64 ch | Next | middle ellipsis, keeping a tail of about 12 ch |
  | code | 12–48 ch | Mono | end ellipsis |
  | number | content | Next, tabular | end-aligned |
  | time | content | Next, tabular | none |
  | status | content | Next | none |

- **`priority`** is `essential`, `high` or `low`. The first data column and node-attribution columns are essential.
- Call sites build their columns in a pure `columns.ts` factory, which the browser tests also import.

### Measurement
- **The Measurer** is an `inert`, `aria-hidden`, zero-size, clipped box. It holds a `max-content` grid of the header plus a sample: the first 40 rows, plus the 5 longest values of each text-like column.
  - Each cell uses its kind's font and ch bounds, so the measured width is already clamped in the right font.
  - Only the first row of the Measurer is read.
- **Triggers.** It runs in a layout effect, before paint, on:
  - the first non-empty data;
  - a change to the column set;
  - fonts finishing loading;
  - a density change;
  - an explicit refit.
- **Live tables.** New rows can only widen columns, at most every 2 s, and never while pointer or focus is inside the grid or it is scrolled away from the top.

### Solver
- `solveColumns({ W, cols, prev })` is a pure, unit-tested function that returns `{ template, hidden, overflow }`. It works in this order:
  1. Widths the operator set are hard tracks.
  2. If everything fits: grow columns get `minmax(base, 1fr)`, the rest get `base`.
  3. Otherwise, truncatable columns shrink proportionally toward their floors.
  4. Otherwise, `low` and then `high` columns are hidden, inline end first, with 16 px of hysteresis against `prev`. Essential columns, node-attribution columns and columns the operator chose to show are never hidden.
  5. Otherwise, the floors are used and the table overflows. The select column, the first data column and the actions column become sticky. Sticky cells layer the row tint over an opaque surface, and an IntersectionObserver sentinel shows the edge shadow.
- **When it runs.** The solver reruns from one rAF-throttled ResizeObserver, never on scroll. In jsdom (W = 0) it shows every column at base. The static renderer uses the same solver.

### Frame, semantics and state
- **Frame order:** toolbar, then the grid, then the state slot. Empty, error and loading content is a sibling of the grid, not a child.
- **Skeleton rows** are `aria-hidden` and are not counted in `aria-rowcount`.
- **The Columns control** always sits in the toolbar, with its space reserved. Its name carries the hidden count, and changes to the count are announced.
- **Focus and ARIA:**
  - the first data column is `rowheader`;
  - the active cell is keyed by column id, and moves to the nearest visible column when its own is hidden;
  - `aria-colcount` and `aria-colindex` count rendered columns only.
- **Sort** stays server-side through the URL. It is announced once the sorted rows land.
- **Clipped values** stay readable through the reveal panel (now anchored with logical properties), Ctrl+C and `title`.
- **Table state** is `{v:1, widths, hidden, shown, order}`, stored under `as.table.<storageKey>` and re-read when the key changes. Old keys are ignored.

### Page parts (all exported by the SDK)
| Part | What it is |
|---|---|
| `Page` | The page frame; a `fill` option gives the last child the remaining height. |
| `PageHeader` | The page's single h1. |
| `Section` | A titled block (h2/h3), plain or as a card. |
| `Toolbar` | The row of controls above a view. |
| `EmptyState` | A union over empty, filtered (needs `onClearFilters`) and unreachable (needs `nodes`). |
| `ErrorState` | Reads `ApiError` by its shape. Maps network/0, 401, 403 (naming the permission), 404, 409, 422 (listing the fields), 429 (giving retry-after), 5xx and broker error kinds to a cause and a next step. |
| `LoadingState` | The loading frame. |
| `StatusBadge` | A state always shown in words. |
| `Stat` | A figure; `null` renders "Unavailable", never 0. |
| `DescriptionList` | Term and value pairs. |
| `ConfirmDialog` | Confirmation, embedding `ConfirmByTyping` for destructive actions. |
| `notify` | One toast helper, announced through aria-live. |

- `ClusterHeader` becomes a context strip beside the page's h1.

### SDK and contract
- **Breaking SDK changes:**
  - `VirtualTable` and `GridColumn` are removed; `DataTable`, `Column`, `ColumnKind`, `RowMenu` and the page parts are added;
  - the `pine` colour is removed.
- **Contract version.** It goes up by one (`Contract.VERSION` and `CONTRACT`). Without the bump, an installed plugin that imports `VirtualTable` would pass validation and crash at render, because the SDK is a shared singleton. With it, the plugin is refused with the existing "built for contract N" message.
- **Plugin template.** It moves to the new parts.

### Layout floor and zoom
- The layout is designed for 1920, 1440 and 1280 px at 100% zoom. Responsive props and px-width media queries are removed.
- Pages use intrinsic layouts (wrapping flex, `auto-fit` grids, `minmax(0, 1fr)`), so they also hold at 200% zoom.
- The one zoom accommodation: the navigation collapses to its rail below `64rem`. That is a rem threshold, so it reacts to zoom, not to device class.
- Grids, canvases and the editor are the two-dimensional reflow exceptions.
- xyflow canvases get `colorMode` from the computed scheme.

### SQL console and Topology
Both get a design step before implementation, appended here. The fixed points:
- **SQL console:**
  - Mod+Enter runs and Mod+. cancels; Escape stays the editor's way out.
  - The cost verdict appears before running.
  - Errors are marked at their position.
  - Results use the grid with the column menu.
- **Topology:**
  - the HA role, pairing, liveness and version in words;
  - level of detail stated;
  - keyboard operation;
  - a table equivalent.

### Verification
- **Vitest projects:**
  - `unit` (jsdom) excludes `*.browser.test.*`.
  - `browser` (`@vitest/browser-playwright`, Chromium) has `css: true`, its own setup (Mantine styles, `theme.css`, fonts, `document.fonts.ready`) and no network.
  - Coverage merges both.
- **`src/app/tables.browser.test.tsx`** renders every grid's real columns at 960 and 1920 px with normal and long fixtures. It asserts:
  - the expected visible columns;
  - no overflow;
  - alignment;
  - sticky edges;
  - middle truncation;
  - keyboard resize steps;
  - axe in both schemes.
- **Screenshot sweep.** An isolated compose overlay (its own project name, Studio port and random broker ports), a seed script for long and large content and a Playwright sweep (`web/scripts/sweep.ts`) capture every route at 3 widths × light/dark/system, plus 200% zoom, in every state. Each capture checks:
  - axe (WCAG 2.2 AA, including target size);
  - page and table overflow;
  - CLS from navigation start;
  - CSP violations, console errors and requests to other hosts.

## SQL console and Topology redesigns

### SQL console

**Approach.** The console is one workspace on a full-height page:
- **Split.** Editor above results, divided by Mantine's `Splitter` (from `@mantine/hooks` `use-splitter`: a `separator` with `aria-valuenow`, arrow keys, Home/End). The split is remembered per browser under `as:sql:split`, the way Flow remembers its split.
- **Cost.** A cost verdict line sits under the editor: one sentence of words and numbers, worked out from the plan by a pure function.
- **Run and cancel.** Run and Cancel sit side by side; Mod+. cancels and Escape never does. Cancelling closes the stream, which already releases the run on the server. It leaves a new `cancelled` state, which is announced.
- **Errors.** A syntax error is a CodeMirror lint diagnostic on the offending token, which is found on the client from the problem's `offending` field. A run failure renders through `ErrorState`.
- **Results.** Results use `DataTable` alone. Its Columns menu gains keyboard move up and down. That replaces the console's own picker and its storage key.
- **Streaming.** Streamed rows are buffered and written once per animation frame.
- **History.** History is keyed on the run's nonce and records the SQL that ran.

Rejected:
- The hand-rolled `role=slider` split handle (a second splitter).
- A ceiling field on the plan (a server change; a refusal states the ceiling after a run).
- `startTransition` alone (still O(N²) copies).

**Cost verdict** (`costVerdict.ts`, pure). Its inputs are `{ text, debounced, blockedReason, plan, planError, planPending }` and it returns `{ badge, tone, sentence }`. An unknown figure is never shown as 0.

| Case | Badge · tone | Sentence |
|---|---|---|
| empty text | Unavailable · neutral | "Not estimated: the editor is empty." |
| gate blocked | Unavailable · neutral | "Not estimated: {reason}." |
| edited or pending | Estimating · neutral | "Estimating the edited query…" |
| syntax error | Unavailable · danger | "Not estimated: {detail}{ Did you mean X?}" |
| other plan error | Unavailable · warning | "Not estimated: {ErrorState title}." |
| no targets | No cost · neutral | "Reads nothing: no queue matches the FROM pattern." |
| index source | Index · neutral | "Reads Studio's index, up to {limit} rows from {q} queues. No broker is read." |
| requires scan | Scan · warning | "Studio reads and examines about {n} messages on {q} queues across {k} nodes." |
| otherwise | Broker-filtered · neutral | "The brokers filter; Studio examines at most {n} messages from {q} queues." |

The verdict is not a live region. It describes the editor and Run (`aria-describedby`).

**Run state** (`useSqlTail`). `RunStatus` gains `cancelled`. `cancel()` closes the stream: from `running` it sets `cancelled`, and from `tailing` it sets `done`. The hook exposes `runId` and `sql`.
- A row event pushes into a buffer that one `requestAnimationFrame` flush writes with a single `setRows`.
- `setFreshKeys` and its timer run once per batch.
- A paused tail's buffer stays bounded.
- `done` and `failed` flush before they set the status.

**Keyboard map:**

| Keys | Where | Does |
|---|---|---|
| Mod+Enter | editor | run |
| Mod+. | editor or page | cancel |
| Ctrl+Space | editor | complete |
| Escape | editor | closes completion, then collapses the selection, then moves focus to the Query toolbar; never cancels |
| F8, Mod+Shift+M | editor | diagnostics |
| ↑ ↓ (step 5, Shift 10), Home/End | split handle | resize |
| grid keys | results | per ADR-0108; Enter opens the message |
| Tab, Space, Enter | Columns menu | show/hide, move earlier/later |

**State ownership:**

| State | Owner |
|---|---|
| query text and live tail | URL (`?q=&live=`) |
| split | `localStorage` `as:sql:split` |
| columns | `as.table.sql.results` |
| history | `artemis-studio.sql.history` |
| everything else | local |

**Columns.** The order is Message ID (essential, row header), Queue, Node, Source, Enqueued, Body, Prio, Size, On broker. The old `artemis-studio.sql.columns` and `artemis-studio.sql.editorFraction` keys are removed.

**Columns menu reorder** (`ui/table`). Each entry gets "Move {header} earlier" and "Move {header} later" buttons, disabled at the bounds. The first declared column stays first. Focus stays on the pressed button, or moves to its sibling at a bound. The move is announced: "{header} moved to position i of n". `tableState` gains `withMovedColumn`.

**ErrorState.** A 422 with no field errors reads as the problem's own title and detail. A string `problem.hint` is the next step.

**IndexSubscriptions:**
- Two h3 sections hold a static `DataTable` of subscriptions (Queues, Mode, Held, Retention, State switch, Delete).
- Delete goes through `ConfirmDialog` with `tone="danger"` and `typedName` set to the pattern.
- Warnings and refusals are `Notice`s; failures are `ErrorState`.
- Loading uses `LoadingState`, and "Nothing is being indexed" is an `EmptyState`.
- Toasts go through `notify`.

**SyntaxHelp.** h3 sections. The column catalogue is a `DescriptionList`.

### Topology

**Approach:**
- **Words.** Every endpoint is described in words by one pure function, `nodeFacts.ts`. The box, the table and the side panel all read from it.
- **Keyboard.** The canvas is one tab stop with roving focus over boxes rendered as `<button>`. React Flow's own node focus is off.
- **View controls.** `DiagramView`'s view controls are extracted to `ui/graph/ViewControls.tsx` and replace xyflow's `<Controls>`.
- **URL.** Selection and view mode live in the URL (`?node=`, `?view=table`).
- **Table equivalent.** A `DataTable` with the same facts.

Rejected: rendering topology through `DiagramView`, because ELK cannot draw the pair axis or split brain.

**Facts per endpoint** (`nodeFacts`):
- **Role:** Primary, Backup, Standalone or Unknown.
- **Liveness:** Live, serving (or "split brain" in danger); Backup, replicating, in sync; Backup, not caught up; Standby; Stopped; Unreachable (with the error); Not polled: no management URL.
- **Pair:** "paired with …", or "no pair (standalone)". The pair state is in words.
- **Version:** the version, with its support note.
- **Last seen:** relative and absolute.
- **Sentence:** the box's accessible name.

The box shows four lines: name and version, the shape mark and liveness, the role and pair, and the address or the error. An unmanaged box no longer holds a nested button; that action moves to the panel.

**Level of detail** (ADR-0056). Up to 24 logical nodes, every detail is drawn. Above 24, each pair is one box in a grid 8 columns wide. A neutral `Notice` states the bound, with a [Show as table] action.

**Page:**
- `PageHeader` "Topology" with a Show as [Graph | Table] control.
- `LoadingState` while loading, `ErrorState` with retry on failure.
- An `EmptyState` when no node has answered.
- The canvas and a `NodePanel` aside: a `Section` with a `DescriptionList` (Role, Liveness, Pair, Version, Node ID, Management URL, Core URL, Last seen, Last error, How found) and an `OutcomeSummary` of the pair, plus [Add a management URL] for an unmanaged node.
- The table view is a `DataTable` grid with the columns Node (essential, row header), Role, Pair, Liveness, Version, Address, Last seen and Node ID.

**Keyboard:**

| Keys | Where | Does |
|---|---|---|
| Tab | into the canvas | lands on the selected box, else the first |
| ←/→ | canvas | moves between columns |
| ↑/↓ | canvas | moves within a column |
| Home/End | canvas | first or last box |
| Enter/Space | box | selects it and announces it |
| Escape | canvas | clears the selection and returns focus to the box |

The focused box is kept in view with `setCenter` at duration 0.

**Theme.** `colorMode` comes from the computed scheme. Fonts, spacing and colour come from tokens; px stays only in canvas geometry. Durations come from tokens, so reduced motion applies. Previews (`interactive={false}`) stay plain `<div>`s.

### Tasks

- **Unit A, SQL** (it owns `features/sql/**`, `ui/table/{ColumnsMenu,tableState,DataTable}`, `ui/ErrorState`, the sql entry in the test views, the SQL guide and the SQL section of the keyboard guide):
  - A1 Columns reorder
  - A2 ErrorState
  - A3 useSqlTail
  - A4 costVerdict
  - A5 QueryEditor
  - A6 SqlConsoleView
  - A7 the meta bar, live-tail banner and syntax help
  - A8 IndexSubscriptions
  - A9 tests, guides and QA log
- **Unit B, Topology** (it owns `features/clusters/{nodeFacts*,layout*,Topology*,NodePanel*,TopologyTable*,topologyColumns*,feature.ts}`, `ui/graph/ViewControls.tsx`, the `DiagramView` import, the topology entry in the test views, the Topology section of the keyboard guide and the high-availability guide):
  - B1 nodeFacts and layout
  - B2 ViewControls
  - B3 TopologyCanvas
  - B4 NodePanel, TopologyTable and the page
  - B5 tests, guides and QA log

## Risks / Trade-offs

- [The new identity changes every screenshot] → the README and site images are regenerated in the last pull request.
- [Plugins built for the old SDK stop loading] → the contract bump makes them fail with a clear message; the commit carries the migration.
- [Measuring a sample can miss a longer value further down] → the 5 longest values per column are included; a double-click refits; clipped values stay readable.
- [Hiding columns hides information] → the hidden count is always shown and announced, any column can be shown again and stays shown, and identity and node columns are never hidden.
- [Browser tests add CI time] → one Chromium project; the frontend job timeout goes from 20 to 30 minutes.
- [Stored widths and SQL column choices reset once] → stated in the breaking-change note.

## Migration Plan

No data migration. Old browser-storage keys are ignored. A plugin rebuilt against the new SDK uses `DataTable` per the migration note in the SDK commit. Rollback is a revert of the pull request.
