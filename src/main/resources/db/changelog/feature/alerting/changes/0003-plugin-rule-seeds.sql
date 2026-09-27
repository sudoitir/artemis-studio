--liquibase formatted sql

-- Which plugin-declared alert rules (ADR-0113) have been created on which cluster. A row is
-- written with the rule, so a rule the operator edits or deletes is never seeded again.
-- Never edit this file once released.

--changeset artemis-studio:feature-alerting-0003-plugin-rule-seeds splitStatements:true
CREATE TABLE alert_rule_seed (
    plugin_id text NOT NULL,
    seed_key text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT pk_alert_rule_seed PRIMARY KEY (cluster_id, plugin_id, seed_key),
    CONSTRAINT fk_alert_rule_seed_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE
);
--rollback DROP TABLE IF EXISTS alert_rule_seed;
