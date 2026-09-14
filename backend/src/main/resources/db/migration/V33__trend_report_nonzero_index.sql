-- Supporting index for the Trend Report page's window-scoped metric_points aggregations
-- (MetricPointRepository.aggregateMetricsTotalsCombined, aggregateMetricsSparklinesCombined,
-- aggregateTokenTypeSparklinesCombined, aggregateSessionCountAndDurationCurrentAndPrior).
--
-- Same root cause as V32, different access path. V32's index leads on session_id because the
-- Sessions grid looks a session up by id; these four queries instead scan a metric over a
-- window -- metric_name IN (...) AND timestamp BETWEEN priorFrom AND to -- so V32's index
-- cannot serve them as a range scan and they were reading the full ghost-row population:
-- 8,586,064 metric_points rows of which only 46,995 (0.55%) carry a non-zero value_delta.
-- Because every figure here is a SUM(value_delta), the totals were always right while the row
-- set was ~180x larger than necessary, which is exactly the failure mode backend/CLAUDE.md
-- warns about under "Streams are never retired" -- it hides because the numbers look correct.
--
-- Measured on the live database, 30-day window compared against the preceding 30 days:
--   GET /api/trends/cost              9.52 s
--   GET /api/trends/token-efficiency 11.96 s
-- against 0.12 s for /reliability, which reads log_records and never touches this table.
-- Per query, with the matching "value_delta IS DISTINCT FROM 0" filters added alongside this
-- migration: the totals query 6828 ms -> 194 ms, the sparklines query 2785 ms -> 49 ms.
--
-- INCLUDE carries the three non-key columns these queries read off each matched row, so the
-- cost/token/session aggregations run as index-only scans. The per-token-type legs still take
-- one heap visit per row for attributes ->> 'type', which is ~4,400 rows per type over 60 days
-- instead of 888,278.
--
-- Partial on the same predicate the queries now carry, which is why it is 5.2 MB against an
-- 8.7 GB table. IS DISTINCT FROM (not <>) keeps value_delta IS NULL rows so the index matches
-- the queries exactly; a plain <> 0 would exclude them and could not be used.
CREATE INDEX IF NOT EXISTS idx_metric_points_nonzero_name_ts
    ON metric_points (metric_name, timestamp)
    INCLUDE (value_delta, session_id, value_double)
    WHERE value_delta IS DISTINCT FROM 0;
