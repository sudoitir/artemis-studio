---
title: Plugins
description: Install, update and remove plugins from the Artemis Studio UI — screens, API, assistant tools and data of their own, most with no restart — and build your own from the template.
---

# Plugins

A plugin adds to Studio what Studio does not do itself: screens, an API, assistant tools, settings,
background jobs and data of its own. It is **one `.jar`**, installed from **Administration → Plugins**.
You see everything it will be able to do before anything is installed, and most plugins start
**without a restart**.

::: warning A plugin is trusted code
A plugin runs inside Studio, with Studio's access to your brokers and database, and its screens act
with the rights of whoever views them. Studio checks every jar thoroughly before installing it, but
those checks stop accidents and misuse of the contract, not a determined attacker. Install plugins
you would run as part of Studio itself.
:::

## Install a plugin

Drop the `.jar` anywhere on **Administration → Plugins**, or choose **Install plugin**. Four steps
follow, and nothing is installed until the third:

1. **Inspect.** Studio reads the jar without running any of its code. It checks the descriptor,
   every class, and every database change. If Studio would refuse the jar, you see every reason at
   once, each with what its author must change, and **Copy report** gives it to them. Nothing is
   stored.
2. **Review: what this plugin will be able to do.** Each capability is stated as a sentence:
   - screens, assistant tools (read-only or changing things), permissions and settings
   - the database changes, with **Show the SQL**
   - what it depends on
   - for an update, what changes: permissions added or removed, and how many roles lose one
3. **Confirm.** The review says who is affected and for how long. You confirm it is you (below)
   and type the plugin's id. The button names the exact action, for example "Update Notes to 1.5.0
   (3 database changes)".
4. **Progress.** A timeline follows the activation as it runs. You can close the dialog: the
   activation carries on, and the plugin's row shows where it has got to.

A plugin's screens appear after you reload Studio. Anyone else who has Studio open is told that
plugins changed and offered a reload; nothing reloads on its own.

### Confirming it is you

Anything that changes a plugin runs code on the server, so it needs a sign-in within the last
**five minutes**:

- **Password accounts** re-enter their password. After five wrong attempts, the session ends.
- **Single sign-on accounts** sign in again at their identity provider, which asks for a fresh
  login. You return to where you were, with nothing you reviewed lost. The provider must report
  when you signed in (`auth_time`). If it does not, the step-up is refused.

Uploading and reviewing need no confirmation, because nothing runs until you activate.

## What needs a restart

Every change is classified before you confirm it, and does no more than it says:

| Class | When | What happens | Downtime |
| --- | --- | --- | --- |
| **Instant** | No database change is pending | The new version starts while the old one keeps serving. Requests switch over once it is ready, and the old version finishes its work and stops. If the new version fails to start, the old one keeps serving. | None |
| **Brief maintenance** | The update changes the plugin's database | For a few seconds that plugin alone answers "updating", while its work drains, its schema changes and the new version starts. | Seconds, that plugin only |
| **Restart** | The plugin says it needs one, or a version did not stop cleanly | The version to start is recorded and starts with Studio. | All of Studio, briefly |

**When a restart is needed, Studio restarts itself if something will start it again.** It stops
gracefully: every plugin is closed and the stop is recorded as clean. Then it exits for its
supervisor to restart it. That works:

- **with the compose files**, which set `ARTEMIS_STUDIO_PLUGINS_RESTART_SUPERVISED=true` next to
  `restart: unless-stopped`. Keep those two together: remove one and you must remove the other.
- **on Kubernetes**, which Studio detects.
- **anywhere else**, once you set `artemis-studio.plugins.restart.supervised=true`, if your process
  manager restarts Studio when it exits.

Otherwise Studio does not stop itself. Exiting would leave it down, so it shows the command to run
instead (`docker compose restart studio`). The review tells you in advance which of the two will
happen.

Installers can also restart Studio from the Plugins tab, for example after a plugin that did not
stop cleanly. That needs the same confirmation as any plugin change, and it is refused within two
minutes of a start, so nothing can hold Studio in a restart loop.

## Update and roll back

- **Upload the new version** the same way as a new plugin. The review shows the difference from
  the version installed. A plugin can only be updated by a jar from the **same vendor**, and never
  to an **older** version: use Roll back for that.
