## How to run this change
This change states **requirements only**. Run it in a fresh Claude session, in number order:

1. `git fetch`, then create a git worktree for this change on a new branch off `origin/main` (`.claude/rules/00-workflow.md`). Work only there, and remove it after the merge.
2. Read this proposal, its specs, the capabilities it names in `openspec/specs/`, and the ADRs they cite.
3. Brainstorm and investigate (`/opsx:explore`, `superpowers:brainstorming`); check libraries with ctx7. Ask the user only what is really theirs to decide.
4. `/opsx:update`: add `design.md`, sharpen the specs (turn ADDED into MODIFIED where a requirement changes an existing one), replace the stub `tasks.md`.
5. `/opsx:apply` with the harness under **Execution**. New decisions get an ADR.
6. Verify: `just verify`; for UI, run Studio and check screenshots (light and dark, empty and error states).
7. PR, merge on green CI, `/opsx:archive`.

## Why
Studio protects every stored secret with one AES-GCM master key from the environment (ADR-0009, ADR-0092). Rotating that key is manual, the key can only come from the environment, and nothing proves that a secret never reaches a log, an audit row, an error body or an exported file. Operators with a secret manager cannot use it, and one leaked master key exposes everything at once.

## What Changes
- Envelope encryption: per-secret data keys wrapped by a key-encryption key, for every stored secret including broker credentials, OIDC client secrets, channel secrets and plugin vault entries.
- Key-encryption-key rotation with online re-wrap and visible status.
- Pluggable secret providers (environment or file, HashiCorp Vault, Kubernetes Secrets) chosen by configuration; the plugin-facing vault contract is unchanged.
- Redaction of secrets and credential-like values in logs, audit, error responses and exported bundles, with tests proving known secrets never appear.
- **BREAKING**: the stored secret format changes; existing installations must re-enter or re-encrypt secrets on upgrade.

## Capabilities
### New Capabilities
- `secret-management`: envelope encryption, key rotation, providers and redaction
### Modified Capabilities
- `plugin-secrets`: stored under the envelope scheme, contract for plugins unchanged
- `studio-settings`: secret provider and rotation settings and status

## Out of scope
- A hardware security module integration.
- Rotating the secrets themselves at the broker or IdP.
- Secret provider plugins from third parties beyond the built-in three.

## Depends on
none

## Execution
**Subagent-driven, plus a reviewer before the PR**: envelope encryption, providers and redaction are independent slices with clear contracts, and a mistake in key handling makes every secret unreadable.

## Impact
Security layer, persistence (all secret columns), settings, logging, audit, plugin vault.
