# QA log: sql-topology

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### sql-topology-1 [S3 · performance · page] Every streamed SQL row copies the whole row array and triggers its own render

- Where: `web/src/features/sql/useSqlTail.ts:206`
- Evidence: Each SSE 'row' event calls setRows((prev)=> [...prev, row]) (or [row, ...prev].slice). A non-tail result of N rows does O(N^2) copying and N separate state updates, so the grid re-renders once per row. While tailing, each row also starts a setTimeout and copies a new Set in setFreshKeys.
- Fix: Buffer incoming rows in a ref and flush them once per animation frame (or a short interval) with a single setRows that appends the batch. Do the same for freshKeys.
- Status: fixed (`useSqlTail` buffers rows and writes them once per animation frame, marks each batch fresh with one timer, keeps a paused tail's buffer bounded, and flushes before `done`, `failed`, a dropped stream or a cancel set the status. `useSqlTail.test.ts` asserts one render for three rows, the flush before each status, newest-first order, and the 2,000-row bound on a paused buffer)

### sql-topology-2 [S4 · reliability · page] Query history skips a repeated run when the text, source and row count are the same

- Where: `web/src/features/sql/SqlConsoleView.tsx:409`
- Evidence: The dedupe key is `${text}@${source}@${rows.length}`, held in recorded.current. Run Q (10 rows), then run Q again with different rows but still 10 rows: the second finished run is never recorded and its entry is not refreshed to the top.
- Fix: Key the dedupe on the run's request nonce from useSqlTail instead of the result's shape.
- Status: fixed (`useQueryHistory` keys on `runId`, which counts runs and survives a reconnect and a cancel, and records `run.sql`, the SQL that ran, not what the editor holds by then; `records the SQL that ran in the history, once, even when the editor changed while it read` fails without it)


## Sweep findings and the redesign

### sql-topology-3 [S2 · a11y · page] axe `scrollable-region-focusable` on the editor pane, in every state

- Where: `web/src/features/sql/SqlConsoleView.module.css` (`.editorPane`), found by the gate A sweep (`sweep-gate-a.md`, `sql` at 1280 dark)
- Evidence: the editor pane scrolled (`overflow-y: auto`) with no keyboard way in and no name. The same rule then failed on the editor's own `.cm-scroller` in the rebuilt console, because axe does not count a `contenteditable` as focusable content.
- Fix: a scroll area is either keyboard-reachable and named, or not a separate scroller. The split's panes no longer scroll (`overflow: hidden`). Each holds one `section` with `tabindex="0"` and an accessible name ("Query and cost", "Results"), and the editor content carries an explicit `tabindex="0"`.
- Status: fixed (`sql.browser.test.tsx` runs axe on the idle, scan, syntax-error, blocked, running, done, no-queue, unanswered-node, tailing, refused, disconnected and cancelled states, in both schemes, and asserts both regions are focusable and scroll)

### sql-topology-4 [S3 · reliability · page] The console keeps two private storage keys beside the table's own

- Where: `web/src/features/sql/SqlConsoleView.tsx` (`artemis-studio.sql.columns`, `artemis-studio.sql.editorFraction`)
- Evidence: the console's own Columns picker kept its order and visibility under one key, and the table's Columns menu kept them under `as.table.sql.results`, so one choice had two owners. The split height had a third key of its own.
- Fix: the picker and both keys are removed. The table's Columns menu gains **Move earlier** and **Move later**, and the split is remembered under `as:sql:split`, as Flow's is. Nothing reads or migrates the old keys.
- Status: fixed (`neither reads nor writes the console's old storage keys`)

### sql-topology-5 [S3 · a11y · part] The table's Columns menu has two 16 px radios with no room around them

- Where: `web/src/ui/table/ColumnsMenu.tsx` (the Row density radios)
- Evidence: axe `target-size` (WCAG 2.5.8) on the open menu, found while adding the move buttons: 16 px targets with 16 px of free space around them.
- Fix: the radios are 24 px, which meets the rule outright. The move buttons are 26 px.
- Status: fixed (`sql.browser.test.tsx`, "the table's Columns menu", runs axe on the open menu in both schemes)

### SQL console redesign

- A1: the table's Columns menu has **Move {header} earlier** and **later**, disabled at the bounds. The first declared column stays first. Focus stays on the pressed button, or moves to its sibling at a bound, and the move is announced ("Source moved to position 3 of 9"). `withMovedColumn` in `tableState`.
- A2: `ErrorState` reads a 422 with no field errors as the problem's own title and detail, and a string `problem.hint` is the next step. The reading moved to `errorReading.tsx` so the cost line can use its title.
- A3, A4: `useSqlTail` gains `cancelled`, `runId`, `sql` and `cancel`; `costVerdict.ts` is pure and has a test for every row of the verdict table.
- A5, A6, A7: the query editor marks the offending token as a lint diagnostic (F8, Mod+Shift+M), Mod+. cancels and Escape never does; the workspace is a vertical `Splitter` under `as:sql:split`; the cost line describes the editor and Run through `aria-describedby`; the meta bar, live-tail banner and syntax help use `Notice`, `StatusBadge`, `Section` and `DescriptionList`.
- A8: `IndexSubscriptions` is two h3 sections, a static `DataTable` of subscriptions (Queues, Mode, Held, Retention, State, Delete), a `ConfirmDialog` with `typedName` for Delete, `Notice`s, `ErrorState`, `LoadingState`, `EmptyState` and `notify`.

## Topology redesign

Found while building the redesign, and fixed in it. Each has a test that fails without the fix.

### sql-topology-t1 [S2 · reliability · page] The Topology error state never settles

- Where: `web/src/features/clusters/TopologyView.tsx` (the sweep's `topology` error captures, `sweep-gate-b.md`)
- Evidence: a failing topology query stays `pending` through TanStack Query's three retries with backoff (about seven seconds for a server error), and the page drew a `Skeleton` for all of it, so the capture timed out with something visible still busy. The page never showed the cause until the retries were spent, and showed nothing at all when only the health query failed.
- Fix: the page reads a query that has failed at least once and has no data as failed (`failureCount > 0`), and draws `ErrorState` with Retry at once. Both queries are read, so a failing health query is no longer a blank page. A query with data keeps showing it through a failed refresh.
- Status: fixed (`TopologyView.test.tsx`, "settles on its error while the query is still retrying, with nothing left busy", fails without the `failureCount` branch; `Topology.browser.test.tsx`, "failed", in both schemes with axe)

### sql-topology-t2 [S2 · correctness · page] The graph left a node out of a pair

- Where: `web/src/features/clusters/layout.ts`, `pairChildren`
- Evidence: a pair was drawn as at most two boxes, the first serving endpoint above the first other one. A pair whose primary was unreachable and whose backup had not been promoted (nothing serving) drew one of its two nodes, and a suspected split brain (two nodes reporting active) drew one of the two. A node the graph does not draw is a node the operator does not know to look at.
- Fix: every endpoint is drawn. The serving nodes stand side by side above the axis and the others side by side below it, the group widens to hold them, and each other node has its replication line (a split brain has none).
- Status: fixed (`layout.test.ts`, "every endpoint is drawn")

### sql-topology-t3 [S2 · a11y · page] The Topology boxes could not be reached from the keyboard

- Where: `web/src/features/clusters/TopologyCanvas.tsx`
- Evidence: the boxes were `div`s inside React Flow's node wrappers with its own node focus on, so every box was a tab stop (a large cluster meant dozens), an unmanaged box held a nested button inside the focusable wrapper, and Enter or Space chose nothing. There was no selection at all, so a screen reader could not be told which node was meant.
- Fix: the boxes are `<button>`s in one tab stop with roving focus (arrows across and within columns, Home and End, Enter and Space to choose and announce, Escape to clear), named by one sentence from `nodeFacts`. React Flow's own node focus and keyboard instructions are off. The focused box is kept in view with `setCenter` at duration 0. "Add a management URL" moved to the panel.
- Status: fixed (`TopologyCanvas.test.tsx`; `Topology.browser.test.tsx`, "keyboard, in a real browser")

### sql-topology-t4 [S3 · consistency · page] The canvas drew React Flow's own controls and its own px, durations and fonts

- Where: `web/src/features/clusters/TopologyGraph.module.css`, `TopologyCanvas.tsx`
- Evidence: xyflow's `<Controls>` brings its own colours and focus ring; the style sheet set `font-size: 10px` and `11px`, spacing in px, and a global `.react-flow__node` transition of 240 ms that also applied to the Flow and Diagram graphs and ignored the duration tokens; `colorMode` was not set.
- Fix: `ui/graph/ViewControls` (extracted from `DiagramView`) replaces `<Controls>`; fonts, spacing, colour and durations come from the tokens; the transition is scoped to the topology canvas and uses `--as-duration-base`; `colorMode` follows the computed scheme. Only the box size stays in px, set once from `layout.ts`.
- Status: fixed (`ViewControls.test.tsx`; `TopologyCanvas.test.tsx`, "hands React Flow the scheme Mantine resolved")

### sql-topology-t5 [S4 · test harness · not a product defect] The shared browser setup does not load React Flow's style sheet

- Where: `web/src/test/browser-setup.ts`
- Evidence: the browser tests load the console's own style sheets but not `@xyflow/react/dist/style.css`, which `main.tsx` loads, so a React Flow node is `position: static` and every box lands in normal flow. The Topology browser test imports the style sheet itself. The Flow and diagram browser tests lay out without it, so their geometry assertions do not describe the pane.
- Fix: the browser setup loads every style sheet `main.tsx` loads (Mantine notifications, spotlight, charts and code highlight, and React Flow), and the Topology test no longer imports its own.
- Status: fixed

## Sweep findings after the pages

### sql-topology-c1 [S2 · design · page] At 200% zoom the editor is squeezed under the cost line, and the cost line moves when the estimate arrives

- Where: `web/src/features/sql/QueryEditor.module.css`, `web/src/features/sql/QueryPane.tsx` (`sql` zoom light error and empty in the full sweep, CLS 0.032; also zoom default, 0.037)
- Evidence: in the 640 x 400 layout the editor pane cannot hold the toolbar, editor, hint and cost line. The editor shrank to nothing (`min-block-size: 0`) while CodeMirror kept its own `4.5rem` floor and was drawn over the cost line, which showed as overlapping text. When the plan arrived with its notice the cost line grew by 85 px, the editor gave that back, and the cost line and the hint moved up 84 px.
- Fix: the editor keeps its floor (its label and `4.5rem` of text, on the host), so a short pane scrolls as a whole by its region instead of overlapping. The hint moved under the editor and the cost line is last in the pane, so an estimate that arrives, or grows with notices, has nothing below it to push.
- Status: fixed (`sql.browser.test.tsx`, "a pane too short for everything in it": the editor keeps 4.5rem and ends above the hint, and the editor and hint do not move when the estimate arrives with three notices; the first fails without the CSS). The header's freshness badge still moves the header's end by 25 px when its word changes (Offline, Polling, Live), 0.0023 on its own, under the budget; it belongs to the shell, not to this page.
