-- Keyset-friendly index for the Metrics DataGrid (GET /api/metrics), mirroring
-- V7's idx_log_records_ts_id rationale for log_records.
--
-- MetricPointRepository#findAllMatchingFilters previously had no LIMIT: it sorted
-- the full metric_points table (order of 1M+ rows) with only idx_metric_points_ts
-- (timestamp alone) to help, which does not cover the ORDER BY timestamp DESC, id
-- DESC used by the new offset-paged queries and forces an external merge sort for
-- any request wide enough to touch multiple same-timestamp rows.
--
-- The composite (timestamp, id) index lets Postgres walk that exact order via a
-- backward index scan, serving LIMIT/OFFSET without materializing or sorting the
-- filtered result set. It subsumes the single-column idx_metric_points_ts (any
-- query ranging on timestamp alone still uses this index's leading column), which
-- is dropped to avoid duplicate write amplification on the ingest path.
CREATE INDEX idx_metric_points_ts_id ON metric_points (timestamp, id);
DROP INDEX idx_metric_points_ts;
