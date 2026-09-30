# ADR-0147: The public API is one contract: a marked break, one list shape, problem+json errors, stated limits

- **Status**: accepted
- **Date**: 2026-09-30
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/09-public-api-contract`

## Context

`/api/v1` is used by scripts, CI and the generated clients as much as by the web UI. Its OpenAPI
document is committed (`web/openapi.json`, ADR-0019) and `info.version` was the literal `v1`. There was
no rule for what counts as a break and nothing that stopped one: a renamed field or a removed endpoint
merged like any other change. About 30 list endpoints returned a bare array, five had their own page
view and two took only `limit`. Errors were problem+json (RFC 9457) on most paths, but 401 had an empty
body and the framework's own exceptions answered in Boot's default JSON. Limits were signalled on token
requests only (ADR-0136). Before stable there is no compatibility promise (`36-stable-release` sets it),
so the need is that a break is visible, not that it is avoided.

## Decision

- **A break is flagged by the Conventional Commit marker.** The `api-compat` job of pull requests runs
  `oasdiff breaking --fail-on ERR` between the latest CalVer tag's `web/openapi.json` and the PR's. It
  passes an ERR-level change only when a commit in `origin/main..HEAD` has `!:` in its subject or a
  `BREAKING CHANGE:` footer, and otherwise fails printing oasdiff's list. The same marker puts the
  commit under `### Breaking` in the release notes (ADR-0051), so CI and the release cannot disagree.
  `just api-diff` runs the same script. oasdiff is pinned by image digest (ADR-0139).
- **`info.version` is the running Studio version**, so a client can read what it talks to; the snapshot
  test pins a placeholder to keep the committed file stable, and the release stamps the real version into
  the published document.
- **Versioning and deprecation use Spring Framework 7 API versioning.** The version stays a path segment
  (`/api/v1/...`): controllers drop the literal prefix and one `/api/{version}` path prefix supplies it, the
  supported version is `1`, and any other gets 400 `invalid-api-version`. An incompatible endpoint later
  ships as `version = "2"` beside the v1 mapping. Deprecation uses the built-in
  `StandardApiVersionDeprecationHandler` (`Deprecation`, RFC 9745; `Sunset`, RFC 8594; `Link` with
  `rel="deprecation"` and `rel="sunset"`), fed by one list of declarations, `ApiDeprecations`, which also
  marks the matching operations `deprecated` in the document. There is no hand-written interceptor or
  annotation. The document keeps concrete `/api/v1/...` paths. Before stable a removal needs only the
  break marker; from `36-stable-release` it needs a deprecation announced for a period that change sets.
- **One list shape.** `PagedView<T>` is `{data, page, pageSize, count, hasNext}`; `count` is null only
  where the total is unknown. Every list takes 1-based `page` and `size` (default 50, at most 500;
  out of range is 400 `invalid-value`). The bespoke page views and `limit` parameters are removed, bare
  lists are wrapped, and a test fails for any `/api/v1` GET that returns a top-level array. Offset over
  cursor: resource lists are assembled in memory from a per-node fan-out, so there is no stable cursor.
- **Every error is problem+json** with a stable type under `https://artemis-studio.dev/problems/`,
  including 401, 403, CSRF, framework exceptions and a 500 that hides its cause behind a request id. The
  document declares `4XX`/`5XX` problem responses, and 429 with `RateLimit-*` and `Retry-After`, on every
  operation; every 429 sends `Retry-After`.
- **Contract tests check every response** against the committed document with undocumented properties
  rejected, so a drift fails the test that produced it.

## Consequences

- A break costs one character in a commit subject, and an accidental one fails the PR.
- The list, error and header conventions are enforced by tests rather than review.
- The change is itself breaking (list bodies, `limit`, 401/403 bodies), shipped with `!` commits and a
  migration note; the web UI is updated with it.
- The baseline is the previous release, so a PR that follows a release which already contained a break
  compares against that release, not against `main`.
- The plugin gateway (`/api/v1/p/**`) and MCP (ADR-0045) keep their own conventions.

## Alternatives considered

- **An `ApiContract.VERSION` integer**, like `Contract.VERSION`. A second version number beside CalVer
  that says nothing in the release notes.
- **openapi-diff** instead of oasdiff. Less maintained, and no stable exit codes.
- **A hand-written `@ApiDeprecation` interceptor.** The framework already implements the RFC headers and version routing.
- **Header or media-type versioning.** URLs stay `/api/v1/...`, which the clients and `schema.d.ts` already use.
- **Cursor pagination everywhere.** No stable cursor exists over the in-memory fan-out; an endpoint that
  needs one can add it without changing the envelope.
- **Baseline from a release download.** The snapshot is committed at every tag, so `git show` needs no
  network or asset.
