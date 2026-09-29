## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks

## 2. Catalogue (root cause)
- [x] 2.1 `PermissionDef` gains `globalOnly` (two-arg constructor defaults false); built-in modules mark global-only permissions
- [x] 2.2 Plugin manifest `permissions[].globalOnly` (schema, `PluginDescriptor.Permission`, registry mapping, template `plugin.json`, plugin docs)
- [x] 2.3 `FeatureRegistry.catalogue()`; `RoleService.catalogue()` and `ManifestController` use it; `PermissionView` gains `featureId`, `featureTitle`, `globalOnly`
- [x] 2.4 IT with a real plugin jar: permissions listed under the plugin, savable into a role, gone after deactivation while the role keeps them

## 3. Consistency check
- [x] 3.1 Guard reference parser (SpEL AST, literal and `T(..).CONST` arguments) with unit test
- [x] 3.2 `PermissionDeclarations` bridge: main-context scan at ready, plugin scan on attach, drop on detach; manifest MCP-tool and metric references
- [x] 3.3 `PermissionsHealthIndicator` in the `studio` group; test for each mismatch kind and the healthy case

## 4. Effective permissions
- [x] 4.1 `GET /api/v1/users/{id}/effective-permissions` with wildcard expansion and the `effective` flag; IT including the 403 for a caller without `user:admin`

## 5. UI
- [x] 5.1 Regenerate OpenAPI types
- [x] 5.2 `PermissionPicker` (groups, search, bulk select, announcements, not-in-catalogue group) in `RolesPanel`; vitest
- [x] 5.3 `diffRoles` + "Compare roles" modal; vitest for `diffRoles`
- [x] 5.4 "Effective permissions" drawer in `UsersPanel`
- [x] 5.5 `plugin-e2e.ts`: grant the plugin's permissions in the role editor, gone after deactivation

## 6. Finish
- [x] 6.1 ADR for declared reach and the declaration check
- [x] 6.2 `just verify` green; screenshots light/dark; PR merged; change archived
