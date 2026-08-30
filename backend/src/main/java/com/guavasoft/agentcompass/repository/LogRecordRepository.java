package com.guavasoft.agentcompass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.LogRecordEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

// Query on the event_name column, NEVER on attributes ->> 'event.name'.
//
// V16 materialized that expression into a stored generated column and, in the same
// migration, REPLACED the expression index idx_log_records_event_name_ts with one on
// the column. So the raw extraction is no longer merely slower -- it has no index at
// all. A query still written that way falls back to idx_log_records_ts_id (timestamp
// only) and then detoasts every row in the window just to evaluate the filter, which
// on this table means pulling whole attribute payloads (142 MB of heap against
// 1478 MB of TOAST) to discard most of them.
//
// That is not hypothetical: V16/V17 migrated only the Logs-page queries, and the
// remaining 32 extractions in this file silently lost their index. Every one of the
// tuning report's 12 statements landed on the fallback plan and converged on ~740 ms
// apiece -- a 9-second report, essentially all of it this one mistake. Measured on
// the live database (7-day window, warm cache) after the swap:
//
//   tool-call mix       828 ms -> 6 ms   (19,939 rows scanned and detoast-filtered
//                                         down to 2,996; now an index scan)
//   bash hotspots       808 ms -> 14 ms  (still parses tool_input jsonb, but only
//                                         for rows the index already selected)
//   failure rollup      778 ms -> 6 ms
//
// The swap is provably semantics-preserving -- event_name IS the same expression,
// verified as 0 mismatches over all 132,254 rows including NULLs -- so there is no
// reason to write the extraction by hand. The same applies to tool_name (V17), though
// that key is a bind param (:toolAttribute) at most call sites and stays a jsonb read
// there to keep tuning.tool-attribute overridable; see AGENTS.md.
//
// V19 finished the job: SpanRepository's 34 extractions, the span_costs / trace_costs
// / span_efforts views, and the idx_log_records_request_id predicate all moved to the
// column too. That index is the reason a migration was needed rather than just a
// find-and-replace -- it is PARTIAL on the event name, and the planner's
// predicate-implication prover compares expressions structurally, so it cannot tell
// that event_name and the extraction it is generated from are the same value. Writing
// the query against the column while the predicate still named the expression silently
// dropped the index (Index Scan at cost 9.72 -> Bitmap Heap Scan at 2287). If you ever
// add another partial index keyed on an event name, write its predicate against
// event_name for the same reason.
public interface LogRecordRepository extends JpaRepository<LogRecordEntity, Long> {

  // MCP calls collapse to one constant tool_name (:mcpToolName, "mcp_tool") on every log-backed
  // aggregation, so every query that groups on the tool dimension needs to split that bucket back
  // out by server, the same way every other tool is already split by :toolAttribute. Real identity
  // lives in :parametersAttribute (tool_parameters), a JSON-encoded STRING, parsed with the same
  // NULLIF/::jsonb idiom tool_input uses elsewhere in this file. Declared once here and
  // concatenated into each @Query string (still a compile-time constant, since both operands are
  // final Strings) rather than copy-pasted 14 times, so a future change to the parsing logic or to
  // TuningProperties.mcpToolName's default cannot silently miss a callsite. The 'mcp:' prefix keeps
  // MCP rows self-identifying and sortable wherever tools are listed, and can never collide with a
  // real tool literally named 'playwright'.
  //
  // NOT applied to aggregateToolRepeats/InRange (per-server identity doesn't give an MCP call a
  // *scope*, so re-admitting it would reintroduce the "(no scope)" false-repeat bug that query's own
  // comment documents) or to findToolEventsForSession (a per-turn detail list, not an analysis
  // surface — see the Sessions page CLAUDE.md).
  String MCP_AWARE_TOOL_EXPRESSION = """
      CASE WHEN attributes ->> :toolAttribute = :mcpToolName
           THEN 'mcp:' || COALESCE(NULLIF((NULLIF(attributes ->> :parametersAttribute, ''))::jsonb ->> :serverKey, ''), 'unknown')
           ELSE COALESCE(attributes ->> :toolAttribute, 'unknown') END""";

  // Companion to MCP_AWARE_TOOL_EXPRESSION for the two report queries whose *scope* column is
  // normally read from tool_input's file_path/command. MCP calls never populate tool_input — their
  // identity lives in :parametersAttribute instead — so without this an MCP row split by server
  // would still show an empty scope. :toolKey is TuningProperties.mcpToolNameAttribute
  // ("mcp_tool_name", e.g. "browser_evaluate"), the one piece of MCP identity that makes an
  // oversized/slow row actionable.
  String MCP_AWARE_TOOL_INPUT_SCOPE_EXPRESSION = """
      CASE WHEN attributes ->> :toolAttribute = :mcpToolName
           THEN COALESCE((NULLIF(attributes ->> :parametersAttribute, ''))::jsonb ->> :toolKey, '')
           ELSE COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path',
                    ltrim(regexp_replace(
                      (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                      '^\\s*(cd\\s+[^&;|\\n]*(&&|;|\\n)\\s*)+',
                      '')),
                    '')
      END""";

  // Cost page's four-way work-category partition, in precedence order -- shared verbatim by
  // aggregateCostByWorkCategoryInRange (page-wide) and aggregateCostByWorkCategoryForSessionsInRange
  // (per-session), so the two can never silently disagree about which category a request with
  // ambiguous attributes (e.g. a skill running inside a subagent) falls into:
  //   1. SUBAGENT  -- query_source starts with :subagentQuerySourcePrefix
  //   2. SKILL     -- carries :skillAttribute (and is not already SUBAGENT)
  //   3. MAIN_LOOP -- query_source is one of :mainLoopQuerySources
  //   4. AUXILIARY -- everything else (compact, generate_session_title, ...)
  String WORK_CATEGORY_CASE_EXPRESSION = """
      CASE
        WHEN starts_with(attributes ->> :querySourceAttribute, :subagentQuerySourcePrefix) THEN 'SUBAGENT'
        WHEN jsonb_exists(attributes, :skillAttribute) THEN 'SKILL'
        WHEN attributes ->> :querySourceAttribute IN (:mainLoopQuerySources) THEN 'MAIN_LOOP'
        ELSE 'AUXILIARY'
      END""";

  // Shared by findCostSplitByTraceIds and findToolEventsSplitByTraceIds: the timestamp each
  // requested trace's own claude_code.interaction root span closed, one row per trace. Kept as
  // a Java-side CTE fragment (not a Postgres view) specifically so trace_id IN :traceIds stays
  // part of the CTE itself -- a view has no parameters, so a caller-side filter on top of it
  // would depend on the planner pushing an IN-list predicate down through the view's own
  // GROUP BY to avoid scanning every root span in the table on every call, which this codebase
  // does not assume without measuring (see AGENTS.md's "measure, don't assume" rule).
  String TRACE_ROOT_ENDS_CTE = """
      root_ends AS (
        SELECT trace_id, MAX(end_timestamp) AS root_end
        FROM spans
        WHERE parent_span_id IS NULL
          AND name LIKE :rootSpanNamePattern
          AND trace_id IN :traceIds
        GROUP BY trace_id
      )""";

  // Logs correlated to a trace by OTLP trace context. Claude Code >= 2.1.152 stamps
  // trace_id + span_id onto every event log emitted inside an active span, so a
  // trace's logs are exactly the rows carrying its trace_id. The frontend then
  // attaches each log to its emitting span by span_id.
  List<LogRecordEntity> findByTraceIdOrderByTimestampAsc(String traceId);

