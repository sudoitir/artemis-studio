# ADR-0166: Trusted publisher keys can be pinned by configuration

- **Status**: accepted
- **Date**: 2026-10-01
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugin-trust-from-configuration`
- **Amends**: [ADR-0141](0141-plugin-jars-are-signed-and-verified-against-pinned-keys.md) D3 (where trusted keys live) and D7 (how keys are added and removed)

## Context

ADR-0141 keeps trusted publisher keys only in the `plugin_trusted_key` table. An installer adds them in
the UI or the API, after a step-up. That does not suit an installation that is built from files: an
air-gapped one, one managed by GitOps, or one an operator creates and destroys by script. Nobody signs
in to such an installation to paste a key, and a key added by hand is state that the next rebuild
loses or that drifts from what the repository says. Those operators need to say which publishers they
trust declaratively, next to the rest of their configuration.

## Decision

1. **Configuration is a second source of trusted keys.** `artemis-studio.plugins.trusted-keys` is a
   list; each item has a `name`, which the UI shows, and a `pem`, an X.509 certificate or a public key
   (what the key dialog accepts). It binds from YAML and from indexed environment variables
   (`ARTEMIS_STUDIO_PLUGINS_TRUSTED_KEYS_0_NAME`, `ARTEMIS_STUDIO_PLUGINS_TRUSTED_KEYS_0_PEM`). The key
   is parsed and fingerprinted exactly as in ADR-0141 D2, so a key means the same thing from either
   source.
2. **The table records where a key came from.** `plugin_trusted_key.source` is `ADMIN` (added in the UI
   or API) or `CONFIGURATION`, `NOT NULL DEFAULT 'ADMIN'` with a check constraint. Existing rows are
   `ADMIN`. The trust decision is still computed from the table (ADR-0141 D4), so nothing else reads
   the configuration.
3. **The table is reconciled with the configuration before any plugin starts at boot.** The
   reconciler is an `ApplicationRunner`; Spring Boot runs runners before it publishes
   `ApplicationReadyEvent`, and the plugin host starts its plugins on that event. So a plugin signed by
   a configured key is trusted on the first boot with that configuration, with no window in which it is
   unverified. The reconciliation:
   - adds each configured key as `CONFIGURATION`, named from the configuration;
   - turns an administrator's key with the same fingerprint into a `CONFIGURATION` key;
   - renames a `CONFIGURATION` key whose configured name changed;
   - removes a `CONFIGURATION` key that is no longer configured. Removing a key from configuration
     un-trusts it, and ADR-0141 D4 and D5 apply unchanged: installed plugins it signed keep running,
     are marked unverified, and health is `DEGRADED`.
   An administrator's key is never removed by it.
4. **A bad entry fails startup.** An entry without a name or a key, one that cannot be parsed, or one
   with the same key as another entry stops Studio from starting, with a message naming the entry's
   index and the reason. A trust list that is silently shorter than the operator wrote is worse than an
   installation that does not start.
5. **A configured key cannot be removed through the API or UI.** `DELETE /api/v1/admin/plugins/keys/{fingerprint}`
   answers 409 with the problem type `configured-key`, naming the property and saying to remove the
   key there and restart. The key listing carries `source`, and the UI shows "From configuration" with
   Remove visible but disabled and the reason beside it. Adding a key that is already configured is
   refused as already trusted (`key-exists`), as for any duplicate.
6. **Every change is audited** with the existing `PLUGIN_KEY_ADD` and `PLUGIN_KEY_REMOVE` actions, the
   actor `configuration`, and the fingerprint, with `change` of `add`, `convert`, `rename` or `remove`.
   The audit row is committed before the change and updated with its outcome (ADR-0078).

This amends ADR-0141 D3 (the table is no longer the only place keys are decided) and D7 (keys are
also added and removed by configuration, without a step-up, because the operator who edits the
configuration already controls the installation). Its other decisions stand.

## Consequences

- An air-gapped or declarative installation pins its publishers' keys in its compose file, Helm values
  or environment, and a rebuild reproduces them.
- The configuration is the authority for the keys it names. An installer cannot undo a configured key
  from the UI; they must change the configuration and restart, which is the point.
- Anyone who can change Studio's configuration can trust a publisher. They could already run any code
  in Studio, so this adds no new power, but it is a trust decision that needs no step-up and leaves no
  UI trace beyond the audit row.
- Several replicas starting together each reconcile. The writes are idempotent, so the result is the
  same, and each replica may audit what it did.
- A key moved from configuration back to hand-managed has to be removed from the configuration and
  added in the UI again.

## Alternatives considered

- **Merging the two sources at read time, with no table row for configured keys.** Every decision would
  read configuration and the table, the key listing would need a second path, and a key's audit trail
  would have nothing to point at. One table stays the single source for the decision.
- **Seeding the table once and treating the keys as ordinary rows afterwards.** An operator who removes
  a key from configuration would expect it to stop being trusted; a seed could not do that, and the
  configuration and the table would drift apart.
- **Letting an installer remove a configured key in the UI.** The next start would add it again, so the
  removal would last until a restart and mislead the operator who made it.
- **Ignoring an entry that cannot be parsed, with a warning.** A typo would leave a plugin untrusted
  with the cause in a log nobody reads; failing at startup shows it at once.
