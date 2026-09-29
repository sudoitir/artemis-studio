## Context

`FeatureRegistry` holds two layers: the built-in modules (`byId`, fixed at startup) and the
active plugins (`plugins`, copy-on-write, filled by the registry's own `PluginBridge.attach`).
Plugin registration works. The defect is on the read side: `RoleService.catalogue()`, which
backs `GET /api/v1/permissions` and so the role editor and the API-key picker, streams only
`features.enabled()` and never `features.plugins()`. `ManifestController` joins both layers
correctly, so the two catalogue readers had drifted apart.

A permission today is `PermissionDef(action, label)`, with no owner and no scope reach.
"Global only" exists implicitly: a guard written as `@perm.can(PERM)`, with no cluster, can only
be satisfied by a GLOBAL grant (ADR-0038 scope walk). Guards reference permissions through
`T(..Permissions).CONST` in core code and through string literals in plugins. The static
`architecture/PermissionCatalogueTest` checks core constants at build time. Nothing checks
plugins or the running installation.

## Goals / Non-Goals

**Goals:**
- One catalogue method that every reader uses, so the readers cannot drift again.
- Each permission carries its owner, description and whether it is global-only.
- A runtime declaration check, reported through operational health, that also covers plugins.
- A picker that scales to many plugins, an effective-permissions preview and a role diff.

**Non-Goals:**
- Changing how grants resolve. `globalOnly` is descriptive only.
- Validating actions on role save. Roles may keep permissions of an inactive plugin (the
  authorization spec already requires this).
- Scoped grants in the UI. The grant dialog stays global-only, as it is today.

## Decisions

**D1. `FeatureRegistry.catalogue()` is the single source.** It returns
`CatalogueEntry(action, description, featureId, featureTitle, globalOnly)` for the enabled
built-ins followed by the active plugins. `RoleService.catalogue()` and `ManifestController`
both call it. `PermissionView` gains `featureId`, `featureTitle` and `globalOnly`.
- *Alternative:* add `plugins()` to `RoleService` only. Rejected because it keeps two joins
  that can drift.

**D2. Reach is declared, not inferred.**
- `PermissionDef` gains `boolean globalOnly`. A two-argument constructor keeps `false` as the
  default.
- Built-ins mark the permissions their guards only check without a cluster.
- The plugin manifest's `permissions[]` gains an optional boolean `globalOnly`, defaulting to
  false, in `plugin.schema.json`.
- *Alternative:* infer reach from guard shapes. Rejected because one permission can be checked
  both ways, and programmatic checks (`ClusterAccessGuard`) are invisible to a guard scan. A
  declaration is explicit, and the consistency check flags the inconsistent case (D3).

**D3. Declaration check as a bridge plus a health indicator.**
`PermissionDeclarations` (in `kernel/security/internal`) collects the permission references:
- Guards come from `@PreAuthorize` on the main context's Studio beans (scanned at
  `ApplicationReadyEvent`) and on each plugin's own context (scanned on `attach`, dropped on
  `detach`).
- The guard parser walks the SpEL AST for `@perm.can(...)` calls. It evaluates only the
  permission argument, and only when it is a literal or a `T(type).FIELD` reference, resolved
  with the owning classloader. Variable arguments and wildcards (`*`, `x:*`) are skipped.
- Plugin manifests contribute their MCP-tool and metric `permission` references.

`PermissionsHealthIndicator` compares these references with `FeatureRegistry.catalogue()` on
every health read. It reports three mismatch kinds:
- a guard that names an unregistered permission (with the bean and method)
- a manifest reference that is unregistered (with the plugin id)
- a registered entry with a blank description

It also reports a global-only permission that a guard checks with a cluster. Any finding makes
the indicator DEGRADED, and no finding makes it UP. It joins the `studio` health group, which
never feeds liveness or readiness, so it never blocks startup.
- *Alternative:* fail startup or plugin activation. Rejected: the spec requires reporting,
  not blocking.
- *Alternative:* regex over the expression text. Rejected: fragile with multi-line and nested
  expressions.

**D4. Effective permissions are computed on the server and expanded against the catalogue.**
`GET /api/v1/users/{id}/effective-permissions` is guarded by `user:admin` on the service method.
Spring Security evaluates the guard before the method body, so a refused caller gets 403 whether
or not the user exists. For each `user_role` row, the service expands the role's actions against
the catalogue: `*` and `resource:*` expand to the concrete entries they match, and an action
missing from the catalogue is still listed. Each entry carries:
- `action`, `description`, `scopeType`, `scopeId`, `roleId`, `roleName`
- `via`, the stored pattern
- `effective`, which is false for a `globalOnly` permission held at environment or cluster scope

This mirrors `GrantLoader` without changing it, because `GrantLoader` merges roles per scope
and loses the source role.

**D5. The role diff is client-side.** `GET /roles` already returns every role's permissions. A
pure `diffRoles(a, b)` returns the actions only in A and only in B, with no endpoint added.

**D6. Picker.** `PermissionPicker` replaces the flat checkbox list in `RolesPanel`:
- A search box filters by action or description, and hides groups with no match.
- Mantine `Accordion` groups by `featureTitle`, with a header checkbox (indeterminate when
  partly selected) that selects or clears the group, and an `n/m` count.
- Each row is a `Checkbox` with the description and a "Global only" badge.
- A visually hidden `aria-live` region announces bulk changes.
- Selected actions that are not in the catalogue (an inactive plugin's, say) appear in an
  "Not in the catalogue" group, still selected, so saving never silently drops them.
- A filtered-empty result says so and offers to clear the search.

The Users table gets an "Effective permissions" action that opens a drawer. The Roles tab gets a
"Compare roles" modal.

## Risks / Trade-offs

- [The guard scan misses programmatic checks (`ClusterAccessGuard.requireCluster`, `PermissionResolver.can` in code)] → The
  build-time `PermissionCatalogueTest` still covers core constants, so the runtime check covers
  plugins and annotated guards.
- [Scanning every main-context bean at startup costs time] → The scan is limited to Studio's own
  package in the main context and to the plugin's `basePackage` in plugin contexts, and it runs
  once.
- [Changing `PermissionView`'s shape] → Only Studio's own UI consumes it (no compatibility
  owed). The OpenAPI types are regenerated (ADR-0019).
