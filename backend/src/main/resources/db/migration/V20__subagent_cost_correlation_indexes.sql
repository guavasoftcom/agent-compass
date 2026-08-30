-- Supporting indexes for LogRecordRepository.aggregateSubagentCostByModelInRange
-- (per-subagent-type cost), which correlates a dispatching tool_result to the LLM
-- spans the subagent itself made through two span joins that had no index to ride:
--
--   1. Find THIS dispatch's own execution span: spans WHERE name = 'claude_code.tool.execution'
--      AND attributes ->> 'tool_use_id' = <the dispatch's tool_use_id>. Without an index this
--      is a sequential scan over every span in the table for every dispatch in the window.
--   2. Find that span's direct children: spans WHERE parent_span_id = <execution span's span_id>
--      AND name = 'claude_code.llm_request'. idx_spans_root (V9) only covers
--      parent_span_id IS NULL -- the ROOT-span lookup -- and does nothing for this, the
--      opposite case of walking DOWN from a known parent.
--
-- Mirrors V14/V15's targeted partial/expression indexes rather than a blanket index on
-- spans(attributes): both lookups are scoped to one span name, so a partial index keeps the
-- index small and fast instead of indexing every span kind these queries never touch.
--
-- The literal span/attribute names below mirror TuningProperties (toolExecutionSpanName,
-- toolCallIdAttribute, llmRequestSpanName) -- the same SQL-mirrors-configuration arrangement
-- span_costs / trace_costs (V14) and span_efforts (V15) already have. Overriding either
-- property means a migration that drops and rebuilds the matching index here, or the new
-- subagent-cost query silently falls back to a sequential scan.

-- Speeds up step 1: locate a specific dispatch's own execution span by tool_use_id. Partial
-- on the span name for the same reason span_efforts' idx_log_records_request_id (V15) is
-- partial on the event name -- no other span kind carries a tool_use_id worth indexing here.
CREATE INDEX IF NOT EXISTS idx_spans_tool_execution_call_id
    ON spans ((attributes ->> 'tool_use_id'))
    WHERE name = 'claude_code.tool.execution';

-- Speeds up step 2: walk from a known parent span down to its direct llm_request children.
-- Partial on parent_span_id IS NOT NULL -- the complement of idx_spans_root (V9), which is
-- partial on IS NULL for the opposite (root-span) lookup -- keeps this index scoped to the
-- rows that can ever satisfy a parent_span_id equality. name is included as the second key
-- column so the same index also serves the llm_request-name filter without a second lookup.
CREATE INDEX IF NOT EXISTS idx_spans_parent_span_id_name
    ON spans (parent_span_id, name)
    WHERE parent_span_id IS NOT NULL;
