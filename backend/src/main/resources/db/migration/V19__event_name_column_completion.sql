-- Finishes the migration V16 started.
--
-- V16 added the stored generated column log_records.event_name AND replaced the
-- V7 expression index idx_log_records_event_name_ts -- which indexed
-- (attributes ->> 'event.name') -- with one on the column. It then updated only
-- the Logs-page queries to read the column. Everything else in the codebase kept
-- filtering on the raw extraction, which V16 had just de-indexed, so those
-- queries silently fell back to idx_log_records_ts_id (timestamp only) and then
-- detoasted every row in the window to evaluate the filter. On this table that
-- means pulling ~11 KB attribute payloads (1478 MB of TOAST against 142 MB of
-- heap) off disk only to discard most of them.
--
-- Measured cost of the half-applied state, live database, 7-day window, warm
-- cache: each of the tuning report's 12 statements converged on ~740 ms, for a
-- 9-second /api/report. After pointing them at the column: 828 ms -> 6 ms
-- (tool-call mix), 808 ms -> 14 ms (bash hotspots), 778 ms -> 6 ms (failure
-- rollup). LogRecordRepository's 32 extractions and SpanRepository's 34 are
-- migrated alongside this file.
--
-- The swap is safe by construction: event_name IS
-- GENERATED ALWAYS AS (attributes ->> 'event.name') STORED, verified as 0
-- mismatches over all 132,254 rows (IS DISTINCT FROM, so NULLs are included).
--
-- What this migration has to fix that a find-and-replace in Java cannot:
--
-- 1) idx_log_records_request_id (V15) is a PARTIAL index whose predicate is
--    written as (attributes ->> 'event.name') = 'api_request'. A partial index
--    is only usable when the planner can prove the query's WHERE clause implies
--    that predicate, and its prover compares expressions structurally -- it does
--    NOT know that the event_name column and the extraction it is generated
--    from are the same value. So a query rewritten to say event_name loses this
--    index entirely. Verified before writing this migration: the raw form plans
--    an Index Scan at cost 9.72, the column form falls back to a Bitmap Heap
--    Scan at cost 2287. Rebuilding the predicate against the column is therefore
--    mandatory, not cosmetic -- without it, "finishing" the swap would make
--    span_efforts slower rather than faster.
--
-- 2) The span_costs / trace_costs (V14) and span_efforts (V15) views embed the
--    same filter. AGENTS.md requires these views and SpanRepository's
--    LEFT JOIN LATERAL predicates -- which deliberately re-run the views' filter
--    against log_records for pushdown -- to move in lockstep, so both sides are
--    changed here and in that repository.
--
-- Semantics are unchanged throughout: same rows, same values, same view column
-- lists. Only the access path changes.
--
-- Deliberately NOT changed: attributes ->> :toolAttribute stays a jsonb read.
-- V17's tool_name column is generated from the literal 'tool_name', but the key
-- is a bind parameter (tuning.tool-attribute) at these call sites, and hardcoding
-- it would break that override for a measured ~1.5 ms once the event_name filter
-- is indexed (6.1 ms -> 4.6 ms on the tool-call mix). Not worth the coupling; see
-- AGENTS.md.

-- 1) Partial index predicate, rebuilt against the column. Same key, same
--    partiality, same name -- only the predicate expression changes.
DROP INDEX IF EXISTS idx_log_records_request_id;
CREATE INDEX idx_log_records_request_id
  ON log_records ((attributes ->> 'request_id'))
  WHERE event_name = 'api_request';

-- 2) Views. Column lists are unchanged, so CREATE OR REPLACE is sufficient and
--    dependent objects keep working.
CREATE OR REPLACE VIEW span_costs AS
  SELECT
    trace_id,
    span_id,
    SUM((attributes->>'cost_usd')::numeric) AS cost_usd
  FROM log_records
  WHERE event_name = 'api_request'
    AND span_id IS NOT NULL
    AND attributes->>'cost_usd' ~ '^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$'
  GROUP BY trace_id, span_id;

CREATE OR REPLACE VIEW trace_costs AS
  SELECT
    trace_id,
    SUM((attributes->>'cost_usd')::numeric) AS cost_usd
  FROM log_records
  WHERE event_name = 'api_request'
    AND trace_id IS NOT NULL
    AND attributes->>'cost_usd' ~ '^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$'
  GROUP BY trace_id;

CREATE OR REPLACE VIEW span_efforts AS
  SELECT
    span_records.trace_id,
    span_records.span_id,
    request_logs.attributes ->> 'effort' AS effort
  FROM spans AS span_records
  JOIN log_records AS request_logs
    ON request_logs.attributes ->> 'request_id'
       = span_records.attributes ->> 'request_id'
  WHERE span_records.attributes ->> 'request_id' IS NOT NULL
    AND span_records.name = 'claude_code.llm_request'
    AND request_logs.event_name = 'api_request'
    AND NULLIF(request_logs.attributes ->> 'effort', '') IS NOT NULL;
