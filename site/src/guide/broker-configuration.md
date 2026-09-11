---
title: Broker configuration
description: Declare the address settings, security settings, diverts, addresses and queues a cluster should run; apply them canary-first over the management API, or export a broker.xml fragment; see every node's drift from the declaration.
---

# Broker configuration

Artemis lets you change a running broker's address settings, security settings
and diverts over its management API, and it keeps those changes across a restart.
That is useful and it is a trap: nothing in the broker records that the change
happened, so the running broker and the `broker.xml` it will be deployed from
next silently disagree.

This page is about the feature that closes that gap. A cluster gets a
**declaration** — what it should run — and Studio measures every live node
against it, applies it where you ask, and tells you in words when a node has
drifted.

## What a declaration is

Four sections, one document per cluster, versioned on every save:

| Section | What an apply does with it |
|---|---|
| **Addresses and queues** | Creates what is missing. Never deletes. A queue that exists with a different configuration is reported, not changed — edit it from the Queues view. |
| **Address settings** | Per match: limits, policies, dead-letter and expiry addresses, redelivery. Applying **replaces** the broker's whole entry for that match. |
| **Security settings** | Per match: which roles may send, consume, create and manage. |
| **Diverts** | Created where missing; a changed one is a delete and a create. |

The rest of `broker.xml` — `global-max-size`, `<ha-policy>`, acceptors and the
like — cannot be applied over the management API on any Artemis version.
Importing a file that has them lists each one as *not applied*; nothing is stored
for them and nothing pretends otherwise.

## Two ways to apply it

The mode is set per cluster in the declaration header, and both actions are
always visible:

- **Managed by Studio.** *Preview & apply* writes the declaration to every live
  node over the management API. The change is durable across restarts, and the
  next drift evaluation shows the nodes in sync.
- **Managed outside Studio.** *Copy broker.xml fragment* renders the declaration
  as the four sections of a `<core>` element for your own configuration
  management. Apply is disabled and says why. Drift is still evaluated, so you
  learn when your deployment matches.

Studio never writes `broker.xml` and never calls `reloadConfigurationFile` — the
reload re-reads the whole file from the broker's disk, including edits Studio has
never seen, which is exactly the kind of action that cannot be previewed.

## Getting a first declaration

Three ways in, from the Configuration view:

1. **Adopt from cluster** reads every live node and builds a declaration from
   what they run. Every address-setting match is seeded with the full entry the
   broker reports, so the first apply changes nothing. Where nodes disagree, both
   values are listed and you choose.
2. **Import XML**: paste a `broker.xml` or a fragment — bare `<address-setting>`
   and `<security-setting>` elements are fine. The preview lists what was
   recognised (per section, added / changed / unchanged), what is *not applied*,
   and any errors with the element named — an unresolved `${…}` placeholder is
   an error, not a value. By default the paste is **merged** into what is already
   declared: pasted entries add to or update their counterparts, and on an
   address setting the pasted keys win while the other declared keys stay.
   *Replace* makes the paste the whole declaration.

   The capability ledger on a cluster's setup and settings pages uses the same
   door. Where a "needs setup" snippet is an address or security setting —
   slow-consumer detection, the notifications permission, the management
   message-size limit — the row says so and offers **Declare it in
   Configuration**, which opens the import preview on that snippet. The static
   half of such a snippet (a `<broker-plugins>` block) is listed as not applied
   and still needs `broker.xml`.
3. **Add an entry** in any section. The editors validate against a catalogue of
   every address-setting key the broker accepts: an unknown key is refused
   rather than becoming a silent no-op on the broker. Every key carries an
   info control with what it governs and an example value, and the long tail
   behind *Other keys* has a finder.

One thing the broker cannot tell Studio: the `view` and `edit` permission types
of a security setting are accepted over management but never reported back
(measured on 2.44). They are sent, left out of the plan, the verification and
the drift check, and the editor says so — confirm those two in the broker's own
configuration.

Every save is a new revision. Two operators editing at once do not overwrite
each other: a save names the revision it was made on and is refused if that
moved.

## Preview and apply

The apply flow is Plan → Confirm → Result, and it is built so it cannot take a
whole cluster down at once.

**Plan.** A dry run reads every targeted node — at most two batched requests per
node, never one per item — and computes, per node, the ordered steps whose
observed state differs from the declaration. A step whose read-back already
matches is *already as declared* and issues no write. Order within a node:
addresses → queues → address settings → security settings → diverts, removals
after additions. Every step shows before → after for every key the broker
reports, so replace semantics are visible: a key you did not declare that will
change anyway is a **High** hazard named *unintended key change*.

