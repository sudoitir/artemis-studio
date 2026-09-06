## 1. Schema

- [ ] 1.1 New changeset `changes/017-rr-reply-addresses.sql` (never edit released 007 or 011): add `reply_addresses TEXT[] NOT NULL DEFAULT '{}'` to `rr_expectation`, backfill `ARRAY[reply_address]` where `reply_address IS NOT NULL`, drop `reply_address`
- [ ] 1.2 Register it in `db/changelog/db.changelog-master.xml`
- [ ] 1.3 Confirm `./mvnw verify` still passes with `ddl-auto=validate` against Testcontainers Postgres

## 2. Resolution

- [ ] 2.1 New `broker/rr/ReplyAddressResolver`: `resolve(clusterId, expectation) -> List<String>` and `matches(expectation, address) -> boolean`, backed by distinct `address` values from `QueueSnapshotRepository.findByClusterId`
- [ ] 2.2 Glob compilation: `*` → any run of characters, anchored at both ends, every other character literal (quote the rest); an entry with no `*` is compared by equality, not compiled
- [ ] 2.3 Cap resolution at 32 addresses per expectation; expose that the cap was hit
- [ ] 2.4 Cache the cluster's address set for one scrape interval so `matches` is not a full scan per notification
- [ ] 2.5 `ReplyAddressResolverTest`: literal match, `*` match, anchoring (`legacy.orders.reply.x` does not match `orders.reply.*`), regex metacharacters in an address are literal, empty set resolves to nothing, cap reported

## 3. Backend — expectations

- [ ] 3.1 `RrExpectationEntity`: `replyAddress` → `List<String> replyAddresses`, never null
- [ ] 3.2 `RrViews.CreateExpectationRequest` / `UpdateExpectationRequest` / `ExpectationView`: `replyAddresses`; blanks dropped, duplicates collapsed, order preserved
- [ ] 3.3 `RequestReplyService` create/update/toView carry the list; keep the duplicate-request-address conflict check
- [ ] 3.4 `RequestReplyServiceTest`: multi-entry round-trip; blanks and duplicates normalised away

## 4. Backend — correlation

- [ ] 4.1 `RrCorrelator.onRequestSeen`: stamp `reply_destination` only when resolution yields exactly one literal (D5)
- [ ] 4.2 `RrCorrelator.onReplySeen`: set `reply_destination` from the joining reply when it was not stamped at creation
- [ ] 4.3 `RrNotificationObserver`: forward `MESSAGE_DELIVERED` on a resolved reply address as `ReplySeen`, alongside the existing temp-queue branch (D6)
- [ ] 4.4 `RrCorrelatorTest`: a reply on the second of three resolved addresses completes the flow and records that address
- [ ] 4.5 `RrNotificationObserverTest`: a delivery on a resolved reply address is forwarded; one on an untraced address is not

## 5. Backend — sampling

- [ ] 5.1 `RrSampler.servingNode` → `servingNodes`: every active, error-free node with a Core URL
- [ ] 5.2 Browse the request address and each resolved reply address per node, at the existing page size and cadence
- [ ] 5.3 Per-expectation failure counter: `warn` on first failure then at most once a minute, naming expectation and node; never propagate
- [ ] 5.4 Test: one failing node does not stop the others on the same tick

## 6. Frontend

- [ ] 6.1 `web/src/rr/ExpectationsView.tsx`: reply address becomes a multi-value input bound to `replyAddresses`, accepting patterns
- [ ] 6.2 Show what each pattern currently resolves to as it is typed, and say when it resolves to nothing yet
- [ ] 6.3 Distinguish an empty set ("replies arrive on a temporary queue named by the request") from an unfilled field, since conflating them is what produced the untraceable expectation
- [ ] 6.4 Expectations table renders the set, not a single value
- [ ] 6.5 `ExpectationsView.test.tsx`: creating with two patterns sends both; the empty case is explained rather than silently accepted
- [ ] 6.6 Regenerate `web/src/api/schema.d.ts`

## 7. Close out

- [ ] 7.1 `CHANGELOG.md` `## [Unreleased]`: a `### Breaking` block for `replyAddress` → `replyAddresses` with the migration step, plus Added/Fixed entries
- [ ] 7.2 `just fmt && just verify`
- [ ] 7.3 Verify end to end on `nova-dev-server-20`: declare `nova.fcb.integration.reply.*`, have real requests sent, confirm `rr_flow` rows reach `COMPLETED` with latencies and that each names the reply queue that answered
- [ ] 7.4 Production remains blocked on its own account (`AMQ229099`, Studio authenticates as the `<cluster-user>`); confirm that is recorded outside this change and not mistaken for a defect in it
