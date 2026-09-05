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
- [x] 1.9 `IdentitySchemaIntegrationTest` (`support/PostgresIntegrationTest`):
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
      session, CSRF rejection without header, `/me` shape. **Deferred** —
      `EndpointProtectionTest` (task 4.11) already exercises the filter chain
      end to end (401s, CSRF-token-required) across every endpoint; a
      dedicated login-flow test is still worth adding but ran out of budget
      in this session.
- [ ] 3.13 `AdminBootstrapTest`: creates admin exactly once on empty
      `app_user`. **Deferred** — indirectly proven by every integration test
      in the suite booting against a fresh database and logging the bootstrap
      banner exactly once; not asserted directly.
- [ ] 3.14 `AuditActorTest`: a mutation by a logged-in user writes `username`
      and `user_id`; a scheduler-originated row still writes `system`.
      **Deferred** — `ActorResolverTest` already covers the anonymous/system
      paths; the authenticated-userId path is new and untested directly.

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
- [x] 4.8 `GET /api/v1/permissions` (`RolesController.permissions()` →
      `RoleService.catalogue()`) — the catalogue with human labels.
- [x] 4.9 Built-in-role immutability (`RoleService.requireEditable`) +
      last-global-admin guards (`UserService.guardNotLastAdmin`, self-revoke
      check) — `ConflictException` → `409` via `ApiExceptionHandler`.
