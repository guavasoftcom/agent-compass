-- Snapshot of the trace's latest span activity (MAX(end_timestamp) across all its spans) as of
-- when this analysis was generated. Lets a stored analysis be flagged out-of-date once the trace
-- has picked up spans after that snapshot -- expected for a trace that was still running, or one
-- that later gained a background/subagent completion, when "Analyze trace" was first run.
--
-- NOT NULL with a temporary DEFAULT so this backfills sanely against any environment that already
-- has trace_analyses rows from before this column existed; the default is dropped immediately
-- after so every future insert must set the real value explicitly rather than silently getting
-- "now".
ALTER TABLE trace_analyses ADD COLUMN last_span_end_timestamp TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE trace_analyses ALTER COLUMN last_span_end_timestamp DROP DEFAULT;
