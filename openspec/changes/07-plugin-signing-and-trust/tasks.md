# Plugin signing and trust: implementation plan

> **For agentic workers:** one `implementer` per task, in order. Steps use
> checkboxes. Every task ends green on its own narrow check and with one commit (Conventional Commits,
> Studio `.claude/rules/05-commits.md`, never mentioning anything outside this repo).

**Goal:** plugin jars are signed with `jarsigner`, verified offline against administrator-managed
publisher keys before activation, with an audited, off-by-default allowance for unverified plugins.

**Architecture:** `PluginValidator` checks the signature and reports the signer. A new
`kernel.plugin.internal.trust` package owns the trusted keys and the allowance, and computes the
decision. `PluginHost.buildPlan` puts the trust facts into `ActivationPlan`, and `beginActivation`
enforces them, together with the acknowledgement. `feature/plugins` exposes keys, the policy and
acknowledgement. The web review screen shows them.

**Tech stack:** Java 25 (`java.util.jar`, `jdk.security.jarsigner` in tests), Spring Boot 4.1,
Liquibase SQL changesets, React 19 + Mantine 9, `maven-jarsigner-plugin` in the template.

**Spec:** `design.md` and `specs/plugin-trust/spec.md` in this change.

## Global constraints

- Fingerprint = SHA-256 of the signer certificate's `getPublicKey().getEncoded()` (SubjectPublicKeyInfo DER),
  upper-case hex, colon-separated (`AB:CD:…`, 95 chars).
- Violation codes: `jar-signature-invalid`, `jar-entry-unsigned`, `jar-signers-mixed`, `jar-entry-missing`,
  `plugin-untrusted`, `plugin-unsigned`, `acknowledgement-required`.
- Acknowledgement reasons (plan field `acknowledgements`): `permissions-added`, `signer-changed`, `unverified`.
- Audit actions: `PLUGIN_KEY_ADD`, `PLUGIN_KEY_REMOVE`, `PLUGIN_TRUST_POLICY`; an unverified activation adds
  `trust=unverified` and `fingerprint` to its `PLUGIN_ACTIVATE`/`PLUGIN_ROLLBACK`/`PLUGIN_ENABLE` params.
  Key and policy actions open the audit row **before** the installer and step-up checks, so refusals are audited.
- Changeset `src/main/resources/db/changelog/kernel/plugin/changes/0008-plugin-trust.sql`, id
  `artemis-studio:kernel-plugin-0008-plugin-trust`, with a `--rollback`. Column order follows CLAUDE.md rule 7.
- No new runtime dependency. No colour literals in components (tokens only), logical CSS properties.
- BREAKING (the commit that gates activation says so with `!` and `BREAKING CHANGE:`): unsigned plugins stop installing by default.

## Review focus

1. An entry appended to a signed jar after signing, including a UI asset under `META-INF/artemis-studio/`, must be refused (`jar-entry-unsigned`). Task 1 tests it.
2. An entry removed after signing must be refused (`jar-entry-missing`), because the JDK alone accepts it. Task 1 tests it.
3. A key removed between plan and activate must refuse activation. Task 3 tests it.
4. `POST /keys {upload}` must take the key from the stored jar, never from the client. Task 4 tests that the stored key equals the jar's signer.
5. Activating without `acknowledge=true` when the plan needs it must answer 409 even if the UI is bypassed. Task 4 tests it.

---

### Task 1: Signature verification in the validator

**Files:**
- Create: `src/main/java/io/github/sudoitir/artemisstudio/kernel/plugin/internal/validation/Signer.java`
- Create: `src/main/java/io/github/sudoitir/artemisstudio/kernel/plugin/internal/validation/JarSignature.java`
- Modify: `.../validation/PluginValidator.java` (open `JarFile` with `verify=true`, call `JarSignature`, allowlist)
- Modify: `.../validation/ValidationReport.java` (add `Signer signer`, nullable)
- Create: `src/test/resources/plugin-signing/publisher.p12`, `other.p12` (PKCS12, EC P-256, alias `publisher`/`other`, password `changeit`, validity 36500 days), made once with
  `keytool -genkeypair -keyalg EC -groupname secp256r1 -alias publisher -dname "CN=Acme Test Publisher" -validity 36500 -storetype PKCS12 -keystore publisher.p12 -storepass changeit`
