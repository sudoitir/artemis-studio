# QA log: sql-topology

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

### sql-topology-1 [S3 · performance · page] Every streamed SQL row copies the whole row array and triggers its own render

- Where: `web/src/features/sql/useSqlTail.ts:206`
- Evidence: Each SSE 'row' event calls setRows((prev)=> [...prev, row]) (or [row, ...prev].slice). A non-tail result of N rows does O(N^2) copying and N separate state updates, so the grid re-renders once per row. While tailing, each row also starts a setTimeout and copies a new Set in setFreshKeys.
- Fix: Buffer incoming rows in a ref and flush them once per animation frame (or a short interval) with a single setRows that appends the batch. Do the same for freshKeys.
- Status: open

### sql-topology-2 [S4 · reliability · page] Query history skips a repeated run when the text, source and row count are the same

- Where: `web/src/features/sql/SqlConsoleView.tsx:409`
- Evidence: The dedupe key is `${text}@${source}@${rows.length}`, held in recorded.current. Run Q (10 rows), then run Q again with different rows but still 10 rows: the second finished run is never recorded and its entry is not refreshed to the top.
- Fix: Key the dedupe on the run's request nonce from useSqlTail instead of the result's shape.
- Status: open

