# ADR-0176: Adoption is a step of registration

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi
- **Amends**: [ADR-0067](0067-broker-configuration-declared-applied-canary-first.md) D8 (drift is advisory;
  action never is)
- **Builds on**: [ADR-0175](0175-management-urls-are-derived-from-a-pattern-and-proved-by-node-id.md)

## Context

ADR-0067 D8 keeps drift advisory: nothing adopts what the brokers run as the declared configuration
unless an operator confirms it, because an adoption also declares a hand-made change nobody has
finished thinking about as intended. That left a freshly registered cluster with no declaration and an
offer on the configuration view, which most operators reached late or never, so a new cluster started
life with nothing to drift from.

Registering a cluster is an operator's deliberate act, made while looking at what Studio found. It is
the natural moment for the same confirmation, and it is the one at which the brokers have not yet been
changed behind anybody's back.

## Decision

1. **The registration check carries the adoption preview.** It is the same `adopt()` the configuration
   view uses, read from the live nodes the check reached: counts per section (addresses, address
   settings, security settings, diverts) and every item on which the nodes disagree, naming the nodes and
   their values. It reads and writes nothing, and is offered only to an operator who may declare
   configuration.
2. **`POST /clusters` takes `adopt`.** With `adopt` true, the cluster and its first revision (source
   `ADOPT`, attributed to the registering operator) are saved in the registration's transaction, through
   a `RegistrationAdoption` interface the configuration feature implements, so the clusters module never
   names the feature. If the adoption fails the whole registration rolls back. With `adopt` false, or
   with the feature off, no revision is saved.
3. **The form decides the default from the evidence.** The switch is on when the nodes agree and off,
   with each disagreement listed, when they do not, because keeping one node's value over another's is a
   choice the operator makes on purpose.
4. **Nothing adopts after registration on its own.** The offer on the configuration view stays for a
   cluster registered without adoption, and its explanation (`WHY_NOT_AUTOMATIC`) now says "when
   registering, or here". The advisory-only drift of D8 is unchanged.

Adoption at registration declares addresses and settings but no queues: the queues of an adoption come
from the snapshot cache, which a cluster registered a moment ago does not have yet.

## Consequences

- A cluster that registers with the switch on starts in sync and shows only real change as drift.
- The registration's audit event records the adoption, and the revision's own audit event names the
  operator.
- Registering needs `config:write` as well as `cluster:write` to adopt; without it the check offers no
  adoption.
- An adoption that cannot read a live node fails the registration; the operator turns the switch off to
  register without it.

## Alternatives considered

- **Adopt asynchronously on a registration event.** It would save intent without the operator having seen
  it, and a failure would leave a half-onboarded cluster. Rejected: D8's point is that only an operator
  says the running state is intended.
- **Keep the offer only on the configuration view.** The status quo; operators did not find it in time.