- Modify: `src/test/java/.../kernel/plugin/support/PluginJarBuilder.java`: `build()` signs with `publisher.p12` by default; add `unsigned()` and `signedBy(String keystoreResource)`.
- Create: `src/test/java/.../kernel/plugin/support/TestSigningKeys.java`: loads the keystores and exposes `PUBLISHER`/`OTHER` as `(PrivateKey, X509Certificate, String fingerprint)`.
- Test: `src/test/java/.../kernel/plugin/internal/validation/JarSignatureTest.java`

**Interfaces:**
- Produces: `record Signer(String fingerprint, String subject, byte[] publicKey)`, with a static `Signer.of(X509Certificate)` that computes the fingerprint per the global constraint, and `static String fingerprint(byte[] spki)`.
- Produces: `ValidationReport.signer()`, which is `null` for an unsigned jar.
- Produces: `PluginJarBuilder.unsigned()`, `PluginJarBuilder.signedBy("plugin-signing/other.p12")`, `TestSigningKeys.PUBLISHER.fingerprint()`.

- [x] 1.1 Write `JarSignatureTest`:
  - signed jar → `signer().fingerprint()` equals `TestSigningKeys.PUBLISHER.fingerprint()`;
  - unsigned → valid report, `signer()==null`;
  - one class byte flipped after signing → `jar-signature-invalid`;
  - an entry appended after signing (both a class and `META-INF/artemis-studio/ui/x.js`) → `jar-entry-unsigned`;
  - an entry deleted after signing → `jar-entry-missing`;
  - a jar re-signed on top by `other` (two `.SF` files) → `jar-signers-mixed`;
  - manifest main attribute changed after signing → `jar-signature-invalid`.
  To tamper, rewrite the signed jar with `ZipInputStream`/`ZipOutputStream`, copying entries and changing one.
- [x] 1.2 Run `./mvnw -q test -Dtest=JarSignatureTest`. Expect FAIL (no `Signer`).
- [x] 1.3 Implement. In `PluginValidator.validate`, open `new JarFile(file, true, ZipFile.OPEN_READ, Runtime.version())`, then run `JarSignature.check(jar, entryNames, violations)` before `checkManifest`. `JarSignature.check`:
  ```java
  // Reading each entry to its end is what makes JarFile verify its digest (JarVerifier).
  for (JarEntry e : list(jar.entries())) { try (InputStream in = jar.getInputStream(e)) { in.transferTo(OutputStream.nullOutputStream()); } }
  // SecurityException from the read → jar-signature-invalid, return null
  ```
  - Signature metadata is any name matching `META-INF/[^/]+\.(SF|RSA|EC|DSA)`. More than one `.SF` → `jar-signers-mixed`.
  - If there are none and no entry has `getCodeSigners()`, return `null` (unsigned).
  - Otherwise every non-directory, non-metadata entry, except `META-INF/MANIFEST.MF`, needs `getCodeSigners()` of length 1 and an equal certificate throughout: `jar-entry-unsigned` or `jar-signers-mixed`.
  - Every key of `jar.getManifest().getEntries()` must be an entry name, else `jar-entry-missing`.
  - Return `Signer.of((X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0))`.
  - Allowlist: add `name.matches("META-INF/[^/]+\\.(SF|RSA|EC|DSA)")`. `PluginJarBuilder` signs with `jdk.security.jarsigner.JarSigner.Builder(privateKey, certPath).digestAlgorithm("SHA-256").signerName("PUBLISHER").build().sign(zipFile, out)`.
