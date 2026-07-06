-- Precomputes reset-aware counter deltas at write time so dashboard aggregations
-- (token usage, cost, sessions, metrics catalog) become plain SUM(value_delta)
-- group-bys instead of a LAG(...) OVER (PARTITION BY attributes ORDER BY timestamp)
-- window function evaluated at read time.
--
-- Why: claude_code.* metrics are CUMULATIVE counters re-emitted roughly every
-- minute. Every dashboard query needed to convert the raw running totals into
-- per-row increments (Prometheus increase() semantics) by sorting the ENTIRE
-- window keyed on the full attributes jsonb blob. On a 30-day window that sort
-- touches every row in metric_points and detoasts every attributes value, which
-- measured as the single biggest cost on the token usage page. Computing the
-- increment once at ingest and storing it lets every read-side query become a
-- plain (metric_name, timestamp)-indexed SUM/GROUP BY.
--
-- stream_id: a stream is one counter, identified by its FULL attribute set (the
-- same (session, model, type) can carry several concurrent streams distinguished
-- by query_source / agent.name — see the historical comments this migration
-- replaces in MetricPointRepository). md5(metric_name || '|' || attributes::text)
-- folds that identity into one small indexable value; jsonb::text is Postgres's
-- canonical (sorted-key, whitespace-normalized) serialization, so two rows with
-- the same attribute map always hash identically regardless of key insertion
-- order. metric_name is included so identical attribute sets on two different
-- metrics never collide.
--
-- value_delta: the reset-aware per-row increment — CASE WHEN current >= previous
-- THEN current - previous ELSE current END, where "previous" is the same
-- stream's most recent earlier row (by timestamp, then id to break exact-
-- timestamp ties deterministically). A reset (the same attribute set reused by a
-- freshly (re)started run whose counter starts low again) counts the new level
-- in full rather than going negative. Computed uniformly for every metric_points
-- row, not only known cumulative-counter metrics: the delta is only *meaningful*
-- for cumulative counters, but computing it everywhere is simpler than
-- maintaining a metric-name allowlist here, and it is harmless for gauges/other
-- shapes that no aggregation reads value_delta for.
--
-- Backfill uses a LAG window (this is a one-time migration-time cost, not a
-- per-query cost) partitioned by the new stream_id, ordered by (timestamp, id).
--
-- Known intentional behavior change: the old read-time LAG only ever looked
-- inside the queried window, so a stream's first in-window row (no in-window
-- predecessor) was always counted at its FULL cumulative value even if the
-- stream had been running before the window started — an overcount at window
-- boundaries. value_delta is computed once against the TRUE previous row
-- (in or out of any later query window), so window totals are now accurate at
-- boundaries. Tests that encoded the old boundary behavior are updated
-- accordingly.
ALTER TABLE metric_points
    ADD COLUMN stream_id text
    GENERATED ALWAYS AS (md5(metric_name || '|' || coalesce(attributes::text, ''))) STORED;

ALTER TABLE metric_points
    ADD COLUMN value_delta double precision;

CREATE INDEX idx_metric_points_stream_ts ON metric_points (stream_id, timestamp, id);

WITH lagged AS (
    SELECT
        id,
        COALESCE(value_long::double precision, value_double, 0) AS current_value,
        COALESCE(LAG(COALESCE(value_long::double precision, value_double, 0))
            OVER (PARTITION BY stream_id ORDER BY timestamp, id), 0) AS previous_value
    FROM metric_points
)
UPDATE metric_points AS metric_point
SET value_delta = CASE
    WHEN lagged.current_value >= lagged.previous_value THEN lagged.current_value - lagged.previous_value
    ELSE lagged.current_value
END
FROM lagged
WHERE metric_point.id = lagged.id;
