## 1. Design
- [x] 1.1 Brainstorm and investigate; `/opsx:update` adds design.md, sharpens specs and replaces these tasks

## 2. Fixes found on the way (own commits)
- [x] 2.1 `StudioInstance.mint`: concurrent first boot mints once (INSERT ON CONFLICT DO NOTHING, re-read)
- [x] 2.2 `BrokerEventWriter`: publish the seqs its own insert produced, after commit, not a global cursor

## 3. Foundation (D1, D3, D8)
- [ ] 3.1 `studio_replica` changelog (drop `studio_boot`), `ReplicaRegistry` with DB-time heartbeat thread, states, reaping in housekeeping
- [ ] 3.2 `PluginHost` crash-loop counts crashes from the registry; stop writes `stopped_at`
- [ ] 3.3 `StudioBus`: dedicated LISTEN connection, `pg_notify` publish in-transaction, frame/events/signal payloads, `ReplicaSignal`, `BusResumed`
- [ ] 3.4 Readiness: `replica` indicator, `/livez` `/readyz`, `timeout-per-shutdown-phase`; DRAIN and RUNS shutdown phases

## 4. Stream across replicas (D4)
- [ ] 4.1 `SseHub.publish` broadcasts, `deliver` local; events batches loaded by seq; 7,500-byte downgrade
- [ ] 4.2 `StreamController`: `lastEventId` param, buffered replay-then-live, `resync` on cap, 503 while draining; `reconnect` on drain
- [ ] 4.3 Client: send last id, handle `reconnect` and `resync`, refetch after a failed reconnect; SQL tail reopens on `reconnect`

## 5. Cluster ownership (D2)
- [ ] 5.1 `cluster_lease` changelog, `ClusterOwnership` (HRW, renew, release, acquire, orphans, self-fence), duty events
- [ ] 5.2 Gate scrape tiers, discovery, alert rules, drift, setup review, rr-sampler, capture/index reconcile, flow sample, plugin messaging, clock offset
- [ ] 5.3 Release drops subscriptions, consumers, `ScrapeCycle`, `StreamSignals`; acquire kicks tier A

## 6. Cache coherence (D5)
- [ ] 6.1 Signals for settings, cluster-deleted, env-index, policy (delete the probe job), session-ended, leases
- [ ] 6.2 Plugins: `reconcileRuntimes` on signal; deterministic `manifestVersion`
- [ ] 6.3 Sweep remaining component-local caches and classify each

## 7. Shared small stores (D6, D7)
- [ ] 7.1 `cluster_node.split_brain`; delete `SplitBrainRegistry`; readers use rows
- [ ] 7.2 `sql_query_ticket` table; `api_request_window` table

## 8. Runs across replicas (D9)
- [ ] 8.1 `replica_id` on bulk and transfer runs; recovery job interrupts only runs of gone replicas
- [ ] 8.2 `run-stop` signal; RUNS phase waits then stops and records INTERRUPTED with progress

## 9. Self-health (spec: one view)
- [ ] 9.1 Replicas section in `StudioHealthController` and `StudioHealth.tsx` (host, version, state, heartbeat age, owned clusters, answering replica)

## 10. Deployment, tests, docs (D10)
- [ ] 10.1 `compose.ha.yaml`, `compose.ha.test.yaml`, `ha/haproxy.cfg`; dev/prod healthchecks on `/readyz`
- [ ] 10.2 `HaReplicasIT` (two contexts)
- [ ] 10.3 `web/scripts/ha-failover.ts` and the `image` job steps in CI
- [ ] 10.4 ADR-0148 (supersedes parts of ADR-0018 and ADR-0093); `site/src/guide/high-availability.md` and nav

## 11. Finish
- [ ] 11.1 `just verify` green; self-health screenshots (light, dark, empty, error)
- [ ] 11.2 Review, PR, green CI and Sonar, merge; `/opsx:archive`
