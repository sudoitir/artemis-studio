--liquibase formatted sql

-- A management URL identifies one node row. Topology discovery used to attach a seed's URL to the
-- first row with the seed's NodeID and role, so after a failover a pair could end up with two rows
-- reading one broker, which the scrape counts as two live nodes (a false split-brain). Of each such
-- group the row discovery named after the broker's connector is kept and the one named after the
-- management address is deleted; what hangs off a node (snapshots, samples, captures) cascades or is
-- unlinked, and is rebuilt by the next scrape. Never edit this file once released; add a new
-- one beside it.

--changeset artemis-studio:platform-clusters-0005-broker-node-unique-jolokia-url
DELETE FROM broker_node
WHERE id IN (
    SELECT id FROM (
        SELECT id, row_number() OVER (
            PARTITION BY cluster_id, jolokia_url
            ORDER BY core_url IS NULL, manual_override DESC, name, id) AS keep_rank
        FROM broker_node
        WHERE jolokia_url IS NOT NULL
    ) ranked
    WHERE keep_rank > 1
);
ALTER TABLE broker_node ADD CONSTRAINT uq_broker_node_cluster_jolokia_url UNIQUE (cluster_id, jolokia_url);
--rollback ALTER TABLE broker_node DROP CONSTRAINT uq_broker_node_cluster_jolokia_url;
