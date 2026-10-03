-- Relabel GitHub Copilot telemetry captured before the resolution map knew about it.
--
-- Until now `tuning.agent-profile-resolution` carried no Copilot entry, so every Copilot row was
-- stamped `unknown` (quarantined, not credited to Claude Code -- see V37). A real capture showed
-- Copilot emits from two service.name values in one VS Code window, `copilot-chat` and
-- `github-copilot`, and both now resolve to the `github-copilot` profile at ingest. This migration
-- applies the same mapping to rows that were already stored.
--
-- WHY THE FILTER LEADS WITH agent_profile = 'unknown'. It rides the V37 (agent_profile, ...) index,
-- so only the small quarantined set is examined; filtering on service.name alone would scan every
-- row of metric_points to read resource_attributes. Rows already stamped by a later ingest, or by
-- an operator's own mapping, are untouched.
--
-- metric_points.stream_id is generated from agent_profile (V38), so these rows get a new stream id.
-- That is a pure relabel of Copilot-only rows -- no Claude Code stream shares them -- so value_delta
-- stays valid: every row of a given stream moves together.
--
-- The hardcoded service names are deliberate. A migration is a point-in-time fix for the rows that
-- exist today; an operator-configured mapping applies to future ingest only.
--
-- Flyway shares the application's pooled 15s statement_timeout, so lift it for this transaction.
SET LOCAL statement_timeout = 0;

UPDATE spans
SET agent_profile = 'github-copilot'
WHERE agent_profile = 'unknown'
  AND resource_attributes ->> 'service.name' IN ('copilot-chat', 'github-copilot');

UPDATE log_records
SET agent_profile = 'github-copilot'
WHERE agent_profile = 'unknown'
  AND resource_attributes ->> 'service.name' IN ('copilot-chat', 'github-copilot');

UPDATE metric_points
SET agent_profile = 'github-copilot'
WHERE agent_profile = 'unknown'
  AND resource_attributes ->> 'service.name' IN ('copilot-chat', 'github-copilot');
