--liquibase formatted sql

-- The feature/alerting module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset artemis-studio:feature-alerting-0001-baseline splitStatements:true
CREATE TABLE alert_delivery (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    delivered_at timestamp with time zone,
    seq bigint NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    payload jsonb NOT NULL,
    last_error text,
    state text DEFAULT 'PENDING'::text NOT NULL,
    kind text DEFAULT 'alert'::text NOT NULL,
    source text,
    dedupe_key text,
    rule_id uuid,
    channel_id uuid NOT NULL,
    CONSTRAINT ck_alert_delivery_kind CHECK ((kind = ANY (ARRAY['alert'::text, 'notice'::text]))),
    CONSTRAINT ck_alert_delivery_rule CHECK (((kind = 'alert'::text) = (rule_id IS NOT NULL))),
    CONSTRAINT ck_alert_delivery_state CHECK ((state = ANY (ARRAY['PENDING'::text, 'SENT'::text, 'FAILED'::text, 'DEAD'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE alert_firing (
    started_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    value double precision,
    seq bigint NOT NULL,
    subject_key text NOT NULL,
    severity text NOT NULL,
    rule_id uuid NOT NULL,
    cluster_id uuid
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000');

CREATE TABLE alert_rule (
    threshold double precision,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    for_seconds integer DEFAULT 0 NOT NULL,
    metric text,
    comparator text,
    severity text DEFAULT 'WARNING'::text NOT NULL,
    scope jsonb,
    name text DEFAULT ''::text NOT NULL,
    state_condition text,
    kind text DEFAULT 'METRIC_THRESHOLD'::text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid,
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_alert_rule_comparator CHECK ((comparator = ANY (ARRAY['GT'::text, 'GTE'::text, 'LT'::text, 'LTE'::text, 'EQ'::text, 'NE'::text]))),
    CONSTRAINT ck_alert_rule_kind CHECK ((kind = ANY (ARRAY['METRIC_THRESHOLD'::text, 'STATE'::text]))),
    CONSTRAINT ck_alert_rule_kind_shape CHECK ((((kind = 'METRIC_THRESHOLD'::text) AND (metric IS NOT NULL) AND (comparator IS NOT NULL) AND (threshold IS NOT NULL) AND (state_condition IS NULL)) OR ((kind = 'STATE'::text) AND (state_condition IS NOT NULL) AND (metric IS NULL) AND (comparator IS NULL) AND (threshold IS NULL)))),
    CONSTRAINT ck_alert_rule_severity CHECK ((severity = ANY (ARRAY['INFO'::text, 'WARNING'::text, 'CRITICAL'::text]))),
    CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text, 'CONFIG_DRIFT'::text, 'SETUP_RISK'::text, 'STORAGE_QUOTA'::text, 'STORAGE_HEALTH'::text, 'AUDIT_DELIVERY'::text]))))
);

CREATE TABLE alert_rule_channel (
    rule_id uuid NOT NULL,
    channel_id uuid NOT NULL
);

CREATE TABLE alert_rule_seed (
    plugin_id text NOT NULL,
    seed_key text NOT NULL,
    cluster_id uuid NOT NULL
);

CREATE TABLE alert_state (
    since timestamp with time zone,
    last_notified_at timestamp with time zone,
    last_value double precision,
    subject_key text NOT NULL,
    state text DEFAULT 'OK'::text NOT NULL,
    rule_id uuid NOT NULL,
    CONSTRAINT ck_alert_state CHECK ((state = ANY (ARRAY['OK'::text, 'PENDING'::text, 'FIRING'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE notification_channel (
    name text NOT NULL,
    kind text NOT NULL,
    config jsonb NOT NULL,
    sealed bytea,
    id uuid DEFAULT uuidv7() NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_notification_channel_kind CHECK ((kind = ANY (ARRAY['WEBHOOK'::text, 'SLACK'::text, 'EMAIL'::text, 'TEAMS'::text, 'PAGERDUTY'::text])))
);

ALTER TABLE alert_delivery ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME alert_delivery_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE alert_firing ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME alert_firing_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY alert_delivery
    ADD CONSTRAINT pk_alert_delivery PRIMARY KEY (seq);

ALTER TABLE ONLY alert_firing
    ADD CONSTRAINT pk_alert_firing PRIMARY KEY (seq);

ALTER TABLE ONLY alert_rule
    ADD CONSTRAINT pk_alert_rule PRIMARY KEY (id);

ALTER TABLE ONLY alert_rule_channel
    ADD CONSTRAINT pk_alert_rule_channel PRIMARY KEY (rule_id, channel_id);

ALTER TABLE ONLY alert_rule_seed
    ADD CONSTRAINT pk_alert_rule_seed PRIMARY KEY (cluster_id, plugin_id, seed_key);

ALTER TABLE ONLY alert_state
    ADD CONSTRAINT pk_alert_state PRIMARY KEY (rule_id, subject_key);

ALTER TABLE ONLY notification_channel
    ADD CONSTRAINT pk_notification_channel PRIMARY KEY (id);

ALTER TABLE ONLY notification_channel
    ADD CONSTRAINT uq_notification_channel_name UNIQUE (name);

CREATE INDEX ix_alert_delivery_channel_seq ON alert_delivery USING btree (channel_id, seq DESC);

CREATE INDEX ix_alert_delivery_due ON alert_delivery USING btree (next_attempt_at) WHERE (state = 'PENDING'::text);

CREATE INDEX ix_alert_delivery_rule_id ON alert_delivery USING btree (rule_id);

CREATE INDEX ix_alert_delivery_source_created ON alert_delivery USING btree (source, created_at) WHERE (source IS NOT NULL);

CREATE INDEX ix_alert_firing_cluster_seq ON alert_firing USING btree (cluster_id, seq DESC);

CREATE INDEX ix_alert_firing_open ON alert_firing USING btree (cluster_id) WHERE (resolved_at IS NULL);

CREATE INDEX ix_alert_firing_rule_id ON alert_firing USING btree (rule_id);

CREATE INDEX ix_alert_rule_channel_channel_id ON alert_rule_channel USING btree (channel_id);

CREATE INDEX ix_alert_rule_cluster_kind ON alert_rule USING btree (cluster_id, kind) WHERE enabled;

CREATE UNIQUE INDEX ux_alert_delivery_source_dedupe ON alert_delivery USING btree (source, dedupe_key) WHERE (dedupe_key IS NOT NULL);

ALTER TABLE ONLY alert_delivery
    ADD CONSTRAINT fk_alert_delivery_channel FOREIGN KEY (channel_id) REFERENCES notification_channel(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_delivery
    ADD CONSTRAINT fk_alert_delivery_rule FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_firing
    ADD CONSTRAINT fk_alert_firing_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_firing
    ADD CONSTRAINT fk_alert_firing_rule FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rule_channel
    ADD CONSTRAINT fk_alert_rule_channel_channel FOREIGN KEY (channel_id) REFERENCES notification_channel(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rule_channel
    ADD CONSTRAINT fk_alert_rule_channel_rule FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rule
    ADD CONSTRAINT fk_alert_rule_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_rule_seed
    ADD CONSTRAINT fk_alert_rule_seed_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY alert_state
    ADD CONSTRAINT fk_alert_state_rule FOREIGN KEY (rule_id) REFERENCES alert_rule(id) ON DELETE CASCADE;

-- Installation-wide state rules, one per state condition.
INSERT INTO alert_rule (name, kind, state_condition, for_seconds, severity) VALUES
    ('Storage quota', 'STATE', 'STORAGE_QUOTA', 0, 'WARNING'),
    ('Storage health', 'STATE', 'STORAGE_HEALTH', 0, 'WARNING'),
    ('Audit delivery', 'STATE', 'AUDIT_DELIVERY', 0, 'WARNING');
--rollback DROP TABLE IF EXISTS notification_channel CASCADE;
--rollback DROP TABLE IF EXISTS alert_state CASCADE;
--rollback DROP TABLE IF EXISTS alert_rule_seed CASCADE;
--rollback DROP TABLE IF EXISTS alert_rule_channel CASCADE;
--rollback DROP TABLE IF EXISTS alert_rule CASCADE;
--rollback DROP TABLE IF EXISTS alert_firing CASCADE;
--rollback DROP TABLE IF EXISTS alert_delivery CASCADE;
