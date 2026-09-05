## 1. Schema

- [x] 1.1 Add Liquibase changeset `014-identity.sql` (`--changeset artemis-studio:014-...`,
      column order by alignment, `pk_`/`fk_`/`uq_`/`ck_`/`ix_` naming, a
      `--rollback` per statement). Do not edit `003-identity.sql` or
      `002-estate.sql`. `<include>` it in `db.changelog-master.xml`.
- [x] 1.2 `app_user`: add `issuer`, `subject` (unique together, nullable),
      `auth_source` (`LOCAL`|`OIDC`), `must_change_password boolean default false`.
- [x] 1.3 `role`: add `builtin boolean not null default false`.
- [x] 1.4 `api_token(id, user_id fk, name, prefix unique, token_hash bytea,
      expires_at, last_used_at, revoked_at, created_at)`.
- [x] 1.5 `api_token_grant(token_id fk, scope_type, scope_id, action)` mirroring
      `user_role`'s scope shape.
- [x] 1.6 `oidc_role_mapping(id, claim, claim_value, role_id fk, scope_type, scope_id)`.
- [x] 1.7 Data backfill changeset: seed `role` rows `ADMIN` (`builtin=true`,
      permission `*`), `OPERATOR` (`builtin=true`, operate-level permissions),
      `VIEWER` (`builtin=true`, `*:read`-shaped permissions), and their
      `role_permission` rows.
- [x] 1.8 Spring Session JDBC schema: verify exact table DDL and Boot 4.1
      property names via `ctx7` (`npx ctx7@latest library "Spring Session"
      ...`), add its tables to the same changeset,
      `spring.session.jdbc.initialize-schema=never`.
- [ ] 1.9 `IdentitySchemaIntegrationTest` (`support/PostgresIntegrationTest`):
      `014` applies cleanly on top of `003`/`002`, seed roles/permissions
      land, rollback works.

## 2. Persistence

- [x] 2.1 `persist/AppUserEntity.java` + `AppUserRepository` (find by username,
      find by issuer+subject, exists-any for bootstrap check).
- [x] 2.2 `persist/RoleEntity.java`, `RolePermissionEntity.java`,
      `UserRoleEntity.java` + repositories (Lombok/JPA conventions from
      `AlertRuleEntity.java` — protected no-args ctor, id-only equals/hashCode,
      explicit `@Column`).
- [x] 2.3 `persist/EnvironmentEntity.java` + `EnvironmentRepository`.
- [x] 2.4 `persist/ApiTokenEntity.java`, `ApiTokenGrantEntity.java` +
      repositories (lookup by `prefix`).
- [x] 2.5 `persist/OidcRoleMappingEntity.java` + `OidcRoleMappingRepository`.
- [x] 2.6 Hibernate `ddl-auto=validate` passes for every new entity against `014`.

## 3. Authentication

