--liquibase formatted sql

-- A management URL is derived from a pattern and proved by NodeID (ADR-0175). The cluster stores the
-- pattern, `scheme://{host}:port/path`, and each node records where its management URL came from (a
-- seed, derived from the pattern, or set by an operator) and, when it has none, why. These replace the
-- `discovered` and `manual_override` flags: the source says which URL discovery may replace, and a
-- manual Core URL, which the source does not cover, has its own flag. Nothing is converted: a node
-- keeps its URL, and the next discovery records the source of every URL it derives. A rejected
-- credential is told apart from an unreachable broker (`last_error_kind`), so cluster health can name
-- the account. `url_checked_at` is when a derivation last failed, so a node is not asked again every
-- tick. Never edit this file once released; add a new changeset beside it.

--changeset artemis-studio:platform-clusters-0007-management-url-derivation
ALTER TABLE cluster ADD COLUMN management_url_pattern text;

ALTER TABLE broker_node ADD COLUMN url_checked_at timestamptz;
ALTER TABLE broker_node ADD COLUMN url_source text;
ALTER TABLE broker_node ADD COLUMN url_problem text;
ALTER TABLE broker_node ADD COLUMN last_error_kind text;
ALTER TABLE broker_node ADD COLUMN core_url_manual boolean NOT NULL DEFAULT false;
ALTER TABLE broker_node ADD CONSTRAINT ck_broker_node_url_source CHECK (url_source IS NULL OR url_source IN ('SEED', 'DERIVED', 'MANUAL'));
ALTER TABLE broker_node DROP COLUMN discovered;
ALTER TABLE broker_node DROP COLUMN manual_override;
--rollback ALTER TABLE broker_node ADD COLUMN manual_override boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE broker_node ADD COLUMN discovered boolean NOT NULL DEFAULT false;
--rollback ALTER TABLE broker_node DROP CONSTRAINT ck_broker_node_url_source;
--rollback ALTER TABLE broker_node DROP COLUMN core_url_manual;
--rollback ALTER TABLE broker_node DROP COLUMN last_error_kind;
--rollback ALTER TABLE broker_node DROP COLUMN url_problem;
--rollback ALTER TABLE broker_node DROP COLUMN url_source;
--rollback ALTER TABLE broker_node DROP COLUMN url_checked_at;
--rollback ALTER TABLE cluster DROP COLUMN management_url_pattern;
