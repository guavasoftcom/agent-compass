-- Indexes to support the Trace Explorer aggregations (histogram, facets, cursor paging).
--
-- The CTE-based aggregation groups by trace_id and filters on MIN(start_timestamp)
-- (the trace's start) within the window. A partial index on parent_span_id IS NULL
-- accelerates the root-span lookup in the shared CTE without indexing every child span.

-- Speeds up the root-span lateral join inside the shared trace CTE.
-- The existing idx_spans_trace (trace_id) covers trace_id lookups;
-- this partial index is specifically for the parent_span_id IS NULL sub-select.
CREATE INDEX IF NOT EXISTS idx_spans_root
    ON spans (trace_id, span_id)
    WHERE parent_span_id IS NULL;

-- The trace CTE filters MIN(start_timestamp) between the window bounds.
-- date_bin bucketing and cursor paging both walk start_timestamp heavily.
-- idx_spans_start_ts (start_timestamp) already exists from V1.
-- Add a composite covering (trace_id, start_timestamp, end_timestamp, status_code)
-- so the per-trace aggregation avoids heap fetches for the common columns.
CREATE INDEX IF NOT EXISTS idx_spans_trace_agg
    ON spans (trace_id, start_timestamp, end_timestamp, status_code);
