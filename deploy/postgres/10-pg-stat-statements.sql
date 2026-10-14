-- Runs once, when the bundled database is first created (mounted into
-- /docker-entrypoint-initdb.d). postgresql.conf preloads the library; this makes its view
-- available. Rahdar never needs it: it is for operators reading statement statistics.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
