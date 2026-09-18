-- Repository attribution: a stored generated column on all three signal tables, sourced from
-- Claude Code's native OTEL_METRICS_INCLUDE_REPOSITORY vcs.* attributes. See
-- .design-docs/repository-attribution-plan.md for the full design and the risk analysis.
--
-- WHICH JSONB COLUMN HOLDS THE KEY. Claude Code's docs describe vcs.repository.url.full as
-- riding "every metric datapoint and event, in addition to the OTLP resource block" -- i.e. both
-- the per-record `attributes` and the per-table `resource_attributes` for log_records and
-- metric_points, and resource_attributes only for spans (a span is neither a datapoint nor an
-- event). This could not be confirmed against live data before writing this migration -- doing so
-- needs a real Claude Code session with the env var on, which is outside what this change can
-- exercise -- so the expression reads both columns via COALESCE, preferring the per-record one.
-- This degrades gracefully regardless of which column Claude Code actually populates: if only one
-- side ever carries the key, the other side of the COALESCE is always NULL and costs nothing.
-- Spot-check this expression against real data once OTEL_METRICS_INCLUDE_REPOSITORY is live and
-- amend it in a follow-up migration if the assumption above turns out wrong for spans.
--
-- WHY ONE COLUMN, NOT FOUR. Claude Code also emits vcs.owner.name, vcs.repository.name, and
-- vcs.provider.name, all of which are derivable from vcs.repository.url.full. A second stored
-- column doubles index surface for no query this application needs -- every existing and planned
-- window-scoped query groups or filters by repository identity as a whole, never by owner or
-- provider alone.
--
-- FORWARD-ONLY, NO BACKFILL. Every row ingested before this migration -- and before the operator
-- turns the env var on -- has no vcs.* attributes at all, so repository_url is NULL for it
-- permanently. There is no way to recover repository identity for historical telemetry.
--
-- PARTIAL-INDEX TRAP (see AGENTS.md). Any future partial index on repository_url must name the
-- generated COLUMN in its predicate, never the raw `attributes ->>` / `resource_attributes ->>`
-- expression -- Postgres's predicate-implication prover matches structurally and will silently
-- fail to use such an index otherwise. Same care applies if V32/V33's partial value_delta indexes
-- ever grow repository-scoped variants.
--
-- MIGRATION WINDOW. ADD COLUMN ... GENERATED ALWAYS AS (...) STORED forces a full table rewrite
-- under ACCESS EXCLUSIVE. metric_points measured 12 GB / 8.6M rows at plan time -- the rewrite
-- there needs that much free space and the app must be stopped for the duration, the same
-- operational shape V18/V30 already document for this table family. log_records is 8.4 GB total
-- but only ~257 MB of actual heap (the rest is TOAST) and should be fast; spans is ~320 MB and
-- trivial. As with V30, the rewrite resets the visibility map -- run VACUUM (ANALYZE) on all three
-- tables immediately after this migration, or the new indexes will plan as heap-visiting scans
-- until autovacuum eventually catches up.
--
-- FLYWAY SHARES THE APP'S 15s STATEMENT_TIMEOUT. Flyway has no dedicated datasource configured
-- (see application.yml), so it migrates over the same Hikari pool as every dashboard query, and
-- `connection-init-sql: SET statement_timeout = 15000` applies to that connection too. A full
-- rewrite of a 12 GB table cannot finish in 15s, so without the override below this migration
-- fails with SQL State 57014 ("canceling statement due to statement timeout") on the
-- metric_points ALTER specifically -- reported against a database at that scale. SET LOCAL scopes
-- the relief to this migration's own transaction (Postgres DDL here is all transactional -- no
-- CONCURRENTLY), so it reverts automatically at commit and the connection returns to the pool with
-- the normal 15s guardrail intact for every other query.
SET LOCAL statement_timeout = 0;

ALTER TABLE spans
    ADD COLUMN repository_url text
    GENERATED ALWAYS AS (resource_attributes ->> 'vcs.repository.url.full') STORED;

CREATE INDEX idx_spans_repository_url_start_ts
    ON spans (repository_url, start_timestamp);

ALTER TABLE log_records
    ADD COLUMN repository_url text
    GENERATED ALWAYS AS (COALESCE(
        attributes ->> 'vcs.repository.url.full',
        resource_attributes ->> 'vcs.repository.url.full')) STORED;

CREATE INDEX idx_log_records_repository_url_ts
    ON log_records (repository_url, timestamp);

ALTER TABLE metric_points
    ADD COLUMN repository_url text
    GENERATED ALWAYS AS (COALESCE(
        attributes ->> 'vcs.repository.url.full',
        resource_attributes ->> 'vcs.repository.url.full')) STORED;

CREATE INDEX idx_metric_points_repository_url_ts
    ON metric_points (repository_url, timestamp);
