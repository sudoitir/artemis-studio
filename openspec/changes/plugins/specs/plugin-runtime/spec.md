## Purpose

Lets an authorised operator extend an installed Studio with third-party plugins. A plugin contributes screens, API, assistant tools and its own data. It is installed, updated, rolled back and removed from the UI, and a faulty plugin can never take Studio down.

## ADDED Requirements

### Requirement: A plugin is one self-describing artifact

A plugin SHALL be delivered as a single jar containing a descriptor that states:
- its identifier, name, version, vendor and description
- its change notes
- its base package and root configuration
- the extension-contract version it was built against, and the range of Studio versions it supports (a minimum, and optionally a maximum)
- the other features it requires
- whether it has a UI
- whether activating it needs a restart
- an optional update URL
- the permissions, setting keys, stream topics and assistant tools it contributes

The system SHALL read the descriptor without executing any code from the plugin.

A plugin identifier SHALL consist of at least two lowercase kebab segments, SHALL be at most 50 characters, and SHALL NOT begin with `identity-`.

#### Scenario: The descriptor is read without running plugin code
- **WHEN** a jar whose classes have static initialisers is inspected
- **THEN** its descriptor and contributions are reported and none of its code has run

#### Scenario: A single-segment identifier is refused
- **WHEN** a plugin declares the identifier `notes`
- **THEN** it is refused with a message that the identifier needs a vendor segment, for example `acme-notes`

### Requirement: A plugin is inspected before anything is stored or run

The system SHALL validate an uploaded plugin and SHALL refuse it, listing every violation in plain language together with what the author must change, when:
- the descriptor is invalid;
- the jar contains an entry outside its declared base package, its own changelog directory or its own descriptor directory;
- the jar's manifest declares a class path, an agent, module opens or exports, or native access;
- the archive exceeds 50 MB compressed or 250 MB uncompressed, holds more than 20 000 entries, has an entry compressed beyond 100:1, contains duplicate, absolute, backslash or parent-relative entry names, or nests an archive;
- its classes overlap Studio's own packages, a platform library's or another plugin's;
- a contribution falls outside the plugin's namespace;
- it was built against a different contract version;
- the running Studio version is outside its supported range;
- it uses a forbidden construct: process exit or halt, process execution, thread stop, changing the context class loader, storing into the HTTP session, its own scheduling or async annotations, auto-configuration, or component scanning outside its package;
- a database changeset is non-transactional, or differs from one already applied.

A refused plugin SHALL leave nothing stored.

#### Scenario: Every violation is listed
- **WHEN** a jar has an entry outside its package and was built for another contract version
- **THEN** the upload is refused with `422` and both violations are listed, each with the change the author must make

#### Scenario: An incompatible Studio version is named
- **WHEN** a plugin requires a newer Studio than the one running
- **THEN** the refusal names the plugin's minimum version and the running version

#### Scenario: A zip-slip entry is refused
- **WHEN** a jar contains an entry named `../../etc/passwd`
- **THEN** the upload is refused and nothing is written outside the system's temporary storage

### Requirement: Plugin contributions live in reserved namespaces

A plugin's contributions SHALL be confined to its identifier's namespace, so that no plugin can collide with a built-in feature, a future built-in feature, or another plugin:
- permissions `<id>:…`
- setting keys `<id>.…`
- stream topics `<id>` or `<id>.…`
- assistant tools, resources and prompts `<id_with_underscores>_…`
- HTTP paths under `/api/v1/p/<id>/` or `/api/v1/clusters/{clusterId}/p/<id>/`
- configuration properties under `artemis-studio.plugins.<id>.`
- UI routes under `/p/<id>/` or `/clusters/{clusterId}/p/<id>/`

#### Scenario: A plugin cannot claim a core permission
- **WHEN** a plugin declares the permission `queue:purge`
- **THEN** it is refused as outside its namespace

### Requirement: Only installers manage plugins, interactively and freshly authenticated

The following actions SHALL be restricted to users in the installer tier:
- uploading, activating, updating and rolling back a plugin
- enabling, disabling and uninstalling a plugin
- purging a plugin's data
- changing who is an installer

Each of these actions except the upload SHALL additionally require that the user authenticated within the last 5 minutes. When that is not the case the system SHALL answer `401` with problem type `reauth-required`.

Callers authenticated by an API token, including the assistant surface, SHALL be refused with `403` problem type `plugin-install-interactive-only`.

At most one activation SHALL be in progress at a time, and a user SHALL be limited to 5 uploads per hour. Past that limit the system SHALL answer `429`.

Operators SHALL be able to turn plugin installation off at deploy time. The installing controls SHALL then remain visible, disabled, with the reason.

