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
