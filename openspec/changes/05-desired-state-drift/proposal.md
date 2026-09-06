## Why

> **Depends on change 01 (`01-queue-and-address-lifecycle`).** This change applies
> drift through that change's lifecycle service and reuses its per-node outcome. It
> should not be applied until 01 is archived.

Once Studio can create and destroy queues across a cluster, the next question
arrives immediately and has no good answer today: *is this cluster still the shape
it is supposed to be?*

Nothing in the product holds an opinion about what a cluster should contain. It can
report what each broker has, and it can compare two nodes' configuration against
each other (`broker-config-diff`) — which catches a primary and backup that
disagree, and catches nothing about a cluster where every node is uniformly wrong.

The failure modes this leaves open are the mundane ones that cause real incidents:

- a queue was created on three of four nodes during an earlier partial fan-out, and
  the fourth is quietly not receiving traffic;
- a queue was created by hand during an incident and never removed;
- an auto-created queue exists in production that nobody intended;
- a queue's max-consumers was changed to unblock something and never changed back.

Every one of these is invisible until it causes an outage, and every one is trivial
to detect if the product knows what the cluster is supposed to look like.

## What Changes

**A new capability, `desired-state`.** An operator declares the queues and
addresses a cluster is expected to have — name, address, routing type, durability,
and the configuration values worth pinning — and Studio continuously compares that
declaration against what each broker actually reports.

**Drift is presented as a report, not an alarm-per-item.** Three kinds:

- **missing** — declared, absent on at least one node (the report names which);
- **unexpected** — present and not declared;
- **divergent** — present everywhere but configured differently from the
  declaration, or differently between nodes.

**Studio never reconciles on its own (ADR-0053).** There is no timer, no rule, no
auto-apply. Drift is reported. Resolving it is an operator action that routes
through change 01's lifecycle service, so it inherits dry-run, the bulk cap, typed
confirmation, per-node outcome and audit without a single new safety mechanism.

This is the load-bearing decision in the change. A monitoring tool that silently
mutates production brokers on a schedule is a fundamentally different and more
dangerous product than the one this codebase has been building, and the difference
is one `@Scheduled` annotation away at all times.

**Declaration is imported from reality.** The first declaration is generated from a
cluster's current state and edited down, because nobody hand-writes a hundred queue
declarations. That import is also the natural moment an operator discovers what is
actually running.

**Unexpected is opt-in per cluster.** A cluster using auto-created queues will
report hundreds of unexpected entries and the report becomes noise. Reporting
unexpected queues is a per-cluster setting, with declarable exclusion patterns.

**Alerting integration is a rule condition, not a new channel.** Drift becomes a
condition the existing alerting capability can fire on, so the delivery, debounce
and notification machinery is reused entirely.

**One new permission**, `desired-state:write`, for editing a declaration. Applying
a fix requires the change-01 lifecycle permission for the operation it performs —
declaring what should exist and being allowed to create it are different
authorities.

## Impact

- **Persistence:** one new Liquibase changeset for the declaration, with column
  ordering per non-negotiable #7. A new changeset only — never an edit to a
  released one.
- **The declaration is not a source of truth for the broker.** It is Studio's
  record of intent. `broker.xml` and whatever manages it remain authoritative; the
  spec says so, so that nobody builds a workflow on the assumption that editing the
  declaration changes a broker.
- **Overlaps with `broker-config-diff` and must not duplicate it.** Config-diff
  compares nodes to each other; this compares nodes to a declaration. They should
  link to one another and share the effective-configuration reader.
- Specs: new `desired-state`; `queue-lifecycle`, `broker-config-diff`,
  `authorization` and `mcp-server` gain requirements.
- ADRs: 0053 (desired state is advisory, never auto-reconciled).
- Not in scope: declaring diverts, addresses' routing configuration beyond what
  change 01 can create, security settings, or address settings. Queues and
  addresses first; the shape generalises later if it earns it.

## Open for refinement

The largest of the five and the least settled. Genuinely open: whether a
declaration should be per-cluster or shared across an environment; whether it
should be importable and exportable as a file for review in version control; and
whether "divergent" is useful at all before change 01's update surface is known.
Brainstorm properly before applying, and expect `tasks.md` and the spec deltas to
change substantially.