#### Scenario: A stale session cannot activate
- **WHEN** an installer who signed in an hour ago confirms an activation
- **THEN** the request is refused with `401 reauth-required`, and succeeds after the installer re-authenticates

#### Scenario: A token cannot install
- **WHEN** a request authenticated by a personal API token uploads a plugin
- **THEN** it is refused with `403 plugin-install-interactive-only`

### Requirement: Uploading shows what a plugin will be able to do before it is activated

Uploading SHALL store the plugin inert, as pending, and SHALL present a review. The review SHALL show:
- the plugin's identity and version
- its compatibility
- what it will contribute, in words: screens, API paths, assistant tools with their read or write posture, permissions, a new data schema and its tables, and dependencies
- the activation class that confirming will trigger, with its expected downtime

For an update the review SHALL also show:
- what is added and removed
- how many roles grant a permission the new version removes
- the change notes
- the database changes as generated SQL
- whether those changes can be reversed

Activation SHALL be confirmed by typing the plugin's identifier.

#### Scenario: A removed permission's impact is counted
- **WHEN** an update removes a permission that two roles grant
- **THEN** the review states that two roles grant it and will lose it

#### Scenario: An irreversible database change is flagged
- **WHEN** an update contains a changeset without a rollback
- **THEN** the review marks it irreversible, states that rollback will not be available, and advises taking a backup first

### Requirement: Every plugin change is classified and never interrupts more than it states

Before confirmation the system SHALL classify every activation, update, enable, disable, uninstall and rollback as one of the following, and SHALL behave as the classification states:

- **Instant.** No pending database changes. The new version starts while the old one keeps serving; only once the new version is ready are requests switched to it, and the old version then finishes its in-flight work and stops. If the new version fails to start, the old version keeps serving.
- **Brief maintenance.** Pending database changes. The plugin alone answers `503` with problem type `plugin-updating` and a `Retry-After` header while its in-flight work drains, its database changes apply, and the new version starts. The rest of Studio is unaffected.
- **Restart.** The plugin declares that it needs a restart, or a previous version did not stop cleanly. The system SHALL mark the plugin as needing a restart and state the exact command; it SHALL NOT restart Studio itself.

#### Scenario: An update without database changes has no downtime
- **WHEN** an installer updates a plugin whose new version has no pending changesets
- **THEN** requests to the plugin are answered throughout, and after the switch they are served by the new version

#### Scenario: A failed start keeps the old version serving
- **WHEN** the new version of a plugin fails to start
- **THEN** the previous version keeps serving, and the plugin is shown as failed with the cause

#### Scenario: Database changes pause only that plugin
- **WHEN** an update with pending changesets is activated
- **THEN** that plugin's API answers `503 plugin-updating` with `Retry-After` until the new version is active, while every other screen and API keeps working

### Requirement: A plugin's data is isolated and never constrains Studio

Each plugin SHALL keep its data in its own database schema, reached through its own bounded set of database connections. An activation SHALL fail, and the previous version SHALL resume, when a plugin's database changes:
- create any object in Studio's own schema; or
- create a foreign key from the plugin's schema to Studio's schema.

A plugin SHALL be refused activation with a stated reason when activating it would take the installation's database connections above 80 % of the server's limit.

#### Scenario: A plugin cannot block cluster deletion
- **WHEN** a plugin's changeset declares a foreign key to the cluster table
- **THEN** the activation fails with a message that plugins may not reference Studio's tables, and the previous version resumes

### Requirement: Database changes fail safely

A failing plugin changeset SHALL NOT be left half-applied.

When a fresh activation fails after its database changes applied:
- if every applied change declared a rollback, the changes SHALL be rolled back and the previous version SHALL resume;
- otherwise the plugin SHALL be marked failed with "schema at version X", and retry, upload a fix, and uninstall SHALL be offered.

A lock left behind by an interrupted migration SHALL be released safely on the next activation.

A migration SHALL wait at most 10 seconds for a database lock before failing.

#### Scenario: A reversible failure resumes the previous version
- **WHEN** version 1.5 applies reversible changes and then fails to start
- **THEN** the changes are rolled back and version 1.4 serves again

### Requirement: Rollback returns to the previous code

The system SHALL keep the previous artifact of each plugin and SHALL offer rolling back to it when the current version applied no database changes. When database changes were applied, the rollback control SHALL be disabled and SHALL state why.

#### Scenario: Rollback after a schema change is explained
- **WHEN** the current version changed the database
- **THEN** the rollback control is disabled and states that the database changed and a fixed version must be uploaded or a backup restored

### Requirement: Studio always starts, whatever its plugins do

A plugin SHALL NEVER prevent Studio from starting or serving its built-in features. The system SHALL quarantine a plugin, showing the reason and a fix action, when:

