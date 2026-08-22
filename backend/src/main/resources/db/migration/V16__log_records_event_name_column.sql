-- Stored generated column for the event.name attribute, mirroring V8's
-- derived_severity fix for the same underlying problem.
--
-- Why: the Logs-page facet/histogram/cursor/offset queries all read
-- attributes ->> 'event.name' (event.name is a literal in every one of
-- them, never a bind param). attributes averages ~11 KB and is TOASTed
-- for the vast majority of rows (1.44 GB of this table's 1.67 GB total
-- size lives in pg_toast), so every such read detoasts the full jsonb
-- value. Measured on the live database (7-day window, 16,867 rows):
-- facetEvent (GROUP BY attributes ->> 'event.name') took ~2.08 s, warm
-- cache, pure CPU (buffer hits, not disk reads) — the same signature V8
-- documented for derived_severity before that fix (~4.0 s -> ~14 ms).
--
-- V7's expression index (idx_log_records_event_name_ts) only serves
-- equality-narrowed lookups; it cannot help a GROUP BY / extraction read
-- over an unconstrained range, for the same planner limitation V8's
-- comment already documents. Replaced here with a plain column + index,
-- same as V8 did for derived_severity.
ALTER TABLE log_records
    ADD COLUMN event_name text
    GENERATED ALWAYS AS (attributes ->> 'event.name') STORED;

DROP INDEX idx_log_records_event_name_ts;
CREATE INDEX idx_log_records_event_name_ts
    ON log_records (event_name, timestamp);
