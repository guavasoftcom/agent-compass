-- Trace and span cost, from the authoritative per-request figure.
--
-- An earlier draft of this feature priced a span's token attributes at
-- published per-model rates. That estimate ran 2-3x off the real spend
-- (verified against 3 months of local telemetry) and, worse, disagreed with
-- the Sessions page, whose per-turn cost comes from what Claude Code actually
-- reports. Both surfaces read the same number instead: `cost_usd` on the
-- `api_request` log record, which is the same quantity that accumulates into
-- the claude_code.cost.usage counter. Don't reintroduce a rate table on
-- either side.
--
-- Correlation is exact, not time-windowed: Claude Code stamps `api_request`
-- logs with the trace id of the interaction that made the request and, when
-- available, the span id of the span that was active at the time -- the
-- interaction root, or the tool.execution span for a request made inside a
-- tool run. A request can carry a trace id without a span id (OTLP permits
-- span-less trace correlation), so `span_costs` and `trace_costs` are NOT
-- guaranteed to agree: `trace_costs` sums every trace-correlated request log
-- and is the authoritative total used everywhere a trace's cost is shown;
-- `span_costs` is a per-span breakdown that only covers requests carrying a
-- span id and can therefore sum to LESS than its trace's `trace_costs` row.
-- Don't add a span_id filter to trace_costs to force the two to agree -- that
-- would silently drop real cost data instead.
--
-- Requests logged without a trace id (older Claude Code builds; ~0% of the
-- last eight weeks locally, ~19% across all history) belong to no trace and
-- are simply absent here -- such a trace renders "--", which is honest about
-- the correlation never having been recorded. Deliberately NOT back-filled
-- with a time-window guess: overlapping traces in one session would then each
-- claim the same request and inflate the total (measured at +20% on one
-- local session).
--
-- The event name and attribute keys below mirror TuningProperties
-- (`apiRequestEventName`, `apiRequestCostAttribute`) -- the same
-- SQL-mirrors-configuration arrangement derive_log_severity() (V6) already
-- has. Changing either property means a migration that redefines these
-- views. CREATE OR REPLACE keeps a future redefinition idempotent.

-- Per-span cost -- a breakdown of trace_costs restricted to requests that
-- carry a span id. Read one trace at a time (WHERE trace_id = :traceId) by
-- TraceService to fill Span.costUsd; not exposed through a JPA @Formula, so
-- there is no per-row correlated subquery cost on span loads.
CREATE OR REPLACE VIEW span_costs AS
  SELECT
    trace_id,
    span_id,
    SUM((attributes->>'cost_usd')::numeric) AS cost_usd
  FROM log_records
  WHERE attributes->>'event.name' = 'api_request'
    AND span_id IS NOT NULL
    AND attributes->>'cost_usd' ~ '^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$'
  GROUP BY trace_id, span_id;

-- Per-trace cost -- what the Traces list's Cost column and sort=cost read,
-- and what the Sessions prompt timeline reuses for a turn that has a trace.
-- Authoritative: sums ALL trace-correlated request logs, including ones with
-- no span id, so a trace's total can exceed the sum of its own span_costs
-- rows. List queries join this view via a LEFT JOIN LATERAL, not a plain
-- LEFT JOIN, so Postgres can push the trace_id equality (and the per-page
-- LIMIT) into the aggregate instead of grouping every api_request log in the
-- table on every page.
CREATE OR REPLACE VIEW trace_costs AS
  SELECT
    trace_id,
    SUM((attributes->>'cost_usd')::numeric) AS cost_usd
  FROM log_records
  WHERE attributes->>'event.name' = 'api_request'
    AND trace_id IS NOT NULL
    AND attributes->>'cost_usd' ~ '^-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?$'
  GROUP BY trace_id;

-- Both views scan log_records by trace_id / span_id; idx_log_records_trace
-- (V1) already covers trace_id, span_id needs its own.
CREATE INDEX IF NOT EXISTS idx_log_records_span_id
  ON log_records (span_id)
  WHERE span_id IS NOT NULL;
