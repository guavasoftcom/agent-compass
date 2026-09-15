-- Fixes a wrong assumption V34 made and flagged as unverified in its own header: that a span
-- carries vcs.repository.url.full only via the OTLP resource block (resource_attributes), never
-- as a per-span attribute, because "a span is neither a datapoint nor an event". Spot-checked
-- against live data now that OTEL_METRICS_INCLUDE_REPOSITORY is actually running: Claude Code
-- DOES stamp it as a per-span attribute too.
--
-- Measured directly against this database before writing this migration:
--   spans.resource_attributes ? 'vcs.repository.url.full'  -> 0 of 134,842 rows (never)
--   spans.attributes ? 'vcs.repository.url.full'           -> 3,448 rows (real data)
--   spans.resource_attributes keys, full set                -> host.arch, os.type, os.version,
--                                                              service.name, service.version only
-- So V34's generated expression for spans -- resource_attributes ->> '...' only -- was reading a
-- column that never carries the key at all, and repository_url was NULL on every single span
-- regardless of real attribution data sitting right next to it. Any repository filter therefore
-- excluded every trace, which is exactly the symptom this migration fixes (reported: selecting a
-- specific repository on the Traces page showed zero results).
--
-- log_records and metric_points are unaffected: their COALESCE(attributes ->> ..., resource_
-- attributes ->> ...) expressions already try the per-record column first, so they were already
-- correct regardless of which side Claude Code actually uses. Spot-checked those too, for the
-- same reason -- both carry the key exclusively in the per-record attributes column, never
-- resource_attributes, matching the pattern spans should have followed from the start.
--
-- WHY A NEW MIGRATION, NOT AN EDIT TO V34. V34 has already run against this database (and
-- possibly others) -- Flyway validates applied migrations by checksum, so editing an already-
-- applied file's content in place breaks startup for every database that already ran it. Fixing
-- forward with a new migration is the same rule this codebase's own migration history already
-- documents repeatedly (see V19's cleanup of V16's trap, V30's note on not repeating V16's
-- mistake).
--
-- Postgres cannot ALTER a generated column's expression in place -- the column must be dropped
-- and re-added. This is a much smaller rewrite than V34's original ADD COLUMN: spans measured
-- 320 MB at plan time (the smallest of the three signal tables), so this is not the multi-GB
-- operational concern V34's own header raises for metric_points.

DROP INDEX IF EXISTS idx_spans_repository_url_start_ts;

ALTER TABLE spans DROP COLUMN repository_url;

ALTER TABLE spans
    ADD COLUMN repository_url text
    GENERATED ALWAYS AS (COALESCE(
        attributes ->> 'vcs.repository.url.full',
        resource_attributes ->> 'vcs.repository.url.full')) STORED;

CREATE INDEX idx_spans_repository_url_start_ts
    ON spans (repository_url, start_timestamp);