- **Check for updates** asks each plugin's update URL, if it names one, for a newer version. The
  check happens only when you ask; Studio never contacts anything on its own. An update is
  downloaded over https only, without following redirects, and must match the checksum its vendor
  published. Then it goes through the same inspection and review as an upload.
- **Roll back** reactivates the version that ran before, instantly, as long as the current version
  **changed no database**. A database change can't be undone reliably once data has been written
  under it, so after one, Roll back is unavailable and says why. The review warns about this
  before you confirm, and flags every change that has no rollback of its own: take a backup first.

## Disable, uninstall, purge

| | Its screens, API, tools, jobs | Its data |
| --- | --- | --- |
| **Disable** | Stop; Enable brings them back | Kept |
| **Uninstall** | Removed | Kept, so installing it again picks the data up |
| **Purge** (after Uninstall) | — | **Deleted for good**: its schema, the roles' grants of its permissions, its saved settings, its stored jars |

Purge shows its reach first: each table with its approximate rows and size. You then type the
plugin's id. If another plugin requires the one you are disabling, Studio says which and offers to
disable them together.

## Who can install plugins

Installing a plugin runs its code, so this is **not a role permission**. No role and no wildcard
permission lets anyone install, not even one that can edit every role. Instead a short list of
**installers** can. It is checked on every request, so removing someone takes effect on their next
click.

- The **administrator Studio creates on first start** is the first installer.
- Or name them in `artemis-studio.plugins.initial-installers`, as a username, or
  `<registration-id>:<subject>` for a single sign-on user. This list is used only while nobody is an
  installer; after that, installers manage installers.
- An installer adds or removes others under **Who can install** on the Plugins tab, after
  confirming it is them. The last installer cannot be removed.
- Administrators who are not installers see everything, with the actions disabled and the reason
  beside them.

Plugins are only ever changed **from a browser session**. API tokens and the MCP endpoint are
refused, even an installer's.

## Security, in short

- **Checked before it is stored.** Studio reads the whole jar's bytecode without loading it.
  - **Refused shapes:** unexpected files; archive tricks such as duplicate or escaping entries,
    oversized entries and extreme compression ratios; and manifest attributes that change how the
    JVM loads code.
  - **Confinement:** classes outside the plugin's own package, and names outside its namespace.
  - **Refused calls:** `System.exit`, starting processes, `@Scheduled`, `@Async`, and database
    changes that run a command or a Java class.
- **Its data is its own.** Each plugin has its own database schema and a pool of 3 connections. It
  may not create anything in Studio's schema or reference Studio's tables. Activation fails if it
  tries, and the previous version resumes.
- **It uses Studio's permissions.** A plugin's API sits behind Studio's sign-in, and its
  permissions are granted through roles like any built-in's.
- **Everything is audited.** Every upload, activation, change and removal is in the audit log with
  who, from where, the jar's checksum and the outcome. Each step is also written to Studio's log,
  because a plugin shares Studio's database role and could, in principle, change the audit table.
- **The kill switch.** `artemis-studio.plugins.upload.enabled=false` switches installing and
  updating off. Installed plugins keep running, and can still be disabled and removed.

## Capacity

Each active plugin takes up to 3 database connections, next to Studio's own 10. Activation is
refused, with the reason, once that would exceed 80% of Postgres' `max_connections`. The Plugins tab
shows the current count.

When a plugin stops, its classes should leave memory. If a stopped version is still in memory a
minute later, the Plugins tab says so and recommends a restart. The compose files cap metaspace at
256 MB (`-XX:MaxMetaspaceSize=256m`), so a plugin that does not unload cleanly fails loudly rather
than growing without limit.

## Configuration

