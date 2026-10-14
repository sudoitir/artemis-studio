## 1. Reactor
- [ ] 1.1 Move the app into `rahdar/` (with `web/`), add the `pom` root and empty SDK modules; fix the Dockerfile, justfile, CI, `frontend-maven-plugin`, osv-scanner, Dependabot, Sonar and JaCoCo `report-aggregate`
- [ ] 1.2 Proof: green CI, `docker build`, `just demo`

## 2. Characterization harness
- [ ] 2.1 A recording HTTP proxy in front of a Testcontainers broker; golden files (bodies, headers, per-node batch counts) for scrape tier A, browse, purge, bulk move, the capability probe and staging

## 3. SDK
- [ ] 3.1 `openbroker-api`: model, facets, SPI, errors, `module-info.java`, with unit tests
- [ ] 3.2 `openbroker-support`: the `openbroker.match` evaluator, the polling watch helper, rate and counter-reset logic
- [ ] 3.3 `openbroker-tck` skeleton and `openbroker-bom`; japicmp on `openbroker-api`; release and SemVer wiring

## 4. Artemis driver
- [ ] 4.1 Move the Jolokia, Core, MBean, notification and relay code into `openbroker-artemis` unchanged, behind the SPI; `CallInterceptor` replaces the direct cross-cutting calls
- [ ] 4.2 Spring `RestClient` to JDK `HttpClient`, keeping auth, error mapping, observation names and tags, SSL eviction, HTTP/1.1 and timeouts; golden files and a meter-name test green
- [ ] 4.3 The TCK runs in the driver's integration tests

## 5. Gateway
- [ ] 5.1 `platform/broker` becomes the gateway: the driver registry, one `ClusterConnection` per cluster, Rahdar's `CallInterceptor`, credentials from secrets, reconnect on topology or TLS change

## 6. Features onto facets (rising risk; each ends with its tests and the TCK cases for its capability)
- [ ] 6.1 Resources and scrape, then metrics
- [ ] 6.2 Queue lifecycle, with the approval gate on `Operations.preview`
- [ ] 6.3 Browse, send and bulk, then triage
- [ ] 6.4 Notifications onto `Events` (per-node reconciliation, `Gap`, the verdict cache, close under load)
- [ ] 6.5 Request-reply and flow
- [ ] 6.6 Capture, SQL and plugin messaging onto created subscriptions and `Transport`
- [ ] 6.7 Transfer and relay; the relay ITs plus a kill-mid-batch test

## 7. System module
- [ ] 7.1 `system/artemis`: broker.xml configuration, setup rule packs, remedy snippets, through `unwrap`

## 8. Data model
- [ ] 8.1 Edit the module baselines to the neutral names; standard metric keys in metric history, alert rules and dashboards; clean stale changesets in `target/classes`

## 9. Lock-down
- [ ] 9.1 ArchUnit rules, `PluginValidator` deny-list, `Contract.VERSION` bump
- [ ] 9.2 ADRs: the SDK and its prior art, the reactor, roles and kinds, the driver SPI, access and capabilities, metric keys
- [ ] 9.3 Docs; `just demo` including HA; PR merged on green CI and an empty Sonar list; change archived
