## Context

A plugin jar is uploaded raw (`PUT /api/v1/admin/plugins/upload`), checked by `PluginValidator`
(it opens the jar with `JarFile(file, false, …)`), stored content-addressed in `plugin_artifact`,
recorded in `plugin_upload`, planned by `PluginHost.buildPlan` and activated through
`beginActivation`, which re-runs `buildPlan`. The update download (`UpdateChecker`) and rollback
reach the same `inspect`/`buildPlan` path. Installer tier and step-up are private helpers in
`PluginAdministration` (`requireInstaller`, `requireStepUp`), audit goes through its `audited(...)`
(ADR-0103). The permission diff on update already exists (`ContributionDiff`, `PlanReview.tsx`).
Vendor identity on update is a string compare on `descriptor.vendor().name()`.

Today nothing checks who built a jar, and the validator's allowlist refuses a signed jar, because
`META-INF/*.SF` and the signature block are not on it.

## Goals / Non-Goals

**Goals:**
- One signature format that authors produce with the JDK alone and Studio verifies offline with
  the `JarFile` it already uses, with no new dependency.
- Trust is a pinned publisher key, decided at read time, so removing a key takes effect at once.
- Every gate is enforced on the server; the UI only explains it.

**Non-Goals:**
- Sigstore/keyless identities for plugins. 06 uses them for Studio's own releases; a plugin
  publisher is an organisation with a key, and many installations are air-gapped.
- Certificate chains, CAs, expiry, timestamps or revocation lists. Trust is the key, not the certificate.
- Several signers on one jar.
- Re-gating plugins that are already installed at boot or on enable (the spec keeps them running, marked unverified).

## Decisions

1. **Standard signed JAR (`jarsigner`).** The validator opens the jar with `verify=true` and reads
   every entry fully, so the JDK checks each digest against the manifest and the manifest against
   the signature file. It then requires:
   - exactly one signature-file set, `META-INF/<NAME>.SF` plus one `.RSA`, `.EC` or `.DSA` block;
     these entries join the allowlist and nothing else under `META-INF/` does;
   - that every entry except directories and the signature metadata has exactly one `CodeSigner`,
     the same one throughout; otherwise `jar-entry-unsigned` (an added or swapped file) or
     `jar-signers-mixed`;
   - that every per-entry section of the manifest names an entry that exists
     (`jar-entry-missing`, for a removed file);
   - a `SecurityException` from the JDK (a changed entry or manifest) becomes `jar-signature-invalid`.
   The JDK's `jdk.jar.disabledAlgorithms` treats weak algorithms as unsigned, which the entry
   check then refuses. An unsigned jar is not a validator error: the report carries `signer = none`
   and the trust decision handles it.
   *Alternatives:* Sigstore bundles (heavier, needs a trust root and a library, and a publisher is
   an OIDC identity rather than a key); a detached Ed25519 or GPG signature (two files to upload,
   and the update download needs a second URL).
2. **The fingerprint is the SHA-256 of the signer certificate's public key** (SubjectPublicKeyInfo
   DER), shown as upper-case colon-separated hex. It stays stable when a publisher re-issues the
   certificate for the same key. The validator report gains `Signer(fingerprint, subject, publicKey)`.
3. **The trust store is a kernel table, not a setting.** It lives in the new package
   `kernel.plugin.internal.trust`, with changeset `0008-plugin-trust`:
   - `plugin_trusted_key(added_at, fingerprint PK, name, subject, public_key bytea, added_by)`;
   - `plugin_trust_policy`, a single row with `changed_at, changed_by, allow_unverified boolean default false`;
   - `plugin_upload` and `plugin_install` gain `signer_fingerprint` and `signer_subject`, both text and null for unsigned jars.
   The allowance is not a `SettingDef`, because settings are edited under an ordinary permission
   and this switch needs the installer tier with fresh authentication.
4. **The trust decision is computed, never stored.** `PluginTrust.decide(fingerprint)` returns
   `TRUSTED(key)`, `UNTRUSTED` or `UNSIGNED`. Tables store only facts (who signed), so removing a
   key flags every plugin it signed without a fan-out update, in the plugin list, the manifest and health.
