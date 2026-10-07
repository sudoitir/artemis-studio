# ADR-0177: Recommended roles are read from the broker account

- **Status**: accepted; amends [ADR-0068](0068-recommended-configuration-from-the-capability-probe.md)
- **Date**: 2026-10-06
- **Deciders**: Mahdi Amirabdollahi

## Context

[ADR-0068](0068-recommended-configuration-from-the-capability-probe.md) turns a capability gap into
a declaration the operator saves in one action. For a security setting, such as the one that lets
Studio subscribe to `activemq.notifications`, it prefilled the roles by copying whoever already held
`consume` on that address, falling back to `#`.

That copy is the wrong source. A security setting is checked against the account Studio connects
to Core with, so it grants Studio something only if it names a role that account holds. A broker
that grants `consume` on `#` to `everyone` yields a block naming `everyone`, which applies cleanly
and gives Studio nothing when its account is in `amq` alone. The broker already knows the right
answer: its user management lists each account's roles.

`ActiveMQServerControl.listUser(String username)` returns that list as a JSON string, double
encoded like the other broker operations: `[{"username":"artemis","roles":["amq"]}]`. It exists on
the basic security manager and on the properties login module. Under any other module (LDAP, a
custom one) it throws, and so does a call from an account without management rights. Passing an
empty username lists every user, so a blank name is never sent.

## Decision

1. **The roles of the Core account are read, once per read of the recommendations.** The account is
   the cluster's effective Core username: the Core credential if one is stored, otherwise the
   management credential. The read is `listUser` over Jolokia, on the one node the recommendations
   are seeded from. When a registration check previews the recommendations there is no cluster yet,
   so the username comes from the request, by the same fallback.
2. **The prefill is the account's roles that the broker already names for that address, or else all
   of the account's roles.** Naming a role the account holds is what makes the block work; naming
   the broker's existing roles first keeps the declaration close to what the operator already
   configured.
3. **A read that fails is an answer, never an error.** When the roles cannot be read, the broker's
   current roles for that address are prefilled as before, and the recommendation says so. Each
   security-setting recommendation carries `accountRoles` and an `accountRolesSource` whose kind is
   `BROKER_ACCOUNT`, `SECURITY_SETTINGS` (the account's roles could not be read) or `NONE` (neither
   source named a role), with a classified reason when the account's roles were not used. The field
   states the source in words.
4. **Nothing else changes.** The roles stay editable, a security setting that would name no role is
   still refused, and declaring still writes nothing to a broker (ADR-0068).

## Consequences

- The default install, where the management account is the Core account and the broker uses the
  properties login module or the basic security manager, gets a block that works without the
  operator knowing which role the account holds.
- Under LDAP or a custom module the recommendation is no better than before, but it is honest about
  it: the operator is told why the account's roles are unknown and that the roles shown are the
  broker's own.
- A username is looked up as given. A broker whose user store is keyed differently from the name
  the account connects with reports no roles; that is stated as such and falls back.
- One more management call per read of the recommendations, charged to the node's rate limiter like
  any other.

## Alternatives considered

- **Keep copying the roles that hold `consume`.** Rejected: it can name roles the account does not
  hold, which is the failure this ADR exists for.
- **Ask the operator to type the role.** Rejected as the default: the broker can answer, and a
  prefilled wrong guess is worse than a prefilled right one the operator can still change.
- **Read the roles from `artemis-roles.properties` or the broker's JAAS configuration.** Rejected:
  the files are not reachable over management, and the module may not be file-based at all.
- **Read the roles once per cluster and store them.** Rejected: roles change on the broker, and a
  stale copy would be worse than one extra read on a screen opened rarely.
