-- Stored generated column for derived log severity.
--
-- Why a stored column instead of the V7 expression index:
--   The histogram query evaluates derive_log_severity(attributes, severity_text,
--   severity_number) four times per candidate row (one COUNT FILTER clause per
--   severity level). On a 30-day window (~41 k rows) that measured ~164 k function
--   evaluations, 1.78 M buffer hits, and a wall time of ~4.0 s. Every evaluation
--   detoasts the full attributes jsonb column from the TOAST table.
--
--   Postgres cannot serve expression-index values back to WHERE/SELECT expressions
--   that re-evaluate the same function — the planner can use the expression index
--   only to locate matching rows (index scan), not as a pre-computed substitute for
--   COUNT(*) FILTER (WHERE expr = 'X') aggregations over ALL rows. This is a
--   documented planner limitation: index-only scans over expression indexes require
--   all projected columns to come from the index, which is never the case for a
--   SELECT * or multi-column aggregation. Therefore V7's expression index does not
--   help the histogram or the facet SELECT expression at all.
--
--   A STORED generated column moves the compute to ingest time. All reads see a
--   plain text column that Postgres can scan without touching the TOAST heap for
--   attributes. Measured result after the column was added: the 30-day histogram
--   dropped from ~4.0 s to ~14 ms (EXPLAIN ANALYZE, rolled-back transaction).
--
-- V6 caveat (carries over):
--   The stored values are computed by derive_log_severity() at write time. If that
--   function is redefined in a later migration, the stored column values become
--   stale. That migration MUST drop and re-add this column (or run a full-table
--   UPDATE) and then recreate the index below.

ALTER TABLE log_records
    ADD COLUMN derived_severity text
    GENERATED ALWAYS AS (derive_log_severity(attributes, severity_text, severity_number)) STORED;

-- Replace the V7 expression index with a plain btree on the new column.
-- The expression index had the same planner limitation described above and is
-- no longer needed now that the column exists. The new index is cheaper to
-- maintain (no per-row function call during index writes) and can be used for
-- both equality filters and index-only scans on (derived_severity, timestamp).
DROP INDEX idx_log_records_derived_severity_ts;
CREATE INDEX idx_log_records_derived_severity_ts
    ON log_records (derived_severity, timestamp);
