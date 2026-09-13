-- Whether ollama.max-prompt-chars forced the call timeline to be elided when this analysis was
-- generated, and how much of it was dropped.
--
-- The model already sees this inline, as a "... N calls omitted from the middle of the trace ..."
-- marker in the timeline itself (TraceAnalysisPromptBuilder#trimMiddleOut). Nothing outside that
-- class previously carried the fact, so a reader had no way to tell a review apart from one written
-- against the trace's full timeline -- on a large trace that matters: measured over 30 days, 44 of
-- 973 traces (4.5%) exceed the timeline's share of the prompt budget, and on the worst of those
-- most of the trace's own calls never reached the model at all.
--
-- NOT NULL DEFAULT false / 0: every row written before this column existed was generated against a
-- prompt whose full timeline fit inside the budget or, for a handful of very old traces, before
-- this signal was tracked at all -- "not known to be truncated" and "known not to be truncated"
-- read the same to a user either way, so defaulting to false rather than a nullable "unknown" is
-- the honest choice, not an approximation.
ALTER TABLE trace_analyses ADD COLUMN timeline_truncated BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE trace_analyses ADD COLUMN omitted_line_count INT NOT NULL DEFAULT 0;
