## Context

See proposal.md for why. Current state that shapes the approach:

- springdoc 3.1.1 serves `/v3/api-docs` (OpenAPI 3.1). `OpenApiSnapshotTest` writes a key-sorted `web/openapi.json`; CI fails on a stale snapshot, and `openapi-typescript` turns it into `web/src/kernel/api/schema.d.ts` (ADR-0019). `info.version` is the literal `"v1"`.
- `kernel/core/Problems` already mints RFC 9457 problems with the stable type base `https://artemis-studio.dev/problems/<slug>`. `ApiExceptionHandler` and eight module advices use it. Paths that do not: `SecurityConfig`'s `HttpStatusEntryPoint(401)` (empty body), `BearerAuthenticationFilter.sendError(401)`, core `AccessDeniedException` and CSRF denials, and every framework exception no advice handles (Boot's default `/error` JSON).
- `kernel/core/PagedView {data, count, page, pageSize}` with `ResourceQuery` (1-based `page`, `size` default 50 cap 500, `q`, `sort`) covers the cluster resource and routing lists. Five lists have bespoke page views (messages, audit, events, rr flows, alert history), two take only `limit` (channel deliveries, config applies), and about 30 return a bare `List<>`.
- `TokenRequestLimitFilter` (ADR-0136) sends `RateLimit-Limit/Remaining/Reset` on every token request and `Retry-After` plus `rate-limited` on 429. Sessions are never limited. Other 429s (`login-throttled`, `too-many-queries`) send no `Retry-After`.
- 109 mutating mappings, no idempotency support. Bulk and transfer already guard execution with a preview id and plan hash.
- There are no deprecations anywhere yet.
- Releases are CalVer (ADR-0042). git-cliff turns `!` / `BREAKING CHANGE:` commits into the `### Breaking` block (ADR-0051). The plugin API's japicmp check (ADR-0102) is the "flag it or fail" precedent. Maven Central (`publish-api`) and npm trusted publishing (`publish-sdk`) jobs already exist in `ci.yml`, and every artifact gets provenance and an SBOM (ADR-0139).

## Goals / Non-Goals

**Goals:**
- One convention every endpoint follows, enforced by tests rather than review.
- A break is visible twice: CI refuses it unless the commit marks it, and the marker puts it in the release notes.
- Clients that are generated from the same document the tests check, so they cannot drift.

**Non-Goals:**
- Shipping a version 2. Only version `1` exists; the versioning machinery is in place for when a break needs one.
- Rate-limiting browser sessions (ADR-0136 stands).
- Moving the web UI to the generated client. It keeps `schema.d.ts` + `request.ts`.
- MCP. It is its own surface (ADR-0045) and keeps its own errors and limits.
- The plugin gateway (`/api/v1/p/**`, `/api/v1/clusters/*/p/**`). It is excluded from the document and belongs to each plugin.

## Decisions

### D1. A break is flagged by the Conventional Commit marker
The CI job `api-compat` runs `oasdiff breaking --fail-on ERR` between `git show <latest CalVer tag>:web/openapi.json` and the PR's `web/openapi.json`. On an ERR-level change it passes only when a commit in `origin/main..HEAD` has `!:` in the subject or a `BREAKING CHANGE:` footer. Otherwise it fails, printing oasdiff's list, which names each change. The marker is the same act that puts the break under `### Breaking`, so "flagged in CI" and "flagged in release notes" cannot disagree.
- Baseline from the tag, not a release download: the snapshot is committed, every tag has it, and the job needs no network or asset.
- oasdiff over openapi-diff: it is maintained, supports OpenAPI 3.1 (GA since 1.15.0), and has stable exit codes. It runs pinned by version. `just api-diff` runs the same comparison locally.
- Alternatives: an `ApiContract.VERSION` integer like `Contract.VERSION`. It was rejected because it is a second version number beside CalVer that says nothing in the release notes.

### D2. Versioning and deprecation use Spring Framework 7 API versioning
- The API version is a first-class Spring MVC concept (Framework 7 `ApiVersionConfigurer`, Boot 4.1 `spring.mvc.apiversion.*`):
  - It is resolved from the path segment, so URLs stay `/api/v1/...`.
  - Controllers drop the literal `/api/v1` from their mappings. One `PathMatchConfigurer.addPathPrefix("/api/{version}", …)` for Studio's REST controllers supplies it.
  - The supported version is `1`. Any other version gets 400 problem+json (`InvalidApiVersionException` → `invalid-api-version`).
  - A future incompatible endpoint ships as `@GetMapping(version = "2")` beside the v1 mapping instead of replacing it.
  - Unversioned mappings match every supported version.