- [x] 3.1 Add `spring-session-jdbc` to `pom.xml` (verified coordinates from 1.8).
- [x] 3.2 ~~`security/StudioUserDetailsService.java`~~ — implemented instead as
      `service/AuthService.login()` checking `AppUserEntity`/`PasswordEncoder`
      directly and building a `StudioPrincipal`, since the login body is JSON
      and there is no `UsernamePasswordAuthenticationFilter` in the chain to
      hand a `UserDetailsService` to (see design.md decision 1's revision).
- [x] 3.3 `security/StudioPrincipal.java` (`userId, username, Set<Grant>`),
      `security/Grant.java` (`scopeType, scopeId, Set<String> permissions`).
- [x] 3.4 Rewrite `config/SecurityConfig.java`: session management,
      `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, CSRF via
      `CookieCsrfTokenRepository.withHttpOnlyFalse()` +
      `CsrfTokenRequestAttributeHandler`, `authorizeHttpRequests` denying by
      default with an explicit allow-list (`/api/v1/auth/login`,
      `/actuator/health`, static SPA assets). Delete `warnIfExposed`.
- [x] 3.5 Implemented as `AuthService.reissueCsrfToken()`, called from both
      `login()` and `logout()` — simpler than a separate filter since both
      paths already run through `AuthService`.
- [x] 3.6 `web/AuthController.java`: `POST /api/v1/auth/login` (JSON body),
      `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`,
      `POST /api/v1/auth/password` (current + new password).
- [x] 3.7 `security/LoginAttemptLimiter.java` modelled on
      `scheduler/NodeCallLimiter.java` — per (username, source IP) failure
      counter, exponential lockout, cleared on success. `ponytail:` comment:
      in-memory only, revisit at multi-instance HA.
- [x] 3.8 `security/AdminBootstrap.java` — `ApplicationReadyEvent` listener;
      if `app_user` is empty, create `admin` with a random 24-char password,
      log it once at WARN in a boxed banner, `must_change_password=true`.
- [x] 3.9 `423 Locked` + `must-change-password` problem type from
      `web/ApiExceptionHandler.java` until the password is changed.
- [x] 3.10 `security/Actor.java` / `ActorResolver.java`: resolve `userId` from
      `StudioPrincipal` instead of hardcoded `null`; `Actor.ANONYMOUS` stays
      only for failed-login rows.
- [x] 3.11 New audited actions: `LOGIN` (one row per attempt; outcome
      SUCCESS/FAILURE distinguishes success from failure, matching the
      existing pending-then-outcome model rather than two action strings),
      `LOGOUT`, `PASSWORD_CHANGE`.
- [ ] 3.12 `AuthControllerIntegrationTest`: login success/failure, lockout
      after N failures, `423` until password change, logout invalidates
      session, CSRF rejection without header, `/me` shape.
- [ ] 3.13 `AdminBootstrapTest`: creates admin exactly once on empty
      `app_user`; no-op on populated table.
- [ ] 3.14 `AuditActorTest`: a mutation by a logged-in user writes `username`
      and `user_id`; a scheduler-originated row still writes `system`.

## 4. Authorization

- [x] 4.1 **Caller audit** (done): grepped every service under
      consideration for callers under `scheduler/`, `sse/`, `broker/`,
      `domain/`. None of `ClusterService`, `MessageService`, `DlqService`,
      `AlertRuleService`, `NotificationChannelService`, `RequestReplyService`,
      `RrMetrics`, `AlertService` are scheduler-reached — every public method
      is web-facing. `SettingsService` is the one mixed case: its typed
      getters (`tierA()`, `bulkCap()`, …) and `applyRuntime()` are read by the
      scheduler and by its own `@EventListener(ApplicationReadyEvent)` and are
      left unannotated; only `effective()`/`put()`/`reset()` are guarded.
      Also found and closed a gap the original plan missed: `RequestReplyService`
      and `RrMetrics.stats()` had no spec coverage for permission checks —
      added `specs/request-reply-tracing/spec.md` (ADDED requirement) and a
      `proposal.md` line, then implemented the same as the others.
- [x] 4.2 `security/Permissions.java` — catalogue implemented (`cluster:read`,
      `cluster:write`, `environment:read/write`, `message:read/send/move/delete`,
      `queue:purge`, `alert:read/write`, `settings:read/write`, `user:admin`,
      `token:admin`), plus `Permissions.catalogue()` for the human-labelled read.
- [x] 4.3 `security/PermissionResolver.java` (`@Component("perm")`) —
      implemented, backed by `security/GrantLoader.java` (resolves a user's
      `user_role` rows once at auth time) and `security/ClusterEnvironmentIndex.java`
      (the cached `clusterId → environmentId` map, invalidated on cluster
      register/delete).
- [x] 4.4 **Design deviation from the literal sketch, made deliberately**:
      per-cluster methods use `service/ClusterAccessGuard.java`
      (`requireCluster(clusterId, permission)`, throwing `NotFoundException`)
      instead of `@PreAuthorize` — see task 4.6's requirement that an
      ungranted cluster be `404`, not `403`; `@PreAuthorize`'s default
      `AccessDeniedException` maps to `403` with no easy per-call override.
      `@PreAuthorize` (via `@perm.can(permission)`, the global overload) is
      used for operations with no cluster id to hide: `ClusterService.register/
      checkConnection`, `NotificationChannelService.*`, `SettingsService.effective/
      put/reset`. `@PostFilter` is used for the two list-shaped reads
      (`ClusterService.list()`, `AlertService.firingCounts()`).
- [x] 4.5 `ClusterService.list()` (`@PostFilter`) and `AlertService.firingCounts()`
      (`@PostFilter`, backing `AlertSummaryController`): filtered to
      `cluster:read`/`alert:read`-granted clusters only.
- [x] 4.6 Every per-cluster method (`ClusterService`, `MessageService`,
      `DlqService`, `AlertRuleService`, `RequestReplyService`, `RrMetrics`,
      `StreamController`) returns `404` via `ClusterAccessGuard` for an
      ungranted cluster, never `403`.
- [x] 4.7 `web/StreamController.java`: `clusterAccess.requireCluster(clusterId,
      CLUSTER_READ)` before the emitter is created.
- [ ] 4.8 `GET /api/v1/permissions` — the catalogue with human labels, for
      the role editor.
- [ ] 4.9 Built-in-role immutability + last-global-admin guards in
      `service/RoleService.java` / `UserService.java` (409 responses, new
      problem types).
- [ ] 4.10 `PermissionResolverTest` — global/cluster/environment scope walk,
      wildcard matching, union across multiple roles.
- [ ] 4.11 `EndpointProtectionTest` — reflect over every `@RequestMapping`
      method under `web/`, unauthenticated MockMvc request asserts `401`
      unless allow-listed; every non-GET handler resolves to a
      `@PreAuthorize`-annotated service method or is explicitly allow-listed
      with a comment explaining why.
- [ ] 4.12 `LastAdminGuardTest` — cannot disable/delete/strip the last global
      admin; cannot self-revoke `user:admin`.
- [ ] 4.13 Update every existing integration test that calls the API
      unauthenticated (this is the proposal's stated BREAKING change) — add a
      shared authenticated-test helper rather than patching each test ad hoc.

## 5. Environments

- [ ] 5.1 `service/EnvironmentService.java`, `web/EnvironmentsController.java`
      (`/api/v1/environments`, CRUD; write needs `environment:write`, list
      needs `cluster:read`).
- [ ] 5.2 `web/dto/EnvironmentViews.java`, `mapper/EnvironmentViewMapper.java`.
- [ ] 5.3 Surface environment id/name/colour on `web/dto/ClusterViews.java`
      via `mapper/ClusterViewMapper.java`.
- [ ] 5.4 Environment removal: `ON DELETE SET NULL` already on
      `cluster.environment_id`; explicitly delete `ENVIRONMENT`-scoped
      `user_role` and `api_token_grant` rows for that environment in the same
      transaction.
- [ ] 5.5 Regenerate `schema.d.ts` (`npm run gen:api`); refresh
      `web/openapi.json` (guarded by `web/OpenApiSnapshotTest`).

## 6. Frontend: auth

- [ ] 6.1 `web/src/auth/LoginView.tsx`, `ChangePasswordView.tsx`.
- [ ] 6.2 `useMe()`, `useLogin()`, `useLogout()`, `useChangePassword()` in
      `web/src/api/client.ts` (sectioned by comment banner, following the
      alerting hooks' pattern).
- [ ] 6.3 `web/src/api/client.ts` `request<T>()`: read `XSRF-TOKEN` cookie and
      send `X-XSRF-TOKEN` on non-GET; on `401`, clear the `me` query and
      redirect to `/login`. This is the one place it goes.
- [ ] 6.4 `web/src/router.tsx`: `/login` and `/change-password` public routes;
      root route `beforeLoad` guard redirecting to `/login` on a failed `/me`.
- [ ] 6.5 `web/src/app/RootLayout.tsx`: user menu (name, change password,
      logout).
- [ ] 6.6 `web/src/auth/Can.tsx` + `useCan()` — permission gate reading
      `useMe()`'s grants.
- [ ] 6.7 `LoginView.test.tsx`, `Can.test.tsx`, a `client` test asserting a
      401 from any hook triggers the redirect, `RootLayout.test.tsx` extended
      for the user menu.

## 7. Frontend: admin

- [ ] 7.1 `web/src/admin/UsersPanel.tsx`, `RolesPanel.tsx`, `GrantsPanel.tsx`,
      `EnvironmentsPanel.tsx` behind `/admin`, tabs following
      `alerts/AlertsView.tsx` + sibling-panel pattern.
- [ ] 7.2 Role editor: catalogue checkboxes from `/api/v1/permissions` plus a
      free-text field (fully dynamic model); built-in roles read-only in the UI.
- [ ] 7.3 Cluster cards / sidebar / `HomeView.tsx`: environment colour chip
      and grouping.
- [ ] 7.4 Audit screen user filter becomes a picker over real users
      (`web/src/audit/*`).
- [ ] 7.5 `EnvironmentsPanel.test.tsx`.

## 8. API tokens

- [ ] 8.1 `security/ApiTokenService.java` — mint (`as_<prefix>_<secret>`,
      SHA-256 stored, shown once), list (no value), revoke.
- [ ] 8.2 `security/ApiTokenAuthenticationFilter.java` — `OncePerRequestFilter`
      before the session filter; `Authorization: Bearer as_…`; builds
      `StudioPrincipal` with grants **intersected** against the owner's
      current grants.
- [ ] 8.3 CSRF matcher: exempt bearer-authenticated requests.
- [ ] 8.4 `last_used_at`: dirty in-memory set flushed at most once a minute
      per token, not on every call. `ponytail:` comment naming this ceiling.
- [ ] 8.5 `web/TokensController.java` (`/api/v1/tokens`), `web/dto/*`,
      `web/src/admin/TokensPanel.tsx`.
- [ ] 8.6 Audit rows for token-authenticated actions record owner + token
      name (`TOKEN_CREATE`, `TOKEN_REVOKE` actions too).
- [ ] 8.7 `ApiTokenAuthenticationFilterTest` — valid, expired, revoked,
      wrong-prefix, narrowed-below-owner, owner-demoted-narrows-token,
      owner-disabled-disables-token.

## 9. OIDC

- [ ] 9.1 `npx ctx7@latest library "Spring Security" "OAuth2 client OIDC login
      configuration Boot 4.1"` — verify starter coordinates and property
      namespace before adding the dependency.
- [ ] 9.2 Add `spring-boot-starter-oauth2-client`; `spring.security.oauth2.client.*`
      config in `application.yml`, client secret from environment.
- [ ] 9.3 `security/oidc/StudioOidcUserService.java` — JIT provisioning by
      issuer+subject, `password_hash=NULL`, `auth_source=OIDC`.
- [ ] 9.4 `security/oidc/ClaimRoleMapper.java` — reads `oidc_role_mapping`,
      re-applies on every login; configured default role or refusal.
- [ ] 9.5 `web/OidcMappingController.java` (`/api/v1/oidc/mappings`, CRUD),
      `web/src/admin/OidcMappingPanel.tsx`.
- [ ] 9.6 `LoginView.tsx`: show the SSO entry point alongside local login when
      a provider is configured.
- [ ] 9.7 `ClaimRoleMapperTest`, `StudioOidcUserServiceTest` — JIT
      provisioning, mapping re-application, refusal with no default role,
      using a stubbed `ClientRegistrationRepository` (full authorization-code
      flow is not end-to-end tested — note this explicitly, don't overclaim).

## 10. Docs and closeout

- [ ] 10.1 ADR-0037 (session auth), ADR-0038 (dynamic permissions + scope
      walk), ADR-0039 (API tokens), ADR-0040 (OIDC), ADR-0041 (audit actor,
      supersedes ADR-0023 — mark 0023 superseded with a link, do not edit its
      decision). Add all five to `docs/adr/README.md`'s index.
- [ ] 10.2 `docs/architecture.md`: replace the "actor resolved before
      authentication exists" paragraph and the `security/` box.
- [ ] 10.3 `README.md`: tick Phase 8's six TODO rows; update Status.
- [ ] 10.4 `openspec/project.md`: roadmap line → "Phase 8 complete; v1.0 next".
- [ ] 10.5 `deploy/compose/*` quick-start doc: note the bootstrap password
      banner and first-login flow.
- [ ] 10.6 `just verify` green (backend + frontend); `just fmt` leaves no diff.
- [ ] 10.7 Manual verification pass per design.md's Migration Plan / the
      approved plan's Verification section against `just up` with a fresh DB.
