# Spec Delta: broker-configuration

## MODIFIED Requirements

### Requirement: A declaration can be adopted from the running cluster

The system SHALL build a declaration from what a cluster's live nodes are running, for review
before it is saved, so that an operator adopting the feature does not enter it by hand. Where
live nodes disagree, the adopted declaration SHALL list the disagreement rather than pick a
value silently.

The broker reports the settings an address resolves to, never the match patterns its
configuration declares. Adoption SHALL therefore seed the catch-all match with every key the
broker reports for it, and one entry per observed address carrying the keys whose resolved value
differs from the catch-all, and SHALL state that the original patterns could not be read. An
operator MAY then merge address entries into the pattern they know.

Adoption SHALL happen only when an operator confirms it: as a step of registering the cluster
(see `cluster-registration`), or from the configuration view of a cluster that has no
declaration. A cluster with live nodes and no declaration SHALL be offered an adoption,
quantified before it is opened: how many entries per section it would declare and which nodes
disagree. Being shown the offer SHALL NOT save a revision, and the system SHALL never adopt on
its own outside those two confirmations.

#### Scenario: Adoption seeds what reproduces the observed state
- **WHEN** an operator adopts a declaration from a cluster where `orders.in` resolves three keys differently from `#`
- **THEN** the adopted document carries `#` with every reported key and an `orders.in` entry with those three keys, and says that match patterns cannot be read from a broker

#### Scenario: Node disagreement is listed
- **WHEN** two live nodes report different values for one key of one match
- **THEN** the adopted declaration names both nodes and both values for that key instead of choosing one

#### Scenario: The first run is offered an adoption it did not perform
- **WHEN** an operator opens the configuration of a cluster with live nodes and no declaration
- **THEN** the counts per section and any node disagreements are shown, and no revision exists until the operator confirms one

### Requirement: The capability probe's recommendations are declared, never auto-applied

The system SHALL turn the capability probe's appliable gaps into a declaration the operator can
save in one action, and SHALL NOT write anything to a broker in doing so. The saved revision
records its source as `RECOMMENDED`, so the audit trail distinguishes it from a hand edit, an
import, and an adoption.

Applying that revision is the ordinary apply, with the ordinary plan, hazards, canary and typed
confirmation (D8): the recommendation is a suggestion, and the operator is still the one who acts.

A recommended security setting grants permissions that the broker checks against the account
Studio uses for Core connections. Its roles SHALL be prefilled with the roles of that account,
read from the broker's user management: the account's roles that the broker already names for
that address, or else all of the account's roles. When the broker cannot report the account's
roles (for example under an LDAP or custom login module), the roles the broker currently names
for that address SHALL be prefilled instead, and the field SHALL say which source it used and
why. The roles SHALL stay editable before they are declared, and a security setting that would
name no role at all SHALL be refused, because a block granting nobody anything applies cleanly
and does nothing.

#### Scenario: Roles come from Studio's account
- **WHEN** the Core account holds the roles `amq` and `ops`, and the broker names `amq` for the recommended match
- **THEN** the field is prefilled with `amq` and says the roles were read from the account

#### Scenario: The broker cannot report the account's roles
- **WHEN** the broker's login module does not support listing users
- **THEN** the field is prefilled with the roles the broker names for that address, and says the account's roles could not be read and why

#### Scenario: Declaring the recommendations writes nothing to a broker
- **WHEN** an operator declares the recommended configuration
- **THEN** a new revision is saved with source `RECOMMENDED`, no broker is written, and the operator is taken to the plan for that revision

#### Scenario: Two recommendations on one match are one entry
- **WHEN** more than one recommendation targets the same address-setting match
- **THEN** they are merged into a single declared entry, so neither replaces the other's keys when it is applied

#### Scenario: A security setting with no roles is refused
- **WHEN** a recommended security setting would be declared with no role named
- **THEN** it is refused with that reason, rather than saved as a block that grants nobody anything

#### Scenario: Declaring needs the edit authority
- **WHEN** a caller who may read the cluster but not edit its configuration declares the recommendations
- **THEN** the request is refused, the same as any other edit
