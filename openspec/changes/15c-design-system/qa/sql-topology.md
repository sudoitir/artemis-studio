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

### Redesign (Unit A)

- A1: the table's Columns menu has **Move {header} earlier** and **later**, disabled at the bounds. The first declared column stays first. Focus stays on the pressed button, or moves to its sibling at a bound, and the move is announced ("Source moved to position 3 of 9"). `withMovedColumn` in `tableState`.
- A2: `ErrorState` reads a 422 with no field errors as the problem's own title and detail, and a string `problem.hint` is the next step. The reading moved to `errorReading.tsx` so the cost line can use its title.
- A3, A4: `useSqlTail` gains `cancelled`, `runId`, `sql` and `cancel`; `costVerdict.ts` is pure and has a test for every row of the verdict table.
- A5, A6, A7: the query editor marks the offending token as a lint diagnostic (F8, Mod+Shift+M), Mod+. cancels and Escape never does; the workspace is a vertical `Splitter` under `as:sql:split`; the cost line describes the editor and Run through `aria-describedby`; the meta bar, live-tail banner and syntax help use `Notice`, `StatusBadge`, `Section` and `DescriptionList`.
- A8: `IndexSubscriptions` is two h3 sections, a static `DataTable` of subscriptions (Queues, Mode, Held, Retention, State, Delete), a `ConfirmDialog` with `typedName` for Delete, `Notice`s, `ErrorState`, `LoadingState`, `EmptyState` and `notify`.