- [x] 1.4 Run `./mvnw -q test -Dtest='JarSignatureTest,PluginValidatorTest'`. Expect PASS. The existing validator tests must still pass with signed jars.
- [x] 1.5 Commit `feat(plugins): verify plugin jar signatures and report the signer`.

### Task 2: Trust store

**Files:**
- Create: `src/main/resources/db/changelog/kernel/plugin/changes/0008-plugin-trust.sql`
  ```sql
  CREATE TABLE plugin_trusted_key (added_at timestamptz NOT NULL DEFAULT now(), fingerprint text PRIMARY KEY,
      name text NOT NULL, subject text NOT NULL, public_key bytea NOT NULL, added_by text NOT NULL);
  CREATE TABLE plugin_trust_policy (changed_at timestamptz, id integer PRIMARY KEY CHECK (id = 1),
      changed_by text, allow_unverified boolean NOT NULL DEFAULT false);
  INSERT INTO plugin_trust_policy (id) VALUES (1);
  ALTER TABLE plugin_install ADD COLUMN signer_fingerprint text, ADD COLUMN signer_subject text;
  ```
- Create: `src/main/java/.../kernel/plugin/internal/trust/{package-info.java, TrustedKeyEntity.java, TrustedKeyRepository.java, PluginTrust.java, TrustDecision.java, PublisherKeys.java}`
- Modify: `.../persistence/PluginInstallEntity.java` (the two columns, getters, set in `update(...)`/`beginInstall`)
- Test: `src/test/java/.../kernel/plugin/internal/trust/PluginTrustIT.java` (Testcontainers PG, following the nearest existing `kernel.plugin` IT), `PublisherKeysTest.java`

**Interfaces:**
- Produces: `enum TrustDecision.Status { TRUSTED, UNTRUSTED, UNSIGNED }` and `record TrustDecision(Status status, String fingerprint, String subject, String keyName)`.
- Produces: `PluginTrust` (Spring `@Component`):
  - `TrustDecision decide(Signer signerOrNull)` and `TrustDecision decide(String fingerprintOrNull, String subject)`;
  - `boolean allowUnverified()`, `void setAllowUnverified(boolean allow, String actor)`;
  - `List<TrustedKey> keys()`, `TrustedKey add(String name, Signer key, String actor)`, `boolean remove(String fingerprint)`;
  - `record TrustedKey(String fingerprint, String name, String subject, Instant addedAt, String addedBy)`.
- Produces: `PublisherKeys.parse(String pem) → Signer`. It accepts `-----BEGIN CERTIFICATE-----` (subject from the certificate) or `-----BEGIN PUBLIC KEY-----` (subject `""`), and throws `IllegalArgumentException` with a readable message otherwise.
- Produces: `PluginInstallEntity.getSignerFingerprint()` and `getSignerSubject()`.

- [x] 2.1 Write `PublisherKeysTest`: a certificate PEM exported from `publisher.p12` and its public-key PEM give the same fingerprint as `TestSigningKeys.PUBLISHER`; garbage is rejected. Write `PluginTrustIT`: add, then decide → TRUSTED; remove, then decide → UNTRUSTED; `null` → UNSIGNED; allowance defaults false and round-trips; adding the same fingerprint twice is refused.
- [x] 2.2 Run them. Expect FAIL.
- [x] 2.3 Implement. `TrustedKeyEntity` maps `plugin_trusted_key` (Lombok, `ddl-auto=validate`). The policy is read and written with `JdbcTemplate` (one row). `PluginTrust.add` refuses a duplicate with `PluginRefusedException(new Violation("key-exists", …))`.
- [x] 2.4 Run `./mvnw -q test -Dtest='PublisherKeysTest,PluginTrustIT'`. Expect PASS.
- [x] 2.5 Commit `feat(plugins): trusted publisher keys and the unverified allowance`.

### Task 3: Plan reports trust, activation enforces it

