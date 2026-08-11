-- Per-span reasoning effort, recovered from the api_request log.
--
-- Claude Code does not emit `effort` as a span attribute. It exists only on the
-- `api_request` log record, so the only way to show it against a span is to
-- correlate the two. Verified against local telemetry before this view was
-- written: `request_id` is globally unique on BOTH sides (14,055 distinct over
-- 14,055 non-null llm_request spans; 14,047 over 14,047 api_request logs), so
-- the join is 1:1 and cannot fan out. As an independent check that it pairs the
-- same physical request rather than colliding on a reused key, input_tokens and
-- output_tokens were compared across all 13,999 joined rows: zero mismatches.
--
-- The join key is `request_id`, NOT `span_id`. Do not "simplify" this to reuse
-- the span_costs correlation -- as V14 explains, api_request logs are stamped
-- with the span that was ACTIVE when the request was issued (the
-- claude_code.interaction root, or a tool.execution span for a request made
-- inside a tool run), never the claude_code.llm_request child that actually
-- represents the call. Joining on span_id would silently attach effort to the
-- wrong span, and the result would look plausible while being wrong.
--
-- Coverage is partial and that is expected: ~92% of llm_request spans resolve to
-- an effort value across all history, but the misses are concentrated in older
-- data (91.8% May 2026, 88.3% June, 98.3% July, 97.9% August). Rows with no
-- effort are absent from this view entirely and the service leaves those spans
-- null, which the UI renders as no effort segment rather than a guessed default.
-- Never substitute a fallback value here; "not recorded" and "medium" are
-- different facts.
--
-- The literals below mirror TuningProperties (`requestIdAttribute`,
-- `apiRequestEventName`, `apiRequestEffortAttribute`, `llmRequestSpanName`) --
-- the same SQL-mirrors-configuration arrangement span_costs / trace_costs (V14)
-- and derive_log_severity() (V6) already have. Overriding any of those
-- properties means a new migration redefining this view. CREATE OR REPLACE
-- keeps that idempotent.
--
-- span_records.name is restricted to claude_code.llm_request as defense in
-- depth: today request_id happens to be stamped exclusively on that span kind,
-- but nothing enforces it, and a stray span carrying a reused/colliding
-- request_id must not silently pick up someone else's effort.
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
    AND request_logs.attributes ->> 'event.name' = 'api_request'
    AND NULLIF(request_logs.attributes ->> 'effort', '') IS NOT NULL;

-- The view is always read one trace at a time (WHERE trace_id = :traceId), so
-- the spans side rides idx_spans_trace. The log_records side is the one that
-- needs help: without this, resolving each request_id means a scan. Partial on
-- the event name because no other log kind carries a request_id worth joining.
CREATE INDEX IF NOT EXISTS idx_log_records_request_id
  ON log_records ((attributes ->> 'request_id'))
  WHERE attributes ->> 'event.name' = 'api_request';
