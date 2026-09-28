## 1. CSRF and sessions

- [ ] 1.1 `BearerAuthenticationFilter` answers 401 to an `Authorization` header that is not a valid bearer token; the CSRF skip then only covers authenticated bearer requests; `EndpointProtectionTest` covers a junk header plus a cookie
- [ ] 1.2 End sessions through `FindByIndexNameSessionRepository` on disable, on role or grant assignment, and on role permission change; `SessionRevocationIT`; ADR-0123

## 2. Headers

- [ ] 2.1 The Content-Security-Policy in `SecurityConfig`; a header test; the template's Playwright run fails on any `securitypolicyviolation`; ADR-0122
- [ ] 2.2 `Content-Security-Policy: sandbox` on every SVG asset a plugin serves; test

## 3. Scans

- [ ] 3.1 `codeql.yml` and `osv-scanner.yml`; fix what the first runs report; ADR-0124

## 4. Jobs

- [ ] 4.1 `ScheduledJob.Scope` (required) and `minimumGap`; ShedLock (`KeepAliveLockProvider` over `JdbcTemplateLockProvider` using DB time) in `JobScheduler` and `ScrapeScheduler`; `shedlock` changeset; `SKIPPED_ELSEWHERE` counts as a finish; ADR-0125
- [ ] 4.2 Classify every job; update the plugin template, its README and the tests; bump `Contract.VERSION`; `JobSchedulerIT` with two schedulers on one database

## 5. Ship

- [ ] 5.1 `just verify` green; PR; merge on green CI
