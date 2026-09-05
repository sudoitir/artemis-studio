--liquibase formatted sql

-- Bootstrap daily partitions for metric_sample (changeset 005 declared it
-- PARTITION BY RANGE (ts) with only a default partition). This changeset only
-- guarantees "today" and a few days ahead have a home the moment migrations
-- finish; persist/MetricPartitionMaintainer takes over day-to-day creation and
-- the eventual drop once a partition ages past retention (scrape-scheduling
-- spec). The default partition is kept permanently as a catch-all for rows
-- written before the first maintainer run.
--
-- A day's partition can be created directly as `PARTITION OF ... FOR VALUES
-- FROM (...) TO (...)` only while the default partition holds no row in that
-- range yet. Applied to a database that was already running before this
-- migration landed, the default partition already holds today's samples
-- (`metric_sample_default` is where every row has landed so far), and
-- Postgres refuses to create an overlapping partition outright: "updated
-- partition constraint for default partition would be violated by some row".
-- So each day's table is created bare, populated with whatever rows the
-- default partition already has for that range, and only then attached —
-- the standard "split the default partition" maneuver.

--changeset artemis-studio:012-metric-partitions-bootstrap splitStatements:false
--comment: idempotent — safe to re-run if this changeset is ever replayed against
--         a database where the maintainer has already created these partitions.
DO $$
DECLARE
    d date;
    part_name text;
    already_exists boolean;
BEGIN
    FOR d IN SELECT generate_series(current_date, current_date + 3, interval '1 day')::date LOOP
        part_name := 'metric_sample_' || to_char(d, 'YYYYMMDD');

        SELECT EXISTS (
            SELECT 1 FROM pg_inherits i
              JOIN pg_class c ON c.oid = i.inhrelid
             WHERE i.inhparent = 'metric_sample'::regclass AND c.relname = part_name
        ) INTO already_exists;

        IF NOT already_exists THEN
            EXECUTE format('CREATE TABLE %I (LIKE metric_sample INCLUDING ALL)', part_name);
            EXECUTE format(
                'ALTER TABLE %I SET (fillfactor = 100, autovacuum_vacuum_insert_scale_factor = 0.0, '
                || 'autovacuum_vacuum_insert_threshold = 10000, autovacuum_analyze_scale_factor = 0.05)',
                part_name);
            EXECUTE format(
                'WITH moved AS (DELETE FROM metric_sample_default WHERE ts >= %L AND ts < %L RETURNING *) '
                || 'INSERT INTO %I SELECT * FROM moved',
                d, d + 1, part_name);
            EXECUTE format(
                'ALTER TABLE metric_sample ATTACH PARTITION %I FOR VALUES FROM (%L) TO (%L)',
                part_name, d, d + 1);
        END IF;
    END LOOP;
END $$;
--rollback -- partitions are dropped by the retention job on their own schedule; nothing to roll back here