- Deprecation uses the built-in `StandardApiVersionDeprecationHandler`:
  - It sends `Deprecation` (RFC 9745), `Sunset` (RFC 8594) and `Link rel="deprecation"`/`rel="sunset"` headers.
  - Declarations live in one place, `ApiDeprecations`: version, optional request predicate for a single endpoint, dates and links. The same declarations feed an `OpenApiCustomizer` that marks matching operations `deprecated: true` and appends the sunset to the description.
  - Nothing is deprecated today. A test configuration proves the headers and the document flag.
- Spring's mechanism is used instead of a hand-written `@ApiDeprecation` interceptor, because the framework already implements the RFC headers and version routing. We keep only the declaration list and the document flag.
- The generated document keeps concrete `/api/v1/...` paths, with no `{version}` template parameter, so clients and `schema.d.ts` are unchanged. If springdoc renders the prefix as a template, the `OpenApiCustomizer` resolves it to `v1`.
- `info.version` is the running Studio version from `BuildProperties`, so a client can read what it talks to from `/v3/api-docs`. The snapshot test pins it to a placeholder so the committed file stays stable. The release job stamps the real version into the published asset.
- The policy is a docs-site page:
  - Before stable, breaks are allowed and must carry the marker.
  - From `36-stable-release`, a removal needs a deprecation that has been announced for a period that change sets.

### D3. One list convention
- `PagedView<T>` becomes `{data, page, pageSize, count, hasNext}`.
  - `count` is nullable only where the total is unknown (browsing messages).
  - `hasNext` makes the next page discoverable without a count.
- Every list endpoint takes `page` (1-based) and `size` (default 50, max 500; out of range → 400 `invalid-value`) through `ResourceQuery`, next to its own filters. Filter-free lists ignore `q`/`sort` only if they have nothing to search.
- The audit, flow and alert-history page views fold into `PagedView`. The message and broker-event pages keep their own types, because they carry facts about the page itself (`countUnavailable`, `node`, `transport`, `dropped`, `oldestRetained`), but they have the same envelope fields. The `limit` parameters are removed.
- The UI's full-list reads (`requestAll`) follow `hasNext` across pages, so a list longer than 500 is never silently cut short.
- Bare-list endpoints are wrapped and paginated in memory when their source is in memory, and in SQL (`LIMIT/OFFSET`) when it is a table.
- Non-list singletons (settings, data stores, group mappings) keep their shape.
- Offset over cursor: the resource lists are assembled in memory from a per-node fan-out, so there is no stable cursor to hand out. Table-backed lists are small or time-ordered with filters. A cursor can be added to a specific endpoint later without breaking the envelope.
- `OpenApiSnapshotTest` gains an assertion that no `/api/v1` GET returns a top-level array. That turns the convention into a build check.

### D4. Every error is problem+json
- `ProblemAuthenticationEntryPoint` (401 `unauthenticated`) and `ProblemAccessDeniedHandler` (403 `forbidden`, and `csrf` for CSRF denials) are wired into `SecurityConfig`. `BearerAuthenticationFilter` delegates to the entry point instead of calling `sendError`.
- `ApiExceptionHandler` extends `ResponseEntityExceptionHandler` and routes framework exceptions through `Problems`:
  - `bad-request`, `method-not-allowed`, `not-acceptable`, `unsupported-media-type`;
  - `not-found` for no handler;
  - `access-denied` for method security.
- A last `Exception` handler returns 500 `internal-error` with the request id and no message. An `ErrorController` for `/error` does the same for anything that escapes MVC.
- `spring.mvc.problemdetails` stays off. Our handler owns all of it, so there is one type scheme.
- An `OpenApiCustomizer` adds `4XX`/`5XX` `application/problem+json` responses (schema `Problem`) to every operation.
  - Every mutating operation also gets the `Idempotency-Key` header parameter.
  - Every operation gets 429 with `RateLimit-*` and `Retry-After` headers.
- Other 429s (`login-throttled`, `too-many-queries`) gain `Retry-After`.

### D5. Idempotency keys, persisted and scoped to the user
- An `IdempotencyFilter` (after security, so the principal is known) handles `POST/PUT/PATCH/DELETE /api/v1/**` requests that carry `Idempotency-Key`. It passes plugin-gateway requests through untouched, because the plugin owns the header there. It refuses multipart uploads that carry a key with 400 `idempotency-unsupported`, so a client never assumes a guarantee it does not have. A request without the header is unchanged.
- Key: 1–255 printable ASCII. Scope: the caller's user id. Tokens and sessions of one user share a namespace, and different users never do.
- Fingerprint: SHA-256 over method, path, sorted query string and body bytes. `dryRun=true` and the real run therefore differ.
- Table `idempotency_record`:
  - columns: `user_id, idem_key` (primary key), `fingerprint, state (PENDING|DONE), status, content_type, body bytea, created_at`;
  - claimed with `INSERT … ON CONFLICT DO NOTHING` through `JdbcClient`.
