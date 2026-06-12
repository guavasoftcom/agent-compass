-- Derives a canonical severity label from a log record's attributes, severity_text,
-- and severity_number. This is the single source of truth for severity classification
-- used by every Logs-page query (histogram, facets, cursor paging, offset paging,
-- count). All queries call derive_log_severity(attributes, severity_text, severity_number)
-- instead of embedding a repeated CASE expression.
--
-- Priority order:
--   1. severity_text when it is one of ERROR/WARN/INFO/DEBUG (case-insensitive)
--   2. severity_number ranges: >= 17 → ERROR, >= 13 → WARN, >= 9 → INFO, > 0 → DEBUG
--   3. Event-based fallback reading attributes->>'event.name' and signal attributes:
--        ERROR: event.name in error events OR error-key present OR success='false' OR decision='reject'
--        WARN:  event.name = 'compaction' OR status='disconnected' OR num_non_blocking_error != '0'
--        DEBUG: event.name in debug events OR (tool_decision AND decision='accept')
--        else INFO
--
-- The lists below mirror the defaults in TuningProperties.errorEventNames /
-- warnEventNames / debugEventNames. If those properties are overridden via
-- application.yml the function will NOT auto-update — override the function in a
-- new migration if the classification needs to change in production.
--
-- IMMUTABLE is valid because the result depends only on the three input arguments
-- (no side-effects, no external state reads).

CREATE OR REPLACE FUNCTION derive_log_severity(
    attributes      jsonb,
    severity_text   text,
    severity_number integer
) RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT
        CASE
            -- 1. Canonical severity_text wins when it is a recognized label.
            WHEN UPPER(severity_text) IN ('ERROR', 'WARN', 'INFO', 'DEBUG')
                THEN UPPER(severity_text)

            -- 2. Numeric ranges (OpenTelemetry log severity spec).
            WHEN severity_number >= 17 THEN 'ERROR'
            WHEN severity_number >= 13 THEN 'WARN'
            WHEN severity_number >= 9  THEN 'INFO'
            WHEN severity_number IS NOT NULL AND severity_number > 0 THEN 'DEBUG'

            -- 3. Event-based fallback when both severity fields are absent/unrecognized.
            WHEN attributes ->> 'event.name' IN (
                     'api_error', 'internal_error', 'api_retries_exhausted')
                 OR jsonb_exists_any(attributes, ARRAY['error', 'error_type', 'error_name', 'error_code'])
                 OR attributes ->> 'success' = 'false'
                 OR attributes ->> 'decision' = 'reject'
                THEN 'ERROR'

            WHEN attributes ->> 'event.name' = 'compaction'
                 OR attributes ->> 'status' = 'disconnected'
                 OR (jsonb_exists(attributes, 'num_non_blocking_error')
                     AND attributes ->> 'num_non_blocking_error' <> '0')
                THEN 'WARN'

            WHEN attributes ->> 'event.name' IN (
                     'hook_execution_start', 'hook_execution_complete', 'hook_registered',
                     'api_request_body', 'api_response_body')
                 OR (attributes ->> 'event.name' = 'tool_decision'
                     AND attributes ->> 'decision' = 'accept')
                THEN 'DEBUG'

            ELSE 'INFO'
        END
$$;