  // Returns every distinct "key=value" pair across log_records.attributes,
  // narrowed to rows
  // that contain every entry in :filters and (optionally) fall within a
  // [startTimestamp,
  // endTimestamp] window. Object- and array-valued attributes are excluded
  // because their
  // text serialization differs between Postgres' jsonb_each_text and the
  // frontend's compact
  // JSON.stringify (see MetricPointRepository for the full rationale).
  @Query(value = """
      SELECT DISTINCT attribute_entry.key || '=' || (attribute_entry.value #>> '{}')
      FROM log_records,
           jsonb_each(attributes) AS attribute_entry
      WHERE attributes IS NOT NULL
        AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
        AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        AND jsonb_typeof(attribute_entry.value) NOT IN ('object', 'array')
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY 1
      """, nativeQuery = true)
  List<String> findDistinctAttributePairs(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Distinct attribute keys across log_records.attributes, narrowed to the same
  // filter/window
  // contract used by findDistinctAttributePairs. Drives the autocomplete's "pick
  // a key" stage.
  // 'body' is excluded — the log body lives in its own column, and any stray
  // attribute named
  // 'body' carries large free-form text that pollutes suggestions.
  @Query(value = """
      SELECT DISTINCT attribute_entry.key
      FROM log_records,
           jsonb_each(attributes) AS attribute_entry
      WHERE attributes IS NOT NULL
        AND attribute_entry.key <> 'body'
        AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
        AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        AND jsonb_typeof(attribute_entry.value) NOT IN ('object', 'array')
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY 1
      """, nativeQuery = true)
  List<String> findDistinctAttributeKeys(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Distinct values for a single attribute key, same filter/window contract as
  // the keys query.
  // Drives the autocomplete's "pick a value for this key" stage.
  @Query(value = """
      SELECT DISTINCT attribute_entry.value #>> '{}'
      FROM log_records,
           jsonb_each(attributes) AS attribute_entry
      WHERE attributes IS NOT NULL
        AND attribute_entry.key = :key
        AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
        AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        AND jsonb_typeof(attribute_entry.value) NOT IN ('object', 'array')
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY 1
      """, nativeQuery = true)
  List<String> findDistinctAttributeValuesForKey(
      @Param("key") String key,
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Tool dimension is MCP-aware (MCP_AWARE_TOOL_EXPRESSION): this feeds the tool-mix donut on the
  // same ToolCallsPage as the calls-over-time chart aggregateToolCallsTimeseries/InRange reads —
  // splitting one and not the other would show the donut and the stacked chart disagreeing on
  // whether an MCP call reads 'mcp_tool' or 'mcp:playwright'.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*) AS calls
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :since
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolCalls(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*) AS calls
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Same population as aggregateToolCalls, but bucketed by time. date_bin aligns
  // buckets to
  // :since so the first bucket starts exactly at the window's lower bound (no
  // half-bucket on
  // the left edge). Returned rows are sparse — a (bucket, tool) pair only appears
  // when at
  // least one call landed there — and the service fills missing buckets with
  // zero.
  // Per-tool latency + output-size aggregates over tool_result events.
  // duration_ms and
  // tool_result_size_bytes are stored inside the attributes jsonb as JSON
  // numbers, so cast
  // via ->>'...'::numeric. p95 uses percentile_cont for a continuous
  // interpolation. Rounded
  // to whole numbers to keep the report digest-friendly. tool_result_size_bytes
  // is NULL on
  // failed calls — AVG ignores NULL, so the column reflects successful calls
  // only.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)                                           AS calls,
        ROUND(AVG((attributes ->> 'duration_ms')::numeric))                                AS avg_ms,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY (attributes ->> 'duration_ms')::numeric)) AS p95_ms,
        ROUND(AVG((attributes ->> 'tool_result_size_bytes')::numeric))                     AS avg_out
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolPerformanceInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-tool context footprint: how many bytes of result text each tool pushed
  // into the context window over the window. Drives the Tokens page's
  // "what's filling the context window" ranking.
  //
  // Deliberately differs from aggregateOversizedToolResultsInRange in two ways,
  // because this answers a different question (where does my context budget go?)
  // rather than that one's (which single calls are tunable offenders?):
  //   - externallyDeterminedTools are NOT excluded. Agent/WebFetch results fill
  //     context like anything else, and "delegate this to a subagent" is itself
  //     one of the levers this ranking is meant to inform, so hiding them would
  //     hide the comparison the card exists to make.
  //   - image/binary reads are NOT excluded either; they still occupy the window.
  //
  // Failed calls count wherever they reported a size: error output is context
  // too. Rows with no tool_result_size_bytes at all are excluded rather than
  // counted as zero, so `calls` reads as "calls we can account for" and never
  // deflates the per-call average with unmeasurable rows. That makes this
  // COUNT(*) legitimately smaller than the one on /api/tool-activity/calls.
  //
  // p95 uses percentile_cont over the same non-null population, matching the
  // interpolation semantics every other percentile in this codebase uses.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)::bigint                                                       AS calls,
        SUM((attributes ->> 'tool_result_size_bytes')::numeric)::bigint        AS total_bytes,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
          ORDER BY (attributes ->> 'tool_result_size_bytes')::numeric))::bigint AS p95_bytes
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> 'tool_result_size_bytes' IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY total_bytes DESC, tool ASC
      """, nativeQuery = true)
  List<Object[]> aggregateToolContextFootprintInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // The tuning report's variant of the footprint aggregation above: same columns,
  // but restricted to rows a rule in AGENTS.md could actually change. The two
  // exclusions are lifted verbatim from aggregateOversizedToolResultsInRange —
  // externally determined tools (the caller doesn't choose how much Agent or
  // WebFetch returns) and image/binary reads (undesirable to ban, impossible to
  // page) — because the report's header tells readers not to write rules against
  // either, and a ranking that led with `Agent` would contradict it.
  //
  // The dashboard card keeps the unfiltered query on purpose: it asks where the
  // context budget went, and "delegate this to a subagent" is one of the answers
  // it exists to inform. Neither query is the other's filtered view; changing one
  // is not automatically a reason to change the other.
  //
  // excludedTools matches RAW tool names (e.g. "Agent") — the exclusion predicate below stays
  // against attributes ->> :toolAttribute on purpose, unlike the projected 'tool' column. Adding
  // "mcp:playwright" here would silently no-op; exclude an MCP server by adding "mcp_tool"
  // instead, which drops every server (there is no per-server exclusion on this query).
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)::bigint                                                       AS calls,
        SUM((attributes ->> 'tool_result_size_bytes')::numeric)::bigint        AS total_bytes,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
          ORDER BY (attributes ->> 'tool_result_size_bytes')::numeric))::bigint AS p95_bytes
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> 'tool_result_size_bytes' IS NOT NULL
        AND COALESCE(attributes ->> :toolAttribute, 'unknown') NOT IN (:excludedTools)
        AND COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path', '')
            !~* '\\.(png|jpe?g|gif|webp|bmp|ico|svg|pdf)$'
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY total_bytes DESC, tool ASC
      """, nativeQuery = true)
  List<Object[]> aggregateTunableToolContextFootprintInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("excludedTools") List<String> excludedTools,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-(tool, error_type, root cause) failure counts over tool_result events.
  // success is
  // stored as a JSON boolean; ->>'success' returns text, so compare to the string
  // 'false'.
  // error_type is only present on failures — COALESCE keeps unlabeled failures
  // grouped under
  // 'unknown'.
  //
  // error_signature splits coarse error_type buckets (a generic ShellError covers
  // dozens of
  // unrelated root causes) into rule-sized groups derived from the raw error
  // message. The
  // regex literals live here rather than TuningProperties because @Query native
  // SQL cannot
  // read Spring properties at query-parse time — same trade-off as
  // derive_log_severity().
  // 'other' deliberately stays a catch-all: the report tells readers those rows
  // need manual
  // triage instead of a single blanket rule.
  //
  // exampleScope is picked via MIN(...) over the matching rows; exampleMessage
  // prefers the
  // smallest NON-EMPTY message (MIN over NULLIF) so a blank-error row can't mask
  // a useful one.
  // Tool dimension is MCP-aware: unsplit, this query would print 'mcp_tool' as one anonymous row
  // a few sections away from the report's dedicated MCP section attributing the same failures to
  // named servers by number (playwright carries the largest single share on live data).
  @Query(value = """
      WITH failure_events AS (
        SELECT
        """ + MCP_AWARE_TOOL_EXPRESSION + """
         AS tool,
          COALESCE(attributes ->> 'error_type', 'unknown')   AS error_type,
          COALESCE(attributes ->> 'error', '')               AS error_message,
          COALESCE(
            (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path',
            (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
            '')                                              AS scope
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> 'success' = 'false'
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        tool,
        error_type,
        CASE
          WHEN error_message ~* 'command not found'                                  THEN 'command-not-found'
          WHEN error_message ~* 'no such file or directory|does not exist|cannot find' THEN 'missing-path'
          WHEN error_message ~* 'permission denied|operation not permitted|not allowed' THEN 'permission-denied'
          WHEN error_message ~* 'timed out|timeout'                                  THEN 'timeout'
          WHEN error_message ~* 'string to replace not found|old_string'             THEN 'old-string-mismatch'
          ELSE 'other'
        END                            AS error_signature,
        MIN(scope)                     AS example_scope,
        MIN(NULLIF(error_message, '')) AS example_message,
        COUNT(*)                       AS failures
      FROM failure_events
      GROUP BY tool, error_type, error_signature
      ORDER BY failures DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailuresInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Counts tool_result events for a single tool, grouped by an inner identifier
  // attribute. The
  // identifier is read from the flat attributes map first (attributes ->>
  // :innerAttribute) and
  // falls back to the same key under the tool_input JSON blob — Claude Code emits
  // some tool
  // arguments at the top level and some only inside tool_input depending on the
  // tool, and
  // skill/subagent dispatchers happen to land in the latter. Rows missing the
  // identifier entirely bucket under :defaultIdentifier rather than being
  // dropped — for the subagent dispatcher that is the agent type the run
  // actually used when the caller named none, so the count lands on a real
  // identifier instead of a bucket that reads like missing data.
  // The second dimension is the model that dispatched the call. tool_result rows
  // carry no model attribute at all — only api_request rows do — so the model is
  // resolved by walking back to the last main-loop api_request in the same
  // session at or before the tool_result. That row is the assistant turn that
  // emitted the tool_use: api_requests made from inside a subagent run are
  // excluded (they carry :agentNameAttribute), otherwise the subagent's own
  // turns, which happen while the dispatching tool call is still in flight,
  // would shadow the dispatcher. Calls whose dispatching turn cannot be found
  // (a session whose first rows predate retention) bucket under 'unknown'.
  // Rows are ordered so that every row for one identifier arrives together, with
  // identifiers sorted by their total call count descending.
  @Query(value = """
      WITH subagent_calls AS (
        SELECT
          COALESCE(
            NULLIF(attributes ->> :innerAttribute, ''),
            NULLIF((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> :innerAttribute, ''),
            :defaultIdentifier)                                        AS identifier,
          attributes ->> 'session.id'                                  AS session_id,
          timestamp                                                    AS dispatched_at
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> :toolAttribute = :toolName
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        subagent_calls.identifier                                      AS identifier,
        COALESCE(dispatching_turn.model, 'unknown')                    AS model,
        COUNT(*)                                                       AS calls
      FROM subagent_calls
      LEFT JOIN LATERAL (
        SELECT NULLIF(main_loop_turn.attributes ->> :modelAttribute, '') AS model
        FROM log_records main_loop_turn
        WHERE main_loop_turn.event_name = :apiRequestEventName
          AND main_loop_turn.attributes ->> 'session.id' = subagent_calls.session_id
          AND NOT jsonb_exists(main_loop_turn.attributes, :agentNameAttribute)
          AND main_loop_turn.timestamp <= subagent_calls.dispatched_at
        ORDER BY main_loop_turn.timestamp DESC
        LIMIT 1
      ) dispatching_turn ON TRUE
      GROUP BY identifier, model
      ORDER BY SUM(COUNT(*)) OVER (PARTITION BY identifier) DESC, identifier, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolInvocationsByInnerAttributeAndModelInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("toolName") String toolName,
      @Param("innerAttribute") String innerAttribute,
      @Param("defaultIdentifier") String defaultIdentifier,
      @Param("apiRequestEventName") String apiRequestEventName,
      @Param("modelAttribute") String modelAttribute,
      @Param("agentNameAttribute") String agentNameAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-(server, tool) MCP usage: calls, failures, latency, and result-size aggregates over
  // tool_result events whose tool_name is the shared :mcpToolName constant. Simpler than
  // aggregateToolInvocationsByInnerAttributeAndModelInRange above, which this is modeled on: no
  // model correlation (MCP calls carry no model dimension) and no LEFT JOIN LATERAL, because
  // tool_result already carries duration_ms and tool_result_size_bytes directly. Identity is
  // extracted with the same COALESCE(NULLIF(...)) idiom used there, reading :serverKey /
  // :toolKey out of the :parametersAttribute JSON string; missing or blank values bucket under
  // 'unknown' rather than being dropped. Denials are NOT covered here — tool_decision rows carry
  // the same tool_parameters, and are picked up by the MCP_AWARE_TOOL_EXPRESSION split of
  // aggregateToolDenials/InRange instead of a second query.
  //
  // Filters on the event_name COLUMN, never attributes ->> 'event.name' — see the file-header
  // comment. tool_name is filtered the same way: the generated column (V17), not
  // attributes ->> :toolAttribute, is what idx_log_records_tool_name_ts actually indexes — the
  // planner cannot use that index for a differently-shaped expression even when the two always
  // agree, which is why "no migration needed" in the design for this feature specifically depends
  // on filtering the column. That ties this query's correctness to tuning.tool-attribute staying
  // "tool_name" (the same tradeoff V17 already made for the rest of the Logs-page queries), so
  // there is no :toolAttribute bind param here — nothing in this query would use it. Ordered so
  // every row for one server arrives together, sorted by that server's total calls descending,
  // matching aggregateToolInvocationsByInnerAttributeAndModelInRange.
  // A window function's PARTITION BY cannot resolve a bare SELECT-list alias — only a genuine
  // ORDER BY/GROUP BY item can use the "output column name" extension Postgres otherwise allows
  // throughout this file (e.g. plain "GROUP BY tool"). aggregateToolInvocationsByInnerAttributeAndModelInRange's
  // "PARTITION BY identifier" only works because identifier is a real column of its subagent_calls
  // CTE, not an alias introduced in the same SELECT list — so this query needs the same CTE shape
  // rather than a flat SELECT, or "PARTITION BY server" fails with "column server does not exist".
  @Query(value = """
      WITH mcp_calls AS (
        SELECT
          COALESCE(NULLIF((NULLIF(attributes ->> :parametersAttribute, ''))::jsonb ->> :serverKey, ''), 'unknown') AS server,
          COALESCE(NULLIF((NULLIF(attributes ->> :parametersAttribute, ''))::jsonb ->> :toolKey, ''), 'unknown')   AS tool,
          (attributes ->> 'duration_ms')::numeric            AS duration_ms,
          (attributes ->> 'tool_result_size_bytes')::numeric AS result_bytes,
          attributes ->> 'success' = 'false'                 AS failed
        FROM log_records
        WHERE event_name = :eventName
          AND tool_name = :mcpToolName
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        server,
        tool,
        COUNT(*)                                                                     AS calls,
        COUNT(*) FILTER (WHERE failed)                                               AS failures,
        ROUND(AVG(duration_ms))                                                      AS avg_ms,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms))             AS p95_ms,
        SUM(result_bytes)::bigint                                                    AS total_bytes,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY result_bytes))::bigint    AS p95_bytes
      FROM mcp_calls
      GROUP BY server, tool
      ORDER BY SUM(COUNT(*)) OVER (PARTITION BY server) DESC, server, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateMcpServerUsageInRange(
      @Param("eventName") String eventName,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("toolKey") String toolKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Counts skill invocations, grouped by the skill-name attribute and the model
  // that ran the skill. Skills are emitted as api_request events (not
  // tool_result), so there is no tool_name filter here — only the event name and
  // the presence of the skill attribute. Those same rows carry the model
  // attribute directly, so no correlation is needed on this side.
  //
  // One invocation is one prompt that entered the skill, NOT one api_request.
  // Claude Code stamps :skillAttribute on every model call made while the skill
  // runs, so counting rows reports how chatty a skill is rather than how often it
  // ran — on real data that inflated totals by 1.7x to 46x and reordered the
  // ranking, since a skill that fans out to subagents outscored one invoked
  // nearly twice as often. Two steps collapse rows back to invocations: turns made
  // inside subagents the skill spawned are dropped (they carry
  // :agentNameAttribute and report the subagent's model, not the one running the
  // skill), and the surviving main-loop turns are deduplicated by
  // :promptIdAttribute.
  //
  // Counting the Skill dispatcher's own tool_result rows instead would undercount
  // just as badly, because a skill invoked as a slash command never goes through
  // that tool.
  //
  // DISTINCT ON keeps the earliest surviving turn per prompt, so an invocation
  // lands in exactly one model bucket and the per-model counts still sum to the
  // identifier total — a prompt whose main-loop turns span models is rare but
  // real. Turns with no prompt id fall back to the row's primary key, which is
  // unique, so they count individually instead of collapsing into one invocation.
  // Row ordering matches aggregateToolInvocationsByInnerAttributeAndModelInRange.
  @Query(value = """
      WITH skill_invocations AS (
        SELECT DISTINCT ON (identifier, prompt_id)
          identifier,
          model
        FROM (
          SELECT
            COALESCE(NULLIF(attributes ->> :skillAttribute, ''), 'unknown')   AS identifier,
            COALESCE(NULLIF(attributes ->> :promptIdAttribute, ''), id::text) AS prompt_id,
            COALESCE(NULLIF(attributes ->> :modelAttribute, ''), 'unknown')   AS model,
            timestamp                                                         AS turn_at,
            id                                                                AS record_id
          FROM log_records
          WHERE event_name = :eventName
            AND jsonb_exists(attributes, :skillAttribute)
            AND NOT jsonb_exists(attributes, :agentNameAttribute)
            AND timestamp >= :start
            AND timestamp <= :end
        ) skill_turns
        ORDER BY identifier, prompt_id, turn_at, record_id
      )
      SELECT
        identifier                                                      AS identifier,
        model                                                           AS model,
        COUNT(*)                                                        AS calls
      FROM skill_invocations
      GROUP BY identifier, model
      ORDER BY SUM(COUNT(*)) OVER (PARTITION BY identifier) DESC, identifier, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateSkillInvocationsByModelInRange(
      @Param("eventName") String eventName,
      @Param("skillAttribute") String skillAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("promptIdAttribute") String promptIdAttribute,
      @Param("agentNameAttribute") String agentNameAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Direct cost sum for skills. Deliberately NOT built on top of
  // aggregateSkillInvocationsByModelInRange above: that query's DISTINCT ON
  // (identifier, prompt_id) dedup and its NOT jsonb_exists(:agentNameAttribute)
  // filter both exist to keep one prompt, or one subagent it spawned, from
  // inflating an INVOCATION count -- but every api_request row under a skill,
  // including the ones from inside a subagent it spawned, genuinely spent
  // money. A dollar total therefore sums ALL of them rather than reusing that
  // dedup, which is why this is a second, simpler query rather than an added
  // SUM column on the first.
  //
  // The identifier expression is copied verbatim from the invocation query
  // (same :skillAttribute key, same 'unknown' fallback, no tool_input lookup --
  // Claude Code always stamps skill.name as a flat attribute) so the two
  // queries agree on how a skill is named and LogService can merge their rows
  // by identifier.
  //
  // COALESCE(SUM(...), 0): a skill run whose turns predate cost_usd being
  // stamped (or a test fixture missing it) still needs a real number here --
  // LogService#mergeIdentifierUsageCost reads cost_usd as a non-null double.
  @Query(value = """
      SELECT
        COALESCE(NULLIF(attributes ->> :skillAttribute, ''), 'unknown')          AS identifier,
        COALESCE(NULLIF(attributes ->> :modelAttribute, ''), 'unknown')          AS model,
        COALESCE(SUM((attributes ->> :costAttribute)::numeric), 0)::double precision AS cost_usd
      FROM log_records
      WHERE event_name = :eventName
        AND jsonb_exists(attributes, :skillAttribute)
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY identifier, model
      ORDER BY identifier, cost_usd DESC
      """, nativeQuery = true)
  List<Object[]> aggregateSkillCostByModelInRange(
      @Param("eventName") String eventName,
      @Param("skillAttribute") String skillAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("costAttribute") String costAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-subagent-type cost, via span correlation rather than the last-main-loop-turn
  // heuristic aggregateToolInvocationsByInnerAttributeAndModelInRange above uses for
  // invocation counts. That heuristic works for counting a dispatch (one row is one
  // call, whoever paid for it) but cannot give a subagent's OWN spend: the dispatching
  // tool_result carries no cost at all, and the model/cost that heuristic resolves
  // belongs to the turn that ISSUED the dispatch, not the subagent's own LLM calls that
  // ran after it.
  //
  // No WITH RECURSIVE is needed. A subagent's own LLM calls run with its dispatch's
  // toolExecutionSpanName span open, so they are DIRECT children of that span -- never
  // several levels down -- which means a single parent_span_id join reaches every
  // llm_request span the subagent itself issued. This mirrors the request_id
  // correlation span_efforts (V15) already does, just starting one hop earlier because
  // subagent identity lives on the LOG side (the dispatching tool_result), not on any
  // span:
  //   1. subagent_dispatches: one row per :subagentToolName tool_result, carrying its
  //      tool_use_id and the resolved identifier -- the same tool_input JSON fallback
  //      + :defaultIdentifier default aggregateToolInvocationsByInnerAttributeAndModelInRange
  //      uses, so the two queries agree on how a subagent is named.
  //   2. LEFT JOIN to the spans row that IS this dispatch's own execution (name =
  //      :toolExecutionSpanName, :toolCallIdAttribute match) -- the span every LLM call
  //      the subagent itself makes hangs directly beneath.
  //   3. LEFT JOIN to that span's direct children named :llmRequestSpanName, each
  //      carrying :requestIdAttribute.
  //   4. LEFT JOIN those request ids back to the priced 'api_request' log that
  //      carries :costAttribute and :modelAttribute. event_name is a literal, not a
  //      bind parameter, so this join can ride idx_log_records_request_id (V19) --
  //      that index is partial on event_name = 'api_request' and Postgres's partial-
  //      index prover matches expressions structurally, so a bound parameter here
  //      could never be proven to satisfy it once the plan is promoted to generic
  //      (see V19's migration comment). Every SpanRepository join against this same
  //      index hardcodes the literal for the same reason.
  // Every join is LEFT, and Postgres's three-valued "x = NULL is never true" logic is
  // what keeps that safe: a dispatch with no matching execution span, or an execution
  // span with no child LLM spans, produces NULL join columns all the way through rather
  // than accidentally matching an unrelated row that also happens to have a null
  // request id. The final WHERE model IS NOT NULL then drops exactly those unmatched
  // rows rather than surfacing them as a spurious 'unknown' bucket at $0 -- an identifier
  // with zero priced calls simply has no row here at all. LogService#mergeIdentifierUsageCost
  // is what turns "no row" into a real costUsd = 0.0 / empty costByModel for that
  // identifier, matching the same "omit zero, don't send an explicit 0" convention
  // costByModel already documents. That distinction is deliberately tested: a dropped
  // IDENTIFIER and "ran zero billed LLM calls" must not look identical to a caller.
  //
  // KNOWN LIMITATION: a subagent that itself dispatches a NESTED subagent only has its
  // own direct LLM cost counted here -- the nested dispatch's LLM spans are
  // grandchildren of this dispatch's execution span, not children, so this single-level
  // join never reaches them. Not solved here: nested subagent dispatch is rare enough
  // in practice that it is not worth the WITH RECURSIVE this design otherwise avoids.
  @Query(value = """
      WITH subagent_dispatches AS (
        SELECT
          attributes ->> :toolCallIdAttribute AS tool_use_id,
          COALESCE(
            NULLIF(attributes ->> :innerAttribute, ''),
            NULLIF((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> :innerAttribute, ''),
            :defaultIdentifier)                                        AS identifier
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> :toolAttribute = :toolName
          AND timestamp >= :start
          AND timestamp <= :end
      ),
      priced_llm_calls AS (
        SELECT
          dispatches.identifier                          AS identifier,
          priced_request.attributes ->> :modelAttribute   AS model,
          (priced_request.attributes ->> :costAttribute)::numeric AS cost_usd
        FROM subagent_dispatches dispatches
        LEFT JOIN spans dispatch_execution
          ON dispatch_execution.name = :toolExecutionSpanName
          AND dispatch_execution.attributes ->> :toolCallIdAttribute = dispatches.tool_use_id
        LEFT JOIN spans child_llm_span
          ON child_llm_span.parent_span_id = dispatch_execution.span_id
          AND child_llm_span.name = :llmRequestSpanName
        LEFT JOIN log_records priced_request
          ON priced_request.event_name = 'api_request'
          AND priced_request.attributes ->> :requestIdAttribute = child_llm_span.attributes ->> :requestIdAttribute
      )
      SELECT
        identifier                                   AS identifier,
        model                                        AS model,
        COALESCE(SUM(cost_usd), 0)::double precision AS cost_usd
      FROM priced_llm_calls
      WHERE model IS NOT NULL
      GROUP BY identifier, model
      ORDER BY identifier, cost_usd DESC
      """, nativeQuery = true)
  List<Object[]> aggregateSubagentCostByModelInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("toolName") String toolName,
      @Param("innerAttribute") String innerAttribute,
      @Param("defaultIdentifier") String defaultIdentifier,
      @Param("toolCallIdAttribute") String toolCallIdAttribute,
      @Param("toolExecutionSpanName") String toolExecutionSpanName,
      @Param("llmRequestSpanName") String llmRequestSpanName,
      @Param("requestIdAttribute") String requestIdAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("costAttribute") String costAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // ---------------------------------------------------------------------------
  // Cost page: work-category partition
  //
  // Every api_request row is assigned to exactly one of four categories, in
  // precedence order -- this ordering IS the partition, since a request can
  // legitimately carry both a subagent query_source AND a skill.name (a skill
  // running inside a subagent), and the two cost queries below would otherwise
  // double count it:
  //   1. SUBAGENT  -- query_source starts with :subagentQuerySourcePrefix
  //   2. SKILL     -- carries :skillAttribute (and is not already SUBAGENT)
  //   3. MAIN_LOOP -- query_source is one of :mainLoopQuerySources
  //   4. AUXILIARY -- everything else (compact, generate_session_title, ...)
  //
  // GROUPING SETS ((), (category), (category, bucket)) computes the page
  // total, the four-way split, and the stacked trend in one scan, the same
  // idiom MetricPointRepository#aggregateCostBreakdown uses for the counter
  // side. category_grouped/bucket_grouped are Postgres's own GROUPING() bits
  // (1 = rolled up), which the service reads to demultiplex the three row
  // shapes rather than inferring row type from nullability.
  @Query(value = """
      SELECT
        category,
        bucket,
        GROUPING(category)                                                AS category_grouped,
        GROUPING(bucket)                                                   AS bucket_grouped,
        COALESCE(SUM(cost_usd), 0)::double precision                       AS cost_usd,
        COUNT(*)::bigint                                                   AS requests,
        COALESCE(SUM(input_tokens), 0)::bigint                             AS input_tokens,
        COALESCE(SUM(output_tokens), 0)::bigint                            AS output_tokens,
        COALESCE(SUM(cache_creation_tokens), 0)::bigint                    AS cache_creation_tokens,
        COALESCE(SUM(cache_read_tokens), 0)::bigint                        AS cache_read_tokens
      FROM (
        SELECT
          """ + WORK_CATEGORY_CASE_EXPRESSION + """
                                                                             AS category,
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
          (attributes ->> :costAttribute)::numeric                         AS cost_usd,
          (attributes ->> 'input_tokens')::numeric                         AS input_tokens,
          (attributes ->> 'output_tokens')::numeric                        AS output_tokens,
          (attributes ->> 'cache_creation_tokens')::numeric                AS cache_creation_tokens,
          (attributes ->> 'cache_read_tokens')::numeric                    AS cache_read_tokens
        FROM log_records
        WHERE event_name = :eventName
          AND timestamp >= :start
          AND timestamp <= :end
      ) categorized
      GROUP BY GROUPING SETS ((), (category), (category, bucket))
      ORDER BY
        CASE WHEN GROUPING(category) = 1 THEN 0 WHEN GROUPING(bucket) = 1 THEN 1 ELSE 2 END,
        category,
        bucket
      """, nativeQuery = true)
  List<Object[]> aggregateCostByWorkCategoryInRange(
      @Param("eventName") String eventName,
      @Param("querySourceAttribute") String querySourceAttribute,
      @Param("subagentQuerySourcePrefix") String subagentQuerySourcePrefix,
      @Param("skillAttribute") String skillAttribute,
      @Param("mainLoopQuerySources") List<String> mainLoopQuerySources,
      @Param("costAttribute") String costAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Equal-prior-window total, for the Cost page's delta-vs-prior KPI. The current-window
  // total is NOT computed here -- CostService#breakdownInRange already gets it for free off
  // aggregateCostByWorkCategoryInRange's GROUPING SETS () total row, so a second scan of the
  // same current window would be pure duplicate work.
  @Query(value = """
      SELECT COALESCE(SUM((attributes ->> :costAttribute)::numeric), 0)::double precision AS prior_total
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :priorStart
        AND timestamp < :start
      """, nativeQuery = true)
  List<Object[]> aggregatePriorApiRequestCostTotalInRange(
      @Param("eventName") String eventName,
      @Param("costAttribute") String costAttribute,
      @Param("priorStart") Instant priorStart,
      @Param("start") Instant start);

  // Cost drivers: model x effort grid. effort is absent on ~7% of api_request
  // rows (see AGENTS.md), so it is left nullable here rather than defaulted --
  // "not recorded" and "ran at some specific level" are different facts.
  @Query(value = """
      SELECT
        COALESCE(NULLIF(attributes ->> :modelAttribute, ''), 'unknown')    AS model,
        attributes ->> :effortAttribute                                    AS effort,
        COALESCE(SUM((attributes ->> :costAttribute)::numeric), 0)::double precision AS cost_usd,
        COUNT(*)::bigint                                                   AS requests,
        COALESCE(SUM((attributes ->> 'input_tokens')::numeric), 0)::bigint AS input_tokens,
        COALESCE(SUM((attributes ->> 'output_tokens')::numeric), 0)::bigint AS output_tokens,
        COALESCE(SUM((attributes ->> 'cache_creation_tokens')::numeric), 0)::bigint AS cache_creation_tokens,
        COALESCE(SUM((attributes ->> 'cache_read_tokens')::numeric), 0)::bigint AS cache_read_tokens
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY model, effort
      ORDER BY cost_usd DESC
      """, nativeQuery = true)
  List<Object[]> aggregateCostByModelAndEffortInRange(
      @Param("eventName") String eventName,
      @Param("modelAttribute") String modelAttribute,
      @Param("effortAttribute") String effortAttribute,
      @Param("costAttribute") String costAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Biggest line items: top sessions by spend in the window, log-side so this
  // sums into the same total the rest of the Cost page reads from.
  @Query(value = """
      SELECT
        attributes ->> 'session.id'                                        AS session_id,
        COALESCE(SUM((attributes ->> :costAttribute)::numeric), 0)::double precision AS cost_usd,
        COUNT(*)::bigint                                                   AS requests
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
        AND attributes ->> 'session.id' IS NOT NULL
      GROUP BY session_id
      ORDER BY cost_usd DESC
      LIMIT :sessionLimit
      """, nativeQuery = true)
  List<Object[]> aggregateTopCostSessionsInRange(
      @Param("eventName") String eventName,
      @Param("costAttribute") String costAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("sessionLimit") int sessionLimit);

  // Per-session breakdown of the same four-way work-category partition
  // aggregateCostByWorkCategoryInRange computes page-wide (identical precedence:
  // SUBAGENT beats SKILL beats MAIN_LOOP beats AUXILIARY), narrowed to the
  // biggest-line-items ranking's own session ids instead of the whole window --
  // this is what lets the session-detail modal show a 4-segment cost bar per
  // session without re-deriving the category logic a second way. No GROUPING
  // SETS here: unlike the page-wide query there is no total row or trend bucket
  // to compute, just one row per (session, category) that had at least one
  // request, so a plain GROUP BY over the categorized subquery is enough. The
  // subquery shape (materializing session_id/category as real output columns of
  // a derived table, then grouping the outer query by those columns) sidesteps
  // Postgres's inability to GROUP BY a repeated parameterized jsonb expression
  // via its own SELECT-list alias -- see LogRecordRepository's class-level notes
  // in AGENTS.md/backend/CLAUDE.md.
  @Query(value = """
      SELECT
        session_id,
        category,
        COALESCE(SUM(cost_usd), 0)::double precision                     AS cost_usd
      FROM (
        SELECT
          attributes ->> 'session.id'                                    AS session_id,
          """ + WORK_CATEGORY_CASE_EXPRESSION + """
                                                                            AS category,
          (attributes ->> :costAttribute)::numeric                       AS cost_usd
        FROM log_records
        WHERE event_name = :eventName
          AND timestamp >= :start
          AND timestamp <= :end
          AND attributes ->> 'session.id' IN :sessionIds
      ) categorized
      GROUP BY session_id, category
      ORDER BY session_id, category
      """, nativeQuery = true)
  List<Object[]> aggregateCostByWorkCategoryForSessionsInRange(
      @Param("querySourceAttribute") String querySourceAttribute,
      @Param("subagentQuerySourcePrefix") String subagentQuerySourcePrefix,
      @Param("skillAttribute") String skillAttribute,
      @Param("mainLoopQuerySources") List<String> mainLoopQuerySources,
      @Param("costAttribute") String costAttribute,
      @Param("eventName") String eventName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("sessionIds") Collection<String> sessionIds);

  // Per-tool success / failure split. success is stored as a JSON boolean;
  // ->>'success' returns
  // text, so 'false' is a failure and anything else (including NULL on rare
  // unlabeled rows) is
  // counted as a success. Returned as one row per tool with both counts so the
  // service can
  // derive failure_rate without a second query.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)                                                        AS calls,
        COUNT(*) FILTER (WHERE attributes ->> 'success' = 'false')      AS failures
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :since
      GROUP BY tool
      ORDER BY failures DESC, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureRates(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)                                                        AS calls,
        COUNT(*) FILTER (WHERE attributes ->> 'success' = 'false')      AS failures
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY failures DESC, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureRatesInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Historically hardcoded 'tool_name' rather than binding :toolAttribute — the one documented
  // exception to this file's "never hardcode tool_name" convention. Splitting MCP rows onto
  // :toolAttribute/MCP_AWARE_TOOL_EXPRESSION brings both back in line with every sibling query.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COALESCE(attributes ->> 'source', 'unknown')    AS source,
        COUNT(*)                                         AS count
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> 'decision' = 'reject'
        AND timestamp >= :since
      GROUP BY tool, source
      ORDER BY count DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolDenials(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COALESCE(attributes ->> 'source', 'unknown')    AS source,
        COUNT(*)                                         AS count
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> 'decision' = 'reject'
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool, source
      ORDER BY count DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolDenialsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'hook_event', 'unknown')                 AS hookEvent,
        COALESCE(attributes ->> 'hook_name', 'unknown')                  AS hookName,
        COUNT(*)                                                          AS total,
        SUM(COALESCE((attributes ->> 'num_success')::int, 0))            AS successes,
        SUM(COALESCE((attributes ->> 'num_blocking')::int, 0))           AS blockingErrors,
        SUM(COALESCE((attributes ->> 'num_non_blocking_error')::int, 0)) AS nonBlockingErrors,
        SUM(COALESCE((attributes ->> 'num_cancelled')::int, 0))          AS cancelled
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :since
      GROUP BY hookEvent, hookName
      ORDER BY blockingErrors DESC, total DESC
      """, nativeQuery = true)
  List<Object[]> aggregateHookExecutions(
      @Param("eventName") String eventName,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'hook_event', 'unknown')                 AS hookEvent,
        COALESCE(attributes ->> 'hook_name', 'unknown')                  AS hookName,
        COUNT(*)                                                          AS total,
        SUM(COALESCE((attributes ->> 'num_success')::int, 0))            AS successes,
        SUM(COALESCE((attributes ->> 'num_blocking')::int, 0))           AS blockingErrors,
        SUM(COALESCE((attributes ->> 'num_non_blocking_error')::int, 0)) AS nonBlockingErrors,
        SUM(COALESCE((attributes ->> 'num_cancelled')::int, 0))          AS cancelled
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY hookEvent, hookName
      ORDER BY blockingErrors DESC, total DESC
      """, nativeQuery = true)
  List<Object[]> aggregateHookExecutionsInRange(
      @Param("eventName") String eventName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Tool dimension is MCP-aware — see the note beside aggregateToolCalls. Splitting this feed
  // also changes LogService#buildToolCallTimeseries's top-N selection: MCP servers now compete
  // for chart slots individually instead of as one 'mcp_tool' lump, which is the intent, but it
  // shifts what falls into the synthetic 'Other' bucket.
  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :since) AS bucket,
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)                                                           AS calls
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :since
      GROUP BY bucket, tool
      ORDER BY bucket, tool
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsTimeseries(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("since") Instant since,
      @Param("bucketSeconds") long bucketSeconds);

  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
        COUNT(*)                                                           AS calls
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket, tool
      ORDER BY bucket, tool
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsTimeseriesInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Per-command-prefix Bash hotspots. Claude Code stores the actual command
  // inside the
  // tool_input attribute as a JSON-encoded string, so we NULLIF the empty-string
  // case to
  // avoid an invalid jsonb cast, then parse and split_part on the first space.
  // Rows that
  // didn't capture a tool_input bucket under 'unknown'.
  //
  // Leading `cd <dir> && ` / `cd <dir>; ` / newline-chained `cd <dir>` prefixes
  // are stripped
  // before taking the first token: agents habitually prefix real commands with
  // cd, which
  // would otherwise charge the real command's latency to a meaningless 'cd'
  // bucket (a 27 s
  // `cd backend && ./mvnw verify` is a slow Maven build, not a slow cd). The (…)+
  // group
  // strips repeated chains; \n is a character-entry escape Postgres ARE accepts
  // inside
  // bracket expressions, so multi-line commands re-bucket correctly too.
  @Query(value = """
      WITH bash_calls AS (
        SELECT
          ltrim(regexp_replace(
            (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
            '^\\s*(cd\\s+[^&;|\\n]*(&&|;|\\n)\\s*)+',
            ''))                                        AS command,
          (attributes ->> 'duration_ms')::numeric            AS duration_ms,
          (attributes ->> 'tool_result_size_bytes')::numeric AS result_bytes
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> :toolAttribute = 'Bash'
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        COALESCE(NULLIF(split_part(command, ' ', 1), ''), 'unknown') AS command_prefix,
        COUNT(*)                                                      AS calls,
        ROUND(AVG(duration_ms))                                       AS avg_ms,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)) AS p95_ms,
        ROUND(AVG(result_bytes))                                      AS avg_out
      FROM bash_calls
      GROUP BY command_prefix
      ORDER BY calls DESC
      LIMIT :hotspotLimit
      """, nativeQuery = true)
  List<Object[]> aggregateBashCommandHotspotsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("hotspotLimit") int hotspotLimit);

  // Largest individual tool results in the window. file_path / command live under
  // the
  // tool_input JSON string; fall through to '' so callers can still see the byte
  // count even
  // when tool_input wasn't captured.
  //
  // Two row classes are excluded because they are not tunable and would crowd
  // actionable
  // rows out of the LIMIT: tools whose result size is externally determined
  // (Agent,
  // WebSearch, …, caller-supplied), and image/binary reads — an agent reading a
  // screenshot
  // is desirable behavior, the bytes don't translate to context tokens the way
  // text does,
  // and an image can't be paged.
  //
  // Identical (tool, scope, bytes) calls collapse into one row with an occurrence
  // count: the
  // same file re-read nine times used to fill nine LIMIT slots while carrying one
  // row of
  // information. The command-branch scope strips leading cd chains with the same
  // regex as
  // the hotspot bucketing so re-runs of `cd x && <cmd>` and plain `<cmd>` group
  // together.
  //
  // Tool dimension is MCP-aware (MCP_AWARE_TOOL_EXPRESSION): left unsplit, a large MCP result
  // would still surface as an anonymous 'mcp_tool' row — the exact problem this feature exists to
  // fix, sitting in the same report as the section that fixes it everywhere else. The scope
  // column is ALSO MCP-aware (MCP_AWARE_TOOL_INPUT_SCOPE_EXPRESSION): MCP calls never populate
  // tool_input (their identity lives in tool_parameters instead), so without it an MCP row would
  // show a real bytes figure against a blank scope. excludedTools below intentionally keeps
  // matching the RAW attributes ->> :toolAttribute value, not the projected 'mcp:<server>' tool —
  // adding "mcp:playwright" there would silently no-op; exclude MCP entirely by adding "mcp_tool".
  @Query(value = """
      WITH sized_calls AS (
        SELECT
        """ + MCP_AWARE_TOOL_EXPRESSION + """
         AS tool,
        """ + MCP_AWARE_TOOL_INPUT_SCOPE_EXPRESSION + """
         AS scope,
          ((attributes ->> 'tool_result_size_bytes')::numeric)::bigint AS bytes
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> 'tool_result_size_bytes' IS NOT NULL
          AND COALESCE(attributes ->> :toolAttribute, 'unknown') NOT IN (:excludedTools)
          AND COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path', '')
              !~* '\\.(png|jpe?g|gif|webp|bmp|ico|svg|pdf)$'
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        tool,
        scope,
        bytes,
        COUNT(*) AS occurrences
      FROM sized_calls
      GROUP BY tool, scope, bytes
      ORDER BY bytes DESC
      LIMIT :resultLimit
      """, nativeQuery = true)
  List<Object[]> aggregateOversizedToolResultsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("toolKey") String toolKey,
      @Param("excludedTools") List<String> excludedTools,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("resultLimit") int resultLimit);

  // (session.id, file_path) pairs where Read was called more than once. file_path
  // lives
  // inside the tool_input JSON string; rows without tool_input are excluded since
  // they have
  // no scope to dedupe on. spanMinutes (first→last) and maxGapMinutes (largest
  // interval
  // between consecutive reads, computed via LAG) let the report distinguish
  // hunting loops
  // (many reads, small max-gap) from incidental spread-across-the-day re-reads.
  @Query(value = """
      WITH read_events AS (
        SELECT
          COALESCE(attributes ->> 'session.id', 'unknown')                AS session_id,
          (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' AS file_path,
          timestamp                                                        AS read_timestamp,
          LAG(timestamp) OVER (
            PARTITION BY COALESCE(attributes ->> 'session.id', 'unknown'),
                         (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path'
            ORDER BY timestamp
          )                                                                AS prev_timestamp
        FROM log_records
        WHERE event_name = :eventName
          AND attributes ->> :toolAttribute = 'Read'
          AND (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' IS NOT NULL
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        session_id,
        file_path,
        COUNT(*)                                                                                AS reads,
        ROUND(EXTRACT(EPOCH FROM (MAX(read_timestamp) - MIN(read_timestamp))) / 60.0)            AS span_minutes,
        ROUND(EXTRACT(EPOCH FROM COALESCE(MAX(read_timestamp - prev_timestamp), INTERVAL '0')) / 60.0) AS max_gap_minutes
      FROM read_events
      GROUP BY session_id, file_path
      HAVING COUNT(*) > 1
      ORDER BY reads DESC
      LIMIT :readLimit
      """, nativeQuery = true)
  List<Object[]> aggregateRedundantFileReadsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("readLimit") int readLimit);

  // (session.id, file_path) pairs where Edit failed two or more times. Same
  // tool_input
  // unwrap as the redundant-read query.
  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'session.id', 'unknown')                          AS session_id,
        (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path'           AS file_path,
        COUNT(*)                                                                   AS failures
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :toolAttribute = 'Edit'
        AND attributes ->> 'success' = 'false'
        AND (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY session_id, file_path
      HAVING COUNT(*) >= 2
      ORDER BY failures DESC
      LIMIT :loopLimit
      """, nativeQuery = true)
  List<Object[]> aggregateEditFailureLoopsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("loopLimit") int loopLimit);

  // Calls that are simultaneously in the slow tail AND have an oversized result.
  // Filters
  // each metric independently against a caller-supplied minimum (the service
  // derives those
  // from the report's existing performance/oversized thresholds), then ranks by
  // the product
  // duration_ms * tool_result_size_bytes so the very worst single calls float to
  // the top.
  // This is the cross-cut the per-tool summaries hide: a call can be average on
  // each axis
  // alone yet dominate context cost when both stack.
  //
  // Externally-determined tools are excluded: a subagent (Agent) running 30+
  // minutes and
  // returning a short summary is the ideal delegation shape, not an offender, and
  // web
  // results are sized by the remote site — neither is tunable from AGENTS.md.
  // Image/binary
  // reads are excluded for the same reason as in the oversized query: the read is
  // desirable
  // and can't be paged.
  //
  // The command-branch scope strips leading cd chains with the same regex as the
  // hotspot
  // bucketing, so the report attributes a worst-call fact to the command that
  // actually ran
  // (`./mvnw verify`), never back to a `cd` bucket.
  //
  // Tool and scope are MCP-aware the same way, and for the same reasons, as
  // aggregateOversizedToolResultsInRange above — see its comment. excludedTools likewise stays
  // against the raw attributes ->> :toolAttribute value.
  @Query(value = """
      SELECT
      """ + MCP_AWARE_TOOL_EXPRESSION + """
       AS tool,
      """ + MCP_AWARE_TOOL_INPUT_SCOPE_EXPRESSION + """
       AS scope,
        ((attributes ->> 'duration_ms')::numeric)::bigint            AS duration_ms,
        ((attributes ->> 'tool_result_size_bytes')::numeric)::bigint AS bytes
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> 'duration_ms' IS NOT NULL
        AND attributes ->> 'tool_result_size_bytes' IS NOT NULL
        AND COALESCE(attributes ->> :toolAttribute, 'unknown') NOT IN (:excludedTools)
        AND COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path', '')
            !~* '\\.(png|jpe?g|gif|webp|bmp|ico|svg|pdf)$'
        AND (attributes ->> 'duration_ms')::numeric >= :minDurationMs
        AND (attributes ->> 'tool_result_size_bytes')::numeric >= :minBytes
        AND timestamp >= :start
        AND timestamp <= :end
      ORDER BY ((attributes ->> 'duration_ms')::numeric
                * (attributes ->> 'tool_result_size_bytes')::numeric) DESC
      LIMIT :resultLimit
      """, nativeQuery = true)
  List<Object[]> aggregateSlowAndLargeCallsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("mcpToolName") String mcpToolName,
      @Param("parametersAttribute") String parametersAttribute,
      @Param("serverKey") String serverKey,
      @Param("toolKey") String toolKey,
      @Param("excludedTools") List<String> excludedTools,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("minDurationMs") long minDurationMs,
      @Param("minBytes") long minBytes,
      @Param("resultLimit") int resultLimit);

  // Coverage check used in the Bash command hotspots blurb: how many Bash
  // tool_result rows
  // actually carry a parseable tool_input (and therefore a command) versus the
  // total. Lets
  // the report state the denominator instead of silently lumping uninstrumented
  // calls into
  // 'unknown'. cd_prefixed counts commands that lead with `cd …` — the hotspot
  // query strips
  // those prefixes before bucketing, and a high share is its own tuning signal
  // (AGENTS.md
  // command examples teaching `cd dir && …` instead of path-scoped invocations).
  @Query(value = """
      SELECT
        COUNT(*) FILTER (WHERE (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command' IS NOT NULL) AS with_command,
        COUNT(*)                                                                                          AS total,
        COUNT(*) FILTER (WHERE (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command' ~ '^\\s*cd\\s') AS cd_prefixed
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :toolAttribute = 'Bash'
        AND timestamp >= :start
        AND timestamp <= :end
      """, nativeQuery = true)
  List<Object[]> bashCommandCoverageInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Failed Read calls grouped by (session, file_path) — the raw material for the
  // path
  // near-miss (typo) detection in the tuning report. The service pairs each row
  // against the
  // distinct successfully-read paths of the same session and keeps the pairs
  // within a small
  // edit distance; the distance math stays in Java to avoid a fuzzystrmatch
  // extension
  // dependency (and its 255-char levenshtein limit — scratchpad paths run long).
  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'session.id', 'unknown')                 AS session_id,
        (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' AS file_path,
        COUNT(*)                                                          AS failures
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :toolAttribute = 'Read'
        AND attributes ->> 'success' = 'false'
        AND (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY session_id, file_path
      ORDER BY failures DESC
      LIMIT :failedReadLimit
      """, nativeQuery = true)
  List<Object[]> aggregateFailedReadPathsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("failedReadLimit") int failedReadLimit);

  // Distinct (session, file_path) pairs that Read succeeded on — the comparison
  // set for the
  // path near-miss detection. Capped defensively; a window busy enough to exceed
  // the cap
  // still yields useful (if incomplete) matches.
  @Query(value = """
      SELECT DISTINCT
        COALESCE(attributes ->> 'session.id', 'unknown')                 AS session_id,
        (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' AS file_path
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :toolAttribute = 'Read'
        AND attributes ->> 'success' = 'true'
        AND (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path' IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      LIMIT :pathLimit
      """, nativeQuery = true)
  List<Object[]> distinctSuccessfulReadPathsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("pathLimit") int pathLimit);

  // Consecutive same-tool repeats per session, scoped by file_path / command /
  // (no scope).
  //
  // Detect "islands" of consecutive rows with the same (tool, scope) inside each
  // session by
  // taking the difference between two row numbers ordered by timestamp: one
  // partitioned by
  // session alone, one partitioned by session+tool+scope. Rows that share both
  // the (tool,
  // scope) value and the same row-number delta are consecutive — the delta only
  // stays
  // constant while the tool/scope doesn't change, so it doubles as a run
  // identifier.
  //
  // Per (session, tool, scope) we take MAX(run_length) as that session's longest
  // run, then
  // roll the per-session longest runs up into a median + max for the (tool,
  // scope) pair. The
  // HAVING MAX(...) >= 2 filter drops sessions whose only "runs" were single
  // isolated calls;
  // those aren't repeats and would only depress the median.
  @Query(value = """
      WITH events AS (
        SELECT
          COALESCE(attributes ->> 'session.id', 'unknown')               AS session_id,
          COALESCE(attributes ->> :toolAttribute, 'unknown')             AS tool,
          CASE
            WHEN attributes ->> :toolAttribute IN ('Edit', 'Write', 'Read', 'MultiEdit')
              THEN COALESCE(
                NULLIF((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path', ''),
                '(no scope)')
            -- A bare first token collapses e.g. `cd backend && ./mvnw test` and `cd frontend &&
            -- yarn dev` onto the same scope (cd), and `git status`/`git commit` onto git --
            -- so unrelated commands in a row looked like a repeat run. Strip a leading cd-chain
            -- and keep the program plus subcommand (two tokens) so distinct commands stay distinct.
            WHEN attributes ->> :toolAttribute = 'Bash'
              THEN COALESCE(
                NULLIF(
                  array_to_string(
                    (regexp_split_to_array(
                      trim(regexp_replace(
                        (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                        '^(cd\\s+\\S+\\s*(&&|;)\\s*)+', '', 'i')),
                      '\\s+'))[1:2],
                    ' '),
                  ''),
                '(no scope)')
            ELSE '(no scope)'
          END                                                            AS scope,
          timestamp
        FROM log_records
        WHERE event_name = :eventName
          AND timestamp >= :since
      ),
      numbered AS (
        SELECT
          session_id, tool, scope,
          ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY timestamp)             AS rn_session,
          ROW_NUMBER() OVER (PARTITION BY session_id, tool, scope ORDER BY timestamp) AS rn_group
        FROM events
      ),
      runs AS (
        SELECT session_id, tool, scope, COUNT(*) AS run_length
        FROM numbered
        GROUP BY session_id, tool, scope, (rn_session - rn_group)
      ),
      longest_per_session AS (
        -- (no scope) is not a real scope match: it means we could not tell what this call
        -- targeted, so any run under it (e.g. two unrelated mcp_tool calls in a row) is not
        -- evidence of repeating the same action. Drop it here, after run detection (where it
        -- still correctly breaks adjacency for the surrounding scoped calls), not from events.
        SELECT session_id, tool, scope, MAX(run_length) AS longest_run
        FROM runs
        WHERE scope <> '(no scope)'
        GROUP BY session_id, tool, scope
        HAVING MAX(run_length) >= 2
      )
      SELECT
        tool,
        scope,
        CAST(ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY longest_run)) AS bigint) AS median_run,
        MAX(longest_run)                                                                 AS max_run,
        COUNT(*)                                                                         AS sessions
      FROM longest_per_session
      GROUP BY tool, scope
      ORDER BY max_run DESC, median_run DESC, sessions DESC
      LIMIT :resultLimit
      """, nativeQuery = true)
  List<Object[]> aggregateToolRepeats(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("since") Instant since,
      @Param("resultLimit") int resultLimit);

  @Query(value = """
      WITH events AS (
        SELECT
          COALESCE(attributes ->> 'session.id', 'unknown')               AS session_id,
          COALESCE(attributes ->> :toolAttribute, 'unknown')             AS tool,
          CASE
            WHEN attributes ->> :toolAttribute IN ('Edit', 'Write', 'Read', 'MultiEdit')
              THEN COALESCE(
                NULLIF((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path', ''),
                '(no scope)')
            -- A bare first token collapses e.g. `cd backend && ./mvnw test` and `cd frontend &&
            -- yarn dev` onto the same scope (cd), and `git status`/`git commit` onto git --
            -- so unrelated commands in a row looked like a repeat run. Strip a leading cd-chain
            -- and keep the program plus subcommand (two tokens) so distinct commands stay distinct.
            WHEN attributes ->> :toolAttribute = 'Bash'
              THEN COALESCE(
                NULLIF(
                  array_to_string(
                    (regexp_split_to_array(
                      trim(regexp_replace(
                        (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                        '^(cd\\s+\\S+\\s*(&&|;)\\s*)+', '', 'i')),
                      '\\s+'))[1:2],
                    ' '),
                  ''),
                '(no scope)')
            ELSE '(no scope)'
          END                                                            AS scope,
          timestamp
        FROM log_records
        WHERE event_name = :eventName
          AND timestamp >= :start
          AND timestamp <= :end
      ),
      numbered AS (
        SELECT
          session_id, tool, scope,
          ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY timestamp)             AS rn_session,
          ROW_NUMBER() OVER (PARTITION BY session_id, tool, scope ORDER BY timestamp) AS rn_group
        FROM events
      ),
      runs AS (
        SELECT session_id, tool, scope, COUNT(*) AS run_length
        FROM numbered
        GROUP BY session_id, tool, scope, (rn_session - rn_group)
      ),
      longest_per_session AS (
        -- (no scope) is not a real scope match: it means we could not tell what this call
        -- targeted, so any run under it (e.g. two unrelated mcp_tool calls in a row) is not
        -- evidence of repeating the same action. Drop it here, after run detection (where it
        -- still correctly breaks adjacency for the surrounding scoped calls), not from events.
        SELECT session_id, tool, scope, MAX(run_length) AS longest_run
        FROM runs
        WHERE scope <> '(no scope)'
        GROUP BY session_id, tool, scope
        HAVING MAX(run_length) >= 2
      )
      SELECT
        tool,
        scope,
        CAST(ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY longest_run)) AS bigint) AS median_run,
        MAX(longest_run)                                                                 AS max_run,
        COUNT(*)                                                                         AS sessions
      FROM longest_per_session
      GROUP BY tool, scope
      ORDER BY max_run DESC, median_run DESC, sessions DESC
      LIMIT :resultLimit
      """, nativeQuery = true)
  List<Object[]> aggregateToolRepeatsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("resultLimit") int resultLimit);

  // Returns tool call count, denial count, and prompt context per session for the
  // given session IDs, in a single scan over log_records (the prompt-info rows
  // are a strict subset of what the counts query already touches, so folding
  // both into one GROUP BY halves the table scans the Sessions grid needs).
  // Tool calls are log records whose event.name = :toolEventName. Denials are
  // tool_decision records whose decision attribute = 'reject'.
  // userPromptCount is the total user_prompt event count. firstUserPrompt is the
  // session's literal chronologically-first prompt body: ARRAY_AGG collects
  // every non-null prompt text for the session ordered by timestamp and [1]
  // takes the head. Whitespace (including embedded newlines from multi-line
  // prompts) collapses to single spaces and the result truncates to 200
  // characters, both in SQL so the service never handles untruncated prompt
  // text. Sessions with no prompt attribute value at all (NULL after NULLIF)
  // still count toward user_prompt_count but contribute no candidate to the
  // ARRAY_AGG, leaving firstUserPrompt null. Sessions with no matching log
  // records at all are omitted; the service defaults missing entries to 0 /
  // null.
  //
  // The three event-name tests read the V16 generated column, NOT
  // attributes ->> 'event.name'. Same detoast reasoning V16 documents for the
  // Logs page, and it bites harder here because this query is one of the two
  // halves of every Sessions-grid page load: log_records is 142 MB of heap
  // against 1478 MB of TOAST, so each jsonb extraction pulls the whole
  // attribute payload (prompt bodies included) back through the toast fetcher.
  // Measured on the live database (25 session ids, 9,769 matched rows, warm
  // cache): 1911 ms -> 393 ms, 366K buffers -> 75K, byte-identical output. The
  // cost was almost entirely in the aggregate node rather than the scan -- the
  // heap scan is only ~475 ms of it -- because each of the three extractions
  // detoasts independently, per row.
  //
  // The 'decision' extraction below stays a jsonb read on purpose: it is ANDed
  // after the event_name test, so it only evaluates for tool_decision rows.
  // The :promptAttribute reads likewise stay jsonb -- the key is a bind param
  // (TuningProperties.promptAttribute), so no generated column can cover them.
  @Query(value = """
      SELECT
          lr.attributes ->> 'session.id'                                           AS session_id,
          COUNT(*) FILTER (WHERE lr.event_name = :toolEventName)                   AS tool_call_count,
          COUNT(*) FILTER (WHERE lr.event_name = :toolDecisionEventName
                             AND lr.attributes ->> 'decision' = 'reject')           AS denial_count,
          COUNT(*) FILTER (WHERE lr.event_name = :userPromptEventName)             AS user_prompt_count,
          (ARRAY_AGG(
              left(regexp_replace(NULLIF(lr.attributes ->> :promptAttribute, ''), '\\s+', ' ', 'g'), 200)
              ORDER BY lr.timestamp ASC
            ) FILTER (WHERE lr.event_name = :userPromptEventName
                        AND NULLIF(lr.attributes ->> :promptAttribute, '') IS NOT NULL)
          )[1]                                                                      AS first_user_prompt
      FROM log_records lr
      WHERE lr.attributes ->> 'session.id' IN :sessionIds
      GROUP BY lr.attributes ->> 'session.id'
      """, nativeQuery = true)
  List<Object[]> aggregateSessionCounts(
      @Param("sessionIds") Collection<String> sessionIds,
      @Param("toolEventName") String toolEventName,
      @Param("toolDecisionEventName") String toolDecisionEventName,
      @Param("userPromptEventName") String userPromptEventName,
      @Param("promptAttribute") String promptAttribute);

  // Full prompt timeline for one session (the Sessions grid's expandable row).
  // Not window-scoped — returns every user_prompt event for the session, oldest
  // first, capped at :promptLimit (the service clamps this the same way
  // PageBounds.MAXIMUM_PAGE_SIZE clamps other list endpoints). trace_id is a
  // top-level column (not an attribute) carrying the claude_code.interaction root
  // span's trace id; the nested NULLIF normalizes both the empty string and the
  // all-zero placeholder trace id (pre-tracing sessions) down to SQL NULL so the
  // service never has to special-case either sentinel value.
  // id ASC is a tiebreaker for the rare case of two user_prompt rows sharing an
  // identical timestamp (e.g. sub-millisecond-identical ingests): it makes the
  // row order -- and therefore LogService#turnIndexForTimestamp's turn
  // boundaries, which are built directly from this ordering -- deterministic
  // across requests instead of depending on incidental heap/plan order.
  //
  // prompt_id is the turn's own identifier, carried by both user_prompt and
  // api_request logs. It is what lets the per-turn token/cost rollups be joined
  // exactly instead of attributed by timestamp interval (see
  // aggregateApiRequestTurnsForSession). It is NULL on rows emitted before
  // Claude Code started stamping it, which is precisely when LogService falls
  // back to the older interval attribution.
  @Query(value = """
      SELECT
        lr.timestamp                                                     AS event_timestamp,
        lr.attributes ->> :promptAttribute                                AS prompt_text,
        NULLIF(NULLIF(lr.trace_id, ''), repeat('0', 32))                  AS trace_id,
        lr.attributes ->> :promptIdAttribute                              AS prompt_id
      FROM log_records lr
      WHERE lr.event_name = :eventName
        AND lr.attributes ->> 'session.id' = :sessionId
      ORDER BY lr.timestamp ASC, lr.id ASC
      LIMIT :promptLimit
      """, nativeQuery = true)
  List<Object[]> findPromptsForSession(
      @Param("sessionId") String sessionId,
      @Param("eventName") String eventName,
      @Param("promptAttribute") String promptAttribute,
      @Param("promptIdAttribute") String promptIdAttribute,
      @Param("promptLimit") int promptLimit);

  // ---------------------------------------------------------------------------
  // Per-API-request token attribution (T3)
  // ---------------------------------------------------------------------------
  //
  // Claude Code stamps every api_request log with the exact token counts, cost,
  // and duration of that one request, plus the prompt.id of the turn that issued
  // it. That makes per-turn rollups an exact GROUP BY rather than the
  // timestamp-interval bucketing LogService otherwise has to do: a request that
  // lands after the user typed the next prompt is billed to the turn that
  // actually issued it, not to whichever interval its timestamp fell in.
  //
  // Token/duration/effort/speed attribute keys are SQL literals here, matching
  // how duration_ms and tool_result_size_bytes are already read elsewhere in this
  // class. The keys that ARE configurable (event name, prompt id, request id,
  // model, cost) come through TuningProperties, same as every other query.
  //
  // Coverage is partial by design and must stay graceful: sessions can exist in
  // metric_points with no api_request logs at all (event logging disabled, or an
  // older CLI), and `effort` is absent on roughly 7% of rows even where the rest
  // is present. Callers therefore treat an empty result as "fall back", never as
  // "this turn cost nothing".

  // Per-turn rollup for one session, grouped by prompt.id.
  //
  // The turn's model is the one that served the most REQUESTS in the turn
  // (mode()), which can differ from the metric-derived rollup's "model with the
  // most tokens" on a turn that mixes models — the two agree whenever a turn
  // uses a single model, which is the overwhelming majority.
  @Query(value = """
      SELECT
        attributes ->> :promptIdAttribute                                     AS prompt_id,
        COUNT(*)::bigint                                                      AS request_count,
        COALESCE(SUM((attributes ->> 'input_tokens')::numeric), 0)::bigint    AS input_tokens,
        COALESCE(SUM((attributes ->> 'output_tokens')::numeric), 0)::bigint   AS output_tokens,
        COALESCE(SUM((attributes ->> 'cache_creation_tokens')::numeric), 0)::bigint AS cache_creation_tokens,
        COALESCE(SUM((attributes ->> 'cache_read_tokens')::numeric), 0)::bigint     AS cache_read_tokens,
        SUM((attributes ->> :costAttribute)::numeric)::double precision       AS cost_usd,
        COALESCE(SUM((attributes ->> 'duration_ms')::numeric), 0)::bigint     AS duration_ms,
        mode() WITHIN GROUP (ORDER BY attributes ->> :modelAttribute)         AS model
      FROM log_records
      WHERE event_name = :apiRequestEventName
        AND attributes ->> 'session.id' = :sessionId
        AND attributes ->> :promptIdAttribute IS NOT NULL
      GROUP BY 1
      """, nativeQuery = true)
  List<Object[]> aggregateApiRequestTurnsForSession(
      @Param("sessionId") String sessionId,
      @Param("apiRequestEventName") String apiRequestEventName,
      @Param("promptIdAttribute") String promptIdAttribute,
      @Param("costAttribute") String costAttribute,
      @Param("modelAttribute") String modelAttribute);

  // Individual requests for one session, oldest first — the per-turn drill-down.
  // Capped by the caller (PageBounds) because a long session can issue thousands
  // of requests; the service reports the cap rather than silently truncating.
  @Query(value = """
      SELECT
        attributes ->> :requestIdAttribute                             AS request_id,
        timestamp                                                      AS request_timestamp,
        attributes ->> :promptIdAttribute                              AS prompt_id,
        attributes ->> :modelAttribute                                 AS model,
        COALESCE((attributes ->> 'input_tokens')::numeric, 0)::bigint  AS input_tokens,
        COALESCE((attributes ->> 'output_tokens')::numeric, 0)::bigint AS output_tokens,
        COALESCE((attributes ->> 'cache_creation_tokens')::numeric, 0)::bigint AS cache_creation_tokens,
        COALESCE((attributes ->> 'cache_read_tokens')::numeric, 0)::bigint     AS cache_read_tokens,
        (attributes ->> :costAttribute)::double precision              AS cost_usd,
        (attributes ->> 'duration_ms')::numeric::bigint                AS duration_ms,
        attributes ->> 'effort'                                        AS effort,
        attributes ->> 'speed'                                         AS speed,
        NULLIF(NULLIF(trace_id, ''), repeat('0', 32))                  AS trace_id
      FROM log_records
      WHERE event_name = :apiRequestEventName
        AND attributes ->> 'session.id' = :sessionId
      ORDER BY timestamp ASC, id ASC
      LIMIT :requestLimit
      """, nativeQuery = true)
  List<Object[]> findApiRequestsForSession(
      @Param("sessionId") String sessionId,
      @Param("apiRequestEventName") String apiRequestEventName,
      @Param("promptIdAttribute") String promptIdAttribute,
      @Param("requestIdAttribute") String requestIdAttribute,
      @Param("costAttribute") String costAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("requestLimit") int requestLimit);

  // Initiating user prompt per trace, for the Traces list rows and the trace
  // detail summary. Claude Code stamps each user_prompt log with the
  // claude_code.interaction root span's trace id in the top-level trace_id
  // column -- the same correlation findPromptsForSession relies on to link a
  // turn to its trace -- so a trace's prompt is just its earliest user_prompt
  // log. DISTINCT ON collapses to one row per trace; the ORDER BY id tiebreaker
  // makes the pick deterministic when two user_prompt rows share a timestamp,
  // matching findPromptsForSession.
  // Unlike aggregateSessionCounts there is deliberately no slash-command
  // deprioritization: that heuristic exists to find the first *meaningful*
  // prompt among a session's many turns, whereas a trace has exactly one
  // initiating turn -- if that turn was '/ship' then '/ship' IS this trace's
  // prompt, not a placeholder to skip past.
  // Whitespace (including embedded newlines from multi-line prompts) collapses
  // to single spaces and the result truncates to :previewLength characters, both
  // in SQL so the service never handles untruncated prompt text.
  // Traces absent from the result are the two null cases the caller defaults:
  // traces not rooted in a conversational turn carry no user_prompt log at all,
  // and traces recorded with prompt-body capture disabled carry the log but no
  // prompt text (NULL after NULLIF).
  @Query(value = """
      SELECT DISTINCT ON (lr.trace_id)
        lr.trace_id                                                                   AS trace_id,
        left(regexp_replace(lr.attributes ->> :promptAttribute, '\\s+', ' ', 'g'),
             :previewLength)                                                          AS first_user_prompt
      FROM log_records lr
      WHERE lr.trace_id IN :traceIds
        AND lr.event_name = :userPromptEventName
        AND NULLIF(lr.attributes ->> :promptAttribute, '') IS NOT NULL
      ORDER BY lr.trace_id, lr.timestamp ASC, lr.id ASC
      """, nativeQuery = true)
  List<Object[]> findFirstUserPromptByTraceIds(
      @Param("traceIds") Collection<String> traceIds,
      @Param("userPromptEventName") String userPromptEventName,
      @Param("promptAttribute") String promptAttribute,
      @Param("previewLength") int previewLength);

  // Per-trace model spend for the given traces, split into what happened
  // while the trace's own claude_code.interaction root span was still open
  // ("own") versus what landed after it closed ("background"). total_cost_usd
  // is the SAME authoritative figure the trace_costs view (V14) and
  // TraceSummary.totalCostUsd resolve to -- the Sessions prompt timeline
  // reuses it so a turn's cost and the cost shown on the trace that turn
  // links to are one number, not two estimates of one number -- though
  // LogService#applyTraceCorrelatedActivity still has to bill each trace's
  // number to only one turn when several turns share a trace.
  //
  // The split exists because a fire-and-forget subagent dispatch (an Agent
  // tool call whose own span closes in milliseconds) can keep issuing
  // api_request logs long after the turn that launched it -- verified on live
  // data: 4.1% of traces in a 14-day window have request activity after their
  // own root span closes, accounting for 22.6% of total spend in that window.
  // Those requests are still correctly billed to this trace (same trace_id),
  // but they did not happen while the dispatching turn was the active turn --
  // background_cost_usd is exactly that later portion, letting a caller show
  // "why this turn cost more than what happened while it was open" instead of
  // silently folding it in or (the bug this replaces) losing it to whichever
  // turn's prompt.id happened to be current when the background request fired.
  //
  // root_ends is computed once per trace (MAX defends against any anomalous
  // multiple-root-like-span trace) rather than joined per log row. A trace
  // with no matching root span (tool/model/mcp-rooted traces -- confirmed 86
  // of 431 recent traces have none) leaves root_end null via the LEFT JOIN,
  // so background_cost_usd safely reports 0 rather than mis-splitting on a
  // missing boundary. Traces with no correlated request log at all are simply
  // absent from the result, same as the view this replaces.
  // Returns (trace_id, total_cost_usd, background_cost_usd).
  @Query(value = """
      WITH\s""" + TRACE_ROOT_ENDS_CTE + """
      SELECT
        lr.trace_id,
        SUM((lr.attributes ->> :costAttribute)::numeric) AS total_cost_usd,
        SUM(CASE WHEN re.root_end IS NOT NULL AND lr.timestamp > re.root_end
                 THEN (lr.attributes ->> :costAttribute)::numeric ELSE 0 END) AS background_cost_usd
      FROM log_records lr
      LEFT JOIN root_ends re ON re.trace_id = lr.trace_id
      WHERE lr.event_name = :apiRequestEventName
        AND lr.trace_id IN :traceIds
        AND lr.attributes ->> :costAttribute ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
      GROUP BY lr.trace_id
      """, nativeQuery = true)
  List<Object[]> findCostSplitByTraceIds(
      @Param("traceIds") Collection<String> traceIds,
      @Param("apiRequestEventName") String apiRequestEventName,
      @Param("costAttribute") String costAttribute,
      @Param("rootSpanNamePattern") String rootSpanNamePattern);

  // Per-trace, per-tool call counts for the given traces, split the same way
  // findCostSplitByTraceIds splits cost -- own_count before the trace's root
  // span closed, background_count after. Deliberately NOT MCP-aware (reads
  // the raw tool_name attribute, no mcp_tool server-splitting), matching
  // findToolEventsForSession's own documented choice: this is a per-turn/
  // per-trace detail list, not an analysis surface.
  //
  // The tool_events CTE materializes "attributes ->> :toolAttribute" into a
  // plain column BEFORE the outer GROUP BY, rather than grouping by the jsonb
  // expression (or its SELECT-list alias) directly: Hibernate binds each
  // occurrence of a repeated native-query named parameter to its own separate
  // JDBC parameter, and Postgres's GROUP BY validity check compares parse-tree
  // nodes by parameter identity, not by runtime value -- so two placeholders
  // bound to the identical string are NOT recognized as the same grouping key
  // and PREPARE fails with "column ... must appear in the GROUP BY clause",
  // confirmed directly against Postgres even with a single occurrence grouped
  // by its own alias. Once tool_name is a real column of the CTE, the outer
  // GROUP BY needs no parameter-equivalence proof at all.
  // Returns (trace_id, tool_name, own_count, background_count).
  @Query(value = """
      WITH\s""" + TRACE_ROOT_ENDS_CTE + """
      ,
      tool_events AS (
        SELECT
          lr.trace_id,
          lr.attributes ->> :toolAttribute AS tool_name,
          lr.timestamp
        FROM log_records lr
        WHERE lr.event_name = :toolEventName
          AND lr.trace_id IN :traceIds
          AND lr.attributes ->> :toolAttribute IS NOT NULL
      )
      SELECT
        te.trace_id,
        te.tool_name,
        COUNT(*) FILTER (WHERE re.root_end IS NULL OR te.timestamp <= re.root_end)     AS own_count,
        COUNT(*) FILTER (WHERE re.root_end IS NOT NULL AND te.timestamp > re.root_end) AS background_count
      FROM tool_events te
      LEFT JOIN root_ends re ON re.trace_id = te.trace_id
      GROUP BY te.trace_id, te.tool_name
      """, nativeQuery = true)
  List<Object[]> findToolEventsSplitByTraceIds(
      @Param("traceIds") Collection<String> traceIds,
      @Param("toolEventName") String toolEventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("rootSpanNamePattern") String rootSpanNamePattern);

  // Every tool_result event for one session, oldest first, feeding the prompt
  // timeline's per-turn "tools" rollup. Not aggregated here: the caller
  // (LogService) buckets each row into its owning turn by comparing this
  // timestamp against the session's ascending prompt timestamps, then groups
  // by tool name per turn — the same "walk the sorted rows" idiom used for the
  // per-turn cost and token/model rollups, so the three per-turn signals share
  // one attribution strategy. Bounded to [firstTurnStart, turnsEndBoundary) --
  // same rationale and NULL-or-compare semantics as
  // MetricPointRepository#findCostPointsForSession -- so a long or resume-heavy
  // session's tool_result history isn't scanned in full on every prompt-panel
  // expand.
  @Query(value = """
      SELECT
        lr.timestamp                     AS event_timestamp,
        lr.attributes ->> :toolAttribute  AS tool_name
      FROM log_records lr
      WHERE lr.event_name = :toolEventName
        AND lr.attributes ->> 'session.id' = :sessionId
        AND lr.attributes ->> :toolAttribute IS NOT NULL
        AND lr.timestamp >= :firstTurnStart
        AND (CAST(:turnsEndBoundary AS timestamptz) IS NULL OR lr.timestamp < :turnsEndBoundary)
      ORDER BY lr.timestamp ASC
      """, nativeQuery = true)
  List<Object[]> findToolEventsForSession(
      @Param("sessionId") String sessionId,
      @Param("toolEventName") String toolEventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("firstTurnStart") Instant firstTurnStart,
      @Param("turnsEndBoundary") Instant turnsEndBoundary);

  // =========================================================================
  // Logs-page aggregation queries (histogram, facets, cursor paging, offset
  // paging). All share the same WHERE macro expressed via cardinality guards
  // and the existing NOT EXISTS filter idiom.
  //
  // Severity is read from the stored generated column derived_severity (added in
  // V8__derived_severity_column.sql). The column is computed at write time by
  // derive_log_severity() (defined in V6__log_severity_function.sql), which is the
  // single source of truth: it prefers severity_text when canonical, falls back to
  // numeric ranges, then to event-based heuristics. Reading from the stored column
  // avoids detoasting the attributes jsonb on every call; the histogram went from
  // ~4.0 s (per-row function evaluation) to ~14 ms after the column was added.
  //
  // event.name is likewise read from the stored generated column event_name (added
  // in V16__log_records_event_name_column.sql), same fix for the same reason:
  // facetEvent's GROUP BY attributes ->> 'event.name' measured ~2.08 s on a 7-day
  // window (16,867 rows), warm cache — pure detoast cost, since attributes averages
  // ~11 KB and is TOASTed for nearly the whole table. event.name is a literal in
  // every query here (never a bind param — unlike the tool dimension below), so it
  // can be a plain generated column with no configurability tradeoff.
  //
  // The scope (scope_name) dimension has been dropped from all filter params:
  // Claude Code emits exactly one scope name for all rows so the facet is
  // uninformative.
  //
  // Tool dimension is read from the stored generated column tool_name (added
  // in V17__log_records_tool_name_column.sql), generated from the literal
  // "tool_name" — TuningProperties.toolAttribute's default and what Claude
  // Code actually emits. Unlike event_name, :toolAttribute WAS a genuine bind
  // param before this column existed (there was no expression-index escape
  // hatch for it either — see the dropped V7 comment this replaces), so this
  // trades that runtime configurability for the same detoast fix: overriding
  // tuning.tool-attribute away from "tool_name" now requires a follow-up
  // migration rebuilding this column, the same tradeoff AGENTS.md documents
  // for api-request-cost-attribute and friends.
  // =========================================================================

  // Histogram — conditional-aggregate over a date_bin series.
  // Severity filter is intentionally omitted from the WHERE so all four series
  // are always populated (legend mutes client-side).
  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :windowStart) AS bucket,
        COUNT(*) FILTER (WHERE derived_severity = 'ERROR') AS error_count,
        COUNT(*) FILTER (WHERE derived_severity = 'WARN')  AS warn_count,
        COUNT(*) FILTER (WHERE derived_severity = 'INFO')  AS info_count,
        COUNT(*) FILTER (WHERE derived_severity = 'DEBUG') AS debug_count
      FROM log_records
      WHERE timestamp >= :windowStart
        AND timestamp <= :windowEnd
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      GROUP BY bucket
      ORDER BY bucket
      """, nativeQuery = true)
  List<Object[]> histogramBuckets(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("filters") String[] filters,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery);

  // Facet: severity counts (all other filters applied, severity excluded).
  @Query(value = """
      SELECT
        derived_severity,
        COUNT(*) AS row_count
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      GROUP BY derived_severity
      """, nativeQuery = true)
  List<Object[]> facetSeverity(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery);

  // Facet: event-name counts (all other filters applied, event excluded).
  @Query(value = """
      SELECT event_name AS facet_value, COUNT(*) AS row_count
      FROM log_records
      WHERE event_name IS NOT NULL
        AND event_name <> ''
        AND (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      GROUP BY facet_value
      ORDER BY row_count DESC
      LIMIT :facetLimit
      """, nativeQuery = true)
  List<Object[]> facetEvent(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("facetLimit") int facetLimit);

  // Facet: tool counts (all other filters applied, tool excluded).
  @Query(value = """
      SELECT
        tool_name AS facet_value,
        COUNT(*) AS row_count
      FROM log_records
      WHERE tool_name IS NOT NULL
        AND tool_name <> ''
        AND (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      GROUP BY facet_value
      ORDER BY row_count DESC
      LIMIT :facetLimit
      """, nativeQuery = true)
  List<Object[]> facetTool(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("facetLimit") int facetLimit);

  // Total count matching all filters — shared denominator for cursor and offset paging.
  @Query(value = """
      SELECT COUNT(*)
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      """, nativeQuery = true)
  long countFiltered(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery);

  // Cursor paging — rows strictly OLDER than (cursorTs, cursorId), newest first.
  // Used for scroll-back (before= param). The row-constructor comparison
  // (timestamp, id) < (ts, id) walks idx_log_records_ts_id directly instead of
  // the OR-form boundary, which the planner cannot map onto a composite index.
  @Query(value = """
      SELECT *
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (timestamp, id) < (CAST(:cursorTs AS timestamptz), :cursorId)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY timestamp DESC, id DESC
      LIMIT :pageLimit
      """, nativeQuery = true)
  List<LogRecordEntity> cursorBefore(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("cursorTs") Instant cursorTs,
      @Param("cursorId") long cursorId,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("pageLimit") int pageLimit);

  // Cursor paging — rows strictly NEWER than (cursorTs, cursorId), newest first.
  // Used for live tail (after= param). Returns [] when nothing new.
  @Query(value = """
      SELECT *
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (timestamp, id) > (CAST(:cursorTs AS timestamptz), :cursorId)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY timestamp DESC, id DESC
      LIMIT :pageLimit
      """, nativeQuery = true)
  List<LogRecordEntity> cursorAfter(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("cursorTs") Instant cursorTs,
      @Param("cursorId") long cursorId,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("pageLimit") int pageLimit);

  // Initial cursor page — no before/after boundary, newest first.
  @Query(value = """
      SELECT *
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY timestamp DESC, id DESC
      LIMIT :pageLimit
      """, nativeQuery = true)
  List<LogRecordEntity> cursorFirst(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("pageLimit") int pageLimit);

  // Offset paging — fixed sort: timestamp DESC, id DESC.
  @Query(value = """
      SELECT *
      FROM log_records
      WHERE (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR event_name = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR tool_name = ANY(CAST(:tools AS text[]))
        )
        AND (
          :fullTextQuery = ''
          OR body ILIKE '%' || :fullTextQuery || '%'
          OR attributes::text ILIKE '%' || :fullTextQuery || '%'
        )
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY timestamp DESC, id DESC
      LIMIT :pageSize OFFSET :pageOffset
      """, nativeQuery = true)
  List<LogRecordEntity> offsetPage(
      @Param("windowStart") Instant windowStart,
      @Param("windowEnd") Instant windowEnd,
      @Param("filters") String[] filters,
      @Param("severities") String[] severities,
      @Param("events") String[] events,
      @Param("tools") String[] tools,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("pageSize") int pageSize,
      @Param("pageOffset") int pageOffset);

  // ---------------------------------------------------------------------------
  // Trend report (GET /api/trends)
  // ---------------------------------------------------------------------------
  //
  // Half-open boundary convention (see MetricPointRepository#aggregateCostCurrentAndPriorTotals):
  // current = [:from, :to], prior = [:priorFrom, :from) -- a tool_result landing
  // exactly on :from counts once, in the current period only.

  // tool_errors and error_rate_pct's shared numerator/denominator, current and
  // prior period, in one pass over the tool_result rows in [:priorFrom, :to].
  // Filters on the event_name column (V16/V17), never attributes ->> 'event.name'
  // -- see this file's header comment.
  @Query(value = """
      SELECT
        COALESCE(COUNT(*) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::bigint
          AS current_total_calls,
        COALESCE(COUNT(*) FILTER (
          WHERE timestamp >= :from AND timestamp <= :to AND attributes ->> :successAttribute = 'false'
        ), 0)::bigint AS current_failures,
        COALESCE(COUNT(*) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::bigint
          AS prior_total_calls,
        COALESCE(COUNT(*) FILTER (
          WHERE timestamp >= :priorFrom AND timestamp < :from AND attributes ->> :successAttribute = 'false'
        ), 0)::bigint AS prior_failures
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :priorFrom
        AND timestamp <= :to
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureCurrentAndPriorTotals(
      @Param("eventName") String eventName,
      @Param("successAttribute") String successAttribute,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom);

  // session_failures: count of DISTINCT sessions with at least one failed
  // tool_result in the period, current and prior. session.id is read as a raw
  // jsonb extraction (no stored generated column on log_records for it, unlike
  // event_name/tool_name) -- the same literal-key pattern every other session
  // grouping in this file uses (see e.g. aggregateSessionCounts).
  @Query(value = """
      SELECT
        COALESCE(COUNT(DISTINCT attributes ->> 'session.id') FILTER (
          WHERE timestamp >= :from AND timestamp <= :to
        ), 0)::bigint AS current_session_failures,
        COALESCE(COUNT(DISTINCT attributes ->> 'session.id') FILTER (
          WHERE timestamp >= :priorFrom AND timestamp < :from
        ), 0)::bigint AS prior_session_failures
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :successAttribute = 'false'
        AND timestamp >= :priorFrom
        AND timestamp <= :to
      """, nativeQuery = true)
  List<Object[]> aggregateSessionFailuresCurrentAndPrior(
      @Param("eventName") String eventName,
      @Param("successAttribute") String successAttribute,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom);

  // ---------------------------------------------------------------------------
  // Trend report sparklines (7 points per side, one call per side)
  // ---------------------------------------------------------------------------
  //
  // Both queries return a zero-based bucket_index rather than a bucket Instant,
  // matching MetricPointRepository's trend-sparkline queries so the service can
  // zero-fill and combine them the same way.

  // Bucketed tool_result call and failure counts -- backs tool_errors' and
  // error_rate_pct's sparklines in one pass.
  @Query(value = """
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
          / :bucketSeconds)::int AS bucket_index,
        COUNT(*)::bigint AS total_calls,
        COUNT(*) FILTER (WHERE attributes ->> :successAttribute = 'false')::bigint AS failures
      FROM log_records
      WHERE event_name = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureTrend(
      @Param("eventName") String eventName,
      @Param("successAttribute") String successAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Bucketed count of distinct sessions with at least one failed tool_result --
  // backs session_failures' sparkline.
  @Query(value = """
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
          / :bucketSeconds)::int AS bucket_index,
        COUNT(DISTINCT attributes ->> 'session.id')::bigint AS failed_sessions
      FROM log_records
      WHERE event_name = :eventName
        AND attributes ->> :successAttribute = 'false'
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateSessionFailuresTrend(
      @Param("eventName") String eventName,
      @Param("successAttribute") String successAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);
}
