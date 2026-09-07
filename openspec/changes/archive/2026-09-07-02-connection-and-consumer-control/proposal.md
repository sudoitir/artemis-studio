## Why

Studio detects slow consumers (ADR-0044) and shows them on a badge. It lists every
connection, session and consumer across the cluster. Then it stops. The operator
who has just been told which consumer is holding up a queue has to leave the
product to do the one thing the finding implies.

That is a loop the codebase already paid for and never closed. Two authorities were
built to identify a slow consumer; six cross-node resource views were built to show
the connections behind it; and the verb that acts on the answer is missing.

The operational case is narrow and sharp: a stuck or wedged consumer holds messages
that would otherwise redeliver. Closing its connection returns those messages to
the queue and lets a healthy instance take them. It is the most common 3am
intervention on an Artemis cluster, and it is the reason people keep a JMX console
open next to Studio.

## What Changes

**A new capability, `connection-control`**, with three operations:

- close one connection, by id, on one node;
- close one session, by id, within a connection;
- close every consumer connection bound to an address, on a cluster.

**Connection identity is node-local and ephemeral (ADR-0050).** A connection id
means something only on the node that issued it, and it may be gone between the
read that listed it and the close that targets it. Three consequences shape the
whole design:

- a close names an explicit node — this is the one broker operation in Studio that
  is *not* a cluster-wide fan-out, and that is correct rather than an omission;
- a close is not idempotent and cannot be made so;
- a target that has already gone is a **success**, not an error. The requested
  state — that connection is not open — holds.

**The address-scoped close is a bulk operation** and is treated as one: it takes a
dry-run that reports how many consumers would be closed, per node, and it is
evaluated against the ADR-0022 safety cap.

**One new permission**, `connection:close`, resolved through the existing scope
walk. It is deliberately not implied by any message permission — being allowed to
delete messages says nothing about being allowed to disconnect an application.

**The UI** gains a row action on the connections, sessions and consumers views,
with the typed-confirmation component already in the repo. The confirmation names
the client id or remote address, not the opaque connection id, because that is what
an operator can recognise as "the right one".

**One MCP tool**, `connection_action`, marked destructive, dry-run by default.

## Impact

- **Studio becomes able to disconnect a running application.** That is a heavier
  authority than anything it currently holds, and it is why the permission is
  separate and the audit record names the client that was closed, not just the id.
- **A close has a visible side effect on message state**: in-flight messages on the
  closed consumer are returned to the queue and redelivery counts increase. The
  confirmation says so; discovering it afterwards from a DLQ is not acceptable.
- Specs: new `connection-control`; `cross-node-resource-views`, `authorization` and
  `mcp-server` gain requirements.
- ADRs: 0050 (connection identifiers are node-local and ephemeral).
- Not in scope: closing consumers by any criterion other than address — no
  "close all slow consumers" button. A heuristic that picks its own targets is a
  different and much more dangerous feature.

## Open for refinement

Proposed, not settled. Whether the address-scoped close is worth its blast radius
at all, and whether the confirmation should require the client id rather than the
queue name, are both genuinely open. Brainstorm further before applying, and revise
`tasks.md` and the spec deltas together with whatever is decided.