- Outcomes:
  - **Claimed:** run the chain with a caching response wrapper. On 2xx–4xx, store the result as `DONE`. On 5xx or an exception, delete the row so the retry runs.
  - **Existing `DONE`, same fingerprint:** replay status, `Content-Type` and body, with `Idempotent-Replayed: true`. Nothing runs.
  - **Existing, different fingerprint:** 422 `idempotency-key-reused`.
  - **Existing `PENDING`:** 409 `idempotency-in-progress`, with `Retry-After: 1`.
- Retention is 24 hours. An `IdempotencyStore` `ManagedStore` (ADR-0134) purges expired rows, so it appears on the data-lifecycle page like every other store. SSE and streaming responses are GETs and never pass the filter.
- Alternatives:
  - In memory: rejected, because it loses the guarantee across a restart, which is exactly when clients retry.
  - Per-endpoint opt-in: rejected, because the requirement is every mutating endpoint.

### D6. Contract tests validate every MockMvc response
- A test-scope `MockMvcBuilderCustomizer` adds a result handler to every auto-configured MockMvc. For each `/api/v1` response with a JSON body, it validates the body against the document's schema for that operation and status. The document used is `web/openapi.json` with `additionalProperties: false` imposed on every object schema.
- An undocumented field, a missing required field or a wrong type fails the test that made the request. The 66 existing MockMvc test classes become contract tests without being rewritten.
- A dedicated test proves that a response carrying an extra field fails.
- The validator library is picked for OpenAPI 3.1 / JSON Schema 2020-12 support (checked with ctx7 at apply time). It is test scope only.

### D7. Release artifacts and generated clients
- **OpenAPI document.** The release job copies `web/openapi.json`, sets `info.version` to the release version, and attaches it as `artemis-studio-<V>.openapi.json` with `.sha256` and `actions/attest` provenance, next to the jar.
- **TypeScript client.** `web/packages/client` → `@artemis-studio/client`:
  - `openapi-typescript` types plus `openapi-fetch`, with a tiny `createStudioClient({baseUrl, token})` that adds the bearer header and turns problem+json into a typed `ProblemError`.
  - Built like `plugin-sdk` (`build.mjs` stamps the version).
  - Published from `ci.yml` with provenance on every release.
- **Java client.** `clients/java/pom.xml` → `io.github.sudoitir:artemis-studio-client`:
  - a standalone pom, since the Studio pom has no modules;
  - `openapi-generator-maven-plugin` with generator `java`, library `native` (java.net.http, Jackson), fed from `web/openapi.json`;
  - built in PR CI so a generator failure shows up before release;
  - published on every release, signed and attested like `publish-api`.
- **Release filter.** `web/openapi.json`, `clients/**` and `web/packages/client/**` join the `changes.release` path filter.
- **Why generated per release.** Clients generated from the tested document on every release are "of that version" by construction. Hand-written clients would drift.

## Risks / Trade-offs

- [Wrapping ~30 bare lists breaks every UI caller] → The frontend is updated in the same change. `schema.d.ts` makes each broken caller a type error, so `tsc -b` finds them all.
- [The idempotency filter buffers request and response bodies] → Multipart is excluded. JSON bodies here are small, and bulk bodies are already capped (ADR-0022).
- [A crash mid-request leaves a `PENDING` key that answers 409 until the 24 h purge] → Taking over a live claim could run a mutation twice. Long work is asynchronous (202 plus a run id), so a stuck claim only blocks a retry that should use a new key.
- [Replays carry only the status, content type and body, not headers such as `Location`] → The body already names what was created. 401, 403 and 429 are not recorded (like 5xx), so a refusal is never replayed. Bodies over 8 MiB with a key are refused with `idempotency-unsupported`.
- [Spring keeps one deprecation spec per API version, so `v1` can carry one endpoint-level deprecation at a time] → Several endpoints share one declaration through a combined predicate. Deprecations with different sunsets are the case a new version exists for.
- [Replaying a stored 4xx means a fixed request needs a new key] → This is the documented semantics (same key = same result). Clients mint a key per logical attempt.
- [Strict `additionalProperties: false` validation will surface existing undocumented fields] → Each one is fixed by documenting it or removing it. That is the point.
- [npm trusted publishing must be configured for a new package] → This is a one-time manual step on npmjs.com by the maintainer, called out in the PR.
- [Root disk near 90% makes broker ITs hang] → Check `df -h /` before the full verify.

## Migration Plan

Breaking, before stable (no compatibility, per the standing decision). Commits are marked `!` with migration notes: list responses are now `{data, page, pageSize, count, hasNext}`; the `limit` params are replaced by `page`/`size`; 401/403 bodies are problem+json. No data migration is needed. The new table starts empty. Rollback is reverting the release.