**Hazards** are classified before any write. The High ones must each be
acknowledged, by id, on the plan:

| Hazard | When |
|---|---|
| Unintended key change | replace semantics reset a key the declaration does not set |
| Message-loss policy | `address-full-policy` becomes `DROP` or `FAIL` on a match with observed addresses |
| Blocking policy | `address-full-policy` becomes `BLOCK` |
| Limit below usage | a new limit is lower than what an address under the match already holds |
| Management access | a security setting covers the management address, or the match is `#` / `*` |
| Broad match | a match of `#` or `*` — every address on the broker |
| Exclusive divert | traffic is taken away, not copied |
| Remove undeclared | you opted into removing something Studio did not apply |

Medium and Low hazards (a divert replaced, a DLQ moved, auto-delete enabled,
an owned item removed) are stated and need no acknowledgement.

**Confirm.** The blast radius is restated — how many writes, on which nodes,
canary first — and you type the cluster's name. Acknowledging hazards is not
confirming; both are needed.

**Result.** The first live node (the canary; pick another on the plan) receives
every step and is read back before any other node is touched. The run then
continues node by node and **halts at the first failure**: remaining steps on
that node and every remaining node are *not attempted*. Nothing is rolled back,
and the result says so in one sentence:

> Halted at broker-1 step 3: AMQ229001 … broker-2 not attempted. Nothing was
> rolled back. Re-running converges.

Re-running the same revision converges: matching steps are already as declared;
failed and not-attempted ones are attempted again.

Every apply, dry runs included, is one audit event with the node × step outcome
attached. A step cap (`config.apply-step-cap`, default 100) refuses a plan larger
than that unless overridden, and the override is recorded.

### What an apply never does

- **Destroy a queue or an address.** Removing one from the declaration stops
  Studio checking for it. Deleting it is the queue's own flow, with its bulk cap.
- **Remove something it did not apply.** Studio records what it applied. A
  setting or divert on a node that the declaration does not mention is reported
  as *undeclared*; removing it is a per-plan opt-in with its own High hazard,
  because if `broker.xml` also has it the removal reverts on the next restart
  and Studio cannot tell.
- **Run on a timer.** Drift evaluation is scheduled; the apply never is. A rule
  can alert on drift (`CONFIG_DRIFT`); nothing can act on it for you.

## Drift

Every live node is evaluated against the current revision on a schedule
(`config.drift-interval`, default five minutes), after every apply, and on
demand. One batched, rate-limited read per node; nothing is ever changed by an
evaluation.

The Drift tab leads with the resolved state as a sentence — *All 3 live nodes
match revision 7* — and, when something differs, groups the findings by kind
with the declared value beside the observed one and the node named on every row:

| Finding | Meaning |
|---|---|
| Missing | declared, not on the node |
| Differs | on the node with different values |
| Undeclared | on the node, not in the declaration (only when reporting is on for the cluster) |
| Queue differs | a declared queue exists with a different configuration — edit it from the Queues view |
| Cannot be verified | a key the broker does not report back |
| Unreachable — not evaluated | the node did not answer; absence is not reported as a fact |

Backups are not evaluated: they show no runtime settings until they become
active, and they receive address settings, security settings and diverts through
replication. A promoted backup is evaluated as soon as it is live.

**Config diff** compares two nodes with each other; drift compares every node
with the declaration. The two link to each other.

## Permissions

| Permission | Grants |
|---|---|
| `cluster:read` | see the declaration, the drift, the history; evaluate now |
| `config:write` | save revisions, import, adopt, change the mode |
| `config:apply` | preview and apply |

`config:apply` is a create-and-update authority: it never implies deleting a
queue or an address. The built-in administrator role holds every permission;
grant the two `config:*` permissions to an operator role as you would
`queue:create` or `divert:write`.

## Over MCP

`broker_config` reads the declaration, the drift, the XML fragment or the apply
history. `broker_config_change` declares (from a document or from XML) or
applies: the dry run — the default — returns the plan with its hazards and the
exact `acknowledge` ids; a real run needs `confirm` to equal the cluster's name
and `expectedPlanHash` to equal what was previewed, and halts exactly as the UI
does. See [MCP server](/guide/mcp).

## How it was decided

[ADR-0067](/reference/adr/0067-broker-configuration-declared-applied-canary-first)
records the twelve decisions behind this page, and the alternatives rejected —
a free-form XML editor, `reloadConfigurationFile`, full fan-out, an automatic
reconciler.
