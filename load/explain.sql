-- Run by scripts/load.sh against Rahdar's database after the simulation (psql, output in the report).
-- It answers two questions the HTTP load cannot: how the history queries without a node filter are planned over
-- uq_metric_sample, and how many events per second the single writer under the broker_event write lock can flush.
\set ON_ERROR_STOP off
\pset pager off

SELECT cluster_id AS cid FROM metric_sample ORDER BY ts DESC LIMIT 1 \gset

\echo == cluster total of a gauge over one hour, summed over every queue with no node filter (MetricSamples.clusterGaugeTotal)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
WITH samples AS (
  SELECT ts, value, lead(ts) OVER w AS next_ts, ts - lag(ts) OVER w AS gap
    FROM metric_sample
   WHERE cluster_id = :'cid' AND subject_type = 'QUEUE' AND metric = 'messageCount'
     AND ts >= now() - interval '70 minutes' AND ts < now()
  WINDOW w AS (PARTITION BY subject_name, node_id ORDER BY ts)
), spans AS (
  SELECT value, date_bin(interval '1 minute', ts, TIMESTAMPTZ '2000-01-01') + interval '1 minute' AS first_end,
         LEAST(COALESCE(next_ts, 'infinity'), ts + COALESCE(gap, interval '10 minutes') * 1.5, now() + interval '1 minute') AS last_end
    FROM samples
)
SELECT bucket_end, sum(value)
  FROM spans, generate_series(first_end, last_end, interval '1 minute') AS bucket_end
 GROUP BY bucket_end ORDER BY bucket_end;

\echo == one queue over one hour, no node filter
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT date_bin(interval '1 minute', ts, TIMESTAMPTZ '2000-01-01') AS bucket, avg(value)
  FROM metric_sample
 WHERE cluster_id = :'cid' AND subject_type = 'QUEUE' AND subject_name = 'load.q.1' AND metric = 'messageCount'
   AND ts >= now() - interval '1 hour' AND ts < now()
 GROUP BY 1 ORDER BY 1;

\echo == every queue of a cluster, one metric, no node filter (the rate query)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT subject_name, node_id, GREATEST(max(value) - min(value), 0)
  FROM metric_sample
 WHERE cluster_id = :'cid' AND subject_type = 'QUEUE' AND metric = 'messagesAdded'
   AND ts >= now() - interval '10 minutes' AND ts < now()
 GROUP BY subject_name, node_id;

\echo == rows in metric_sample and broker_event
SELECT (SELECT count(*) FROM metric_sample) AS metric_samples, (SELECT count(*) FROM broker_event) AS broker_events;

\echo == flush throughput under the write lock: 200 batches of 500 rows, one writer, one commit per batch
DO $$
DECLARE
  started timestamptz;
  cluster uuid := (SELECT cluster_id FROM metric_sample LIMIT 1);
  batches int := 200;
  seconds double precision;
BEGIN
  started := clock_timestamp();
  FOR i IN 1..batches LOOP
    PERFORM pg_advisory_xact_lock(hashtext('broker_event.write'));
    INSERT INTO broker_event (occurred_at, type, address, routing_name, consumer_name, session_name,
                              connection_name, remote_address, username, props, cluster_id, node_id)
    SELECT now(), 'CONSUMER_CREATED', 'load.a.' || g, 'load.q.' || g, 'consumer-' || g, 'session-' || g,
           'connection-' || g, '10.0.0.' || (g % 250), 'artemis', '{"filter":null,"size":42}'::jsonb, cluster, NULL
      FROM generate_series(1, 500) g;
    COMMIT;
  END LOOP;
  seconds := extract(epoch FROM clock_timestamp() - started);
  RAISE NOTICE 'flush: % rows in % s = % rows/s', batches * 500, round(seconds::numeric, 2), round((batches * 500 / seconds)::numeric);
END $$;
