## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs, replaces these tasks
- [x] 1.2 ADRs: 0146 public API contract (versioning, `!` flag, deprecation, pagination/error/limit convention), 0147 idempotency keys, 0148 published generated clients (revisits ADR-0019 in part); 0143 to 0145 were taken by the account-security changes

## 2. Errors and limits (D4)
- [ ] 2.1 Problem+json `AuthenticationEntryPoint` (401 `unauthenticated`) and `AccessDeniedHandler` (403 `forbidden`, `csrf`) wired in `SecurityConfig`; `BearerAuthenticationFilter` delegates to the entry point
- [ ] 2.2 `ApiExceptionHandler` extends `ResponseEntityExceptionHandler`: framework exceptions, method-security denials and a 500 `internal-error` catch-all through `Problems`; `/error` fallback returns problem+json
- [ ] 2.3 `Retry-After` on `login-throttled` and `too-many-queries`
- [ ] 2.4 `OpenApiCustomizer`: `4XX`/`5XX` problem+json responses on every operation; 429 with `RateLimit-*`/`Retry-After` headers
- [ ] 2.5 Tests: 401 no credentials, 401 bad token, 403, CSRF 403, 400 unreadable body, 405, unknown route 404, 500 hides detail

## 3. Pagination (D3)
- [ ] 3.1 `PagedView` becomes `{data, page, pageSize, count, hasNext}`; `ResourceQuery` refuses out-of-range `page`/`size` with `invalid-value`
- [ ] 3.2 Fold the bespoke page views (messages, audit, events, rr flows, alert history) into `PagedView`
- [ ] 3.3 Replace `limit` with `page`/`size` on channel deliveries and config applies
- [ ] 3.4 Wrap and paginate every bare-list `/api/v1` GET (SQL paging for table-backed lists)
- [ ] 3.5 `OpenApiSnapshotTest` asserts no `/api/v1` operation returns a top-level array
- [ ] 3.6 Frontend: regenerate `schema.d.ts`, update every caller and its tests for the new shapes

## 4. Idempotency keys (D5)
- [ ] 4.1 Liquibase changeset for `idempotency_record`; `IdempotencyRecords` (JdbcClient) claim/complete/release/find
- [ ] 4.2 `IdempotencyFilter` after security: key validation, user-scoped claim, fingerprint, replay with `Idempotent-Replayed`, 422 reused, 409 in progress, 5xx releases, multipart 400, plugin gateway passthrough
- [ ] 4.3 `ManagedStore` with 24 h retention and scheduled purge
- [ ] 4.4 Document the `Idempotency-Key` header parameter on every mutating operation
- [ ] 4.5 Tests: replay applies once, different body 422, concurrent 409, other user's key independent, dryRun vs real, 5xx retry runs again, purge

## 5. Versioning and deprecation (D2)
- [ ] 5.1 `info.version` from `BuildProperties`; snapshot test normalises it
- [ ] 5.2 Spring API versioning: path-segment version via an `/api/{version}` path prefix for Studio's REST controllers (drop the literal `/api/v1` from their mappings), supported `1`, unsupported → 400 `invalid-api-version`; document keeps concrete `/api/v1` paths
- [ ] 5.4 `StandardApiVersionDeprecationHandler` fed by `ApiDeprecations` declarations, which also mark operations `deprecated` in the document; test proves headers and flag
- [ ] 5.3 Docs-site page: versioning, break marker, deprecation policy, pagination, problem types, rate-limit headers, idempotency

## 6. Contract tests (D6)
- [ ] 6.1 Pick the OpenAPI 3.1 validator (ctx7); test-scope `MockMvcBuilderCustomizer` validates every `/api/v1` JSON response against `web/openapi.json` with undocumented properties rejected
- [ ] 6.2 Fix every drift it surfaces (document or remove the field)
- [ ] 6.3 Negative test: an extra field fails validation

## 7. Break detection (D1)
- [x] 7.1 `api-compat` PR job in `ci.yml`: pinned oasdiff against `<latest CalVer tag>:web/openapi.json`, passes on break only with a `!`/`BREAKING CHANGE:` commit in the PR; joins `ci-ok`
- [x] 7.2 `just api-diff` recipe running the same comparison

## 8. Release artifacts and clients (D7)
- [x] 8.1 Release job attaches `artemis-studio-<V>.openapi.json` (version stamped) with `.sha256` and provenance
- [x] 8.2 `web/packages/client` → `@artemis-studio/client` (openapi-typescript + openapi-fetch, bearer auth, `ProblemError`), built like `plugin-sdk`, unit-tested
- [x] 8.3 `clients/java/pom.xml` → `io.github.sudoitir:artemis-studio-client` (openapi-generator `java`/`native`); built in PR CI
- [x] 8.4 Publish both clients on every release (npm provenance, Central signed + attested); add their paths to `changes.release`

## 9. Finish
- [ ] 9.1 `just verify` green; `just api-diff` shows this change's breaks
- [ ] 9.2 UI check of changed list pages (own ports and compose project; light and dark, empty and error), stack stopped
- [ ] 9.3 Reviewer pass; PR with `!` commits; CI and Sonar green; merged; change archived
