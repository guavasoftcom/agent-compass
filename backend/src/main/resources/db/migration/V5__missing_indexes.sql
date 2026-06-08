-- spans.attributes had no GIN index (V2 only covered log_records and metric_points).
-- Every aggregateToolLatency call was doing a full table scan on jsonb_exists().
CREATE INDEX idx_spans_attributes_gin
    ON spans USING gin (attributes jsonb_path_ops);

-- Tool-latency queries filter: scope_name = ? AND name = ? AND start_timestamp >= ?
-- The old idx_spans_name_start (name, start_timestamp) lacked the most-selective
-- leading column. Replace the query path with a tighter composite.
CREATE INDEX idx_spans_scope_name_start
    ON spans (scope_name, name, start_timestamp);

-- Every log aggregation filters: scope_name = ? AND timestamp >= ?
-- The bare idx_log_records_ts (timestamp) can't be used selectively after a
-- scope_name equality predicate. A leading scope_name column fixes this.
CREATE INDEX idx_log_records_scope_ts
    ON log_records (scope_name, timestamp);

-- findTraceSummariesSince / findTraceSummariesInRange filter on end_timestamp
-- (e.g. end_timestamp >= :since) but only start_timestamp was indexed.
CREATE INDEX idx_spans_end_ts
    ON spans (end_timestamp);
