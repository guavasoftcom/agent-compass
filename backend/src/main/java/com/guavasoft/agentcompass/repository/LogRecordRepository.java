package com.guavasoft.agentcompass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.LogRecordEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface LogRecordRepository extends JpaRepository<LogRecordEntity, Long> {

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

  @Query(value = """
      SELECT
        COALESCE(attributes ->> :toolAttribute, 'unknown') AS tool,
        COUNT(*)                                            AS calls
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :since
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolCalls(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> :toolAttribute, 'unknown') AS tool,
        COUNT(*)                                            AS calls
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
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
        COALESCE(attributes ->> :toolAttribute, 'unknown') AS tool,
        COUNT(*)                                           AS calls,
        ROUND(AVG((attributes ->> 'duration_ms')::numeric))                                AS avg_ms,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY (attributes ->> 'duration_ms')::numeric)) AS p95_ms,
        ROUND(AVG((attributes ->> 'tool_result_size_bytes')::numeric))                     AS avg_out
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolPerformanceInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
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
        COALESCE(attributes ->> :toolAttribute, 'unknown')                     AS tool,
        COUNT(*)::bigint                                                       AS calls,
        SUM((attributes ->> 'tool_result_size_bytes')::numeric)::bigint        AS total_bytes,
        ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
          ORDER BY (attributes ->> 'tool_result_size_bytes')::numeric))::bigint AS p95_bytes
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND attributes ->> 'tool_result_size_bytes' IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY total_bytes DESC, tool ASC
      """, nativeQuery = true)
  List<Object[]> aggregateToolContextFootprintInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
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
  @Query(value = """
      WITH failure_events AS (
        SELECT
          COALESCE(attributes ->> :toolAttribute, 'unknown') AS tool,
          COALESCE(attributes ->> 'error_type', 'unknown')   AS error_type,
          COALESCE(attributes ->> 'error', '')               AS error_message,
          COALESCE(
            (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path',
            (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
            '')                                              AS scope
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
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
  // identifier
  // entirely bucket under 'unknown' so callers see them rather than silently
  // dropping the count.
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
            'unknown')                                                 AS identifier,
          attributes ->> 'session.id'                                  AS session_id,
          timestamp                                                    AS dispatched_at
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
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
        WHERE main_loop_turn.attributes ->> 'event.name' = :apiRequestEventName
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
      @Param("apiRequestEventName") String apiRequestEventName,
      @Param("modelAttribute") String modelAttribute,
      @Param("agentNameAttribute") String agentNameAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Counts skill invocations, grouped by the skill-name attribute and the model
  // that served the turn. Skills are emitted as api_request events (not
  // tool_result), so there is no tool_name filter here — only the event name and
  // the presence of the skill attribute. Those same rows carry the model
  // attribute directly, so no correlation is needed on this side. Row ordering
  // matches aggregateToolInvocationsByInnerAttributeAndModelInRange.
  @Query(value = """
      WITH skill_calls AS (
        SELECT
          COALESCE(NULLIF(attributes ->> :skillAttribute, ''), 'unknown') AS identifier,
          COALESCE(NULLIF(attributes ->> :modelAttribute, ''), 'unknown') AS model
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
          AND jsonb_exists(attributes, :skillAttribute)
          AND timestamp >= :start
          AND timestamp <= :end
      )
      SELECT
        identifier                                                      AS identifier,
        model                                                           AS model,
        COUNT(*)                                                        AS calls
      FROM skill_calls
      GROUP BY identifier, model
      ORDER BY SUM(COUNT(*)) OVER (PARTITION BY identifier) DESC, identifier, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateSkillInvocationsByModelInRange(
      @Param("eventName") String eventName,
      @Param("skillAttribute") String skillAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-tool success / failure split. success is stored as a JSON boolean;
  // ->>'success' returns
  // text, so 'false' is a failure and anything else (including NULL on rare
  // unlabeled rows) is
  // counted as a success. Returned as one row per tool with both counts so the
  // service can
  // derive failure_rate without a second query.
  @Query(value = """
      SELECT
        COALESCE(attributes ->> :toolAttribute, 'unknown')              AS tool,
        COUNT(*)                                                        AS calls,
        COUNT(*) FILTER (WHERE attributes ->> 'success' = 'false')      AS failures
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :since
      GROUP BY tool
      ORDER BY failures DESC, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureRates(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> :toolAttribute, 'unknown')              AS tool,
        COUNT(*)                                                        AS calls,
        COUNT(*) FILTER (WHERE attributes ->> 'success' = 'false')      AS failures
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool
      ORDER BY failures DESC, calls DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolFailureRatesInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'tool_name', 'unknown') AS tool,
        COALESCE(attributes ->> 'source', 'unknown')    AS source,
        COUNT(*)                                         AS count
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND attributes ->> 'decision' = 'reject'
        AND timestamp >= :since
      GROUP BY tool, source
      ORDER BY count DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolDenials(
      @Param("eventName") String eventName,
      @Param("since") Instant since);

  @Query(value = """
      SELECT
        COALESCE(attributes ->> 'tool_name', 'unknown') AS tool,
        COALESCE(attributes ->> 'source', 'unknown')    AS source,
        COUNT(*)                                         AS count
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND attributes ->> 'decision' = 'reject'
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY tool, source
      ORDER BY count DESC
      """, nativeQuery = true)
  List<Object[]> aggregateToolDenialsInRange(
      @Param("eventName") String eventName,
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
      WHERE attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY hookEvent, hookName
      ORDER BY blockingErrors DESC, total DESC
      """, nativeQuery = true)
  List<Object[]> aggregateHookExecutionsInRange(
      @Param("eventName") String eventName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :since) AS bucket,
        COALESCE(attributes ->> :toolAttribute, 'unknown')                 AS tool,
        COUNT(*)                                                           AS calls
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :since
      GROUP BY bucket, tool
      ORDER BY bucket, tool
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsTimeseries(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
      @Param("since") Instant since,
      @Param("bucketSeconds") long bucketSeconds);

  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(attributes ->> :toolAttribute, 'unknown')                 AS tool,
        COUNT(*)                                                           AS calls
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket, tool
      ORDER BY bucket, tool
      """, nativeQuery = true)
  List<Object[]> aggregateToolCallsTimeseriesInRange(
      @Param("eventName") String eventName,
      @Param("toolAttribute") String toolAttribute,
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
        WHERE attributes ->> 'event.name' = :eventName
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
  @Query(value = """
      WITH sized_calls AS (
        SELECT
          COALESCE(attributes ->> :toolAttribute, 'unknown')      AS tool,
          COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path',
                   ltrim(regexp_replace(
                     (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                     '^\\s*(cd\\s+[^&;|\\n]*(&&|;|\\n)\\s*)+',
                     '')),
                   '')                                            AS scope,
          ((attributes ->> 'tool_result_size_bytes')::numeric)::bigint AS bytes
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
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
        WHERE attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :eventName
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
  @Query(value = """
      SELECT
        COALESCE(attributes ->> :toolAttribute, 'unknown') AS tool,
        COALESCE((NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'file_path',
                 ltrim(regexp_replace(
                   (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                   '^\\s*(cd\\s+[^&;|\\n]*(&&|;|\\n)\\s*)+',
                   '')),
                 '')                                       AS scope,
        ((attributes ->> 'duration_ms')::numeric)::bigint            AS duration_ms,
        ((attributes ->> 'tool_result_size_bytes')::numeric)::bigint AS bytes
      FROM log_records
      WHERE attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :eventName
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
            WHEN attributes ->> :toolAttribute = 'Bash'
              THEN COALESCE(
                NULLIF(
                  split_part(
                    (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                    ' ',
                    1),
                  ''),
                '(no scope)')
            ELSE '(no scope)'
          END                                                            AS scope,
          timestamp
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
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
        SELECT session_id, tool, scope, MAX(run_length) AS longest_run
        FROM runs
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
            WHEN attributes ->> :toolAttribute = 'Bash'
              THEN COALESCE(
                NULLIF(
                  split_part(
                    (NULLIF(attributes ->> 'tool_input', ''))::jsonb ->> 'command',
                    ' ',
                    1),
                  ''),
                '(no scope)')
            ELSE '(no scope)'
          END                                                            AS scope,
          timestamp
        FROM log_records
        WHERE attributes ->> 'event.name' = :eventName
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
        SELECT session_id, tool, scope, MAX(run_length) AS longest_run
        FROM runs
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
  // userPromptCount is the total user_prompt event count. firstUserPrompt picks
  // the first *meaningful* prompt body: ARRAY_AGG collects every non-null prompt
  // text for the session ordered with bare slash commands ('/ship', optionally
  // trailing whitespace) sorted after every other prompt so a real prompt wins
  // whenever the session has one, falling back to the literal first prompt only
  // when every prompt in the session is a slash command; [1] takes the head of
  // that ordered array. Whitespace (including embedded newlines from multi-line
  // prompts) collapses to single spaces and the result truncates to 200
  // characters, both in SQL so the service never handles untruncated prompt
  // text. Sessions with no prompt attribute value at all (NULL after NULLIF)
  // still count toward user_prompt_count but contribute no candidate to the
  // ARRAY_AGG, leaving firstUserPrompt null. Sessions with no matching log
  // records at all are omitted; the service defaults missing entries to 0 /
  // null.
  @Query(value = """
      SELECT
          lr.attributes ->> 'session.id'                                           AS session_id,
          COUNT(*) FILTER (WHERE lr.attributes ->> 'event.name' = :toolEventName)  AS tool_call_count,
          COUNT(*) FILTER (WHERE lr.attributes ->> 'event.name' = :toolDecisionEventName
                             AND lr.attributes ->> 'decision' = 'reject')           AS denial_count,
          COUNT(*) FILTER (WHERE lr.attributes ->> 'event.name' = :userPromptEventName) AS user_prompt_count,
          (ARRAY_AGG(
              left(regexp_replace(NULLIF(lr.attributes ->> :promptAttribute, ''), '\\s+', ' ', 'g'), 200)
              ORDER BY (NULLIF(lr.attributes ->> :promptAttribute, '') ~ '^\\s*/\\S*\\s*$') ASC, lr.timestamp ASC
            ) FILTER (WHERE lr.attributes ->> 'event.name' = :userPromptEventName
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
      WHERE lr.attributes ->> 'event.name' = :eventName
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
      WHERE attributes ->> 'event.name' = :apiRequestEventName
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
      WHERE attributes ->> 'event.name' = :apiRequestEventName
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
        AND lr.attributes ->> 'event.name' = :userPromptEventName
        AND NULLIF(lr.attributes ->> :promptAttribute, '') IS NOT NULL
      ORDER BY lr.trace_id, lr.timestamp ASC, lr.id ASC
      """, nativeQuery = true)
  List<Object[]> findFirstUserPromptByTraceIds(
      @Param("traceIds") Collection<String> traceIds,
      @Param("userPromptEventName") String userPromptEventName,
      @Param("promptAttribute") String promptAttribute,
      @Param("previewLength") int previewLength);

  // Per-trace model spend for the given traces, straight off the trace_costs
  // view (V14) — the summed cost_usd of the api_request logs stamped with each
  // trace id. This is the SAME authoritative figure TraceSummary.totalCostUsd
  // resolves to (the trace-list queries re-run the identical predicates via a
  // LEFT JOIN LATERAL for pushdown rather than joining this view directly, but
  // it's the same rows/grouping), which is the point: the Sessions prompt
  // timeline reuses it so a turn's cost and the cost shown on the trace that
  // turn links to are one number, not two estimates of one number -- though
  // LogService#applyTraceCorrelatedCosts still has to bill each trace's number
  // to only one turn when several turns share a trace. Traces with no
  // correlated request log are simply absent from the result.
  // Returns (trace_id, cost_usd).
  @Query(value = """
      SELECT trace_id, cost_usd
      FROM trace_costs
      WHERE trace_id IN :traceIds
      """, nativeQuery = true)
  List<Object[]> findCostByTraceIds(@Param("traceIds") Collection<String> traceIds);

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
      WHERE lr.attributes ->> 'event.name' = :toolEventName
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
  // The scope (scope_name) dimension has been dropped from all filter params:
  // Claude Code emits exactly one scope name for all rows so the facet is
  // uninformative.
  //
  // Tool dimension uses :toolAttribute (defaults to "tool_name") bound from
  // TuningProperties so the key is not hardcoded.
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
      @Param("fullTextQuery") String fullTextQuery);

  // Facet: event-name counts (all other filters applied, event excluded).
  @Query(value = """
      SELECT attributes ->> 'event.name' AS facet_value, COUNT(*) AS row_count
      FROM log_records
      WHERE attributes ->> 'event.name' IS NOT NULL
        AND attributes ->> 'event.name' <> ''
        AND (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("facetLimit") int facetLimit);

  // Facet: tool counts (all other filters applied, tool excluded).
  // Tool attribute key is bound via :toolAttribute (defaults to "tool_name").
  @Query(value = """
      SELECT
        attributes ->> :toolAttribute AS facet_value,
        COUNT(*) AS row_count
      FROM log_records
      WHERE attributes ->> :toolAttribute IS NOT NULL
        AND attributes ->> :toolAttribute <> ''
        AND (CAST(:windowStart AS timestamptz) IS NULL OR timestamp >= :windowStart)
        AND (CAST(:windowEnd AS timestamptz) IS NULL OR timestamp <= :windowEnd)
        AND (
          cardinality(CAST(:severities AS text[])) = 0
          OR derived_severity = ANY(CAST(:severities AS text[]))
        )
        AND (
          cardinality(CAST(:events AS text[])) = 0
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
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
          OR attributes ->> 'event.name' = ANY(CAST(:events AS text[]))
        )
        AND (
          cardinality(CAST(:tools AS text[])) = 0
          OR attributes ->> :toolAttribute = ANY(CAST(:tools AS text[]))
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
      @Param("toolAttribute") String toolAttribute,
      @Param("fullTextQuery") String fullTextQuery,
      @Param("pageSize") int pageSize,
      @Param("pageOffset") int pageOffset);
}
