## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git pull --ff-only` on `main`; branch for this change.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
Studio's tests run against Artemis 2.44.0 and client 2.57.0, while the docs mention 2.39 and 2.56, and no supported range is stated. Operators cannot tell whether their broker works, and a broker outside what was tested fails in unclear ways. Capabilities that need a newer broker are not explained as such.

## What Changes
- A documented supported Artemis range, minimum and latest, chosen from evidence in the session.
- CI integration tests against the minimum and the latest version in range.
- Studio detects the broker version on registration and warns or refuses outside the range.
- Capabilities that need a newer broker are gated and explained in the UI.
- A documentation page with the support matrix; ActiveMQ Classic is stated as out of scope.

## Capabilities
### New Capabilities
- none
### Modified Capabilities
- `broker-capabilities`: version-gated capabilities and range status
- `cluster-registration`: version detection and range check on registration

## Out of scope
- Supporting ActiveMQ Classic.
- Testing every intermediate release.
- Supporting brokers older than the chosen minimum.

## Depends on
none

## Execution
**Inline**: evidence gathering, a detection check and documentation in one context.

## Impact
Broker connectivity, registration, CI integration tests, operator UI, documentation.
