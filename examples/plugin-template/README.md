# Artemis Studio plugin template

A complete, working plugin — **Notes**, notes operators leave on queues — to copy and make your own.
It shows every kind of contribution a plugin can make:

| What | Where |
| --- | --- |
| Its descriptor: id, version, what it adds, which Studio it supports | `src/main/resources/META-INF/artemis-studio/plugin.json` |
| Its own database schema, migrated on activation, with rollbacks | `src/main/resources/db/changelog/plugin/acme-notes/` |
| A JPA entity and an audited service | `Note.java`, `NotesService.java` |
| An API behind Studio's permissions (`acme-notes:read`, `acme-notes:write`) | `NotesController.java` |
| An assistant (MCP) tool | `NotesTools.java` |
| A setting on Studio's Settings page | `NotesSettings.java` |
| Its table under Studio's data retention, quotas and purge (Administration → Data) | `NotesStore.java` |
| A page in each cluster, a navigation entry, a panel in every queue's details, live updates | `web/src/` |

## Build

```bash
mvn verify
```

That builds `target/acme-notes-1.0.0.jar` — descriptor, classes, database changes and UI in one jar — and
checks it exactly as Studio will when it is uploaded: the build fails on anything Studio would refuse, and
says how to fix it. Install it from **Administration → Plugins** in Studio.

You need Java 25 and Maven. Node is downloaded by the build.

## Sign your plugin

Studio installs only a plugin it can tell who built. Sign the jar with a key of your own; an installer
trusts your key once and every later version you sign is accepted. An unsigned jar is refused unless an
installer switches on **Allow unverified plugins** in Administration → Plugins.

1. **Make a key, once.** Keep the keystore private and back it up: losing it means your users must trust a
   new key.

   ```bash
   keytool -genkeypair -alias acme -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
       -validity 36500 -dname "CN=Acme Ltd, O=Acme" -keystore acme.p12 -storetype PKCS12
   ```

2. **Build with it.** The password comes from the environment so it never lands in shell history or the pom:

   ```bash
   export PLUGIN_SIGNING_STOREPASS=…
   mvn verify -Dplugin.signing.keystore=acme.p12 -Dplugin.signing.alias=acme
   ```

   The build signs the jar and then checks it as Studio will; it prints `Signed by …, key <fingerprint>`.
   Without `plugin.signing.keystore` the jar is unsigned and the build warns `[plugin-unsigned]`.

3. **Publish your certificate**, next to your downloads, so installers can compare its fingerprint:

   ```bash
   keytool -exportcert -rfc -alias acme -keystore acme.p12 -file acme.pem
   ```

4. **Check a build against it.** The build fails unless the jar was signed by that key, and also when it is
   unsigned:

   ```bash
   mvn verify -Dplugin.signing.keystore=acme.p12 -Dplugin.signing.alias=acme -Dartemis-studio.plugin.certificate=acme.pem
   ```

An installer sees your key's fingerprint on the review screen, compares it with the one you published, and
chooses **Trust this key**; keys are managed under Administration → Plugins. The fingerprint is that of the
key, so renewing the certificate for the same key changes nothing. Sign every version with the same key: an
update signed by a different trusted key is allowed, but the installer must confirm the change.

## Make it yours

1. Pick an id: lowercase kebab-case, your organisation first, as in `acme-notes`. Rename it everywhere —
   `plugin.json`, the changelog directory, `@RequestMapping`, the permission, setting, topic and tool names,
   and `web/src/api.ts`. Rename the package `com.acme.notes` and set `basePackage` and `configuration`.
2. Set `studio.version` in `pom.xml` to the **oldest** Studio you support. The plugin compiles against that
   version's API and Studio refuses it on anything older. Use the same version of `@artemis-studio/plugin-sdk`
   in `web/package.json`.
3. Keep to your namespace. Studio refuses a plugin whose classes are outside its `basePackage`, whose API is
   outside `/api/v1/p/<id>` and `/api/v1/clusters/{clusterId}/p/<id>`, whose routes are outside `p/<id>`, or
   whose permissions, settings, topics and tools are not prefixed with its id.
4. Use only types marked `@PluginApi` from Studio: those are kept stable within a contract version. Anything
   else can change in any release.

## Rules worth knowing before you write code

- **A paid plugin can ask for a license.** Add `"requiresLicense": true` to `plugin.json` and Studio stores the
  file an administrator uploads, shows its state on the Plugins tab and tells the plugin when it changes. Inject
  `PluginLicense`, read `file()`, check it your own way and `report(...)` the verdict. Notes needs none, so the
  descriptor leaves it out. See Licensing in the plugin guide.
- **A table that grows with use is a store.** Contribute a `HousekeepingContributor`, the way `NotesStore`
  does, rather than a pruning job: operators then see it, set its retention and quota, and preview a purge on
  Administration → Data, and Studio purges it in small batches on one instance and audits each purge.
- **No `@Scheduled`, `@Async` or threads of your own.** Contribute a `ScheduledJob` bean; Studio runs it and
  stops it with the plugin. Give it a scope: `INSTALLATION` for work on shared rows (it runs on one Studio
  instance per tick), `INSTANCE` for work on this instance's own state (it runs everywhere).
- **Describe every permission, and declare its scope.** Each `permissions` entry needs a
  `description`, which the role editor shows, and a `scope`. A permission your guards check without a cluster
  (`@perm.can('acme-notes:admin')`) takes effect only through a global grant, so declare it `"scope": "global"`;
  one checked against a cluster is `"cluster"`; one checked against a queue or address is `"resource"` and also
  names the `resourceKinds` (`"queue"`, `"address"`) it acts on. A permission that is only useful together with
  another lists it under `requires` (`acme-notes:write` requires `acme-notes:read`), and the role editor adds it.
  The role editor and the effective-permissions preview show the scope. Studio's
  `permissions` health check (in `/actuator/health/studio`) reports a guard or manifest entry naming a
  permission you did not declare.
- **Audit every change** with `AuditService`, the way `NotesService` does. Operators rely on Studio's audit
  log being complete.
- **Your schema is yours alone.** No foreign keys into Studio's tables and nothing created outside your schema;
  store ids and handle their deletion.
- **Give every changeset a rollback.** An update that applies one without a rollback cannot be rolled back, and
  the review screen says so before anyone confirms it.
- **Bundling a library?** Studio refuses classes outside your `basePackage`, so shade it and relocate it under
  your package:

  ```xml
  <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <executions>
          <execution>
              <phase>package</phase>
              <goals><goal>shade</goal></goals>
              <configuration>
                  <createDependencyReducedPom>false</createDependencyReducedPom>
                  <artifactSet><includes><include>org.example:some-library</include></includes></artifactSet>
                  <relocations>
                      <relocation>
                          <pattern>org.example</pattern>
                          <shadedPattern>com.acme.notes.shaded.org.example</shadedPattern>
                      </relocation>
                  </relocations>
              </configuration>
          </execution>
      </executions>
  </plugin>
  ```
- **In the UI, import from `@artemis-studio/plugin-sdk`, `@mantine/core`, `@mantine/hooks`, React and TanStack
  only** — Studio shares those with your bundle, and the build refuses anything else from Mantine. Show
  notifications with the SDK's `notify`.

The full guide: <https://sudoitir.github.io/artemis-studio/guide/plugins>.
