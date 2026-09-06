## 1. Schema

- [ ] 1.1 New changeset `changes/0NN-rr-reply-addresses.sql` (next free number; never edit 007 or 011): add `reply_addresses TEXT[] NOT NULL DEFAULT '{}'` to `rr_expectation`, backfill `ARRAY[reply_address]` where `reply_address IS NOT NULL`, drop `reply_address`
- [ ] 1.2 Register the changeset in `db/changelog/db.changelog-master.xml`
- [ ] 1.3 Confirm the Liquibase-vs-Testcontainers validation in `./mvnw verify` still passes with `ddl-auto=validate`

## 2. Backend — expectations

- [ ] 2.1 `RrExpectationEntity`: `replyAddress` → `List<String> replyAddresses`, mapped to the array column, never null
- [ ] 2.2 `RrViews.CreateExpectationRequest` / `UpdateExpectationRequest` / `ExpectationView`: `replyAddress` → `List<String> replyAddresses`; blank entries dropped, duplicates collapsed, order preserved
- [ ] 2.3 `RequestReplyService.create` / `update` / `toView`: carry the list through; keep the existing duplicate-request-address conflict check
- [ ] 2.4 `RequestReplyServiceTest`: a multi-address expectation round-trips; blanks and duplicates are normalised away

## 3. Backend — correlation

- [ ] 3.1 `RrCorrelator.onRequestSeen`: stamp `reply_destination` only when the expectation declares exactly one reply address (design D3)
- [ ] 3.2 `RrCorrelator.onReplySeen`: set the flow's `reply_destination` from the joining reply when it was not stamped at creation
- [ ] 3.3 `RrNotificationObserver` / `isTracedRequestAddress`: recognise any declared reply address as belonging to its expectation
- [ ] 3.4 `RrCorrelatorTest`: a reply on the second of three declared addresses completes the flow and records that address

## 4. Backend — sampling

- [ ] 4.1 `RrSampler.servingNode` → `servingNodes`: every active, error-free node with a Core URL
- [ ] 4.2 Browse the request address and each reply address per node, at the existing page size and cadence
- [ ] 4.3 Per-expectation failure counter: `warn` on first failure, then at most once per minute, naming the expectation and the node; never propagate
- [ ] 4.4 Test: one failing node does not stop the others on the same tick

## 5. Frontend

- [ ] 5.1 `web/src/rr/ExpectationsView.tsx`: reply address becomes a Mantine `TagsInput` bound to `replyAddresses`
- [ ] 5.2 Helper text: an empty set means replies arrive on a temporary queue named by the request; if neither holds, flows will end orphaned
- [ ] 5.3 Expectations table renders the set, not a single value
- [ ] 5.4 `ExpectationsView.test.tsx`: creating with two reply addresses sends both; the empty case is explained rather than silently accepted
- [ ] 5.5 Regenerate `web/src/api/schema.d.ts` from the changed API

## 6. Close out

- [ ] 6.1 `CHANGELOG.md` `## [Unreleased]`: `### Breaking` note for `replyAddress` → `replyAddresses`, plus Added/Fixed entries
- [ ] 6.2 `just fmt && just verify`
- [ ] 6.3 Verify end to end against `nova-dev-server-20`: declare the three `nova.fcb.integration.reply.nova-10.100.7.2X` addresses, have real requests sent, confirm `rr_flow` rows reach `COMPLETED` with latencies and that the reply destination names the queue that answered
