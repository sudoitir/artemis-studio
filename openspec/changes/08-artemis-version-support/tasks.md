## 1. Evidence

- [x] 1.1 Read `ActiveMQServerControl` at each release tag for the operations Studio calls; set the minimum (2.33.0, after 1.2 found the security settings call missing in 2.32.0) and latest tested (2.57.0)
- [x] 1.2 Make the integration broker image configurable (`-Dartemis.image`) and run the full suite at 2.32.0; classify every failure

## 2. Version model

- [x] 2.1 `BrokerVersion`: parse, compare, `MINIMUM` / `LATEST_TESTED`, `support()`
- [x] 2.2 `VersionGate` record, per-node assessment, empty `ALL`
- [x] 2.3 `BrokerCommands.Command.requires` and `NodeStatus.UNSUPPORTED_VERSION` (partial, not failed), in the lifecycle view, MCP partial and the node outcome words

## 3. Registration and views

- [x] 3.1 Read each seed's version in `connectAll`; refuse below the minimum with `UNSUPPORTED_VERSION` (422) on the check and the registration
- [x] 3.2 `NodeEndpointView.versionSupport` and `CapabilitiesView.versionGates`; regenerate the OpenAPI snapshot and `schema.d.ts`

## 4. Divert fallback

- [x] 4.1 `DivertOperations.createDivert` falls back to the positional form on a broker without the JSON form
- [x] 4.2 Rerun the classes that failed at 2.32.0 against 2.33.0; the test helper's Jolokia client accepts `text/plain` like Studio's own

## 5. UI

- [x] 5.1 Topology node shows an unsupported or untested release in words
- [x] 5.2 Registration check warns about untested releases; the refusal shows as the check's error
- [x] 5.3 Capability ledger lists version gates with the release they need

## 6. CI, docs, decision

- [x] 6.1 Backend CI matrix over both ends of the range; `BrokerVersionTest` ties it to the constants
- [x] 6.2 Dev and demo compose on `apache/artemis:2.57.0`
- [x] 6.3 `site/src/guide/supported-versions.md` in the sidebar
- [x] 6.4 ADR-0140

## 7. Finish

- [x] 7.1 Contract 6 (japicmp flags `Command.requires`); plugin template follows
- [ ] 7.2 Full backend suite at both ends in CI (the local 2.57.0 run was stopped for memory after 166 classes, all green but the snapshot it rewrote); web build, lint, format and tests green
- [x] 7.3 Studio screenshots (light and dark): refused registration, mixed-version check and topology
- [ ] 7.4 PR, merge on green CI and Sonar, `/opsx:archive`
