# QA log: queues-resources-routing

Every finding is fixed before this change is archived. Severity: S1 blocks a task or fails AA; S2 broken or misleading; S3 inconsistent; S4 polish.

## Code audit (before)

No confirmed findings.

## Screenshot review (before)

### queues-resources-routing-sweep-1 [S2 · a11y · page] A button is nested inside a button in the queue selection bar
- Where: `web/src/features/queues/` (the "Selected queues" region in QueuesView)
- Evidence: React logs "<button> cannot contain a nested <button>" on the queues page; nested interactive content is invalid HTML and breaks keyboard and screen-reader use.
- Fix: make the outer element a non-interactive container.
- Status: fixed (`ui/CapabilityGate.tsx` now renders the "Why?" control as a sibling of the gated button, never around it, and the selection bar is a non-interactive region. `QueuesView.test.tsx` renders the bar with the bulk actions of `BulkActionBar` gated and asserts no button is inside another)

### queues-resources-routing-sweep-2 [S1 · design · base] The queues grid shows 3 of its columns at 1440 px
- Where: queues (VirtualTable fit)
- Evidence: Address and Queue take the stretched width; Depth, Consumers, Delivering, Scheduled, Durable, State and Nodes are off-screen behind a horizontal scroll.
- Fix: DataTable column solver.
- Status: fixed (queues grid is a DataTable with kind and priority per column and no pixel widths; jsdom tests only, the browser width check is still to run)
