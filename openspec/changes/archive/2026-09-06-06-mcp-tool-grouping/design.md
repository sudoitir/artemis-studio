## Context

See `proposal.md` — Why. **This file is a starting position, not a decision.** It
records the constraints that any answer has to survive, so the brainstorming
session starts from facts rather than from a blank page.

What is already true and not in question:

- **ADR-0045** — the MCP server is a capability surface of intent-shaped tools,
  not a REST mirror, and the listing size is a build failure.
- **ADR-0050** — the listing ceiling scales per tool; enum members and JSON body
  shapes live in the `studio://tools` resource; a capability is never removed from
  the surface to fit a number.
- **Non-negotiable #5** — honest capability gating. A capability that exists but is
  not currently reachable must say so. A model concluding a capability does not
  exist, when it does, is the same failure wearing a different hat.
- The house style already collapses verb families behind a discriminator:
  `queue_action` covers five message verbs, `queue_lifecycle` covers eight
  lifecycle operations. Grouping is not a new idea here — the question is how much
  further it goes and whether discovery becomes dynamic.

## The open questions

These are the ones the brainstorm has to answer. They are listed as questions on
purpose.

**Q1. Static grouping, or dynamic disclosure?** Dynamic gives the smallest
listing. It also means a tool's existence depends on session state, and a host
that does not re-fetch on `notifications/tools/list_changed` leaves the model
believing Studio cannot do something it can. Is that risk acceptable, and is it
even detectable from the server side?

**Q2. What does the protocol actually guarantee?** `tools/list_changed` is in the
MCP specification, but "in the specification" and "honoured by the hosts our users
run" are different claims. **Verify, do not assume** — this needs the current MCP
specification and the Spring AI MCP version this project actually depends on,
checked through `ctx7` and the library's own types.

**Q3. How far does grouping go before selection gets worse, not better?** A tool
whose `op` spans twelve unrelated operations has an argument schema that is a
union of twelve shapes, and a model must read the detail resource to use it at
all. There is a point where one more level of grouping costs more accuracy than it
saves tokens. Where is it?

**Q4. What is the unit of grouping?** Domain (`queues`, `messages`, `alerts`),
posture (read vs mutate), or blast radius (safe, destructive)? Grouping
destructive operations together has a safety story that domain grouping does not,
and a discoverability cost that it does.

**Q5. Does the graph belong in a resource rather than in the tool shape?** Studio
already puts reference data in resources — the cluster catalogue, the capability
ledger, per-node settings, tool detail. A capability graph as one more resource
would give rich discovery at zero listing cost and leave the tools flat. That may
make Q1 moot, and it is the cheapest thing to try first.

**Q6. What breaks for existing agents?** Tool names are an interface. Renaming
`queue_action` to `messages` with `op=move` breaks every prompt and agent
configuration that names it. Is there a deprecation path, and is it worth one?

## Constraints any answer must satisfy

- No capability leaves the surface.
- A model that reads nothing but `tools/list` must still be able to reach every
  operation, even if less efficiently. Discovery may be progressive; it may not be
  mandatory.
- Every discriminator stays validated server-side, with the rejection naming the
  values it would have accepted. That is what makes a terse schema safe.
- The budget stays enforced by a test, in whatever form the shape implies. The
  point of ADR-0045 was that review does not catch listing growth and a build
  failure does; that remains true whatever the shape.

## Not to be decided here

What the tools *do*. This change is about presentation and discovery only. A
grouping that quietly changes an operation's semantics while moving it is two
changes wearing one coat.

---

## The answers

Recorded here as the brainstorm settled them. The reasoning is in
[ADR-0054](../../../docs/adr/0054-mcp-discovery-is-a-tool-not-a-resource.md).

**A1 — Static, and not by preference.** Dynamic disclosure is not expressible.
`addTool`/`removeTool`/`listTools` in MCP SDK 2.0.0 act on the **server**, not on a
session or exchange, and `mcp-core` ships no tool filter or interceptor type. A
tool registered because one model asked would appear for every connected client.
That holds in stateful mode too, so it is not something `protocol: STATELESS` is
costing us — abandoning STATELESS would spend multi-instance HA and the session
posture that pins spring-ai 2.0.1 and buy nothing.

**A2 — Less than it looks.** `notifications/tools/list_changed` is optional in the
specification, gated behind a `capabilities.tools.listChanged` declaration, and
worded as *should* send. There is no client obligation to re-fetch. Separately,
`McpStatelessSyncServer` has no `notify*` methods at all.

**A3 — At the point where the argument schema stops being usable on its own.** A
tool whose `op` spans disjoint argument clusters cannot be called correctly without
first fetching detail, which turns progressive discovery from an optimisation into
a precondition. The `mcp-server` spec forbids that.

**A4 — Posture first, then target; both bind.** Posture is forced by the protocol:
a host gates a whole tool on one `destructiveHint`, so a read behind a
purge-capable tool makes every read prompt the operator — which is how an operator
learns to reflex-approve the purge. Target is what makes an argument union
coherent. Posture alone is not enough: all nine read tools share a posture, and
merging them would fail A3.

**A5 — The graph belongs in a tool, and only mirrored in a resource.** This is the
correction to ADR-0050. `resources` is an optional server capability and no client
is obliged ever to call `resources/read`; tools are the one thing every host
implements. ADR-0050 put the detail a model needs on the least reliable channel MCP
has. `studio_help(topic?)` costs ~35 listing tokens, works everywhere, and — because
the channel is now reliable — licenses stripping the hedging descriptions out of
every schema, so the listing ends up smaller than before the tool was added.

**A6 — Nothing worth preserving.** No backward compatibility and no deprecation, by
project rule and explicit decision. An alias would double the listing cost of
exactly the tools this change made leaner, which is the opposite of the decision.
`queue_action`, `cluster_health`, `diagnose_queue` and `message_body` are removed
names; the commit is `feat(mcp)!:` with a `### Breaking` block.
