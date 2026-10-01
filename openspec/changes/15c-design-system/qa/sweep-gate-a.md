# QA log: sweep after the foundation (gate A)
Production build, every route at 1920/1440/1280 px in light, dark and system, 200% zoom, and the loading, error, empty, filtered and forbidden states at 1280. Each page unit fixes its area's items; one line per distinct finding.

## admin
- [ ] `admin-api-keys` (1280 light default): layout shift CLS 0.0126
- [ ] `admin-api-keys` (1440 light default): layout shift CLS 0.0135
- [ ] `admin-api-keys` (zoom light default): layout shift CLS 0.0376
- [ ] `admin-data` (zoom light default): layout shift CLS 0.0388
- [ ] `admin-diagnostics` (zoom light default): layout shift CLS 0.0283
- [ ] `admin-environments` (1920 dark default): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js
- [ ] `admin-governance-findings` (zoom light default): layout shift CLS 0.0283
- [ ] `admin-governance-rules` (1280 dark default): page overflow {'scrollWidth': 4435, 'clientWidth': 1280}
- [ ] `admin-plugins` (1280 dark default): axe link-in-text-block on p > .m_849cf0da.mantine-Anchor-root[data-underline="hover"]
- [ ] `admin-plugins` (zoom light default): layout shift CLS 0.0283
- [ ] `admin-roles` (1280 light default): layout shift CLS 0.0111
- [ ] `admin-users` (zoom light default): page overflow {'scrollWidth': 823, 'clientWidth': 640}
- [ ] `admin-users` (zoom light default): layout shift CLS 0.0473

## alerting
- [ ] `alerts-firing` (zoom light default): layout shift CLS 0.2109
- [ ] `alerts-history` (zoom light default): layout shift CLS 0.0222
- [ ] `alerts-rules` (1280 dark default): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js
- [ ] `alerts-rules` (zoom light default): layout shift CLS 0.2287

## audit
- [ ] `audit` (1280 light default): layout shift CLS 0.1018
- [ ] `audit` (1440 light default): layout shift CLS 0.1086
- [ ] `audit` (1920 dark default): layout shift CLS 0.0785
- [ ] `audit` (zoom light default): layout shift CLS 0.1796

## brokerconfig
- [ ] `config-diff` (1280 dark default): axe scrollable-region-focusable on #mantine-3hj62zdvb-panel-broker > .m_4ba554d4.mantine-Accordion-content > ._scroll_m6eis_3, #mantine-3hj62zdvb-panel-addressSettings > .m_4ba554d4.mantine-Accordion-content > ._scroll_m6eis_3
- [ ] `config-diff` (zoom light default): layout shift CLS 0.2116
- [ ] `configuration-declared` (1280 dark default): axe link-in-text-block on p:nth-child(4) > .m_849cf0da.mantine-Anchor-root[data-underline="hover"]
- [ ] `configuration-declared` (1280 dark default): layout shift CLS 0.0346
- [ ] `configuration-declared` (1440 system default): layout shift CLS 0.0354
- [ ] `configuration-declared` (zoom light default): layout shift CLS 0.043
- [ ] `configuration-history` (1280 light default): layout shift CLS 0.0506
- [ ] `configuration-history` (1440 light default): layout shift CLS 0.0468
- [ ] `configuration-history` (zoom light default): layout shift CLS 0.0299
- [ ] `configuration-recommended` (1280 light default): layout shift CLS 0.0424
- [ ] `configuration-recommended` (1440 dark default): layout shift CLS 0.033
- [ ] `configuration-recommended` (1920 dark default): layout shift CLS 0.0313
- [ ] `configuration-recommended` (zoom light default): axe scrollable-region-focusable on .m_f744fd40 > .m_c0783ff9.mantine-ScrollArea-viewport[data-scrollbars="xy"]
- [ ] `configuration-recommended` (zoom light default): layout shift CLS 0.0257
- [ ] `config-diff` (1280 light error): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/sdk-MhpnaN1g.js
- [ ] `configuration-declared` (1280 dark empty): layout shift CLS 0.0106
- [ ] `configuration-history` (1280 dark empty): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/sdk-MhpnaN1g.js

