# Design: plugin-licensing

The decision and its alternatives are recorded in `docs/adr/0153-plugins-declare-licenses-studio-stores-them-plugins-judge-them.md`. In short:

- The plugin declares, Studio stores opaque bytes, the plugin judges and reports. Studio never reads a file, so it never learns a license scheme, and an administrator cannot register a key of their own.
- The scoped `PluginLicense` bean takes no plugin id, so a plugin can neither read nor judge another's license. A report for a file that is no longer the stored one is ignored in the same statement that would write it, so a late report cannot mark a replaced file valid.
- The state shown to administrators is derived: `MISSING` (declared, no file), `UNCHECKED` (a file, no verdict for it), the reported status, and `EXPIRING` when a valid license ends within 30 days. A valid verdict whose expiry has passed shows as expired.
- A write publishes a bus signal in its own transaction; every replica republishes it into its copy of the plugin as `PluginLicenseChanged`. A replica that lost the bus tells every plugin with a file when it reconnects.
- Health is computed from the shared table, so replicas agree, and it is not part of readiness.
- Upload and removal need a plugin installer and a recent sign-in, are audited with the plugin, the hash and the size, and never put the content in an audit entry, a log line or a problem detail.
- The file is not encrypted at rest: a license is signed by its issuer, not secret.
