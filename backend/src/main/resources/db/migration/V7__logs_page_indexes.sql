-- Indexes for the Logs-page query shapes (histogram, facets, cursor/offset paging)
-- and the tool aggregations.

-- 1) attributes ->> 'event.name' equality. The V2 GIN index uses jsonb_path_ops,
--    which only accelerates containment (@>) — extraction equality (->> =) cannot
--    use a GIN index at all, so every event-filtered aggregation fell back to the
--    timestamp btrees alone. A btree expression index serves the hot predicate
--    directly. The tool dimension (attributes ->> :toolAttribute) stays unindexed
--    here because its key is a bind parameter, which can never match an
--    expression index.
CREATE INDEX idx_log_records_event_name_ts
    ON log_records ((attributes ->> 'event.name'), timestamp);

-- 2) Derived severity. derive_log_severity (V6) is IMMUTABLE, so it is legal in
--    an expression index. Serves the severity filter on the facet, cursor-page,
--    and count queries without re-evaluating the CASE chain per candidate row.
--    If the function is redefined in a later migration, REINDEX this index in
--    the same migration — the stored values are computed at write time.
CREATE INDEX idx_log_records_derived_severity_ts
    ON log_records (derive_log_severity(attributes, severity_text, severity_number), timestamp);

-- 3) Keyset paging sorts ORDER BY timestamp DESC, id DESC and bounds on the
--    (timestamp, id) row constructor. The composite index lets Postgres walk
--    that order directly (backward index scan) instead of sorting, and it
--    subsumes the original single-column idx_log_records_ts, which is dropped
--    to save write amplification on the ingest path.
CREATE INDEX idx_log_records_ts_id ON log_records (timestamp, id);
DROP INDEX idx_log_records_ts;