## bulk
- [ ] `bulk-run-unknown` (zoom light default): layout shift CLS 0.1633
- [ ] `bulk` (zoom light default): layout shift CLS 0.043
- [ ] `bulk` (1280 light error): layout shift CLS 0.0304

## clusters
- [ ] `topology` (zoom light default): layout shift CLS 0.0605
- [ ] `topology` (1280 dark loading): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js

## events
- [ ] `events` (1280 dark default): layout shift CLS 0.1018
- [ ] `events` (1440 dark default): layout shift CLS 0.0968
- [ ] `events` (1440 light default): layout shift CLS 0.0956
- [ ] `events` (1920 dark default): layout shift CLS 0.0786
- [ ] `events` (1920 light default): layout shift CLS 0.0791
- [ ] `events` (zoom light default): layout shift CLS 0.2397

## flow
- [ ] `flow-table` (1280 dark default): layout shift CLS 0.0262
- [ ] `flow-table` (zoom light default): layout shift CLS 0.0586
- [ ] `flow` (1280 dark default): layout shift CLS 0.0163
- [ ] `flow` (1280 light default): axe aria-prohibited-attr on ._overlay_1hdbz_11
- [ ] `flow` (zoom light default): axe target-size on ._minimapButton_1hdbz_266
- [ ] `flow` (zoom light default): layout shift CLS 0.2282

## identity-local
- [ ] `enrol-second-factor` (1280 dark default): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js
- [ ] `enrol-second-factor` (1440 system default): layout shift CLS 0.0307
- [ ] `enrol-second-factor` (1920 light default): layout shift CLS 0.0294
- [ ] `enrol-second-factor` (zoom light default): axe aria-prohibited-attr on .m_b34414df
- [ ] `enrol-second-factor` (zoom light default): layout shift CLS 0.017

## messages
- [ ] `dlq` (zoom light default): layout shift CLS 0.0299
- [ ] `messages-dlq-queue` (1280 dark default): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js
- [ ] `messages-dlq-queue` (1280 light default): layout shift CLS 0.0278
- [ ] `messages-dlq-queue` (1440 dark default): layout shift CLS 0.1326
- [ ] `messages-dlq-queue` (1440 light default): layout shift CLS 0.0213
- [ ] `messages-dlq-queue` (zoom light default): layout shift CLS 0.1138
- [ ] `messages-long-name` (1440 light default): layout shift CLS 0.0777

## metrics
- [ ] `metrics` (1440 dark default): axe scrollable-region-focusable on .m_a100c15

## plugins
- [ ] `plugin-unavailable-root` (zoom light default): layout shift CLS 0.0283

## queues
- [ ] `queues` (1280 dark default): layout shift CLS 0.243
- [ ] `queues` (1440 dark default): layout shift CLS 0.2284

## resources
- [ ] `addresses` (1280 dark default): layout shift CLS 0.1145
- [ ] `addresses` (1440 light default): layout shift CLS 0.1423
- [ ] `connections` (1280 dark default): layout shift CLS 0.0221
- [ ] `connections` (1440 dark default): layout shift CLS 0.0206
- [ ] `connections` (zoom light default): layout shift CLS 0.2191
- [ ] `consumers` (1280 dark default): layout shift CLS 0.0644
- [ ] `consumers` (1440 light default): layout shift CLS 0.0728
- [ ] `consumers` (1920 dark default): layout shift CLS 0.0606
- [ ] `consumers` (zoom light default): layout shift CLS 0.219
- [ ] `sessions` (1280 dark default): layout shift CLS 0.0447
- [ ] `sessions` (1440 light default): layout shift CLS 0.0467
- [ ] `sessions` (1920 dark default): layout shift CLS 0.0438
- [ ] `sessions` (zoom light default): layout shift CLS 0.2251
- [ ] `addresses` (1280 light filtered-empty): layout shift CLS 0.0303

## routing
- [ ] `routing` (1440 dark default): layout shift CLS 0.0155
- [ ] `routing` (1920 dark default): layout shift CLS 0.0101