**Files:**
- Modify: `.../host/ActivationPlan.java`. Add `PlanTrust trust` and `List<String> acknowledgements`.
- Create: `.../host/PlanTrust.java`: `record PlanTrust(TrustDecision.Status status, String fingerprint, String subject, String keyName, String previousFingerprint, boolean signerChanged, boolean allowed)`.
- Modify: `.../host/PluginHost.java`:
  - `buildPlan` fills `trust` and `acknowledgements`;
  - `activate`, `activateUpload`, `rollback` and `enable` take `boolean acknowledged`;
  - `beginActivation` refuses `plugin-untrusted`, `plugin-unsigned` and `acknowledgement-required`;
  - `finishSuccess` (and the RESTART branch's store update) records the signer on the install row.
- Modify: `.../host/PluginSummary.java`: add `String signerFingerprint, String signerSubject, boolean verified` (`verified` = decide(...) is TRUSTED, computed in `list()`/`status()`).
- Create: `src/main/java/.../kernel/plugin/internal/trust/PluginTrustHealthIndicator.java`, registered in the `studio` health group the same way `JobsHealthIndicator` is.
- Create: `src/test/java/.../kernel/plugin/support/TrustedTestKey.java` with `static void trust(JdbcTemplate)`. It inserts `TestSigningKeys.PUBLISHER`, and every existing test that activates a plugin calls it in `@BeforeEach`.
- Test: `src/test/java/.../kernel/plugin/internal/host/PluginTrustGateIT.java`

**Interfaces:**
- Consumes: `ValidationReport.signer()`, `PluginTrust`.
- Produces: `PluginHost.activateUpload(String sha256, String actor, boolean acknowledged)`, `rollback(String id, String actor, boolean acknowledged)`, `enable(String id, String actor, boolean acknowledged)`.
- Produces: `ActivationPlan.trust()` and `ActivationPlan.acknowledgements()`.
- `acknowledgements`:
  - `permissions-added` when `diff.permissionsAdded()` is non-empty on an update;
  - `signer-changed` when the previous install's fingerprint is non-null and differs;
  - `unverified` when the status is not TRUSTED and the allowance lets it through.
- `allowed` = TRUSTED, or the allowance is on.

- [x] 3.1 Write `PluginTrustGateIT`:
  - trusted install activates;
  - unsigned and allowance off → `plugin-unsigned`, and the upload stays pending;
  - signed by `other` → `plugin-untrusted`, and the plan's `trust.fingerprint` equals OTHER's;
  - allowance on and unsigned, without acknowledging → `acknowledgement-required`; with it, active;
  - key removed after the plan → activation refused;
  - update that adds a permission → requires `permissions-added`;
  - update signed by a second trusted key → `signer-changed`;
  - rollback to an unsigned previous version with the allowance off → refused;
  - after removing the key, `PluginSummary.verified()` is false and health is `DEGRADED` naming the id.
- [x] 3.2 Run it. Expect FAIL.
- [x] 3.3 Implement. `inspect` no longer forgets an upload because of trust, since `buildPlan` does not throw for it. The health indicator returns `StudioHealth.DEGRADED` with detail `unverified: [ids]` when any installed (non-uninstalled) plugin is not TRUSTED, and `UP` otherwise.
- [x] 3.4 Update every existing test that activates a plugin to call `TrustedTestKey.trust(jdbc)`, and pass `acknowledged` where the new signatures need it. Run `./mvnw -q test -Dtest='*Plugin*'`. Expect PASS.
- [x] 3.5 Commit `feat(plugins)!: refuse unsigned and untrusted plugins unless allowed`, with the footer `BREAKING CHANGE: unsigned plugins no longer install unless an installer allows unverified plugins.`

### Task 4: Admin API for keys, policy and acknowledgement

**Files:**
- Modify: `src/main/java/.../feature/plugins/PluginAdministration.java`:
  - `keys()` needs installer;
  - `addKey(request, name, uploadSha256OrNull, pemOrNull)`, `removeKey(request, fingerprint)` and `setAllowUnverified(request, boolean)` need step-up, with the audit row opened first;
  - `activate`, `rollback` and `enable` take `acknowledge` and add `trust`/`fingerprint` params when unverified.
- Modify: `src/main/java/.../kernel/plugin/internal/host/PluginHost.java`: add `Signer uploadSigner(String sha256)` (materialize, validate, return `report.signer()`, refuse `plugin-unsigned` when null).
- Modify: `.../feature/plugins/web/PluginAdminController.java` and `PluginAdminViews.java`:
  - `GET /api/v1/admin/plugins/keys` → `TrustedKeysView(List<TrustedKeyView> keys, boolean allowUnverified, Map<String,List<String>> signedPlugins)`;
  - `POST /keys` body `AddKeyRequest(String name, String upload, String pem)`, exactly one of `upload`/`pem`;
  - `DELETE /keys/{fingerprint}`;
  - `PUT /trust-policy` body `TrustPolicyRequest(boolean allowUnverified)`;
  - `?acknowledge=true` on activate, rollback and enable;
  - `PluginPlanView` gains `PluginTrustView trust` and `List<String> acknowledgements`;
  - `PluginView` gains `signerFingerprint`, `signerSubject` and `verified`;
  - `acknowledgement-required` maps to 409 wherever `PluginRefusedException` codes are mapped.
- Test: `src/test/java/.../feature/plugins/web/PluginAdminControllerIT.java` (extend)

- [ ] 4.1 Extend the IT:
  - a non-installer adding a key → 403, and a `PLUGIN_KEY_ADD` audit row with outcome failed;
  - an installer without fresh auth → reauthentication required;
  - `POST /keys {upload}` for an OTHER-signed upload stores OTHER's fingerprint, even when the client also sends a `pem`, which is a 400 (exactly one);
  - `PUT /trust-policy` audited;
  - activate without `acknowledge` when required → 409 `acknowledgement-required`;
  - `GET /keys` lists `signedPlugins` per fingerprint.
- [ ] 4.2 Run it. Expect FAIL. Implement. Run `./mvnw -q test -Dtest=PluginAdminControllerIT`. Expect PASS.
- [ ] 4.3 Regenerate the web schema: `./mvnw -q test -Dtest=OpenApiExportTest` (or whatever writes `web/openapi.json`; grep for it), then `cd web && npm run gen:api`.
- [ ] 4.4 Commit `feat(plugins): trusted-key and trust-policy admin API with acknowledged activation`.

### Task 5: Author kit, docs and ADR (needs tasks 1–2)

**Files:**
- Modify: `src/main/java/.../kernel/plugin/PluginVerifier.java`. It prints `Signed by <subject>, key <fingerprint>` or `WARNING [plugin-unsigned] …`. With `-Dartemis-studio.plugin.certificate=<pem path>` it returns 1 unless the jar's fingerprint equals `PublisherKeys.parse(pem)`'s.
- Modify: `examples/plugin-template/pom.xml`. Add a profile `sign`, activated by the property `plugin.signing.keystore`, running `maven-jarsigner-plugin` (version via ctx7/Maven Central, pinned) goal `sign` at `package`. It uses `keystore=${plugin.signing.keystore}`, `alias=${plugin.signing.alias}`, `storepass=${env.PLUGIN_SIGNING_STOREPASS}`, `tsa` unset, before the `verify`-phase `PluginVerifier` run.
- Modify: `examples/plugin-template/README.md`: a "Sign your plugin" section covering the keytool keypair, `-Dplugin.signing.keystore=… -Dplugin.signing.alias=…`, publishing `keytool -exportcert -rfc`, and checking with `-Dartemis-studio.plugin.certificate`.
- Modify: `.github/workflows/ci.yml` `plugin-template` job. Generate a throwaway keystore, build with the sign profile, then run the verifier with the exported certificate.
- Modify: `site/src/guide/plugins.md`: a "Signing and trust" section covering trusted keys, "Trust this key", the allowance, the badge and health.
- Create: `docs/adr/0140-plugin-jars-are-signed-and-verified-against-pinned-keys.md` (Nygard). It records design decisions 1–7 and amends ADR-0099/0103 by link. Add it to `docs/adr/README.md`.
- Test: `src/test/java/.../kernel/plugin/PluginVerifierTest.java` (extend or create): signed + matching cert → 0; other cert → 1; unsigned + cert → 1; unsigned without cert → 0 with a warning.

- [ ] 5.1 Write the verifier tests. Run them: FAIL. Implement. Run: PASS.
- [ ] 5.2 Template: `cd examples/plugin-template && ./mvnw -q -Dplugin.signing.keystore=… verify` (or `mvn`), with a throwaway key, passes.
- [ ] 5.3 Commit `feat(plugin-sdk): sign plugins from the template and check them against the publisher's key`, plus `docs: ADR-0140 and the plugin signing guide`.

### Task 6: Web — review, keys, badges

**Files:**
- Modify: `web/src/features/plugins/api.ts`. Add hooks for keys, add/remove key, the trust policy, and `acknowledge` on activate/rollback/enable.
- Modify: `web/src/features/plugins/PlanReview.tsx`. A "Publisher" section showing:
  - vendor, subject, fingerprint (monospace, copyable);
  - a status Badge (`Verified`/`Unverified`/`Untrusted key`/`Unsigned`);
  - "Signer changed: old → new" when `signerChanged`;
  - for `untrusted` with `canInstall`, a "Trust this key…" button opening `TrustKeyDialog`;
  - added permissions highlighted;
  - when `acknowledgements` is non-empty, a checkbox listing the reasons that gates the Activate button.
- Modify: `web/src/features/plugins/InstallDialog.tsx`. Pass `acknowledge`; after a key is added, invalidate the plan query to re-plan. Also fix the dropped warnings on resume (`warnings: []`) only if the plan endpoint already offers them; otherwise leave it.
- Create: `web/src/features/plugins/TrustKeyDialog.tsx`: name input, fingerprint and subject, and a required "I compared this fingerprint with the one the publisher publishes" checkbox. It posts `{name, upload: sha256}`.
- Create: `web/src/features/plugins/TrustedKeysDialog.tsx` (modelled on `InstallersDialog.tsx`):
  - a list of keys with the plugins each signed;
  - Add (name + PEM textarea);
  - Remove (a confirm that names the plugins that become unverified);
  - an "Allow unverified plugins" Switch with a danger note.
  Opened from `PluginsPanel` next to Installers.
- Modify: `PluginsPanel.tsx` and `PluginDrawer.tsx`. An `Unverified` Badge (`size="xs" variant="light"`, warning token colour) when `!verified`; signer rows in the drawer next to the sha256.
- Modify: `words.ts` for the labels. Test: `PluginsPanel.test.tsx` (extend) plus `PlanReview.test.tsx` (create):
  - the badge renders for `verified:false`;
  - Activate is disabled until the acknowledgement is ticked;
  - "Trust this key…" appears only for untrusted.

- [ ] 6.1 Write the tests. Run `cd web && npx vitest run src/features/plugins`: FAIL. Implement. Run: PASS. Then run `npm run lint && npm run format:check && npx tsc -b`.
- [ ] 6.2 Commit `feat(plugins): show publisher and trust in the review, manage trusted keys, badge unverified plugins`.

### Task 7: Finish

- [ ] 7.1 `just verify` green (via `verifier`).
- [ ] 7.2 Run Studio on its own ports and compose project, then screenshot, light and dark: the review (trusted, untrusted, unverified-allowed), the trusted-keys dialog (empty and with keys), the plugin list with the badge. Stop the stack afterwards.
- [ ] 7.3 One `reviewer` pass over the branch diff (security-critical). Fix the findings.
- [ ] 7.4 Rebase on `origin/main`, push, open the PR, fix CI and Sonar until green, merge.
- [ ] 7.5 `/opsx:archive` via its own PR. Tick 07 in the workspace `ROADMAP.md` via a workspace PR. Remove the worktree and branches.
