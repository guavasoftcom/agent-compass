-- Stored generated column for metric_points' session-id dimension, extending
-- the V16/V17 pattern (log_records.event_name / log_records.tool_name) to this
-- table -- but for a DIFFERENT underlying mechanism than those two, so read
-- this rationale rather than assuming it's another TOAST-detoast fix.
--
-- Measured on the live database: GET /api/sessions?minutes=10080&page=0&size=25
-- (MetricPointRepository#aggregateSessionSummaries) took 1.6-1.8s server-side,
-- consistent across repeated runs (not cold cache). EXPLAIN (ANALYZE, BUFFERS)
-- showed ~1.26M buffer blocks (~10GB) read out of the table's 4.5M rows / 5.9GB.
-- Three of the query's window-scoped CTEs (session_window, token_per_session,
-- session_meta) filter on metric_name + timestamp -- covered by
-- idx_metric_points_name_ts (V1) -- but every one of them also needs
-- attributes ->> 'session.id' (two of them also attributes ->> 'type' /
-- 'start_type' / 'terminal.type'), which lives inside the jsonb attributes
-- column and isn't part of that index. So every row the (metric_name,
-- timestamp) index matches still forces a heap-page visit to extract
-- session.id. Average row width here is ~821 bytes -- only ~9 rows per 8KB
-- page -- so filtering even a modest fraction of the table still touches
-- nearly every page.
--
-- This is NOT the TOAST-detoast cost V16/V17 fixed on log_records: confirmed
-- on the live database that metric_points.attributes is essentially never
-- TOASTed (avg ~440 bytes/row; only ~1.2MB of the whole table lives in
-- pg_toast, against log_records' ~11KB average and 1.44GB toasted). The win
-- here is purely about enabling INDEX-ONLY SCANS to skip heap I/O entirely,
-- which requires session_id to be a real column so it can ride in an index
-- (either as a key column, or as an INCLUDE payload alongside metric_name +
-- timestamp) instead of being re-derived from the heap tuple on every match.
--
-- This does NOT touch aggregateSessionSummaries' session_totals CTE, the
-- single biggest contributor to the ~10GB read (~5.3GB) -- that CTE's
-- unbounded whole-session join was already investigated (see the comment
-- block on MetricPointRepository#aggregateSessionSummaries, ~lines 387-399):
-- Postgres deliberately chooses a sequential/bitmap scan of the full
-- cost+active-time metric history over a nested loop through
-- idx_metric_points_session_id_name_ts there, confirmed by prior EXPLAIN
-- ANALYZE work, and is out of scope for this migration.
--
-- Two index changes accompany the new column:
--
-- 1. idx_metric_points_session_id_name_ts (V13) was an expression index on
--    ((attributes ->> 'session.id'), metric_name, timestamp). Recreated here
--    on the stored column in the same (session_id, metric_name, timestamp)
--    shape -- same query coverage, but now eligible for index-only scans.
--
-- 2. idx_metric_points_name_ts (V1, (metric_name, timestamp)) gains an
--    INCLUDE payload of (session_id, value_delta, start_timestamp) -- the
--    columns session_window / token_per_session / session_meta select
--    alongside their metric_name + timestamp filter. INCLUDE columns are not
--    key columns: they don't change what the leading (metric_name,
--    timestamp) pair filters or sorts on, so every other caller of this
--    index (aggregateTotalTokens, aggregateCostCurrentAndPriorTotals,
--    aggregateCostBreakdown, aggregateMetricTotals, aggregateMetricTrend,
--    etc.) is unaffected -- they simply ignore the extra payload. Same
--    "widen an existing index rather than add a parallel one" call V12 made
--    (idx_metric_points_ts_id replacing + dropping the narrower
--    idx_metric_points_ts, "subsumes... dropped to avoid duplicate write
--    amplification").
--
-- Only session_window ends up as a true index-only scan: its SELECT list and
-- filters touch nothing but metric_name/timestamp (key) and session_id/
-- value_delta (INCLUDE). token_per_session and session_meta still read
-- attributes ->> :tokenTypeAttribute / 'start_type' / 'terminal.type', which
-- aren't part of this index, so they remain heap-visiting Bitmap Heap Scans --
-- expected, not a shortfall of this migration.
--
-- OPERATIONAL CAVEAT confirmed while validating this migration against the
-- live database: ADD COLUMN ... GENERATED ALWAYS AS (...) STORED forces a
-- full table rewrite, which resets the visibility map (relallvisible dropped
-- to 0 on a 504k-page table immediately after applying this migration here).
-- Index-only scans require the visibility map, so EXPLAIN (ANALYZE, BUFFERS)
-- run right after migrating still showed the OLD Bitmap-Heap-Scan plan and the
-- OLD ~1.6s runtime -- no measurable improvement -- until a VACUUM (ANALYZE)
-- metric_points ran (dead tuples 616933 -> 1024, relallvisible 0 -> 504306,
-- ~1.7s). Only after that did session_window's leg switch to a genuine Index
-- Only Scan (Heap Fetches: 156, versus a full per-match heap visit before),
-- and end-to-end execution time drop from ~1.6-1.8s to ~1.3-1.6s. Applying
-- this migration to a live database therefore isn't complete without a
-- follow-up VACUUM ANALYZE metric_points (or waiting for autovacuum) --
-- Flyway's own migrate run doesn't need this called out separately since a
-- fresh/CI database has no meaningful visibility-map history to lose, but a
-- production apply does.
--
-- NOTE: if applying this out-of-band to a live database (rather than via a
-- fresh Flyway migrate), the DROP + CREATE pairs below take a brief
-- table-locking write outage on metric_points; build the replacement indexes
-- CONCURRENTLY first and drop the old ones only once the new ones report
-- valid, mirroring V13's caveat:
--   CREATE INDEX CONCURRENTLY idx_metric_points_session_id_name_ts_new
--       ON metric_points (session_id, metric_name, timestamp);
--   CREATE INDEX CONCURRENTLY idx_metric_points_name_ts_new
--       ON metric_points (metric_name, timestamp)
--       INCLUDE (session_id, value_delta, start_timestamp);
--   -- then DROP the old two and rename the _new ones into place.
ALTER TABLE metric_points
    ADD COLUMN session_id text
    GENERATED ALWAYS AS (attributes ->> 'session.id') STORED;

DROP INDEX idx_metric_points_session_id_name_ts;
CREATE INDEX idx_metric_points_session_id_name_ts
    ON metric_points (session_id, metric_name, timestamp);

DROP INDEX idx_metric_points_name_ts;
CREATE INDEX idx_metric_points_name_ts
    ON metric_points (metric_name, timestamp)
    INCLUDE (session_id, value_delta, start_timestamp);
