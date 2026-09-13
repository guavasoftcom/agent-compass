-- Replaces middle-elision of an oversized call timeline with lossless partitioning: an oversized
-- trace is now split into consecutive, non-overlapping review windows (TraceAnalysisPromptBuilder
-- #partitionToBudget), each a full "DRAFTING" model call, merged in code (TraceAnalysisFindingsMerge)
-- rather than a stretch of the timeline being dropped. review_pass_count is how many of those windows
-- this analysis was generated from (1 for an ordinary trace, and for every row written before this
-- migration); timeline_call_count is the trace's total call count, unaffected by how many windows it
-- took.
--
-- timeline_truncated / omitted_line_count stay: they are historical-only from here on (see the column
-- comment below) rather than dropped, since an old row can still carry true/nonzero truthfully and
-- there is no reason to lose that fact from a row nobody has regenerated since.
ALTER TABLE trace_analyses ADD COLUMN review_pass_count INT NOT NULL DEFAULT 1;
ALTER TABLE trace_analyses ADD COLUMN timeline_call_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN trace_analyses.timeline_truncated IS
  'Historical only. No analysis generated after V30 sets this true -- the timeline is now partitioned
   into consecutive non-overlapping windows instead of elided. See review_pass_count.';
