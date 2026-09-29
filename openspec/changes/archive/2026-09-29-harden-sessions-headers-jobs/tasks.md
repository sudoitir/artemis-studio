## 1. CSRF and sessions

- [x] 1.1 `BearerAuthenticationFilter` answers 401 to an `Authorization` header that is not a valid bearer token; the CSRF skip then only covers authenticated bearer requests; `EndpointProtectionTest` covers a junk header plus a cookie
- [x] 1.2 Sessions actually in JDBC (`spring-boot-starter-session-jdbc`; session data serializable); end sessions through `FindByIndexNameSessionRepository` on disable, grant removal and role permission change; `SessionRevocationIT`; ADR-0123

## 2. Headers

- [x] 2.1 The Content-Security-Policy in `SecurityConfig`; a header test; the template's Playwright run fails on any `securitypolicyviolation`; ADR-0122
- [x] 2.2 `Content-Security-Policy: sandbox` on every SVG asset a plugin serves; test

## 3. Scans

- [x] 3.1 `codeql.yml` and `osv-scanner.yml`; fix what the first runs report; ADR-0124

## 4. Jobs

- [x] 4.1 `ScheduledJob.Scope` (required) and `minimumGap`; ShedLock (`KeepAliveLockProvider` over `JdbcTemplateLockProvider` using DB time) in `JobScheduler` and `ScrapeScheduler`; `shedlock` changeset; `SKIPPED_ELSEWHERE` counts as a finish; ADR-0125
- [x] 4.2 Classify every job; update the plugin template, its README and the tests; bump `Contract.VERSION`; `JobSchedulerIT` with two schedulers on one database

## 5. Ship

- [x] 5.1 `just verify` green; PR; merge on green CI
