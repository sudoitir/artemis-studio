## Why

During an incident an operator needs to pause, purge, or delete tens of queues at
once — a poison-message storm across `orders.*.retry`, a leaked test namespace, a
fleet of stuck consumers. Studio can do each of these to one queue at a time, so
today the operator either clicks forty times, with forty confirmations and no view
of the whole, or leaves Studio for a script with none of its safety. Roadmap B names
this gap: multi-queue operations with dry-run, capped execution, typed confirmation
and audit.

## What Changes

- An operator can select queues on the queues screen — individually, or every queue
  matching the current filter — and pause, resume, purge, or delete them as one
  **bulk run**.
- A bulk run is **previewed** before it can be armed. The preview:
  - freezes the exact set of queues;
  - states the blast radius: queues, nodes, and the messages destroyed, or that the
    estimate is unavailable;
  - lists every queue that will be refused, and why.
- Executing a run checks that the preview is the one being executed, then works
  through the queues one at a time in the background.
  - Each queue goes through the existing single-queue command, with its dry-run,
    its per-node outcome, its audit row, and the node rate limiter.
  - Progress streams live.
  - The operator can stop the run, and it stops at the first failure unless they
    chose to continue past failures.
- Bulk runs are **persisted**. A run survives a page reload and can be revisited
  from a history. A Studio restart mid-run leaves it `interrupted`, with the one
  queue that was in flight marked `unknown`. Studio never guesses about it and
  never resumes it on its own.
- Caps:
  - A bulk run is capped at a configurable number of queues (`safety.bulk-queue-cap`,
    default 200). This cap has no override.
  - The run's total estimated messages is checked against the existing
    `safety.bulk-cap` (ADR-0022). Going over it needs an explicit override.
- Destructive runs (purge, delete) confirm by typing the action and the count,
  e.g. `delete 37 queues`. Pause and resume are reversible and confirm once.
- Audit: the run is one audit event, and each queue's audit event links to it, so
  the trail shows both the whole and every part.

## Capabilities

### New Capabilities
- `bulk-operations`: previewing, executing, observing, stopping and recovering a bulk
  run of one queue operation over a frozen set of queues.

### Modified Capabilities
- `audit-log`: an audit event may belong to a parent event, and the trail shows the
  link both ways.
- `operator-ui`: the queues screen supports selection (including "all matching the
  filter"). A bulk run has a live progress view and a history.

## Impact

- **Backend**
  - New module `feature/bulk`, with tables `bulk_run` and `bulk_run_item`.
  - Endpoints under `/api/v1/clusters/{clusterId}/bulk/…`.
  - A new SSE topic `bulk` and a new setting `safety.bulk-queue-cap`.
  - Uses the public API of `feature.queues`, `feature.messages` and `feature.resources`.
- **Kernel**
  - `audit_event` gains a nullable `parent_id` (new changeset).
  - The audit service links an event to an ambient parent when one is in scope.
- **Frontend**
  - New feature folder `features/bulk`, and a new kernel slot `queues.selection`.
  - Row selection on `features/queues/QueuesView`.
  - The audit view links a child to its bulk run.
- **ADR-0093**: bulk operations are persisted runs over the existing single-queue
  commands. It builds on ADR-0022 (bulk cap), ADR-0078 (audit in its own
  transaction), ADR-0069/0070 (module and slot boundaries).
- **Not in this change**:
  - Bulk move and bulk retry of messages.
  - Bulk operations over MCP. An LLM-driven mass delete deserves its own decision.
