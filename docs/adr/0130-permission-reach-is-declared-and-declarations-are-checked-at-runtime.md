# ADR-0130: Permission reach is declared, and declarations are checked at runtime

- **Status**: accepted; amended by [ADR-0172](0172-teams-own-queues-and-addresses-and-every-check-names-its-resource.md)
- **Date**: 2026-09-29
- **Deciders**: maintainers

## Context

The role editor read its permission catalogue from the enabled built-in modules only, so an
active plugin's permissions could not be granted through the UI. The feature manifest built
its own catalogue from built-ins plus plugins. The two readers had drifted apart, and nothing
noticed.

The scope walk (ADR-0038) means a permission that a guard checks without a cluster, such as
`@perm.can(PERM)`, takes effect only through a global grant. Studio never recorded that, so an
administrator could grant such a role on one cluster and assume it did something. Nothing
compared the permissions that guards and plugin manifests name with the ones that are
registered. A plugin guarding an endpoint with a typo made that endpoint unreachable, and
nothing reported it.

## Decision

- `FeatureRegistry.catalogue()` is the only source of the permission catalogue. It returns the
  enabled modules' permissions, then every active plugin's, each with its owner, description
  and reach. The role editor, the API-key picker and the manifest all read it.
- A permission declares its reach: `PermissionDef.globalOnly`, and `globalOnly` on a plugin
  manifest's `permissions[]` entry, which defaults to false. Reach is descriptive only and
  never changes how a grant resolves.
- A runtime check, the `permissions` contributor in the `studio` health group, compares the
  permissions named by `@PreAuthorize("@perm.can(...)")` guards and by plugin manifest
  references (MCP tools, metrics) with the catalogue.
  - Studio's own guards are read once at startup. A plugin's guards are read when it activates
    and dropped when it deactivates.
  - A guard argument is resolved only when it is a literal or a `T(type).CONSTANT`.
  - The check reports DEGRADED when something is unregistered, undescribed, or global-only yet
    checked against a cluster. It never blocks startup or activation.

## Consequences

- Plugin permissions can be granted, and the readers cannot drift again.
- Plugin authors get an operational signal for a mistyped or undeclared permission.
- Reach has to be declared by hand. A wrong declaration that no annotated guard contradicts,
  for example one only checked programmatically through `ClusterAccessGuard`, goes unnoticed.
  The build-time `PermissionCatalogueTest` still covers core constants.
- The manifest schema gains an optional field. Existing plugins stay valid.

## Alternatives considered

- **Add `plugins()` to the role catalogue only.** It fixes the symptom but leaves two readers
  that can drift again.
- **Infer reach from guard shapes.** One permission can be checked both with and without a
  cluster, and programmatic checks are invisible to a guard scan.
- **Fail startup or plugin activation on a mismatch.** That turns a descriptive mistake into an
  outage. The operational-health spec asks for reporting.
- **Match guards with a regex over the expression text.** It is fragile with multi-line and
  compound expressions. Walking the SpEL AST is exact.
