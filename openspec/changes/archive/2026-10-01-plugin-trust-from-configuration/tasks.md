## 1. Configuration and reconciliation

- [x] 1.1 ADR-0166, and the amendment link on ADR-0141.
- [x] 1.2 Changeset `kernel-plugin-0011-plugin-trusted-key-source`: `source text NOT NULL DEFAULT 'ADMIN'`, a check on `ADMIN` and `CONFIGURATION`, with a rollback.
- [x] 1.3 `PluginProperties.trustedKeys`, with binding tests for YAML and indexed environment variables.
- [x] 1.4 `PluginTrust.pin`, `unpin` and the refusal to remove a configured key.
- [x] 1.5 `TrustedKeyReconciler`, an `ApplicationRunner`, with unit tests for add, convert, rename, remove, a duplicate, an unparsable entry and a missing name or key.
- [x] 1.6 An integration test on a real second start: a plugin signed by a configured key starts as trusted at boot, a key dropped from configuration is un-trusted, and an invalid entry fails startup.

## 2. API and UI

- [x] 2.1 `source` on the keys listing, and 409 `configured-key` for removing a configured key, with an integration test; regenerated OpenAPI document and web types.
- [x] 2.2 "From configuration" badge and a disabled Remove with its reason in the Trusted keys dialog, with tests.

## 3. Documentation

- [x] 3.1 The plugin guide's section on pinning keys by configuration, and commented examples in the compose files.
- [x] 3.2 `openspec validate plugin-trust-from-configuration --strict`, then archive.
