# ADR-0141: Plugin jars are signed and verified against pinned publisher keys

- **Status**: accepted; amended by [ADR-0166](0166-trusted-publisher-keys-can-be-pinned-by-configuration.md) (keys from configuration)
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi

## Context

A plugin jar runs with the full trust of Studio. Installers are trusted and step up before every
change (ADR-0103), and the validator checks a jar's shape and bytecode (ADR-0099), but nothing
tells an installer who built a jar or whether it was altered after its author released it. A
stolen installer session or a swapped file yields arbitrary code in Studio, and the validator's
allowlist refuses a signed jar outright, because the signature entries are not on it.

Studio's own releases are signed keylessly (Sigstore). A plugin publisher is an organisation with
a key, and many installations are air-gapped, so verification must work offline with what the JDK
already provides.

## Decision

1. **A plugin is a standard signed JAR (`jarsigner`).** The validator opens the jar with
   verification on and reads every entry, so the JDK checks each digest against the manifest and
   the manifest against the signature file. It then requires:
   - exactly one signature-file set (`META-INF/<NAME>.SF` and one `.RSA`, `.EC` or `.DSA` block),
     which join the allowlist; nothing else under `META-INF/` does;
   - that every entry except directories and the signature metadata has exactly one signer, the
     same throughout (`jar-entry-unsigned`, `jar-signers-mixed`);
   - that every per-entry manifest section names an entry that exists (`jar-entry-missing`);
   - that the JDK raises no `SecurityException` (`jar-signature-invalid`).

   An unsigned jar is not a validator error: the report carries no signer and the trust decision
   handles it.
2. **The identity is the key, as a fingerprint**: the SHA-256 of the signer certificate's public
   key (SubjectPublicKeyInfo DER), as upper-case colon-separated hex. It survives a publisher
   re-issuing the certificate for the same key. There are no chains, CAs, expiry, timestamps or
   revocation lists.
3. **Trusted keys live in a kernel table**, `plugin_trusted_key`, with a single-row
   `plugin_trust_policy` holding the `allow_unverified` switch, and `plugin_install` recording each
   version's signer. The switch is not a `SettingDef`: settings are edited under an ordinary
   permission, and this one needs the installer tier with fresh authentication.
4. **The trust decision is computed, never stored**: `TRUSTED(key)`, `UNTRUSTED` or `UNSIGNED`,
   from the jar's signer and the key table at the time it is read. Removing a key therefore flags
   every plugin it signed at once, in the list, the manifest and health, with no fan-out update.
5. **The plan reports trust; activation enforces it**, the pattern `missingRequires` already uses.
   Install, update download, rollback and enable all pass through `beginActivation`, which
   re-plans, so a key removed between review and activation is caught. An untrusted or unsigned jar
   is refused (`plugin-untrusted`, `plugin-unsigned`) unless `allow_unverified` is on. A jar with an
   invalid signature never reaches storage; one validly signed by an unknown key stays a pending
   upload so the review can offer to trust it. An installed plugin whose key was removed keeps
   running, marked unverified, and its next update needs a trusted key.
6. **Confirmation is enforced on the server.** A plan lists `acknowledgements`
   (`permissions-added`, `signer-changed`, `unverified`), and activation without `acknowledge=true`
   answers 409. A change of signer between versions is allowed but shown as old fingerprint to new
   fingerprint.
7. **Keys are added from the review or by PEM.** `POST /api/v1/admin/plugins/keys` takes
   `{name, upload: sha256}` or `{name, pem}`. With `upload` the server takes the key from the
   stored jar, so a client cannot substitute another; with `pem` it accepts an X.509 certificate or
   a public key. The dialog shows the fingerprint and subject and requires the installer to confirm
   they compared it with the publisher's. Adding or removing a key and changing the allowance need
   step-up and are audited, refusals included (`PLUGIN_KEY_ADD`, `PLUGIN_KEY_REMOVE`,
   `PLUGIN_TRUST_POLICY`); an unverified activation records `trust=unverified` and the fingerprint.

`PluginTrustHealthIndicator` joins the `studio` health group and reports `DEGRADED`, naming the
installed unverified plugins. The plugin template signs its build with a `sign` profile, and
`PluginVerifier` names the signer and, given the publisher's certificate, fails unless that key
signed the jar.

This amends the validation list in [ADR-0099](0099-runtime-plugins-are-child-contexts-installed-from-the-ui.md)
(signature entries are allowed, and a jar's signature is checked) and the lifecycle actions in
[ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md) (managing keys and the
allowance are installer-tier, step-up actions, and activation needs acknowledgement when the plan
asks for it). Their other decisions stand.

## Consequences

- **Breaking:** unsigned plugins stop installing. The allowance is the documented escape hatch.
  Plugins already installed keep running, marked unverified, and the `studio` health group is
  `DEGRADED` until they are re-signed or their key is trusted.
- Authors need a key. The template makes signing one command, and a jar signed once keeps its
  identity across certificate renewals.
- Installers carry one new decision: whether a fingerprint really is the publisher's. The dialog,
  the step-up and the audit trail make a careless click visible, not impossible.
- Removing a key is the only revocation. A leaked publisher key is handled by removing it, which
  flags the installed plugins and blocks their updates.
- Studio's tests sign their fixture jars with a test key that the fixtures trust; one path builds an
  unsigned jar on purpose.

## Alternatives considered

- **Sigstore bundles for plugins.** Heavier, needs a trust root and a library, and a plugin
  publisher is an organisation with a key, not an OIDC identity; many installations are air-gapped.
- **A detached Ed25519 or GPG signature.** Two files to upload, and the update download would need
  a second URL; the JDK cannot verify it without a new dependency.
- **Certificate chains and a CA.** Expiry, timestamps and revocation lists are infrastructure
  nobody here would operate; pinning the key gives the same protection against a swapped jar.
- **The allowance as an ordinary setting.** Rejected: settings are edited under an ordinary
  permission, and this switch must need the installer tier with fresh authentication.
- **Storing the trust decision on each plugin.** Rejected: removing a key would need a fan-out
  update, and a missed row would leave a plugin looking trusted.
