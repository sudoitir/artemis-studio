## Why

Trusted publisher keys live only in the `plugin_trusted_key` table, and an installer adds them in the UI or API after a step-up. An air-gapped, GitOps or scripted installation has nobody to sign in and paste a key, and a key added by hand is state that the next rebuild loses. Operators of such installations need to pin their publishers' keys declaratively, with the rest of their configuration.

## What Changes

- A list `artemis-studio.plugins.trusted-keys` (YAML, or indexed environment variables such as `ARTEMIS_STUDIO_PLUGINS_TRUSTED_KEYS_0_NAME` and `..._0_PEM`) whose items have a `name` and a `pem`, an X.509 certificate or a public key.
- At every start, before any installed plugin is started, the trusted key table is reconciled with the list: a configured key is added, an administrator's key with the same fingerprint becomes a configured one, and a configured key no longer listed is removed, which un-trusts it as removing any key does.
- A bad entry (no name, no key, an unreadable key, or the same key twice) stops Studio from starting, naming the entry's index and the reason.
- Each key records its `source`, `ADMIN` or `CONFIGURATION`. The keys listing returns it, the API refuses to remove a configured key with a 409 problem of type `configured-key`, and the dialog shows "From configuration" with Remove visible but disabled and the reason beside it.
- Every change the reconciliation makes is audited with `PLUGIN_KEY_ADD` or `PLUGIN_KEY_REMOVE`, the actor `configuration` and the fingerprint.
- Breaking: none. Existing keys become `ADMIN`.

## Capabilities

### Modified Capabilities
- `plugin-trust`: trusted keys can come from configuration.

## Out of scope

- Configuring the unverified allowance: it stays an installer-tier, step-up, audited switch.
- Reading keys from files or a secret store: the environment variable and the YAML value carry the PEM.

## Impact

Plugin trust store and boot sequence, plugin administration API and UI, database (`plugin_trusted_key.source`, changeset `kernel-plugin-0011`), plugin guide. See ADR-0166.