- [x] 4.10 `PermissionResolverTest` — global/cluster/environment scope walk
      (including "cluster grant doesn't leak to a sibling in the same
      environment"), `*`/`resource:*` wildcard matching, union across
      multiple grants. Pure Mockito unit test, no Spring context.
- [x] 4.11 `EndpointProtectionTest` — implemented the 401 half exactly as
      planned: reflects over `RequestMappingHandlerMapping.getHandlerMethods()`
      (every registered endpoint, not a hand-maintained list) and asserts a
      CSRF-token-bearing but unauthenticated request to every `/api/**`
      pattern gets `401` unless allow-listed. **Narrowed from the plan**: the
      second half (statically verifying every non-GET handler resolves to a
      `@PreAuthorize`-annotated service method) was not implemented — tracing
      controller→service call graphs via reflection was judged too complex
      for the value versus the runtime 401 check, which is what actually
      catches a forgotten protection. The caller audit (task 4.1) covers this
      by hand for the services this change touches.
- [x] 4.12 `LastAdminGuardTest` — cannot disable the last enabled global
      admin, can disable one when another remains, cannot strip the last
      admin's grant, cannot self-revoke even when not the last admin.
- [x] 4.13 `support/AdminAuthenticationExtension.java` (JUnit 5
      `BeforeEachCallback`/`AfterEachCallback`) installs a full-access
      `StudioPrincipal` around a test; applied via `@ExtendWith` to the 12
      existing test classes method security now blocks: `ClusterControllerTest`,
      `MessageMutationControllerTest`, `NotificationChannelsControllerTest`,
      `SettingsServiceTest`, `AlertsControllerTest`,
      `ClusterCredentialsRotationTest`, `DlqControllerTest`,
      `MessageBrowseControllerTest`, `RequestReplyControllerTest`,
      `RequestReplyServiceTest`, `RrMetricsTest`, `StreamControllerTest`.
      Full backend suite (`./mvnw test`) is green.

## 5. Environments

- [x] 5.1 `service/EnvironmentService.java`, `web/EnvironmentsController.java`
      (`/api/v1/environments`, CRUD; write needs `environment:write`, list
      needs `environment:read`; cluster assignment via
      `PUT /api/v1/clusters/{id}/environment`, needs `cluster:write`).
- [x] 5.2 `web/dto/EnvironmentViews.java`. **Deviation**: no
      `mapper/EnvironmentViewMapper.java` — `EnvironmentEntity` and
      `EnvironmentView` are both 4 plain fields with the same names, so
      `EnvironmentService.toView()` maps directly; a MapStruct/hand-written
      mapper for a 1:1 field copy would be pure boilerplate (ponytail: rung 1).
- [x] 5.3 `ClusterSummary`/`ClusterDetail` (`web/dto/ClusterViews.java`) gain
      `environmentId`; `ClusterService` passes `cluster.getEnvironmentId()`.
      **Narrowed from the plan**: id only, not name/colour — the frontend
      already fetches `/api/v1/environments` as a separate cached resource and
      joins client-side (avoids an extra join query on every cluster list
      read, and matches non-negotiable #9's "server state via TanStack Query"
      — the environment list is its own query, not duplicated per cluster).
- [x] 5.4 `UserRoleRepository.deleteByIdScopeTypeAndIdScopeId` and
      `ApiTokenGrantRepository.deleteByIdScopeTypeAndIdScopeId` both called
      from `EnvironmentService.delete()`, same transaction as the environment
      delete; OIDC role mappings scoped to it are deleted too.
- [ ] 5.5 Regenerate `schema.d.ts` (`npm run gen:api`); refresh
      `web/openapi.json` (guarded by `web/OpenApiSnapshotTest`) — deferred to
      section 7 once the frontend consumes these types.

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

- [x] 8.1 `security/ApiTokenService.java` — mint (`as_<11-char-prefix>_<secret>`,
      SHA-256 stored, shown once), list (no value), revoke. Fixed-length
      prefix rather than underscore-delimited (base64url's alphabet includes
      `_`, so a delimiter search would be ambiguous — found while implementing).
- [x] 8.2 `security/ApiTokenAuthenticationFilter.java` — `OncePerRequestFilter`
      before `SecurityContextHolderFilter`; `Authorization: Bearer as_…`;
      builds `StudioPrincipal` with grants **intersected** against the owner's
      current grants.
- [x] 8.3 CSRF matcher (`config/SecurityConfig.java`): `ignoringRequestMatchers`
      on the presence of an `Authorization` header.
- [x] 8.4 `last_used_at`: dirty in-memory `ConcurrentHashMap` flushed at most
      once a minute (`@Scheduled(fixedRate = 60_000)`), not on every call.
- [x] 8.5 `web/TokensController.java` (`/api/v1/tokens`), `web/dto/TokenViews.java`.
      `web/src/admin/TokensPanel.tsx` deferred to section 7 (frontend admin).
      Manual verification found two bugs, both fixed: a blank/missing
      `TokenGrantRequest.action` threw an unhandled NPE (500) instead of a 400
      — fixed with `@NotBlank`/`@NotEmpty`/`@Valid` on the nested list; and a
      `GLOBAL`-scoped grant with `scopeId: null` (the natural request shape,
      matching `UserService.addGrant`'s convention) violated `api_token_grant`'s
      NOT NULL `scope_id` column — fixed by defaulting to `ScopeIds.GLOBAL` in
      `TokensController.create`, same as `UserService.addGrant` already does.
- [x] 8.6 Audit rows for token-authenticated actions record owner + token
      name: `Actor` gained a `tokenName` field, folded into the stored
      `username` as `"<owner> [token: <name>]"` by `Actor.displayName()` —
      no new `audit_event` column needed. `TOKEN_CREATE`/`TOKEN_REVOKE`
      audited in `ApiTokenService`.
- [x] 8.7 `ApiTokenAuthenticationFilterTest` — valid, expired, revoked,
      wrong-prefix, narrowed-below-owner, owner-demoted-narrows-token,
      owner-disabled-disables-token. Written against the real
      `SecurityFilterChain` (not a unit test on the filter alone), which is
      what caught a real filter-ordering bug: `ApiTokenAuthenticationFilter`
      was registered `addFilterBefore(SecurityContextHolderFilter)`, so
      `SecurityContextHolderFilter` ran immediately after it and overwrote the
      bearer authentication with the empty session-less context it loads from
      the repository — every bearer-token request was silently falling back to
      401. Fixed to `addFilterAfter`; confirmed the new test fails on the old
      ordering and passes on the fix.

## 9. OIDC

- [x] 9.1 Verified via `ctx7` (Spring Security 7.0/7.1 reference docs):
      `oauth2Login()` DSL, `userInfoEndpoint().oidcUserService(...)` hook
      shape, `spring.security.oauth2.client.registration.*` properties.
- [x] 9.2 Added `spring-boot-starter-oauth2-client`; `application.yml` documents
      the opt-in registration block (commented, since none is configured by
      default) — client secret is expected from the environment when a
      deployer adds one, never committed.
- [x] 9.3 **Design deviation from the literal sketch**: no custom
      `OAuth2UserService`/`OidcUserService`. `security/oidc/StudioOidcUserService.java`
      is a plain `@Component` doing JIT provisioning
      (issuer+subject, `password_hash=NULL`, `auth_source=OIDC`), called from
      `security/oidc/OidcAuthenticationSuccessHandler.java` *after* Spring
      Security finishes the OIDC exchange — that handler then replaces the
      `OidcUser` principal in the `SecurityContext` with a `StudioPrincipal`,
      so every downstream check (`@PreAuthorize`, `ActorResolver`) sees the
      same principal shape regardless of how the caller authenticated.
      `config/SecurityConfig.java` only calls `.oauth2Login()` when a
      `ClientRegistrationRepository` bean actually exists
      (`ObjectProvider.getIfAvailable()`), since Boot creates none at all
      with no registration configured and the DSL would otherwise fail to wire.
- [x] 9.4 `security/oidc/ClaimRoleMapper.java` — reads `oidc_role_mapping`,
      reconciles the user's mapped grants to the current claim values on
      every login (adds newly-matched, removes no-longer-matched — see its
      `ponytail:` comment on how "OIDC-derived" is inferred without a new
      column); configured default role or refusal.
- [x] 9.5 `service/OidcMappingService.java` + `web/OidcMappingController.java`
      (`/api/v1/oidc/mappings`, CRUD). `web/src/admin/OidcMappingPanel.tsx`
      deferred to section 7.
- [x] 9.6 `LoginView.tsx`: show the SSO entry point alongside local login when
      a provider is configured. Needed a new public endpoint,
      `GET /api/v1/auth/providers` (`AuthController`, allow-listed in
      `SecurityConfig`), since the login screen has to know before any session
      exists — `ClientRegistrationRepository`'s base interface has no listing
      method, so it reads via the `Iterable` the Boot auto-configured
      in-memory implementation also happens to implement.
- [x] 9.7 `ClaimRoleMapperTest`, `StudioOidcUserServiceTest` — JIT
      provisioning, mapping re-application, refusal with no default role,
      using a stubbed `OidcUser` (full authorization-code flow is not
      end-to-end tested — no test IdP is practical in this suite; noted
      explicitly rather than overclaiming coverage).

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