- it is invalid;
- the running Studio version is outside its supported range: the plugin is shown as incompatible;
- it fails, or takes longer than 60 seconds, to start;
- its stored artifact fails an integrity check.

A crash during a plugin's activation SHALL mark that plugin failed on the next start, rather than retrying it. After 3 unclean stops within 15 minutes the system SHALL start with no plugin running, and SHALL say so to administrators.

Operators SHALL be able to force this safe mode at deploy time.

#### Scenario: An incompatible plugin after a Studio upgrade
- **WHEN** Studio is upgraded past a plugin's maximum supported version
- **THEN** Studio starts, the plugin is not started, and it is shown as incompatible with Update and Uninstall offered

#### Scenario: A crash loop ends in safe mode
- **WHEN** Studio stops uncleanly three times within fifteen minutes
- **THEN** the next start runs no plugin and administrators see a banner explaining safe mode

### Requirement: A plugin's security annotations are enforced or the plugin does not run

Method-security, transaction and validation annotations in a plugin SHALL be enforced exactly as in a built-in feature. If any annotated component would not be enforced, the plugin SHALL NOT be activated.

#### Scenario: A method-security rule in a plugin is enforced
- **WHEN** a plugin endpoint requires a permission the caller lacks
- **THEN** the call is refused with `403`

### Requirement: Updates can be found and installed from the UI

An installer SHALL be able to update a plugin by uploading a newer jar, or by asking Studio to check the update URL each plugin declares.

Checking SHALL happen only on request. The check SHALL:
- use HTTPS only, with no redirects and a 5-second timeout;
- read at most 64 KB of metadata and 50 MB of artifact;
- compare the downloaded artifact's SHA-256 with the digest the metadata published, and refuse a mismatch.

A downloaded update SHALL go through the same inspection and review as an upload.

A downgrade, or a jar from a different vendor under an existing identifier, SHALL be refused.

#### Scenario: A mismatched download is refused
- **WHEN** the downloaded jar's SHA-256 differs from the one the update metadata states
- **THEN** the update is refused and nothing is stored

### Requirement: Removing a plugin keeps its data until purged

Disabling or uninstalling a plugin SHALL keep its data, grants and settings; they SHALL be inert.

Purging a plugin SHALL:
- require that the plugin is uninstalled;
- be previewable (dry run), estimating its tables, rows and size;
- be confirmed by typing its identifier;
- remove its schema, its grants, its settings and its artifacts;
- be audited.

A plugin that other active plugins require SHALL be disabled only together with them, and they SHALL be listed before confirmation.

#### Scenario: Reusing an identifier after purge starts clean
- **WHEN** a plugin is purged and a plugin with the same identifier is later installed
- **THEN** it inherits no grants, settings or data from the purged one

### Requirement: Every plugin action is audited and visible

Every plugin lifecycle action SHALL be recorded in the audit log with:
- the actor and source address
- the plugin identifier, versions and artifact digest
- the outcome

It SHALL also be written to the application log.

Administrators SHALL see a banner for every plugin that is pending, failed, incompatible or needs a restart, and while safe mode is active.

#### Scenario: A failed activation is visible to every administrator
- **WHEN** an activation fails
- **THEN** every administrator sees a banner naming the plugin, the cause and who started it

### Requirement: A plugin's UI joins the console without being able to break it

An active plugin with a UI SHALL contribute its routes, navigation entries, palette actions, slot contributions and stream-topic handlers to the console as a built-in feature does, within its namespace and the existing navigation groups.

A plugin UI that fails to load, or a plugin component that throws, SHALL NOT affect built-in screens. A plugin's address SHALL explain whether the plugin is:
- not installed
- disabled
- updating
- failed
- incompatible

After plugins change, the console SHALL offer a reload without forcing one.

#### Scenario: A broken plugin panel does not break the queue drawer
- **WHEN** a plugin's queue-drawer panel throws while rendering
- **THEN** the drawer renders its own content and shows that the plugin's panel failed

#### Scenario: A disabled plugin's address explains itself
- **WHEN** an operator opens the address of a disabled plugin's screen
- **THEN** the page states that the plugin is disabled and, for an installer, links to its entry in Admin → Plugins

### Requirement: Plugin authors have a published, versioned kit

The system SHALL publish, with every release:
- the Java API plugins compile against, with its supported types marked;
- a UI kit with types and a build preset;
- a verifier that runs the same inspection as upload.

A binary-incompatible change to the marked Java API SHALL NOT be released without incrementing the extension-contract version.

The release process SHALL build, install, update and roll back a reference plugin against the release candidate.

#### Scenario: An unannounced API break fails the build
- **WHEN** a marked API type changes incompatibly and the contract version is unchanged
- **THEN** the build fails
