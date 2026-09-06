## 1. Schema

- [x] 1.1 New changeset `changes/017-rr-reply-addresses.sql` (never edit released 007 or 011): add `reply_addresses TEXT[] NOT NULL DEFAULT '{}'` to `rr_expectation`, backfill `ARRAY[reply_address]` where `reply_address IS NOT NULL`, drop `reply_address`
- [x] 1.2 Register it in `db/changelog/db.changelog-master.xml`
- [x] 1.3 Confirm `./mvnw verify` still passes with `ddl-auto=validate` against Testcontainers Postgres

## 2. Resolution

- [x] 2.1 New `broker/rr/ReplyAddressResolver`: `resolve(clusterId, expectation) -> List<String>` and `matches(expectation, address) -> boolean`, backed by distinct `address` values from `QueueSnapshotRepository.findByClusterId`
- [x] 2.2 Glob compilation: `*` → any run of characters, anchored at both ends, every other character literal (quote the rest); an entry with no `*` is compared by equality, not compiled
- [x] 2.3 Cap resolution at 32 addresses per expectation; expose that the cap was hit
- [x] 2.4 Cache the cluster's address set for one scrape interval so `matches` is not a full scan per notification
- [x] 2.5 `ReplyAddressResolverTest`: literal match, `*` match, anchoring (`legacy.orders.reply.x` does not match `orders.reply.*`), regex metacharacters in an address are literal, empty set resolves to nothing, cap reported

## 3. Backend — expectations

- [x] 3.1 `RrExpectationEntity`: `replyAddress` → `List<String> replyAddresses`, never null
- [x] 3.2 `RrViews.CreateExpectationRequest` / `UpdateExpectationRequest` / `ExpectationView`: `replyAddresses`; blanks dropped, duplicates collapsed, order preserved
- [x] 3.3 `RequestReplyService` create/update/toView carry the list; keep the duplicate-request-address conflict check
- [x] 3.4 `RequestReplyServiceTest`: multi-entry round-trip; blanks and duplicates normalised away

## 4. Backend — correlation

- [x] 4.1 `RrCorrelator.onRequestSeen`: stamp `reply_destination` only when resolution yields exactly one literal (D5)
- [x] 4.2 `RrCorrelator.onReplySeen`: set `reply_destination` from the joining reply when it was not stamped at creation
- [x] 4.3 `RrNotificationObserver`: forward `MESSAGE_DELIVERED` on a resolved reply address as `ReplySeen`, alongside the existing temp-queue branch (D6)
- [x] 4.4 `RrCorrelatorTest`: a reply on the second of three resolved addresses completes the flow and records that address
- [x] 4.5 `RrNotificationObserverTest`: a delivery on a resolved reply address is forwarded; one on an untraced address is not

## 5. Backend — sampling

- [x] 5.1 `RrSampler.servingNode` → `servingNodes`: every active, error-free node with a Core URL
- [x] 5.2 Browse the request address and each resolved reply address per node, at the existing page size and cadence
- [x] 5.3 Per-expectation failure counter: `warn` on first failure then at most once a minute, naming expectation and node; never propagate
- [x] 5.4 Test: one failing node does not stop the others on the same tick

## 6. Frontend

- [x] 6.1 `web/src/rr/ExpectationsView.tsx`: reply address becomes a multi-value input bound to `replyAddresses`, accepting patterns
- [x] 6.2 Show what each pattern currently resolves to as it is typed, and say when it resolves to nothing yet
- [x] 6.3 Distinguish an empty set ("replies arrive on a temporary queue named by the request") from an unfilled field, since conflating them is what produced the untraceable expectation
- [x] 6.4 Expectations table renders the set, not a single value
- [x] 6.5 `ExpectationsView.test.tsx`: creating with two patterns sends both; the empty case is explained rather than silently accepted
- [x] 6.6 Regenerate `web/src/api/schema.d.ts`

## 7. Close out

- [x] 7.1 `CHANGELOG.md` `## [Unreleased]`: a `### Breaking` block for `replyAddress` → `replyAddresses` with the migration step, plus Added/Fixed entries
- [x] 7.2 `just fmt && just verify`
- [x] 7.3 Cover the full chain in tests: a reply on the second of several declared addresses completes the flow and records that address (`RrCorrelatorTest`), every serving node is browsed for every resolved address and one failing node does not stop the others (`RrSamplerTest`), a delivery on a resolved reply address is observed and one on an untraced address is not (`RrNotificationObserverTest`), and glob semantics including anchoring and the cap (`ReplyAddressResolverTest`)
- [x] 7.4 Production remains blocked on its own account (`AMQ229099`, Studio authenticates as the `<cluster-user>`); confirm that is recorded outside this change and not mistaken for a defect in it

