-- Stored generated column for the tool-dimension attribute, same fix as V16
-- (event_name) for the same reason.
--
-- Why a fixed-key column despite :toolAttribute being a bind param: every
-- Logs-page query reads attributes ->> :toolAttribute, and V7's comment notes
-- this dimension "stays unindexed... because its key is a bind parameter,
-- which can never match an expression index" — the same detoast tax as
-- event_name, with no expression-index escape hatch at all. Measured on the
-- live database (7-day window, 16,867 rows, real key "tool_name"): facetTool
-- took ~725 ms, warm cache, same pure-CPU signature as the pre-fix
-- facetEvent/histogram numbers documented in V16/V8.
--
-- tuning.tool-attribute defaults to "tool_name" (TuningProperties.toolAttribute)
-- and that is what Claude Code actually emits (verified on the live database:
-- 38,888 rows carry a "tool_name" key, 0 carry "tool"). This column is
-- generated from that literal default, matching the tradeoff AGENTS.md already
-- documents for api-request-cost-attribute and friends: overriding
-- tuning.tool-attribute away from "tool_name" now requires a follow-up
-- migration that drops and re-adds this column (and rebuilds the index below)
-- against the new key, or the Logs-page tool facet/filter/histogram silently
-- read the wrong attribute.
ALTER TABLE log_records
    ADD COLUMN tool_name text
    GENERATED ALWAYS AS (attributes ->> 'tool_name') STORED;

CREATE INDEX idx_log_records_tool_name_ts
    ON log_records (tool_name, timestamp);
