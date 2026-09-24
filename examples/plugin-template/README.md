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
| A background job | `NotesConfiguration.java` |
| A page in each cluster, a navigation entry, a panel in every queue's details, live updates | `web/src/` |

## Build

```bash
mvn verify
```

That builds `target/acme-notes-1.0.0.jar` — descriptor, classes, database changes and UI in one jar — and
checks it exactly as Studio will when it is uploaded: the build fails on anything Studio would refuse, and
says how to fix it. Install it from **Administration → Plugins** in Studio.

You need Java 25 and Maven. Node is downloaded by the build.

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

- **No `@Scheduled`, `@Async` or threads of your own.** Contribute a `ScheduledJob` bean; Studio runs it and
  stops it with the plugin.
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
