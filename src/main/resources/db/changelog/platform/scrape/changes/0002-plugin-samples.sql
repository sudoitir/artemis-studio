--liquibase formatted sql

-- Plugin metrics (ADR-0113) are sampled per Studio instance, not per broker node, so their
-- metric_sample rows carry no node. Dropping NOT NULL on the partitioned parent propagates to
-- every partition. Never edit this file once released.

--changeset artemis-studio:platform-scrape-0002-plugin-samples
ALTER TABLE metric_sample ALTER COLUMN node_id DROP NOT NULL;
--rollback DELETE FROM metric_sample WHERE node_id IS NULL;
--rollback ALTER TABLE metric_sample ALTER COLUMN node_id SET NOT NULL;
