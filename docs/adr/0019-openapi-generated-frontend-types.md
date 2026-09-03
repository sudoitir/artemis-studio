# ADR-0019: Frontend API types are generated from the backend's OpenAPI document

- **Status**: accepted
- **Date**: 2026-09-03
- **Deciders**: Mahdi Amirabdollahi

## Context

Phase 1's `web/src/api/client.ts` hand-writes TypeScript interfaces that mirror
the Java records in `web/dto/ClusterViews.java` — field names matched one to one,
with no guard against drift. It worked for one controller and nine DTOs.

Phase 2 adds roughly six new view types (queues, addresses, consumers, sessions,
connections, producers), a paged-view envelope, settings DTOs, and the SSE event
shape. Every one would be a second hand-written mirror, and a rename or a field
type change on the Java side would silently produce `undefined` in the browser.

## Decision

We will generate the frontend's API types from the backend's OpenAPI document.

- **Backend:** add `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0`
  (its 3.x line is built against Spring Boot 4.1.0). It exposes `/v3/api-docs`
  (JSON) and a Swagger UI — acceptable on an internal tool.
- **Frontend:** add `openapi-typescript` as a dev dependency and a
  `gen:api` script that writes `web/src/api/schema.d.ts` from the spec. It runs
  in `npm run build`. A committed spec snapshot lets CI regenerate and diff
  without a running backend.
- `web/src/api/client.ts` is rewritten to consume `paths` and
  `components["schemas"]` from the generated file. Every hand-written DTO
  interface is deleted. `ApiError` (RFC 9457 parsing) and the query-key helpers
  stay.
- Contract drift becomes a `verify-web` failure: regenerate, and either the diff
  is intended or a type no longer compiles.

**Fallback.** If `springdoc` 3.1.0 proves unstable against Spring Boot 4.1 in
practice, we keep hand-written types **plus** a thin runtime shape check
(`assertShape(res, "QueueView")`) at the fetch boundary on the new endpoints so a
mismatch fails loudly in the browser instead of rendering `undefined`, and
OpenAPI generation is filed as a fast-follow. This fallback is the recorded
contingency, not a second supported mode.

## Status update (Phase 2 implementation)

The **fallback was taken** for the Phase 2 delivery. The `openapi-typescript`
generation chain needs either a running backend serving `/v3/api-docs` or a
committed spec snapshot regenerated in CI; wiring and validating that was not
feasible in the implementation environment. So Phase 2 ships with hand-written
types in `web/src/api/client.ts` for the new DTOs **plus** `assertShape` /
`assertPaged` runtime boundary checks on every new list endpoint — exactly the
recorded contingency below. `springdoc` + generation is filed as the first
Phase 3 chore. The decision above stands; only its rollout slipped.

## Consequences

- One source of truth for the wire contract: the Java controllers and DTOs.
- `verify-web` now depends on `web/src/api/schema.d.ts` being current; the
  committed snapshot + `gen:api` in `build` keep it honest.
- Two new dependencies (`springdoc` runtime, `openapi-typescript` dev) and a
  build step. Weighed against hand-maintaining ~15 mirrored types with no drift
  guard, this is the cheaper long-term cost.
- Swagger UI is served in all environments. Fine while the tool is internal and
  unauthenticated (Phase 8 owns auth); revisit when auth lands.

## Alternatives considered

- **Keep hand-written mirrors.** Consistent with Phase 1, no new toolchain — but
  every new DTO is a manual mirror with unguarded drift, and Phase 2 roughly
  triples the DTO count. Rejected as the primary approach; retained only as the
  fallback above.
- **Hand-written types + a runtime boundary check, no generation.** Catches
  drift at runtime but not at build time, and still hand-maintains every type.
  This is the fallback, not the choice.
- **A full typed client generator (`openapi-fetch`, `orval`).** Generates
  request functions too, not just types. More output to own and it would replace
  the existing thin `fetch` wrappers and TanStack Query hooks that already work.
  `openapi-typescript` (types only) is the smaller step. Rejected for now.
