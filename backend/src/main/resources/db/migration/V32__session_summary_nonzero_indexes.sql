-- Supporting indexes for MetricPointRepository.aggregateSessionSummaries (the Sessions grid),
-- which was timing out outright: measured on the live database over a 30-day window it took
-- 15,424 ms against the pooled statement_timeout of 15s, so the page returned
-- "canceling statement due to statement timeout" rather than a slow answer.
--
-- WHY IT WAS THAT SLOW. The query resolves a page of sessions by running three correlated
-- laterals per session -- totals (cost/active), tokens (four-way breakdown), meta
-- (start/terminal type) -- and the ORDER BY/LIMIT can only be applied afterwards, since every
-- sort column except session_id is computed by those laterals. So a 25-row page paid for
-- 195 sessions x 3 laterals = 585 lateral executions, touching ~1.9M buffers (~15 GB, more
-- than the whole 8.7 GB table).
--
-- The multiplier is the ghost-row effect this schema's own notes describe under "Streams are
-- never retired" (see backend/CLAUDE.md and V11): Claude Code's exporter re-emits every
-- cumulative counter about once a minute for the life of the process at an unchanged value, so
-- of 8,586,064 metric_points rows only 46,995 -- 0.55% -- carry a non-zero value_delta. Every
-- figure the Sessions grid shows is computed from that 0.55%, but the laterals were reading all
-- of it, 195 times over.
--
-- WHY A FILTER ALONE WAS NOT ENOUGH, AND THIS IS AN INDEX. Adding
-- "value_delta IS DISTINCT FROM 0" to the token lateral cut its rows per session from 13,900 to
-- 75 and bought almost nothing -- 15,424 ms -> 12,978 ms -- because value_delta rides the
-- existing indexes only as an INCLUDE payload, never as a key: the filter was applied after the
-- heap fetch, so the heap blocks (885,241) did not move. These indexes make the ghost rows
-- unreachable instead of merely discarded, which is the difference between a filter and an
-- index here. Both are PARTIAL on exactly the rows the queries can use, which is why they are
-- 5.8 MB and 3.0 MB against an 8.7 GB table -- cheaper than the single scan they replace.
--
-- Measured end to end on the live database, same window, both indexes in place:
-- 15,424 ms -> 18.9 ms, buffers 1,938,070 -> 15,097. Per-session lateral cost went
-- 43.3 ms -> 0.03 ms (totals, now an index-only scan), 28.3 ms -> 0.31 ms (tokens),
-- 12.1 ms -> 0.03 ms (meta).

-- Serves the totals and tokens laterals. session_id/metric_name/timestamp are their filter
-- columns in that order; value_delta, start_timestamp and value_double are INCLUDEd so the
-- totals lateral -- whose every output is built from those three -- runs as an index-only scan
-- and never visits the heap at all. The tokens lateral still takes one heap visit per row for
-- attributes ->> 'type', which is now ~75 rows per session instead of ~14,000.
--
-- The predicate mirrors the "value_delta IS DISTINCT FROM 0" filter the query's own
-- session_window CTE has always carried, and the two lateral filters added alongside this
-- migration. IS DISTINCT FROM (not <>) keeps rows whose value_delta is NULL, matching the
-- query exactly -- a plain <> 0 would drop them and the index could not serve the query.
CREATE INDEX IF NOT EXISTS idx_metric_points_nonzero_session_name_ts
    ON metric_points (session_id, metric_name, timestamp)
    INCLUDE (value_delta, start_timestamp, value_double)
    WHERE value_delta IS DISTINCT FROM 0;

-- Serves the meta lateral, which reads one session.count row per session to get start_type /
-- terminal.type. It cannot use the index above: session.count rows are heartbeats that are
-- overwhelmingly zero-delta (412,615 rows across 389 sessions), so the non-zero predicate would
-- exclude nearly all of them and change which row -- or whether any row -- is found. That
-- matters here specifically because a start_type=resume stream emits ONLY session.count.
--
-- The second key column is the COALESCE expression, not a bare column, so it matches the
-- lateral's "ORDER BY COALESCE(mp.start_timestamp, mp.timestamp) LIMIT 1" and the row is read
-- straight off the index in order. With the plain (session_id, metric_name, timestamp) index
-- the sort key did not match, so Postgres sorted ~1,121 rows per session to take one: a
-- top-N heapsort costing 12.1 ms x 195 sessions, which was 2.36 s of the remaining 2.59 s
-- after the first index landed.
--
-- The 'claude_code.session.count' literal mirrors TuningProperties.sessionCountMetric, the same
-- SQL-mirrors-configuration arrangement V14/V15/V20 already have. Overriding that property
-- means a migration rebuilding this index against the new name, or the meta lateral silently
-- falls back to the sort.
CREATE INDEX IF NOT EXISTS idx_metric_points_session_count_start
    ON metric_points (session_id, (COALESCE(start_timestamp, timestamp)))
    WHERE metric_name = 'claude_code.session.count';
