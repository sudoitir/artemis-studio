--liquibase formatted sql

-- Clock discipline for request-reply tracing (ADR-0053).
--
-- A flow's deadline was taken from the message's absolute JMSExpiration — the
-- PRODUCER's clock plus its TTL — and compared against Studio's clock by the
-- deadline sweep. A producer running ten minutes fast meant no flow ever timed
-- out; ten minutes slow meant every flow timed out the moment it was seen. Four
-- clocks are involved in a traced exchange (broker, producer, consumer, Studio)
-- and nothing in the schema recorded what any of them said.
--
-- What is stored here is the evidence, not the correction: offsets are measured
-- from the timestamp Jolokia already puts on every response, and persisted so a
-- restart does not start blind and an operator can see the number Studio acted on.
--
-- 002-estate.sql, 007-request-reply.sql and 013-alerting.sql are released and are
-- never edited.

--changeset artemis-studio:020-broker-node-clock-offset
--comment: TIMESTAMPTZ and BIGINT lead the 8-byte group; the INTEGER uncertainty joins
--         the 4-byte group ahead of the uuid/boolean tail. All nullable: a node whose
--         Jolokia agent strips the timestamp has no reading, which is UNKNOWN and must
--         not be spelled as zero.
ALTER TABLE broker_node
    ADD COLUMN clock_measured_at     TIMESTAMPTZ,
    ADD COLUMN clock_offset_ms       BIGINT,
    ADD COLUMN clock_uncertainty_ms  INTEGER;

COMMENT ON COLUMN broker_node.clock_offset_ms IS
    'How far ahead of Studio this broker''s clock is, in milliseconds; negative means behind. '
    'Measured from the Jolokia response timestamp (ADR-0053). NULL means never measured, not zero.';
COMMENT ON COLUMN broker_node.clock_uncertainty_ms IS
    'Half the best round trip plus Jolokia''s second granularity. An offset inside this band '
    'is indistinguishable from agreement and is never reported as skew.';

--rollback ALTER TABLE broker_node DROP COLUMN clock_uncertainty_ms, DROP COLUMN clock_offset_ms, DROP COLUMN clock_measured_at;

--changeset artemis-studio:020-rr-flow-latency-source
--comment: The two enqueue instants and the two skews are 8-byte; latency_source (TEXT)
--         and latency_bound_ms (INTEGER) join the 4-byte group. latency_source is NOT
--         NULL with a default because every existing row was measured the old way, and
--         a null would be a third state nobody can interpret.
ALTER TABLE rr_flow
    ADD COLUMN request_enqueued_at TIMESTAMPTZ,
    ADD COLUMN reply_enqueued_at   TIMESTAMPTZ,
    ADD COLUMN request_skew_ms     BIGINT,
    ADD COLUMN reply_skew_ms       BIGINT,
    ADD COLUMN latency_source      TEXT NOT NULL DEFAULT 'OBSERVED',
    ADD COLUMN latency_bound_ms    INTEGER;

ALTER TABLE rr_flow ADD CONSTRAINT ck_rr_flow_latency_source
    CHECK (latency_source IN ('OBSERVED', 'MESSAGE_TIMESTAMPS'));

COMMENT ON COLUMN rr_flow.latency_source IS
    'How latency_ms was arrived at. OBSERVED is the difference between two sample ticks and is '
    'therefore quantised to the sample interval (latency_bound_ms). MESSAGE_TIMESTAMPS is the '
    'difference between the two messages'' own enqueue times, normalised onto Studio''s clock, and '
    'is only used when neither carries forward skew beyond tolerance (ADR-0053).';
COMMENT ON COLUMN rr_flow.request_skew_ms IS
    'How far into the future the request claimed to have been produced, once the broker''s own '
    'offset is removed. Only forward skew is evidence: a negative value is ordinary queue '
    'residency and is never recorded here.';

--rollback ALTER TABLE rr_flow DROP CONSTRAINT ck_rr_flow_latency_source;
--rollback ALTER TABLE rr_flow DROP COLUMN latency_bound_ms, DROP COLUMN latency_source, DROP COLUMN reply_skew_ms, DROP COLUMN request_skew_ms, DROP COLUMN reply_enqueued_at, DROP COLUMN request_enqueued_at;

--changeset artemis-studio:020-alert-rule-clock-skew
--comment: ADR-0035 fixed the state conditions at four. A wrong clock is a cluster-state
--         fact of exactly the same kind — measured from polled state, not from a metric
--         row — so it joins them rather than becoming a second mechanism (ADR-0053).
--         The constraint is replaced, not edited in place: 013 is released.
ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;

ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition
    CHECK (state_condition IS NULL
        OR state_condition IN ('SPLIT_BRAIN', 'NODE_DOWN', 'REPLICATION_BEHIND', 'CLUSTER_DEGRADED', 'CLOCK_SKEW'));

--rollback ALTER TABLE alert_rule DROP CONSTRAINT ck_alert_rule_state_condition;
--rollback ALTER TABLE alert_rule ADD CONSTRAINT ck_alert_rule_state_condition CHECK (state_condition IS NULL OR state_condition IN ('SPLIT_BRAIN', 'NODE_DOWN', 'REPLICATION_BEHIND', 'CLUSTER_DEGRADED'));