| Property (environment variable) | Default | |
| --- | --- | --- |
| `artemis-studio.plugins.upload.enabled` (`ARTEMIS_STUDIO_PLUGINS_UPLOAD_ENABLED`) | `true` | `false` switches installing and updating off |
| `artemis-studio.plugins.initial-installers` | — | Who can install while nobody can: usernames or `registration-id:subject` |
| `artemis-studio.plugins.restart.supervised` (`ARTEMIS_STUDIO_PLUGINS_RESTART_SUPERVISED`) | unset: `true` on Kubernetes | Something starts Studio again after it exits |
| `artemis-studio.plugins.safe-mode` (`ARTEMIS_STUDIO_PLUGINS_SAFE_MODE`) | `false` | Start without any plugin |
| `artemis-studio.plugins.start-timeout-seconds` | `60` | How long a plugin gets to start |
| `artemis-studio.features.plugins.enabled` | `true` | `false` removes the Plugins tab and its API; installed plugins still run |

## When something goes wrong

A plugin can never stop Studio from starting. Studio is already serving before any plugin starts,
and each plugin starts on its own, within a time limit.

| What you see | Why, and what to do |
| --- | --- |
| **"This jar cannot be installed"**, with a list | Studio would refuse it. Nothing was stored. **Copy report** for the plugin's author. |
| **Failed**, with a reason | It did not start. If an update failed, the previous version kept serving. Retry, upload a fixed version, or uninstall. |
| **Failed: "schema at version X"** | The update changed its database, the new version then failed to start, and the change could not be undone. Upload a fixed version, or restore from backup. |
| **Incompatible** | It does not support this version of Studio (its `since`/`until`). Update the plugin, or uninstall it. It becomes active again by itself if Studio moves back into its range. |
| **Restart required** | See [What needs a restart](#what-needs-a-restart). |
| **Refused: "another plugin operation is in progress"** | One change at a time, across all plugins. Wait for it to finish. |
| **Refused: connection budget** | Disable another plugin, or raise Postgres' `max_connections`. |
| **A plugin's page says it could not show its screens** | The plugin is running, but its UI failed to load in this browser. The rest of Studio is unaffected. Reload, then check the plugin's row. |
| **Safe mode: no plugin is running** | Studio stopped uncleanly three times in 15 minutes, so it started without plugins. Or `safe-mode` is set. Fix or remove the plugin that failed, then restart normally. |

## Build a plugin

Start from the template:
[`examples/plugin-template`](https://github.com/sudoitir/artemis-studio/tree/main/examples/plugin-template).
It is a complete plugin, **Notes**, notes operators leave on queues, with every kind of contribution:

- an entity in its own schema, with a reversible changelog
- an audited service
- an API behind Studio's permissions
- an assistant tool
- a setting and a background job
- a page in every cluster, a navigation entry, a panel in every queue's details, and live updates

```bash
mvn verify
```

That builds the plugin into one jar: descriptor, classes, database changes and UI. Then it checks the
jar **exactly as Studio will on upload**, so a refusal shows up in your build, not in front of an
operator.

**What a plugin builds against:**

- **Java.** `io.github.sudoitir:artemis-studio` from Maven Central, `provided`. Use only the types
  marked `@PluginApi`: those are kept stable within a contract version. Anything else can change in
  any release.
- **UI.** [`@artemis-studio/plugin-sdk`](https://www.npmjs.com/package/@artemis-studio/plugin-sdk)
  from npm. Its Vite preset, `studioPlugin({ id })`, builds a Module Federation bundle that takes
  React, Mantine and TanStack from Studio, so one bundle is small and matches Studio's look.
- **Versions.** Build against the Studio and SDK version of the **oldest** Studio you support. It
  becomes your `studio.since`, and Studio refuses the plugin on anything older.

**The rules Studio enforces** (the template's README explains each):

- **Its namespace.** One id, lowercase kebab-case with your organisation first (`acme-notes`),
  prefixes everything the plugin adds:
  - its API: `/api/v1/p/<id>` and `/api/v1/clusters/{clusterId}/p/<id>`
  - its routes: `p/<id>/…`
  - its permissions (`<id>:…`), settings (`<id>.…`), live topics and assistant tools
    (`<id_in_snake_case>_…`)
- **Its own package.** Classes live only under its `basePackage`. A library it bundles must be
  shaded and relocated under it.
- **Studio runs its threads.** No `@Scheduled`, `@Async` or threads of its own: contribute a
  `ScheduledJob`, and Studio runs it and stops it with the plugin.
- **Changes are audited.** Audit every change with `AuditService`, like Studio's own features do.
- **Its data is its own.** Its tables live in its own schema, with no foreign keys to Studio's. Give
  every changeset a rollback, or updates that apply it cannot be rolled back.

### Messages and secrets

A plugin can react to messages, send them and keep credentials without a client, thread or store of
its own. Inject the two beans Studio puts into every plugin's context; each is bound to that plugin
and acts only on its own data.

**`PluginMessaging`** registers what the plugin wants delivered, and Studio makes it true on every
serving node, after every restart and failover, and undoes it when the registration is removed or
the plugin stops running:

```java
@Component
class Orders implements PluginMessageHandler {

    Orders(PluginMessaging messaging, ...) { ... }

    void watch(UUID clusterId, UUID operatorId) {
        messaging.register(new RegistrationSpec(
                "orders", clusterId, "ORDERS.IN", RegistrationMode.TAP, operatorId));
    }

    @Override
    public Disposition onMessage(PluginMessage message) {
        // message.body(), .headers(), .properties(), .deliveryCount()
        return Disposition.ACCEPT;
    }
}
```

- **`TAP`** delivers a copy of every message routed to the queue. Existing consumers and producers
  are untouched. If the plugin falls behind, the oldest copies are dropped, and
  `registration(key).droppedCopies()` says how many. It needs the acting user to hold
  `message:read`, and Studio's broker role to be set
  (`artemis-studio.capture.broker-role`, as for capture).
- **`CONSUME`** makes the plugin one of the queue's consumers. `ACCEPT` removes the message.
  `REJECT`, an exception, or the plugin stopping first leaves it for redelivery, within the broker's
  `max-delivery-attempts`. It needs `message:read` and `queue:purge`.
- **`send(OutboundMessage)`** sends a body, headers and properties to an address. It needs
  `message:send`.
- **`checkSend(clusterId, address, actingUserId)`** answers why such a send would be refused (a
  reserved address, an unknown cluster, a missing `message:send`) without sending, so a plugin can
  reject a bad target when a user configures it. `send` still checks every message.
- **Every registration acts for a user**, whose grants are checked when it is made and on every pass
  after (`artemis-studio.plugins.messaging.reconcile-interval`, 10 s by default). If the user loses
  a permission, the registration is `SUSPENDED` and says why. It resumes when the permission
  returns.
- Studio calls the handler on its own threads, one message at a time per registration and node.
  Keep it bounded: a handler that blocks holds only its own registration's delivery.
- Queues under Studio's own prefixes and the broker's management addresses are refused.

**`PluginSecrets`** stores named values encrypted with Studio's secret key: `put`, `get`, `delete`
and `list` (names and dates only). No Studio interface returns a value, and a plugin should keep it
that way: accept secrets, never echo them. Purging the plugin deletes them.

**Offer updates** by naming an `updateUrl` (https) in `plugin.json` that answers:

```json
{ "version": "1.5.0", "url": "https://acme.example/acme-notes-1.5.0.jar", "sha256": "…", "changeNotes": "…" }
```

Studio asks only when an installer chooses **Check for updates**. It downloads the jar without
following redirects, and refuses it unless it hashes to that `sha256`.

## How it works

Each plugin runs in its own Spring context, with its own class loader, schema and connection pool.
One gateway routes its API, and Studio's registries take and drop its contributions at runtime. Its
UI is a Module Federation bundle loaded when Studio starts. The design and its trade-offs are in
[ADR-0099](/reference/adr/0099-runtime-plugins-are-child-contexts-installed-from-the-ui) (the
runtime), [ADR-0100](/reference/adr/0100-plugin-uis-are-module-federation-remotes) (the UI),
[ADR-0101](/reference/adr/0101-each-plugin-owns-a-schema-pool-and-entity-manager) (the data),
[ADR-0102](/reference/adr/0102-the-plugin-api-is-published-to-central-and-npm) (the API),
[ADR-0103](/reference/adr/0103-plugin-installer-tier-and-step-up-reauthentication) (who can install),
[ADR-0104](/reference/adr/0104-studio-restarts-itself-for-plugins-when-supervised) (restarts)
and [ADR-0111](/reference/adr/0111-plugin-scoped-beans-and-plugin-messaging) (messages and secrets).
