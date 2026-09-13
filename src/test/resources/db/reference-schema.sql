--
-- PostgreSQL database dump
--

\restrict QTXL7NgwCpWLbcZLaCpUW0TrdfKqHFyW9l46zoPSLxV4Vw422PZx1T6HXrZf1VO

-- Dumped from database version 17.11
-- Dumped by pg_dump version 17.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;


--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;


--
-- Name: EXTENSION pgcrypto; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION pgcrypto IS 'cryptographic functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: alert_delivery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_delivery (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    next_attempt_at timestamp with time zone DEFAULT now() NOT NULL,
    delivered_at timestamp with time zone,
    seq bigint NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    payload jsonb NOT NULL,
    last_error text,
    state text DEFAULT 'PENDING'::text NOT NULL,
    rule_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    CONSTRAINT ck_alert_delivery_state CHECK ((state = ANY (ARRAY['PENDING'::text, 'SENT'::text, 'FAILED'::text, 'DEAD'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');


--
-- Name: alert_delivery_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.alert_delivery ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.alert_delivery_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: alert_firing; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_firing (
    started_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    value double precision,
    seq bigint NOT NULL,
    subject_key text NOT NULL,
    severity text NOT NULL,
    rule_id uuid NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000');


--
-- Name: alert_firing_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.alert_firing ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.alert_firing_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: alert_rule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rule (
    threshold double precision,
    for_seconds integer DEFAULT 0 NOT NULL,
    metric text,
    comparator text,
    severity text DEFAULT 'WARNING'::text NOT NULL,
    scope jsonb,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    name text DEFAULT ''::text NOT NULL,
    state_condition text,
    kind text DEFAULT 'METRIC_THRESHOLD'::text NOT NULL,
    CONSTRAINT ck_alert_rule_comparator CHECK ((comparator = ANY (ARRAY['GT'::text, 'GTE'::text, 'LT'::text, 'LTE'::text, 'EQ'::text, 'NE'::text]))),
    CONSTRAINT ck_alert_rule_kind CHECK ((kind = ANY (ARRAY['METRIC_THRESHOLD'::text, 'STATE'::text]))),
    CONSTRAINT ck_alert_rule_kind_shape CHECK ((((kind = 'METRIC_THRESHOLD'::text) AND (metric IS NOT NULL) AND (comparator IS NOT NULL) AND (threshold IS NOT NULL) AND (state_condition IS NULL)) OR ((kind = 'STATE'::text) AND (state_condition IS NOT NULL) AND (metric IS NULL) AND (comparator IS NULL) AND (threshold IS NULL)))),
    CONSTRAINT ck_alert_rule_severity CHECK ((severity = ANY (ARRAY['INFO'::text, 'WARNING'::text, 'CRITICAL'::text]))),
    CONSTRAINT ck_alert_rule_state_condition CHECK (((state_condition IS NULL) OR (state_condition = ANY (ARRAY['SPLIT_BRAIN'::text, 'NODE_DOWN'::text, 'REPLICATION_BEHIND'::text, 'CLUSTER_DEGRADED'::text, 'CLOCK_SKEW'::text]))))
);


--
-- Name: alert_rule_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_rule_channel (
    rule_id uuid NOT NULL,
    channel_id uuid NOT NULL
);


--
-- Name: alert_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.alert_state (
    since timestamp with time zone,
    last_notified_at timestamp with time zone,
    last_value double precision,
    subject_key text NOT NULL,
    state text DEFAULT 'OK'::text NOT NULL,
    rule_id uuid NOT NULL,
    CONSTRAINT ck_alert_state CHECK ((state = ANY (ARRAY['OK'::text, 'PENDING'::text, 'FIRING'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');


--
-- Name: api_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_token (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    name text NOT NULL,
    prefix text NOT NULL,
    token_hash bytea NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_id uuid NOT NULL
);


--
-- Name: api_token_grant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_token_grant (
    action text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    token_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_api_token_grant_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);


--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    username text NOT NULL,
    email text,
    password_hash text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    disabled boolean DEFAULT false NOT NULL,
    issuer text,
    subject text,
    auth_source text DEFAULT 'LOCAL'::text NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_app_user_auth_source CHECK ((auth_source = ANY (ARRAY['LOCAL'::text, 'OIDC'::text])))
);


--
-- Name: audit_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_event (
    id bigint NOT NULL,
    ts timestamp with time zone DEFAULT now() NOT NULL,
    affected_count bigint,
    action text NOT NULL,
    target_type text,
    target_name text,
    username text,
    outcome text DEFAULT 'PENDING'::text NOT NULL,
    error text,
    request_id text,
    params jsonb,
    source_ip inet,
    user_id uuid,
    cluster_id uuid,
    node_id uuid,
    dry_run boolean DEFAULT false NOT NULL,
    outcome_detail jsonb,
    CONSTRAINT ck_audit_event_outcome CHECK ((outcome = ANY (ARRAY['PENDING'::text, 'SUCCESS'::text, 'FAILURE'::text])))
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000', autovacuum_analyze_scale_factor='0.02');


--
-- Name: COLUMN audit_event.outcome_detail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.audit_event.outcome_detail IS 'Per-node outcome of a cluster-wide fan-out command (ADR-0049 D2/D4): a JSON array of {nodeId, nodeName, status, affected, error}. Null for single-node actions.';


--
-- Name: audit_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: broker_config_apply; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_config_apply (
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    id bigint NOT NULL,
    revision_id bigint NOT NULL,
    audit_event_id bigint,
    plan jsonb NOT NULL,
    outcome_detail jsonb,
    outcome text NOT NULL,
    summary text,
    actor text NOT NULL,
    cluster_id uuid NOT NULL,
    canary_node_id uuid,
    dry_run boolean NOT NULL,
    CONSTRAINT ck_broker_config_apply_outcome CHECK ((outcome = ANY (ARRAY['DRY_RUN'::text, 'APPLIED'::text, 'HALTED'::text, 'FAILED'::text])))
);


--
-- Name: broker_config_apply_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.broker_config_apply ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.broker_config_apply_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: broker_config_declaration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_config_declaration (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    current_revision_id bigint NOT NULL,
    apply_mode text DEFAULT 'STUDIO_MANAGED'::text NOT NULL,
    undeclared_exclusions jsonb DEFAULT '[]'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    report_undeclared boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_broker_config_declaration_mode CHECK ((apply_mode = ANY (ARRAY['STUDIO_MANAGED'::text, 'CONFIG_MANAGED'::text])))
);


--
-- Name: broker_config_node_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_config_node_state (
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    verified_revision integer,
    findings jsonb DEFAULT '[]'::jsonb NOT NULL,
    state text NOT NULL,
    detail text,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    basis_ref bigint,
    basis text,
    CONSTRAINT ck_broker_config_node_state_basis CHECK (((basis IS NULL) OR (basis = ANY (ARRAY['VERIFIED_APPLY'::text, 'ADOPTED'::text, 'OBSERVED_MATCH'::text])))),
    CONSTRAINT ck_broker_config_node_state_state CHECK ((state = ANY (ARRAY['IN_SYNC'::text, 'DRIFTED'::text, 'NOT_EVALUATED'::text, 'UNREACHABLE'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');


--
-- Name: broker_config_owned_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_config_owned_item (
    applied_at timestamp with time zone DEFAULT now() NOT NULL,
    revision_id bigint NOT NULL,
    kind text NOT NULL,
    item_key text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_broker_config_owned_item_kind CHECK ((kind = ANY (ARRAY['ADDRESS_SETTING'::text, 'SECURITY_SETTING'::text, 'DIVERT'::text])))
);


--
-- Name: broker_config_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_config_revision (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    revision integer NOT NULL,
    document jsonb NOT NULL,
    source text NOT NULL,
    note text,
    created_by text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_broker_config_revision_source CHECK ((source = ANY (ARRAY['EDIT'::text, 'IMPORT_XML'::text, 'ADOPT'::text, 'MCP'::text, 'RECOMMENDED'::text])))
);


--
-- Name: broker_config_revision_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.broker_config_revision ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.broker_config_revision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: broker_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_credential (
    kind text NOT NULL,
    username text,
    secret_ct bytea NOT NULL,
    secret_nonce bytea NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL
);


--
-- Name: broker_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_event (
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint NOT NULL,
    type text NOT NULL,
    address text,
    routing_name text,
    consumer_name text,
    session_name text,
    connection_name text,
    remote_address text,
    username text,
    props jsonb,
    cluster_id uuid NOT NULL,
    node_id uuid
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000');


--
-- Name: broker_event_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.broker_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.broker_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: broker_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_node (
    last_seen_at timestamp with time zone,
    artemis_node_id text,
    name text NOT NULL,
    jolokia_url text,
    core_url text,
    ha_role text DEFAULT 'STANDALONE'::text NOT NULL,
    pair_group text,
    state text DEFAULT 'UNKNOWN'::text NOT NULL,
    version text,
    last_error text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    discovered boolean DEFAULT false NOT NULL,
    manual_override boolean DEFAULT false NOT NULL,
    observed_cycle bigint,
    active boolean,
    replica_sync boolean,
    clock_measured_at timestamp with time zone,
    clock_offset_ms bigint,
    clock_uncertainty_ms integer,
    CONSTRAINT ck_broker_node_ha_role CHECK ((ha_role = ANY (ARRAY['PRIMARY'::text, 'BACKUP'::text, 'STANDALONE'::text])))
);


--
-- Name: COLUMN broker_node.clock_offset_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.broker_node.clock_offset_ms IS 'How far ahead of Studio this broker''s clock is, in milliseconds; negative means behind. Measured from the Jolokia response timestamp (ADR-0053). NULL means never measured, not zero.';


--
-- Name: COLUMN broker_node.clock_uncertainty_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.broker_node.clock_uncertainty_ms IS 'Half the best round trip plus Jolokia''s second granularity. An offset inside this band is indistinguishable from agreement and is never reported as skew.';


--
-- Name: broker_tls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker_tls (
    truststore_ref text,
    client_cert_ref text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    verify_hostname boolean DEFAULT true NOT NULL
);


--
-- Name: cluster; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cluster (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    name text NOT NULL,
    description text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    environment_id uuid,
    read_only boolean DEFAULT false NOT NULL,
    management_write_observed_at timestamp with time zone,
    management_write_status text,
    management_write_reason text,
    CONSTRAINT ck_cluster_management_write_status CHECK (((management_write_status IS NULL) OR (management_write_status = ANY (ARRAY['AVAILABLE'::text, 'UNAVAILABLE'::text]))))
);


--
-- Name: COLUMN cluster.management_write_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.cluster.management_write_status IS 'Evidence of an attempted management write (ADR-0049 D5): AVAILABLE once one has succeeded, UNAVAILABLE once one has been refused for an authorization reason, NULL until one is attempted. A write that fails for any other reason must not set this.';


--
-- Name: databasechangelog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangelog (
    id character varying(255) NOT NULL,
    author character varying(255) NOT NULL,
    filename character varying(255) NOT NULL,
    dateexecuted timestamp without time zone NOT NULL,
    orderexecuted integer NOT NULL,
    exectype character varying(10) NOT NULL,
    md5sum character varying(35),
    description character varying(255),
    comments character varying(255),
    tag character varying(255),
    liquibase character varying(20),
    contexts character varying(255),
    labels character varying(255),
    deployment_id character varying(10)
);


--
-- Name: databasechangeloglock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangeloglock (
    id integer NOT NULL,
    locked boolean NOT NULL,
    lockgranted timestamp without time zone,
    lockedby character varying(255)
);


--
-- Name: environment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.environment (
    sort_order integer DEFAULT 0 NOT NULL,
    name text NOT NULL,
    colour text,
    id uuid DEFAULT gen_random_uuid() NOT NULL
);


--
-- Name: message_capture_node; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_capture_node (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    captured_from timestamp with time zone,
    dropped_estimate bigint DEFAULT 0 NOT NULL,
    held_bytes bigint DEFAULT 0 NOT NULL,
    capture_state text DEFAULT 'PENDING'::text NOT NULL,
    capture_detail text,
    subscription_id uuid NOT NULL,
    node_id uuid NOT NULL,
    CONSTRAINT ck_message_capture_node_state CHECK ((capture_state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'DEGRADED'::text, 'FAILED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');


--
-- Name: message_index; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_index (
    observed_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    message_id bigint NOT NULL,
    timestamp_ms bigint DEFAULT 0 NOT NULL,
    expiration_ms bigint DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    priority integer DEFAULT 4 NOT NULL,
    message_type integer DEFAULT 0 NOT NULL,
    queue_name text NOT NULL,
    address text NOT NULL,
    node_name text NOT NULL,
    correlation_id text,
    group_id text,
    user_id text,
    reply_to text,
    jms_type text,
    body text,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    source_message_id bigint,
    origin text DEFAULT 'SAMPLED'::text NOT NULL,
    orig_address text,
    body_truncated boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_message_index_origin CHECK ((origin = ANY (ARRAY['SAMPLED'::text, 'CAPTURED'::text])))
)
PARTITION BY RANGE (observed_at);


--
-- Name: message_index_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_index_default (
    observed_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    message_id bigint NOT NULL,
    timestamp_ms bigint DEFAULT 0 NOT NULL,
    expiration_ms bigint DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    priority integer DEFAULT 4 NOT NULL,
    message_type integer DEFAULT 0 NOT NULL,
    queue_name text NOT NULL,
    address text NOT NULL,
    node_name text NOT NULL,
    correlation_id text,
    group_id text,
    user_id text,
    reply_to text,
    jms_type text,
    body text,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    source_message_id bigint,
    origin text DEFAULT 'SAMPLED'::text NOT NULL,
    orig_address text,
    body_truncated boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_message_index_origin CHECK ((origin = ANY (ARRAY['SAMPLED'::text, 'CAPTURED'::text])))
)
WITH (fillfactor='90', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.02');


--
-- Name: message_index_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_index_subscription (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    capture_from timestamp with time zone DEFAULT now() NOT NULL,
    interval_ms bigint DEFAULT 5000 NOT NULL,
    retention_days integer DEFAULT 7 NOT NULL,
    queue_pattern text NOT NULL,
    created_by text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    ring_size bigint DEFAULT 10000 NOT NULL,
    max_bytes bigint DEFAULT '5368709120'::bigint NOT NULL,
    max_rate integer DEFAULT 500 NOT NULL,
    body_cap_bytes integer DEFAULT 262144 NOT NULL,
    mode text DEFAULT 'SAMPLE'::text NOT NULL,
    filter_string text,
    CONSTRAINT ck_message_index_subscription_body_cap CHECK (((body_cap_bytes >= 1024) AND (body_cap_bytes <= 16777216))),
    CONSTRAINT ck_message_index_subscription_interval CHECK ((interval_ms >= 1000)),
    CONSTRAINT ck_message_index_subscription_max_bytes CHECK ((max_bytes > 0)),
    CONSTRAINT ck_message_index_subscription_mode CHECK ((mode = ANY (ARRAY['SAMPLE'::text, 'CAPTURE'::text]))),
    CONSTRAINT ck_message_index_subscription_rate CHECK (((max_rate >= 1) AND (max_rate <= 1000000))),
    CONSTRAINT ck_message_index_subscription_retention CHECK (((retention_days >= 1) AND (retention_days <= 90))),
    CONSTRAINT ck_message_index_subscription_ring CHECK (((ring_size >= 100) AND (ring_size <= 10000000)))
);


--
-- Name: metric_sample; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
PARTITION BY RANGE (ts);


--
-- Name: metric_sample_20260913; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample_20260913 (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');


--
-- Name: metric_sample_20260914; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample_20260914 (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');


--
-- Name: metric_sample_20260915; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample_20260915 (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');


--
-- Name: metric_sample_20260916; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample_20260916 (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');


--
-- Name: metric_sample_default; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.metric_sample_default (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_type text NOT NULL,
    subject_name text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='10000', autovacuum_analyze_scale_factor='0.05');


--
-- Name: notification_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_channel (
    name text NOT NULL,
    kind text NOT NULL,
    config jsonb NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    secret_ct bytea,
    secret_nonce bytea,
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_notification_channel_kind CHECK ((kind = ANY (ARRAY['WEBHOOK'::text, 'SLACK'::text])))
);


--
-- Name: oidc_role_mapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.oidc_role_mapping (
    claim text NOT NULL,
    claim_value text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_oidc_role_mapping_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);


--
-- Name: queue_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.queue_snapshot (
    ts timestamp with time zone DEFAULT now() NOT NULL,
    message_count bigint DEFAULT 0 NOT NULL,
    consumer_count bigint DEFAULT 0 NOT NULL,
    delivering_count bigint DEFAULT 0 NOT NULL,
    scheduled_count bigint DEFAULT 0 NOT NULL,
    messages_added bigint DEFAULT 0 NOT NULL,
    messages_acked bigint DEFAULT 0 NOT NULL,
    messages_expired bigint DEFAULT 0 NOT NULL,
    address text NOT NULL,
    queue_name text NOT NULL,
    routing_type text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_queue_snapshot_routing CHECK ((routing_type = ANY (ARRAY['ANYCAST'::text, 'MULTICAST'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');


--
-- Name: role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role (
    name text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    builtin boolean DEFAULT false NOT NULL
);


--
-- Name: role_permission; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permission (
    action text NOT NULL,
    role_id uuid NOT NULL
);


--
-- Name: rr_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rr_event (
    ts timestamp with time zone DEFAULT now() NOT NULL,
    kind text NOT NULL,
    detail jsonb,
    flow_id uuid NOT NULL,
    node_id uuid,
    seq bigint NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000');


--
-- Name: rr_event_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.rr_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.rr_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: rr_expectation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rr_expectation (
    deadline_ms integer,
    sample_per_min integer DEFAULT 10 NOT NULL,
    request_address text NOT NULL,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    correlation_property text,
    capture_payload boolean DEFAULT false NOT NULL,
    reply_addresses text[] DEFAULT '{}'::text[] NOT NULL
);


--
-- Name: rr_flow; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.rr_flow (
    requested_at timestamp with time zone,
    replied_at timestamp with time zone,
    deadline_at timestamp with time zone,
    latency_ms bigint,
    request_address text,
    reply_destination text,
    reply_kind text NOT NULL,
    state text NOT NULL,
    correlation_id text,
    requester_session text,
    responder_session text,
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    cluster_id uuid NOT NULL,
    observed_at timestamp with time zone,
    request_message_id text,
    reply_message_id text,
    responder_consumer text,
    node_id uuid,
    request_enqueued_at timestamp with time zone,
    reply_enqueued_at timestamp with time zone,
    request_skew_ms bigint,
    reply_skew_ms bigint,
    latency_source text DEFAULT 'OBSERVED'::text NOT NULL,
    latency_bound_ms integer,
    CONSTRAINT ck_rr_flow_latency_source CHECK ((latency_source = ANY (ARRAY['OBSERVED'::text, 'MESSAGE_TIMESTAMPS'::text]))),
    CONSTRAINT ck_rr_flow_reply_kind CHECK ((reply_kind = ANY (ARRAY['TEMP_QUEUE'::text, 'SHARED_QUEUE'::text]))),
    CONSTRAINT ck_rr_flow_state CHECK ((state = ANY (ARRAY['AWAITING_REPLY'::text, 'COMPLETED'::text, 'TIMED_OUT'::text, 'ORPHANED'::text, 'RESPONDER_DROPPED'::text, 'ORPHANED_REPLY'::text])))
)
WITH (fillfactor='90', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');


--
-- Name: COLUMN rr_flow.request_skew_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rr_flow.request_skew_ms IS 'How far into the future the request claimed to have been produced, once the broker''s own offset is removed. Only forward skew is evidence: a negative value is ordinary queue residency and is never recorded here.';


--
-- Name: COLUMN rr_flow.latency_source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.rr_flow.latency_source IS 'How latency_ms was arrived at. OBSERVED is the difference between two sample ticks and is therefore quantised to the sample interval (latency_bound_ms). MESSAGE_TIMESTAMPS is the difference between the two messages'' own enqueue times, normalised onto Studio''s clock, and is only used when neither carries forward skew beyond tolerance (ADR-0053).';


--
-- Name: spring_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session (
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100),
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL
);


--
-- Name: spring_session_attributes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);


--
-- Name: studio_config_property; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.studio_config_property (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    application text NOT NULL,
    profile text NOT NULL,
    label text NOT NULL,
    key text NOT NULL,
    value text
);


--
-- Name: studio_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.studio_setting (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    value jsonb NOT NULL,
    key text NOT NULL
);


--
-- Name: user_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_role (
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_user_role_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);


--
-- Name: message_index_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index ATTACH PARTITION public.message_index_default DEFAULT;


--
-- Name: metric_sample_20260913; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_sample ATTACH PARTITION public.metric_sample_20260913 FOR VALUES FROM ('2026-09-12 22:00:00+00') TO ('2026-09-13 22:00:00+00');


--
-- Name: metric_sample_20260914; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_sample ATTACH PARTITION public.metric_sample_20260914 FOR VALUES FROM ('2026-09-13 22:00:00+00') TO ('2026-09-14 22:00:00+00');


--
-- Name: metric_sample_20260915; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_sample ATTACH PARTITION public.metric_sample_20260915 FOR VALUES FROM ('2026-09-14 22:00:00+00') TO ('2026-09-15 22:00:00+00');


--
-- Name: metric_sample_20260916; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_sample ATTACH PARTITION public.metric_sample_20260916 FOR VALUES FROM ('2026-09-15 22:00:00+00') TO ('2026-09-16 22:00:00+00');


--
-- Name: metric_sample_default; Type: TABLE ATTACH; Schema: public; Owner: -
--

ALTER TABLE ONLY public.metric_sample ATTACH PARTITION public.metric_sample_default DEFAULT;


--
-- Name: databasechangeloglock databasechangeloglock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.databasechangeloglock
    ADD CONSTRAINT databasechangeloglock_pkey PRIMARY KEY (id);


--
-- Name: message_index pk_message_index; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index
    ADD CONSTRAINT pk_message_index PRIMARY KEY (node_id, queue_name, message_id, observed_at);


--
-- Name: message_index_default message_index_default_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index_default
    ADD CONSTRAINT message_index_default_pkey PRIMARY KEY (node_id, queue_name, message_id, observed_at);


--
-- Name: alert_delivery pk_alert_delivery; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery
    ADD CONSTRAINT pk_alert_delivery PRIMARY KEY (seq);


--
-- Name: alert_firing pk_alert_firing; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_firing
    ADD CONSTRAINT pk_alert_firing PRIMARY KEY (seq);


--
-- Name: alert_rule pk_alert_rule; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT pk_alert_rule PRIMARY KEY (id);


--
-- Name: alert_rule_channel pk_alert_rule_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule_channel
    ADD CONSTRAINT pk_alert_rule_channel PRIMARY KEY (rule_id, channel_id);


--
-- Name: alert_state pk_alert_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_state
    ADD CONSTRAINT pk_alert_state PRIMARY KEY (rule_id, subject_key);


--
-- Name: api_token pk_api_token; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_token
    ADD CONSTRAINT pk_api_token PRIMARY KEY (id);


--
-- Name: api_token_grant pk_api_token_grant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_token_grant
    ADD CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id);


--
-- Name: app_user pk_app_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT pk_app_user PRIMARY KEY (id);


--
-- Name: audit_event pk_audit_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_event
    ADD CONSTRAINT pk_audit_event PRIMARY KEY (id);


--
-- Name: broker_config_apply pk_broker_config_apply; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_apply
    ADD CONSTRAINT pk_broker_config_apply PRIMARY KEY (id);


--
-- Name: broker_config_declaration pk_broker_config_declaration; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_declaration
    ADD CONSTRAINT pk_broker_config_declaration PRIMARY KEY (cluster_id);


--
-- Name: broker_config_node_state pk_broker_config_node_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_node_state
    ADD CONSTRAINT pk_broker_config_node_state PRIMARY KEY (cluster_id, node_id);


--
-- Name: broker_config_owned_item pk_broker_config_owned_item; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_owned_item
    ADD CONSTRAINT pk_broker_config_owned_item PRIMARY KEY (cluster_id, kind, item_key);


--
-- Name: broker_config_revision pk_broker_config_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_revision
    ADD CONSTRAINT pk_broker_config_revision PRIMARY KEY (id);


--
-- Name: broker_credential pk_broker_credential; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_credential
    ADD CONSTRAINT pk_broker_credential PRIMARY KEY (id);


--
-- Name: broker_event pk_broker_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_event
    ADD CONSTRAINT pk_broker_event PRIMARY KEY (seq);


--
-- Name: broker_node pk_broker_node; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_node
    ADD CONSTRAINT pk_broker_node PRIMARY KEY (id);


--
-- Name: broker_tls pk_broker_tls; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_tls
    ADD CONSTRAINT pk_broker_tls PRIMARY KEY (id);


--
-- Name: cluster pk_cluster; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cluster
    ADD CONSTRAINT pk_cluster PRIMARY KEY (id);


--
-- Name: environment pk_environment; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.environment
    ADD CONSTRAINT pk_environment PRIMARY KEY (id);


--
-- Name: message_capture_node pk_message_capture_node; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_capture_node
    ADD CONSTRAINT pk_message_capture_node PRIMARY KEY (subscription_id, node_id);


--
-- Name: message_index_subscription pk_message_index_subscription; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index_subscription
    ADD CONSTRAINT pk_message_index_subscription PRIMARY KEY (id);


--
-- Name: notification_channel pk_notification_channel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_channel
    ADD CONSTRAINT pk_notification_channel PRIMARY KEY (id);


--
-- Name: oidc_role_mapping pk_oidc_role_mapping; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oidc_role_mapping
    ADD CONSTRAINT pk_oidc_role_mapping PRIMARY KEY (id);


--
-- Name: queue_snapshot pk_queue_snapshot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.queue_snapshot
    ADD CONSTRAINT pk_queue_snapshot PRIMARY KEY (node_id, queue_name);


--
-- Name: role pk_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role
    ADD CONSTRAINT pk_role PRIMARY KEY (id);


--
-- Name: role_permission pk_role_permission; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT pk_role_permission PRIMARY KEY (role_id, action);


--
-- Name: rr_event pk_rr_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_event
    ADD CONSTRAINT pk_rr_event PRIMARY KEY (seq);


--
-- Name: rr_expectation pk_rr_expectation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_expectation
    ADD CONSTRAINT pk_rr_expectation PRIMARY KEY (id);


--
-- Name: rr_flow pk_rr_flow; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_flow
    ADD CONSTRAINT pk_rr_flow PRIMARY KEY (id);


--
-- Name: studio_config_property pk_studio_config_property; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.studio_config_property
    ADD CONSTRAINT pk_studio_config_property PRIMARY KEY (application, profile, label, key);


--
-- Name: studio_setting pk_studio_setting; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.studio_setting
    ADD CONSTRAINT pk_studio_setting PRIMARY KEY (key);


--
-- Name: user_role pk_user_role; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id, scope_type, scope_id);


--
-- Name: spring_session_attributes spring_session_attributes_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);


--
-- Name: spring_session spring_session_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session
    ADD CONSTRAINT spring_session_pk PRIMARY KEY (primary_id);


--
-- Name: api_token uq_api_token_prefix; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_token
    ADD CONSTRAINT uq_api_token_prefix UNIQUE (prefix);


--
-- Name: app_user uq_app_user_issuer_subject; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uq_app_user_issuer_subject UNIQUE (issuer, subject);


--
-- Name: app_user uq_app_user_username; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT uq_app_user_username UNIQUE (username);


--
-- Name: broker_config_revision uq_broker_config_revision; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_revision
    ADD CONSTRAINT uq_broker_config_revision UNIQUE (cluster_id, revision);


--
-- Name: broker_credential uq_broker_credential_cluster_kind; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_credential
    ADD CONSTRAINT uq_broker_credential_cluster_kind UNIQUE (cluster_id, kind);


--
-- Name: broker_node uq_broker_node_cluster_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_node
    ADD CONSTRAINT uq_broker_node_cluster_name UNIQUE (cluster_id, name);


--
-- Name: broker_tls uq_broker_tls_cluster; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_tls
    ADD CONSTRAINT uq_broker_tls_cluster UNIQUE (cluster_id);


--
-- Name: cluster uq_cluster_env_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cluster
    ADD CONSTRAINT uq_cluster_env_name UNIQUE (environment_id, name);


--
-- Name: environment uq_environment_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.environment
    ADD CONSTRAINT uq_environment_name UNIQUE (name);


--
-- Name: message_index_subscription uq_message_index_subscription; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index_subscription
    ADD CONSTRAINT uq_message_index_subscription UNIQUE (cluster_id, queue_pattern);


--
-- Name: notification_channel uq_notification_channel_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_channel
    ADD CONSTRAINT uq_notification_channel_name UNIQUE (name);


--
-- Name: oidc_role_mapping uq_oidc_role_mapping; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oidc_role_mapping
    ADD CONSTRAINT uq_oidc_role_mapping UNIQUE (claim, claim_value, role_id, scope_type, scope_id);


--
-- Name: role uq_role_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role
    ADD CONSTRAINT uq_role_name UNIQUE (name);


--
-- Name: rr_expectation uq_rr_expectation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_expectation
    ADD CONSTRAINT uq_rr_expectation UNIQUE (cluster_id, request_address);


--
-- Name: ix_alert_delivery_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_delivery_due ON public.alert_delivery USING btree (next_attempt_at) WHERE (state = 'PENDING'::text);


--
-- Name: ix_alert_firing_cluster_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_firing_cluster_seq ON public.alert_firing USING btree (cluster_id, seq DESC);


--
-- Name: ix_alert_firing_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_firing_open ON public.alert_firing USING btree (cluster_id) WHERE (resolved_at IS NULL);


--
-- Name: ix_alert_rule_cluster_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_alert_rule_cluster_kind ON public.alert_rule USING btree (cluster_id, kind) WHERE enabled;


--
-- Name: ix_api_token_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_api_token_user ON public.api_token USING btree (user_id);


--
-- Name: ix_audit_event_cluster; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_event_cluster ON public.audit_event USING btree (cluster_id, ts DESC);


--
-- Name: ix_audit_event_ts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_event_ts ON public.audit_event USING btree (ts DESC);


--
-- Name: ix_audit_event_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_audit_event_user ON public.audit_event USING btree (user_id, ts DESC);


--
-- Name: ix_broker_config_apply_cluster; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_broker_config_apply_cluster ON public.broker_config_apply USING btree (cluster_id, started_at DESC);


--
-- Name: ix_broker_event_cluster_seq; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_broker_event_cluster_seq ON public.broker_event USING btree (cluster_id, seq DESC);


--
-- Name: ix_broker_event_cluster_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_broker_event_cluster_time ON public.broker_event USING btree (cluster_id, occurred_at DESC);


--
-- Name: ix_broker_event_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_broker_event_type ON public.broker_event USING btree (cluster_id, type, occurred_at DESC);


--
-- Name: ix_broker_node_cluster; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_broker_node_cluster ON public.broker_node USING btree (cluster_id);


--
-- Name: ix_message_index_body; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_message_index_body ON ONLY public.message_index USING gin (body public.gin_trgm_ops);


--
-- Name: ix_message_index_body_fts; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_message_index_body_fts ON ONLY public.message_index USING gin (to_tsvector('simple'::regconfig, body)) WHERE ((body IS NOT NULL) AND (message_type <> 4));


--
-- Name: ix_message_index_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_message_index_lookup ON ONLY public.message_index USING btree (cluster_id, queue_name, observed_at DESC);


--
-- Name: ix_message_index_props; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_message_index_props ON ONLY public.message_index USING gin (props jsonb_path_ops);


--
-- Name: ix_metric_sample_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_metric_sample_lookup ON ONLY public.metric_sample USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: ix_metric_sample_ts_brin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_metric_sample_ts_brin ON ONLY public.metric_sample USING brin (ts) WITH (pages_per_range='32');


--
-- Name: ix_queue_snapshot_address; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_queue_snapshot_address ON public.queue_snapshot USING btree (cluster_id, address);


--
-- Name: ix_queue_snapshot_cluster; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_queue_snapshot_cluster ON public.queue_snapshot USING btree (cluster_id);


--
-- Name: ix_rr_event_flow; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_event_flow ON public.rr_event USING btree (flow_id, ts);


--
-- Name: ix_rr_flow_address; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_flow_address ON public.rr_flow USING btree (cluster_id, request_address, requested_at DESC);


--
-- Name: ix_rr_flow_cluster_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_flow_cluster_state ON public.rr_flow USING btree (cluster_id, state);


--
-- Name: ix_rr_flow_correlation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_flow_correlation ON public.rr_flow USING btree (cluster_id, correlation_id);


--
-- Name: ix_rr_flow_deadline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_flow_deadline ON public.rr_flow USING btree (deadline_at) WHERE (state = 'AWAITING_REPLY'::text);


--
-- Name: ix_rr_flow_open_reply; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_rr_flow_open_reply ON public.rr_flow USING btree (cluster_id, reply_destination, correlation_id) WHERE (state = 'AWAITING_REPLY'::text);


--
-- Name: message_index_default_body_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_index_default_body_idx ON public.message_index_default USING gin (body public.gin_trgm_ops);


--
-- Name: message_index_default_cluster_id_queue_name_observed_at_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_index_default_cluster_id_queue_name_observed_at_idx ON public.message_index_default USING btree (cluster_id, queue_name, observed_at DESC);


--
-- Name: message_index_default_props_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_index_default_props_idx ON public.message_index_default USING gin (props jsonb_path_ops);


--
-- Name: message_index_default_to_tsvector_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_index_default_to_tsvector_idx ON public.message_index_default USING gin (to_tsvector('simple'::regconfig, body)) WHERE ((body IS NOT NULL) AND (message_type <> 4));


--
-- Name: metric_sample_20260913_cluster_id_subject_type_subject_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260913_cluster_id_subject_type_subject_name_idx ON public.metric_sample_20260913 USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: metric_sample_20260913_ts_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260913_ts_idx ON public.metric_sample_20260913 USING brin (ts) WITH (pages_per_range='32');


--
-- Name: metric_sample_20260914_cluster_id_subject_type_subject_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260914_cluster_id_subject_type_subject_name_idx ON public.metric_sample_20260914 USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: metric_sample_20260914_ts_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260914_ts_idx ON public.metric_sample_20260914 USING brin (ts) WITH (pages_per_range='32');


--
-- Name: metric_sample_20260915_cluster_id_subject_type_subject_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260915_cluster_id_subject_type_subject_name_idx ON public.metric_sample_20260915 USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: metric_sample_20260915_ts_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260915_ts_idx ON public.metric_sample_20260915 USING brin (ts) WITH (pages_per_range='32');


--
-- Name: metric_sample_20260916_cluster_id_subject_type_subject_name_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260916_cluster_id_subject_type_subject_name_idx ON public.metric_sample_20260916 USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: metric_sample_20260916_ts_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_20260916_ts_idx ON public.metric_sample_20260916 USING brin (ts) WITH (pages_per_range='32');


--
-- Name: metric_sample_default_cluster_id_subject_type_subject_name__idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_default_cluster_id_subject_type_subject_name__idx ON public.metric_sample_default USING btree (cluster_id, subject_type, subject_name, metric, ts);


--
-- Name: metric_sample_default_ts_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX metric_sample_default_ts_idx ON public.metric_sample_default USING brin (ts) WITH (pages_per_range='32');


--
-- Name: spring_session_ix1; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX spring_session_ix1 ON public.spring_session USING btree (session_id);


--
-- Name: spring_session_ix2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix2 ON public.spring_session USING btree (expiry_time);


--
-- Name: spring_session_ix3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX spring_session_ix3 ON public.spring_session USING btree (principal_name);


--
-- Name: uq_rr_flow_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_rr_flow_request ON public.rr_flow USING btree (cluster_id, request_address, request_message_id) WHERE (request_message_id IS NOT NULL);


--
-- Name: message_index_default_body_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_message_index_body ATTACH PARTITION public.message_index_default_body_idx;


--
-- Name: message_index_default_cluster_id_queue_name_observed_at_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_message_index_lookup ATTACH PARTITION public.message_index_default_cluster_id_queue_name_observed_at_idx;


--
-- Name: message_index_default_pkey; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.pk_message_index ATTACH PARTITION public.message_index_default_pkey;


--
-- Name: message_index_default_props_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_message_index_props ATTACH PARTITION public.message_index_default_props_idx;


--
-- Name: message_index_default_to_tsvector_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_message_index_body_fts ATTACH PARTITION public.message_index_default_to_tsvector_idx;


--
-- Name: metric_sample_20260913_cluster_id_subject_type_subject_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_lookup ATTACH PARTITION public.metric_sample_20260913_cluster_id_subject_type_subject_name_idx;


--
-- Name: metric_sample_20260913_ts_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_ts_brin ATTACH PARTITION public.metric_sample_20260913_ts_idx;


--
-- Name: metric_sample_20260914_cluster_id_subject_type_subject_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_lookup ATTACH PARTITION public.metric_sample_20260914_cluster_id_subject_type_subject_name_idx;


--
-- Name: metric_sample_20260914_ts_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_ts_brin ATTACH PARTITION public.metric_sample_20260914_ts_idx;


--
-- Name: metric_sample_20260915_cluster_id_subject_type_subject_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_lookup ATTACH PARTITION public.metric_sample_20260915_cluster_id_subject_type_subject_name_idx;


--
-- Name: metric_sample_20260915_ts_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_ts_brin ATTACH PARTITION public.metric_sample_20260915_ts_idx;


--
-- Name: metric_sample_20260916_cluster_id_subject_type_subject_name_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_lookup ATTACH PARTITION public.metric_sample_20260916_cluster_id_subject_type_subject_name_idx;


--
-- Name: metric_sample_20260916_ts_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_ts_brin ATTACH PARTITION public.metric_sample_20260916_ts_idx;


--
-- Name: metric_sample_default_cluster_id_subject_type_subject_name__idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_lookup ATTACH PARTITION public.metric_sample_default_cluster_id_subject_type_subject_name__idx;


--
-- Name: metric_sample_default_ts_idx; Type: INDEX ATTACH; Schema: public; Owner: -
--

ALTER INDEX public.ix_metric_sample_ts_brin ATTACH PARTITION public.metric_sample_default_ts_idx;


--
-- Name: alert_delivery fk_alert_delivery_channel; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery
    ADD CONSTRAINT fk_alert_delivery_channel FOREIGN KEY (channel_id) REFERENCES public.notification_channel(id) ON DELETE CASCADE;


--
-- Name: alert_delivery fk_alert_delivery_rule; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_delivery
    ADD CONSTRAINT fk_alert_delivery_rule FOREIGN KEY (rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;


--
-- Name: alert_firing fk_alert_firing_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_firing
    ADD CONSTRAINT fk_alert_firing_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: alert_firing fk_alert_firing_rule; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_firing
    ADD CONSTRAINT fk_alert_firing_rule FOREIGN KEY (rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;


--
-- Name: alert_rule_channel fk_alert_rule_channel_channel; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule_channel
    ADD CONSTRAINT fk_alert_rule_channel_channel FOREIGN KEY (channel_id) REFERENCES public.notification_channel(id) ON DELETE CASCADE;


--
-- Name: alert_rule_channel fk_alert_rule_channel_rule; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule_channel
    ADD CONSTRAINT fk_alert_rule_channel_rule FOREIGN KEY (rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;


--
-- Name: alert_rule fk_alert_rule_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_rule
    ADD CONSTRAINT fk_alert_rule_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: alert_state fk_alert_state_rule; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.alert_state
    ADD CONSTRAINT fk_alert_state_rule FOREIGN KEY (rule_id) REFERENCES public.alert_rule(id) ON DELETE CASCADE;


--
-- Name: api_token_grant fk_api_token_grant_token; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_token_grant
    ADD CONSTRAINT fk_api_token_grant_token FOREIGN KEY (token_id) REFERENCES public.api_token(id) ON DELETE CASCADE;


--
-- Name: api_token fk_api_token_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_token
    ADD CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;


--
-- Name: audit_event fk_audit_event_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_event
    ADD CONSTRAINT fk_audit_event_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE SET NULL;


--
-- Name: audit_event fk_audit_event_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_event
    ADD CONSTRAINT fk_audit_event_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE SET NULL;


--
-- Name: audit_event fk_audit_event_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_event
    ADD CONSTRAINT fk_audit_event_user FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE SET NULL;


--
-- Name: broker_config_apply fk_broker_config_apply_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_apply
    ADD CONSTRAINT fk_broker_config_apply_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_config_apply fk_broker_config_apply_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_apply
    ADD CONSTRAINT fk_broker_config_apply_revision FOREIGN KEY (revision_id) REFERENCES public.broker_config_revision(id);


--
-- Name: broker_config_declaration fk_broker_config_declaration_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_declaration
    ADD CONSTRAINT fk_broker_config_declaration_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_config_declaration fk_broker_config_declaration_revision; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_declaration
    ADD CONSTRAINT fk_broker_config_declaration_revision FOREIGN KEY (current_revision_id) REFERENCES public.broker_config_revision(id);


--
-- Name: broker_config_node_state fk_broker_config_node_state_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_node_state
    ADD CONSTRAINT fk_broker_config_node_state_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_config_node_state fk_broker_config_node_state_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_node_state
    ADD CONSTRAINT fk_broker_config_node_state_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE CASCADE;


--
-- Name: broker_config_owned_item fk_broker_config_owned_item_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_owned_item
    ADD CONSTRAINT fk_broker_config_owned_item_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_config_revision fk_broker_config_revision_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_config_revision
    ADD CONSTRAINT fk_broker_config_revision_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_credential fk_broker_credential_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_credential
    ADD CONSTRAINT fk_broker_credential_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_event fk_broker_event_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_event
    ADD CONSTRAINT fk_broker_event_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_event fk_broker_event_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_event
    ADD CONSTRAINT fk_broker_event_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE SET NULL;


--
-- Name: broker_node fk_broker_node_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_node
    ADD CONSTRAINT fk_broker_node_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: broker_tls fk_broker_tls_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker_tls
    ADD CONSTRAINT fk_broker_tls_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: cluster fk_cluster_environment; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cluster
    ADD CONSTRAINT fk_cluster_environment FOREIGN KEY (environment_id) REFERENCES public.environment(id) ON DELETE SET NULL;


--
-- Name: message_capture_node fk_message_capture_node_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_capture_node
    ADD CONSTRAINT fk_message_capture_node_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE CASCADE;


--
-- Name: message_capture_node fk_message_capture_node_subscription; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_capture_node
    ADD CONSTRAINT fk_message_capture_node_subscription FOREIGN KEY (subscription_id) REFERENCES public.message_index_subscription(id) ON DELETE CASCADE;


--
-- Name: message_index_subscription fk_message_index_subscription_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_index_subscription
    ADD CONSTRAINT fk_message_index_subscription_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: oidc_role_mapping fk_oidc_role_mapping_role; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.oidc_role_mapping
    ADD CONSTRAINT fk_oidc_role_mapping_role FOREIGN KEY (role_id) REFERENCES public.role(id) ON DELETE CASCADE;


--
-- Name: queue_snapshot fk_queue_snapshot_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.queue_snapshot
    ADD CONSTRAINT fk_queue_snapshot_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: queue_snapshot fk_queue_snapshot_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.queue_snapshot
    ADD CONSTRAINT fk_queue_snapshot_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE CASCADE;


--
-- Name: role_permission fk_role_permission_role; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES public.role(id) ON DELETE CASCADE;


--
-- Name: rr_event fk_rr_event_flow; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_event
    ADD CONSTRAINT fk_rr_event_flow FOREIGN KEY (flow_id) REFERENCES public.rr_flow(id) ON DELETE CASCADE;


--
-- Name: rr_expectation fk_rr_expectation_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_expectation
    ADD CONSTRAINT fk_rr_expectation_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: rr_flow fk_rr_flow_cluster; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_flow
    ADD CONSTRAINT fk_rr_flow_cluster FOREIGN KEY (cluster_id) REFERENCES public.cluster(id) ON DELETE CASCADE;


--
-- Name: rr_flow fk_rr_flow_node; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.rr_flow
    ADD CONSTRAINT fk_rr_flow_node FOREIGN KEY (node_id) REFERENCES public.broker_node(id) ON DELETE SET NULL;


--
-- Name: user_role fk_user_role_role; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES public.role(id) ON DELETE CASCADE;


--
-- Name: user_role fk_user_role_user; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_role
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;


--
-- Name: spring_session_attributes spring_session_attributes_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES public.spring_session(primary_id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict QTXL7NgwCpWLbcZLaCpUW0TrdfKqHFyW9l46zoPSLxV4Vw422PZx1T6HXrZf1VO

