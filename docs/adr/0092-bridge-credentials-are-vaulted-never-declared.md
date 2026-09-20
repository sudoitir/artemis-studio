# ADR-0092: Bridge credentials are vaulted, never declared

- **Status**: accepted
- **Date**: 2026-09-20
- **Deciders**: Artemis Studio maintainers

## Context

A bridge authenticates to the broker it forwards to. `createBridge` accepts `user` and
`password` in the same document as every other field (ADR-0091).

Every other field in that document is declaration data, and declaration data is deliberately
well travelled. It is stored as a jsonb document, kept as an immutable revision, rendered as a
difference between two revisions, recorded in the plan an operator reviews, carried in an
audit row's parameters, returned by the MCP configuration tools, and written into an exported
`broker.xml` fragment that people paste into repositories.

A password taking that route is six disclosures from one field, several of them permanent, and
none of them would fail a test that was not written to look for them. This is the failure mode
that leaks quietly for a year.

Studio already has somewhere for this. `SecretVault` (ADR-0009) holds broker credentials,
encrypted with AES-GCM, and `AuditParamsFilter` already exists as the choke point for audit
parameters under the content policy (ADR-0075).

The measurement in ADR-0091 settles the one question that could have made this harder:
`BridgeControl` exposes neither `User` nor `Password`. The broker never hands the credential
back. So every disclosure path that exists is one Studio would have created itself, and
closing them is entirely within our control.

## Decision

We will hold a bridge's credential in `SecretVault` and carry **only a reference to it** in
the declaration.

**D1 — The declaration holds `credentialRef`, never a secret.** `BridgeDecl` carries the
reference. The apply resolves it from the vault at the moment of the broker call and nowhere
earlier, so the plaintext exists only in the request being sent.

**D2 — Six paths, closed explicitly, each with its own test.** The stored document, a stored
revision, a revision difference, an audit row's parameters, the MCP tool responses, and the
exported configuration. A difference that involves the credential states *that* it changed and
shows neither value. This is enumerated rather than left to a general rule, because a general
rule is what a seventh path slips past.

**D3 — Export names what is missing.** An exported fragment carries a placeholder identifying
the credential that must be supplied, so a pasted `broker.xml` fails loudly on a missing
password rather than silently forwarding unauthenticated or appearing complete.

**D4 — Rotation is a vault operation.** Changing a bridge's password does not cut a
declaration revision, because the declaration did not change. It re-applies the bridge, which
under ADR-0091 D3 is a removal and a creation, with that hazard stated.

**D5 — The verification says nothing about the credential.** The broker does not report it, so
an applied bridge is never reported as matching on it (ADR-0091 D5). A wrong password shows up
as a bridge that is started and not connected — a fault, reported as such, not as drift.

## Consequences

Good: the secret has one home, already encrypted, already rotatable, and the declaration stays
what it is meant to be — a description of configuration that is safe to read, diff, export and
audit.

Good: the six paths are named, so a seventh is a visible addition rather than an oversight.

Bad: a declaration is no longer self-contained. Exporting it and applying it elsewhere needs
the credential supplied out of band, and importing a fragment that carries a real password
must reject or vault it rather than store it. That is the cost of not storing secrets, and it
is the right one.

Bad: D4 means a password rotation carries a bridge replacement's hazard — a real gap in
forwarding for a change that is conceptually trivial. The alternative is a broker operation
that does not exist.

## Alternatives considered

- **Storing the password in the declaration document, encrypted.** Rejected: it would still
  travel through the diff, the plan, the audit parameters, the MCP responses and the export,
  and an encrypted blob in an exported `broker.xml` is useless to the broker anyway.
- **Refusing to support authenticated bridges.** Rejected: a bridge to another organisation's
  broker is the normal case, and refusing it would make the capability ornamental.
- **Prompting for the password at apply time and never storing it.** Genuinely safer, and
  rejected reluctantly: the apply is also reached from a scheduled evaluation and from MCP,
  neither of which has an operator to prompt, and a bridge that cannot be re-applied without a
  human is a bridge that silently stops being managed.
- **Reusing the cluster's own broker credential.** Rejected: the target of a bridge is a
  different broker, often in a different trust domain, and conflating the two would send
  Studio's management credential somewhere it has no business being.
