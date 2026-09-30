## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs and replaces these tasks

## 2. Backend
- [x] 2.1 `LogRingBuffer` Logback appender (redacting layout, 5 000 lines), attached at startup and after Boot's logging reset; unit test that a planted secret is stored redacted
- [x] 2.2 `diagnostics` module: descriptor, `@FeatureModule`, `package-info`, `diagnostics:bundle` permission, registered in `StudioFeatures`
- [x] 2.3 `DiagnosticsService`: sections about/settings/health/threads/plugins/logs, each redacted; the environment summary; snapshot cache bound to the user (10 min)
- [x] 2.4 `DiagnosticsController`: `GET /api/v1/diagnostics/summary`, `POST /api/v1/admin/diagnostics/bundles`, `POST …/bundles/{id}/download` (zip, audited `CREATE_DIAGNOSTICS_BUNDLE`)
- [x] 2.5 Integration test: 403 for a non-admin, zip holds only the kept sections, audit row, a planted secret in logs and settings and a message body and property absent, summary for a viewer, foreign snapshot 404
- [x] 2.6 ADR for the log buffer and the previewed snapshot; module doc

## 3. Web
- [x] 3.1 Regenerate `openapi.json` / `schema.d.ts`; `api.ts` hooks and a raw download fetch; move `download()` to `web/src/ui/`
- [x] 3.2 Diagnostics admin tab: intro, prepare, section list with sizes and redaction counts, preview with `[redacted]` highlighted, exclude, expiry countdown, download, audit link, empty, error and no-permission states
- [x] 3.3 Report-a-bug dialog from the user menu and Spotlight: title and template, environment block, Copy, Open on GitHub (URL cap fallback)
- [x] 3.4 Vitest tests for both

## 4. Docs and finish
- [x] 4.1 User guide page `site/src/guide/diagnostics.md` and sidebar
- [ ] 4.2 `just verify` green; screenshots light and dark
- [ ] 4.3 PR, green CI and Sonar, merge, `/opsx:archive`