## rr
- [ ] `rr-expectations` (zoom light default): layout shift CLS 0.0299
- [ ] `rr-flows` (1280 dark default): axe link-in-text-block on a[target="_blank"]
- [ ] `rr-flows` (zoom light default): layout shift CLS 0.0412
- [ ] `rr-latency` (1920 dark default): console: uncaught: Failed to fetch dynamically imported module: http://127.0.0.1:18080/assets/hostInit-CNMgCAa2.js
- [ ] `rr-latency` (zoom light default): layout shift CLS 0.0629
- [ ] `rr-stuck` (zoom light default): layout shift CLS 0.1257

## settings
- [ ] `settings-alerting-channels` (zoom light default): page overflow {'scrollWidth': 925, 'clientWidth': 640}
- [ ] `settings-alerting-channels` (zoom light default): layout shift CLS 0.2337
- [ ] `settings-clusters-capabilities` (zoom light default): layout shift CLS 0.0104
- [ ] `settings-clusters-credentials` (1280 dark default): layout shift CLS 0.0134
- [ ] `settings-clusters-credentials` (1440 dark default): layout shift CLS 0.0133
- [ ] `settings-clusters-credentials` (zoom light default): layout shift CLS 0.3246
- [ ] `settings-clusters-register` (zoom light default): layout shift CLS 0.2289
- [ ] `settings-clusters-remove` (zoom light default): layout shift CLS 0.24
- [ ] `settings-display` (1440 dark default): layout shift CLS 0.013
- [ ] `settings-health` (1280 light default): layout shift CLS 0.0158
- [ ] `settings-operational` (zoom light default): layout shift CLS 0.0104
- [ ] `settings-security` (1920 light default): layout shift CLS 0.011
- [ ] `settings-security` (zoom light default): layout shift CLS 0.0588
- [ ] `settings-sql-index` (1920 dark default): layout shift CLS 0.0155
- [ ] `settings-sql-index` (zoom light default): layout shift CLS 0.2289
- [ ] `settings-clusters-credentials` (1280 dark error): layout shift CLS 0.0198
- [ ] `settings-security` (1280 dark forbidden): axe aria-prohibited-attr on div[aria-label="Loading key status"]

## setupreview
- [ ] `setup-review` (zoom light default): axe scrollable-region-focusable on .m_6d731127.mantine-Stack-root:nth-child(6) > article > .m_6d731127.mantine-Stack-root > .m_6d731127.mantine-Stack-root:nth-child(5) > pre, .m_6d731127.mantine-Stack-root:nth-child(7) > article:nth-child(2) > .m_6d731127.mantine-Stack-root > .m_6d731127.mantine-Stack-root:nth-child(5) > pre
- [ ] `setup-review` (zoom light default): layout shift CLS 0.0669

## shell
- [ ] `account` (1280 dark default): layout shift CLS 0.11
- [ ] `account` (1280 light default): layout shift CLS 0.0817
- [ ] `account` (1920 dark default): layout shift CLS 0.0773
- [ ] `account` (zoom light default): layout shift CLS 0.0283
- [ ] `home` (zoom light default): layout shift CLS 0.0344
- [ ] `account` (1280 dark empty): layout shift CLS 0.0379
- [ ] `account` (1280 dark error): axe aria-prohibited-attr on span[aria-label="Loading two-step verification"], span[aria-label="Loading sessions"]

## sql
- [ ] `sql` (1280 dark default): axe scrollable-region-focusable on ._editorPane_3paie_46
- [ ] `sql` (zoom light default): layout shift CLS 0.0104

## transfer
- [ ] `transfer-run-unknown` (zoom light default): layout shift CLS 0.1635

## triage
- [ ] `consumer-health` (1440 dark default): layout shift CLS 0.1129
- [ ] `consumer-health` (1440 light default): layout shift CLS 0.1152
- [ ] `consumer-health` (1920 dark default): layout shift CLS 0.1558
- [ ] `consumer-health` (zoom light default): layout shift CLS 0.2193
- [ ] `consumer-health` (1280 dark filtered-empty): layout shift CLS 0.0303