5. **Gates sit in `buildPlan`**, so install, update download, rollback and the re-plan inside
   `beginActivation` all pass through them:
   - An untrusted or unsigned jar is refused unless `allow_unverified` is on. The refusal still
     returns the plan's signer, so the review screen can name the fingerprint.
   - A jar whose signature is invalid fails validation and is never stored. A validly signed jar
     from an untrusted key stays a pending upload, so "Trust this key" can re-plan it. Nothing runs
     from a pending upload, and it expires after a day like any other upload.
   - A key removed between review and activation is caught by the re-plan.
   - An installed plugin whose key was removed keeps running, marked unverified. Its next update
     is refused unless that update is signed by a trusted key.
6. **Explicit acknowledgement is enforced on the server.** The plan returns `requiresAcknowledgement`
   with reasons: `permissions-added`, `signer-changed` or `unverified`. Activation without
   `acknowledge=true` answers 409. A signer change between versions is allowed but shown as old
   fingerprint → new fingerprint.
7. **"Trust this key" from the review.** `POST /api/v1/admin/plugins/keys` takes either
   `{name, upload: sha256}` or `{name, pem}`:
   - With `upload`, the server takes the key from the stored jar's signer, so the client cannot
     substitute another key.
   - With `pem`, it accepts an X.509 certificate or a public key.
   - The dialog shows the fingerprint and subject and asks the installer to confirm they compared
     it with the one the publisher publishes.
   - Adding or removing a key and changing the allowance need step-up. Each is audited
     (`PLUGIN_KEY_ADD`, `PLUGIN_KEY_REMOVE`, `PLUGIN_TRUST_POLICY`), including refusals.
   - An unverified activation records `trust=unverified` and the fingerprint in its `PLUGIN_ACTIVATE` event.
8. **Health.** `PluginTrustHealthIndicator` joins the `studio` health group. It reports `DEGRADED`
   with the ids of installed unverified plugins, and `UP` otherwise.
9. **Author kit.**
   - The template's `pom.xml` gains a `sign` profile. It is active when `plugin.signing.keystore`
     is set, and runs `maven-jarsigner-plugin` at `package` with the alias and passwords from
     properties or the environment.
   - The README documents a one-time `keytool -genkeypair -keyalg EC`, and publishing the
     certificate with `keytool -exportcert -rfc`.
   - `PluginVerifier` (`@PluginApi`) prints the signer's fingerprint and subject, and warns when
     the jar is unsigned.
   - With `-Dartemis-studio.plugin.certificate=<pem>`, `PluginVerifier` exits 1 unless the jar was
     signed by that key.
   - CI's `plugin-template` job signs with a throwaway key and verifies against its certificate.
10. **ADR-0140** records decisions 1–7 and amends ADR-0099's validation list and ADR-0103's
    list of lifecycle actions. The `plugins` guide gains a "Signing and trust" section.

## Risks / Trade-offs

- [An installer clicks "Trust this key" without checking] → the dialog shows the fingerprint and
  asks for an explicit confirmation, the action needs step-up and is audited, and the key list shows who added it.
- [A publisher's private key leaks] → removing the key flags installed plugins at once and blocks
  their updates. Revocation infrastructure is out of scope (proposal).
- [The JDK accepts a jar whose signature is valid but covers fewer entries] → the every-entry,
  one-signer check and the missing-entry check close the gap. Tests cover the added, swapped,
  removed and changed-manifest cases.
- [BREAKING: unsigned plugins stop installing] → the allowance is the documented escape hatch.
  Plugins already installed keep running, marked unverified.
- [Studio's own tests upload many jars] → `PluginJarBuilder` signs by default with a test key that
  the test fixtures trust. One test path builds an unsigned jar on purpose.

## Migration Plan

Changeset `0008` adds the tables and nullable columns; installed plugins get a null signer, which
reads as unverified. Nothing else migrates (standing decision: no backward compatibility).
