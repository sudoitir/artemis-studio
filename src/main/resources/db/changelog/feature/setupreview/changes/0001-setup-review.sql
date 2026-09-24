--liquibase formatted sql

-- Setup review (ADR-0106). setup_review is one row per cluster: the last run and which nodes it
-- could read. setup_finding is the latest finding per (cluster, code, subject), rewritten every
-- run for the subjects that run evaluated and kept, marked stale, for the ones it could not — a
-- disposable cache like queue_snapshot, updated in place every interval (hence fillfactor and
-- autovacuum). setup_finding_acceptance is an operator's decision that a finding is a known risk,
-- and is not a cache: it survives the finding disappearing, so a regression is accepted only if
-- it is the same risk again.
-- Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:feature-setupreview-0001-setup-review splitStatements:true
CREATE TABLE setup_review (
    reviewed_at timestamp with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    nodes_total integer NOT NULL,
    nodes_reviewed integer NOT NULL,
    nodes jsonb NOT NULL,
    not_assessed jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    cluster_evaluated boolean NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE setup_finding (
    first_seen_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    code text NOT NULL,
    subject text NOT NULL,
    severity text NOT NULL,
    category text NOT NULL,
    finding jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_setup_finding_severity CHECK (severity IN ('CRITICAL', 'WARNING', 'INFO'))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE setup_finding_acceptance (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    code text NOT NULL,
    subject text NOT NULL,
    reason text NOT NULL,
    accepted_by text NOT NULL,
    cluster_id uuid NOT NULL
);

ALTER TABLE ONLY setup_review
    ADD CONSTRAINT pk_setup_review PRIMARY KEY (cluster_id);

ALTER TABLE ONLY setup_finding
    ADD CONSTRAINT pk_setup_finding PRIMARY KEY (cluster_id, code, subject);

ALTER TABLE ONLY setup_finding_acceptance
    ADD CONSTRAINT pk_setup_finding_acceptance PRIMARY KEY (cluster_id, code, subject);

ALTER TABLE ONLY setup_review
    ADD CONSTRAINT fk_setup_review_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY setup_finding
    ADD CONSTRAINT fk_setup_finding_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY setup_finding_acceptance
    ADD CONSTRAINT fk_setup_finding_acceptance_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS setup_finding_acceptance;
--rollback DROP TABLE IF EXISTS setup_finding;
--rollback DROP TABLE IF EXISTS setup_review;
