-- Rebuilds metric_points.stream_id to include the emitting agent AND per-resource identity.
--
-- WHAT WAS WRONG. V11 defined stream identity as md5(metric_name || '|' || attributes::text) -- the
-- metric name plus its per-datapoint attribute set, and nothing else. A "stream" is one cumulative
-- counter whose consecutive rows are diffed into value_delta by
-- MetricPointRepository#recomputeValueDeltas, so two emitters that happen to produce the same metric
-- name with the same attributes interleave into ONE stream and each row's increment is computed
-- against the other emitter's value. The result is garbage in value_delta, which is what every
-- token / cost / active-time rollup in the application reads.
--
-- THIS IS NOT ONLY A CROSS-AGENT PROBLEM. The obvious case is two different agents colliding, but
-- Copilot collides with ITSELF: it carries session.id in the OTLP RESOURCE block (unique per VS Code
-- window) rather than on each datapoint, so two windows emitting copilot_chat.session.count with
-- identical datapoint attributes hash identically today. Claude Code never exposed this because its
-- session.id rides in the per-datapoint `attributes`, which V11's expression already covered.
--
-- WHY A NAMED RESOURCE KEY RATHER THAN THE WHOLE RESOURCE BLOCK. Hashing resource_attributes::text
-- wholesale would make stream identity depend on every resource attribute, so an innocuous
-- service.version bump mid-session would fork a live stream -- and a forked stream has no visible
-- predecessor, which is precisely the spike this column exists to prevent (see below). Naming the one
-- key that carries per-emitter identity keeps the hash stable across upgrades.
--
-- MIRRORED IN SQL (see AGENTS.md). 'session.id' is a literal here because a generated column cannot
-- read Spring configuration. It is catalogued as MIRRORED on TuningProperties#streamIdentityResourceAttribute
-- -- overriding that property requires a new migration redefining this column, or ingest and the
-- schema will disagree about what a stream is.
--
-- WHY EVERY ROW MUST BE RECOMPUTED, NOT JUST NEW ONES. This is the subtle part and the reason a
-- cheaper migration is not available. recomputeValueDeltas computes a row's delta as
-- value - LAG(value) OVER (PARTITION BY stream_id ORDER BY timestamp, id), with COALESCE(..., 0) when
-- there is no predecessor -- so a row whose predecessor is not visible under the SAME stream_id gets
-- its FULL cumulative value as its increment. If historical rows kept V11's hash while new rows got
-- this one, every still-running Claude Code stream would spike by its entire lifetime total on its
-- next emission. Dropping and re-adding the generated column recomputes it consistently for all
-- 11.1M existing rows in one pass, so a continuing stream still finds its true predecessor.
--
-- EXISTING value_delta VALUES ARE UNTOUCHED AND STAY CORRECT. value_delta is a plain stored column,
-- not a generated one, so recomputing stream_id does not recompute it. Every existing row already
-- holds the increment that was correct when it was ingested, and all existing rows share one profile
-- ('claude-code', backfilled by V37) and carry no resource session.id, so both new hash components
-- are constant across the whole table: the PARTITIONING of history is unchanged, only its hash
-- values are. No delta backfill is needed, and COUNT(DISTINCT stream_id) should be identical before
-- and after.
--
-- The per-stream advisory lock (MetricPointRepository#lockStreamsForIngest) keys off stream_id and
-- needs no change -- it hashes whatever this column now produces.
--
-- DROP/ADD RATHER THAN PG17's ALTER COLUMN ... SET EXPRESSION. Both force the same single table
-- rewrite, but DROP/ADD works on PostgreSQL 12+. backend/.env.example lets an operator point
-- SPRING_DATASOURCE_URL at their own server, which need not be the postgres:18 both compose files
-- pin. The column moves to the end of the column order, which nothing depends on.
--
-- MIGRATION WINDOW -- READ BEFORE RUNNING. ADD COLUMN ... GENERATED ALWAYS AS (...) STORED forces a
-- full table rewrite under ACCESS EXCLUSIVE. metric_points measured 11 GB of heap / 15 GB total /
-- 11.1M rows when this was written, so the rewrite needs AT LEAST that much FREE DISK on top of the
-- existing table, and the application must be stopped for the duration. Check free space first --
-- a rewrite that runs out of disk fails partway and takes the whole volume with it. V34's header
-- documents the same operational shape for this table family.
--
-- Flyway shares the app's 15s statement_timeout (no dedicated datasource -- see application.yml and
-- V34's header), so without the override below this fails with SQL State 57014. SET LOCAL reverts at
-- commit, leaving the pooled connection's normal guardrail intact.
--
-- AFTER THIS MIGRATION, RUN: VACUUM (ANALYZE) metric_points;
-- The rewrite resets the table's visibility map, so until that runs, idx_metric_points_stream_ts and
-- every other index-only scan on this table plans as a heap-visiting scan. VACUUM cannot run inside
-- a transaction block, so it cannot be part of this migration -- it is an operator step, exactly as
-- V34 documents for the same reason.
SET LOCAL statement_timeout = 0;

DROP INDEX idx_metric_points_stream_ts;

ALTER TABLE metric_points
    DROP COLUMN stream_id;

ALTER TABLE metric_points
    ADD COLUMN stream_id text
    GENERATED ALWAYS AS (
        md5(
            agent_profile
            || '|' || metric_name
            || '|' || COALESCE(resource_attributes ->> 'session.id', '')
            || '|' || COALESCE(attributes::text, '')
        )
    ) STORED;

CREATE INDEX idx_metric_points_stream_ts
    ON metric_points (stream_id, timestamp, id);
