-- Agent profile stamping: which coding agent emitted each row. Groundwork for ingesting a second
-- agent (GitHub Copilot) into the same database. See .design-docs/multi-agent-telemetry-plan.md for
-- the overall design and .design-docs/copilot-telemetry-ingest-plan.md for the ingest-side gaps this
-- is part of closing.
--
-- WHY A HARD PARTITION, NOT A FILTER LIKE repository_url. The two dimensions look alike and behave
-- oppositely on purpose. Two repositories' worth of Claude Code telemetry share one vocabulary and
-- one measurement pipeline, so summing across them is meaningful -- which is why every query reads
-- `:repositoryUrl IS NULL` as "every repository". Two different AGENTS do not: Copilot emits no cost
-- metric at all, reports tokens as an OTLP histogram where Claude Code reports a cumulative counter
-- read through value_delta, and uses session.id to mean a VS Code window rather than a conversation.
-- Blending them would not produce mixed data, it would produce silently wrong numbers -- a "total
-- tokens" card would read Claude Code's figure and label it the total. So an absent agent profile
-- must resolve to the DEFAULT profile on the read side, never to "all", and there is deliberately no
-- "All agents" view. The read-side predicate is NOT part of this migration (see below).
--
-- WHY DEFAULT THEN DROP DEFAULT. The default backfills every existing row in place -- Postgres 11+
-- stores a constant column default in the catalog, so this is a metadata-only change and does NOT
-- rewrite these tables, unlike V34's generated columns. Dropping the default afterwards means the
-- DATABASE never guesses a profile: with no default and NOT NULL, an insert that omits the column
-- (raw SQL, a hand-written migration) fails rather than silently stamping a foreign agent's
-- telemetry as Claude Code's. Backfilling to 'claude-code' is correct rather than a guess -- it is
-- the only agent that has ever written to this database.
--
-- Note the Java side does NOT rely on that constraint to catch a forgetful mapper: the three
-- entities default their agentProfile field to the 'unknown' profile (see
-- com.guavasoft.agentcompass.model.AgentProfiles), so an insert path that never consults
-- AgentProfileResolver is quarantined rather than mis-credited. The constraint and the field default
-- guard different things on purpose -- the constraint covers paths that bypass the entities
-- entirely, and neither one can produce a row wrongly attributed to Claude Code.
--
-- READ PATH IS DELIBERATELY UNTOUCHED. No query filters on this column yet, so every existing
-- dashboard figure is unchanged by this migration and nothing is user-visible. Threading the
-- predicate through the ~123 window-scoped repository methods is a separate change, guarded by
-- AgentProfileCoverageTest so a newly added query cannot escape the partition unnoticed.
--
-- PARTIAL-INDEX TRAP (see AGENTS.md, and V34's own header). V32/V33's partial value_delta indexes
-- and V19's partial idx_log_records_request_id are NOT extended here -- that belongs in the read-path
-- change, where it can be measured with EXPLAIN against real two-profile data. When they are: any
-- partial index keyed on profile identity must write its predicate against this agent_profile COLUMN,
-- never an expression it could be derived from. Postgres's predicate-implication prover matches
-- structurally and will silently stop using such an index otherwise.
--
-- MIGRATION WINDOW. The ALTERs are metadata-only, but each CREATE INDEX scans the whole table
-- (metric_points was 11 GB of heap / 11.1M rows when this was written), which cannot finish inside
-- the 15s statement_timeout Flyway inherits from the app's Hikari pool -- see V34's header for why
-- that timeout applies to migrations at all and fails them with SQL State 57014. SET LOCAL scopes
-- the relief to this transaction, so the connection returns to the pool with the normal guardrail
-- intact. No table rewrite means no visibility-map reset here, so this one needs no follow-up VACUUM.
SET LOCAL statement_timeout = 0;

ALTER TABLE log_records
    ADD COLUMN agent_profile text NOT NULL DEFAULT 'claude-code';

ALTER TABLE log_records
    ALTER COLUMN agent_profile DROP DEFAULT;

CREATE INDEX idx_log_records_agent_profile_ts
    ON log_records (agent_profile, timestamp);

ALTER TABLE metric_points
    ADD COLUMN agent_profile text NOT NULL DEFAULT 'claude-code';

ALTER TABLE metric_points
    ALTER COLUMN agent_profile DROP DEFAULT;

CREATE INDEX idx_metric_points_agent_profile_ts
    ON metric_points (agent_profile, timestamp);

-- spans times itself with start_timestamp / end_timestamp and has no column called `timestamp`
-- at all (see backend/CLAUDE.md's column-name list -- the single most common mistake in
-- hand-written queries against this schema), so its index pairs the profile with start_timestamp.
ALTER TABLE spans
    ADD COLUMN agent_profile text NOT NULL DEFAULT 'claude-code';

ALTER TABLE spans
    ALTER COLUMN agent_profile DROP DEFAULT;

CREATE INDEX idx_spans_agent_profile_start_ts
    ON spans (agent_profile, start_timestamp);
