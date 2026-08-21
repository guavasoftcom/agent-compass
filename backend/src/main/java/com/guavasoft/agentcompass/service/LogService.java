package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.mapper.LogRecordMapper;
import com.guavasoft.agentcompass.model.BashCommandCoverage;
import com.guavasoft.agentcompass.model.BashCommandHotspot;
import com.guavasoft.agentcompass.model.EditFailureLoop;
import com.guavasoft.agentcompass.model.FacetValue;
import com.guavasoft.agentcompass.model.HistogramBucket;
import com.guavasoft.agentcompass.model.HookExecutionSummary;
import com.guavasoft.agentcompass.model.IdentifierUsageCount;
import com.guavasoft.agentcompass.model.LogCursor;
import com.guavasoft.agentcompass.model.LogCursorPage;
import com.guavasoft.agentcompass.model.LogFacets;
import com.guavasoft.agentcompass.model.LogHistogram;
import com.guavasoft.agentcompass.model.LogPage;
import com.guavasoft.agentcompass.model.LogQueryCriteria;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.PathNearMiss;
import com.guavasoft.agentcompass.model.RedundantFileRead;
import com.guavasoft.agentcompass.model.SessionApiRequest;
import com.guavasoft.agentcompass.model.SessionPrompt;
import com.guavasoft.agentcompass.model.SessionPromptToolCount;
import com.guavasoft.agentcompass.model.SessionTokenBreakdown;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolCallTimeseries;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailure;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolPerformance;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogService {

  private static final String OTHER_BUCKET_LABEL = "Other";
  private static final long MIN_BUCKET_SECONDS = 60L;
  private static final int TARGET_BUCKETS_PER_WINDOW = 40;
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int DEFAULT_TIMESERIES_TOP_N = 8;
  private static final int BASH_HOTSPOT_LIMIT = 10;
  private static final int OVERSIZED_RESULT_LIMIT = 10;
  // bytes → estimated context tokens. The ~4-bytes-per-token rule of thumb is
  // accurate enough to RANK tools against each other, which is the only claim the
  // context-footprint card makes; it is not a billed figure and must never be
  // added to or compared against the exact counts on /api/sessions/token-usage.
  private static final int BYTES_PER_ESTIMATED_TOKEN = 4;
  private static final int REDUNDANT_READ_LIMIT = 10;
  private static final int EDIT_FAILURE_LOOP_LIMIT = 10;
  private static final int SLOW_AND_LARGE_LIMIT = 10;
  private static final int TOOL_REPEAT_LIMIT = 15;
  private static final long SLOW_AND_LARGE_MIN_DURATION_MS = 1_000L;
  private static final long SLOW_AND_LARGE_MIN_BYTES = 4_000L;
  private static final int FAILED_READ_PATH_LIMIT = 25;
  private static final int SUCCESSFUL_READ_PATH_LIMIT = 1_000;
  // A retyped path differs by a transposed/dropped character or two; anything
  // farther apart is more likely a genuinely different file than a typo.
  private static final int NEAR_MISS_MAX_EDIT_DISTANCE = 3;

  // Attribute key carrying the model that served a claude_code.token.usage point.
  // Not on TuningProperties: mirrors MetricService.MODEL_ATTRIBUTE, a structural
  // OTLP semantic-convention key rather than a deployment-tunable event/attribute
  // name.
  private static final String MODEL_ATTRIBUTE = "model";

  // How far apart an api_request_body log and an api_request's derived issue instant
  // may sit and still be treated as the same LLM call. Measured over three days of
  // live data the two sides are effectively simultaneous -- every body with a real
  // counterpart landed within 100ms of it (p95 3ms) -- while a body whose api_request
  // never arrived sat more than 5s from the nearest candidate. One second sits in
  // that empty gap with an order of magnitude of headroom on both sides, so an
  // orphaned body stays on its original span instead of stealing an unrelated call's.
  private static final long BODY_PAIRING_TOLERANCE_MILLIS = 1_000L;

  // Joins prompt id and model into one partition key. A NUL byte cannot occur in
  // either value, so the two halves can never run together into a colliding key.
  private static final String PARTITION_KEY_SEPARATOR = "\0";

  // Raw values of the token-type attribute (TuningProperties#getTokenTypeAttribute).
  // Mirrors MetricService's identically-named constants -- these are the actual
  // spellings Claude Code emits (camelCase, confirmed against MetricService's
  // existing token-usage-by-type switch), not the snake_case forms an earlier
  // draft of the API spec guessed at.
  private static final String INPUT_TOKEN_TYPE = "input";
  private static final String OUTPUT_TOKEN_TYPE = "output";
  private static final String CACHE_CREATION_TOKEN_TYPE = "cacheCreation";
  private static final String CACHE_READ_TOKEN_TYPE = "cacheRead";
  private static final int TOKEN_BREAKDOWN_KIND_COUNT = 4;

  private final LogRecordRepository logRecordRepository;
  private final SpanRepository spanRepository;
  private final MetricPointRepository metricPointRepository;
  private final LogRecordMapper logRecordMapper;
  private final TuningProperties tuningProperties;

  // Claude Code >= 2.1.152 stamps OTLP trace context onto its event logs, so every
  // log belonging to a trace carries that trace's trace_id. We fetch by trace_id,
  // then re-point each log onto the fine-grained span that actually did the work
  // (see resolveLeafSpans); the frontend attaches each log to its span by span_id.
  public List<LogRecord> logsForTrace(String traceId) {
    List<LogRecordEntity> logRecordEntities = logRecordRepository.findByTraceIdOrderByTimestampAsc(traceId);
    List<LogRecord> logRecords = logRecordMapper.toLogRecords(logRecordEntities);
    List<SpanEntity> traceSpans = spanRepository.findByTraceIdOrderByStartTimestampAsc(traceId);
    resolveLeafSpans(logRecords, traceSpans);
    return logRecords;
  }

  // Claude Code stamps most event logs with a coarse span id: tool_result and
  // api_request* land on whichever span was merely *open* when they were emitted,
  // rather than on the tool.execution / llm_request span that did the work
  // (tool_decision is the exception — it correctly lands on its
  // tool.blocked_on_user span). Both signals carry exact correlation keys, so we
  // re-point each stranded log onto its true leaf span: tool logs via tool_use_id,
  // LLM logs via request_id, api_request_body via the pairing below. Logs with no
  // matching key (hooks, user_prompt) keep their original span id.
  //
  // A log is only moved when the span it arrived on CONTAINS the span its key
  // points to. That containment test is what separates the two cases: the open
  // span is always an ancestor of the leaf that did the work, whereas
  // tool_decision's tool.blocked_on_user span is a sibling of the tool.execution
  // its tool_use_id names, so it correctly stays put. Testing ancestry rather than
  // "is this the trace root" also covers subagents: work dispatched through the
  // Task tool runs with that tool's execution span open, so its logs are stamped
  // with a span that has a parent, and a root-only test left every one of them
  // piled on the Task span while the subagent's own spans showed none.
  private void resolveLeafSpans(List<LogRecord> logRecords, List<SpanEntity> traceSpans) {
    Map<String, String> parentSpanIdBySpanId = traceSpans.stream()
        .filter(span -> span.getParentSpanId() != null)
        .collect(Collectors.toMap(SpanEntity::getSpanId, SpanEntity::getParentSpanId, (first, second) -> first));

    String toolCallIdAttribute = tuningProperties.getToolCallIdAttribute();
    String requestIdAttribute = tuningProperties.getRequestIdAttribute();
    Map<String, String> spanIdByToolCallId = indexSpanIdByAttribute(
        traceSpans, tuningProperties.getToolExecutionSpanName(), toolCallIdAttribute);
    Map<String, String> spanIdByRequestId = indexSpanIdByAttribute(
        traceSpans, tuningProperties.getLlmRequestSpanName(), requestIdAttribute);
    Map<LogRecord, String> requestIdByBodyLog = pairApiRequestBodiesToRequests(logRecords);

    for (LogRecord logRecord : logRecords) {
      Map<String, Object> attributes = logRecord.getAttributes();
      if (attributes == null) {
        continue;
      }
      String resolvedSpanId = spanIdByToolCallId.get(stringAttribute(attributes, toolCallIdAttribute));
      if (resolvedSpanId == null) {
        resolvedSpanId = spanIdByRequestId.get(stringAttribute(attributes, requestIdAttribute));
      }
      if (resolvedSpanId == null) {
        resolvedSpanId = spanIdByRequestId.get(requestIdByBodyLog.get(logRecord));
      }
      if (resolvedSpanId == null || resolvedSpanId.equals(logRecord.getSpanId())) {
        continue;
      }
      if (logRecord.getSpanId() == null || containsSpan(logRecord.getSpanId(), resolvedSpanId, parentSpanIdBySpanId)) {
        logRecord.setSpanId(resolvedSpanId);
      }
    }
  }

  // Whether candidateAncestorSpanId is a strict ancestor of spanId, by walking the
  // parent chain up from spanId. The visited set keeps a malformed trace whose
  // parent links form a cycle from spinning here.
  private static boolean containsSpan(
      String candidateAncestorSpanId, String spanId, Map<String, String> parentSpanIdBySpanId) {
    Set<String> visitedSpanIds = new HashSet<>();
    String ancestorSpanId = parentSpanIdBySpanId.get(spanId);
    while (ancestorSpanId != null && visitedSpanIds.add(ancestorSpanId)) {
      if (ancestorSpanId.equals(candidateAncestorSpanId)) {
        return true;
      }
      ancestorSpanId = parentSpanIdBySpanId.get(ancestorSpanId);
    }
    return false;
  }

  // An api_request_body log carries no request_id of its own, so it has to borrow one
  // from the api_request describing the same call. Pairing the two by "the next
  // api_request in event.sequence order" broke whenever a turn dispatched several
  // calls at once: Claude Code logs all the bodies up front, so every one of them
  // picked the same following request and their logs piled onto one llm_request span
  // while its concurrent siblings showed none.
  //
  // Both sides do carry enough to pair exactly. A body is logged when its call is
  // issued; an api_request is logged when that call completes and reports how long it
  // took, so subtracting the duration recovers the same issue instant -- it
  // reproduces the llm_request span's start timestamp to the millisecond. So we match
  // the two instants within a prompt.id + model partition, closest pair first, and let
  // each api_request be claimed only once, which keeps concurrent calls apart. Bodies
  // left unclaimed (no api_request logged for them) resolve to nothing and stay on the
  // span they arrived on, exactly like a log with no correlation key at all.
  private Map<LogRecord, String> pairApiRequestBodiesToRequests(List<LogRecord> logRecords) {
    String eventNameAttribute = tuningProperties.getEventNameAttribute();
    String apiRequestEventName = tuningProperties.getApiRequestEventName();
    String apiRequestBodyEventName = tuningProperties.getApiRequestBodyEventName();
    String promptIdAttribute = tuningProperties.getPromptIdAttribute();
    String requestIdAttribute = tuningProperties.getRequestIdAttribute();
    String durationAttribute = tuningProperties.getApiRequestDurationAttribute();

    Map<String, List<LogRecord>> bodyLogsByPartition = new HashMap<>();
    Map<String, List<IssuedApiRequest>> requestsByPartition = new HashMap<>();
    for (LogRecord logRecord : logRecords) {
      Map<String, Object> attributes = logRecord.getAttributes();
      if (attributes == null || logRecord.getTimestamp() == null) {
        continue;
      }
      Object eventName = attributes.get(eventNameAttribute);
      boolean isRequestBody = apiRequestBodyEventName.equals(eventName);
      if (!isRequestBody && !apiRequestEventName.equals(eventName)) {
        continue;
      }
      String partitionKey = bodyPairingPartitionKey(attributes, promptIdAttribute);
      if (partitionKey == null) {
        continue;
      }
      if (isRequestBody) {
        bodyLogsByPartition.computeIfAbsent(partitionKey, key -> new ArrayList<>()).add(logRecord);
      } else {
        String requestId = stringAttribute(attributes, requestIdAttribute);
        Long durationMillis = longAttribute(attributes, durationAttribute);
        if (requestId != null && durationMillis != null) {
          requestsByPartition.computeIfAbsent(partitionKey, key -> new ArrayList<>())
              .add(new IssuedApiRequest(requestId, logRecord.getTimestamp().minusMillis(durationMillis)));
        }
      }
    }

    Map<LogRecord, String> requestIdByBodyLog = new IdentityHashMap<>();
    for (Map.Entry<String, List<LogRecord>> partition : bodyLogsByPartition.entrySet()) {
      List<IssuedApiRequest> requests = requestsByPartition.get(partition.getKey());
      if (requests != null) {
        claimNearestRequests(partition.getValue(), requests, requestIdByBodyLog);
      }
    }
    return requestIdByBodyLog;
  }

  // Assigns each body log the api_request issued closest to it in time, one body per
  // request. Working through the candidate pairs closest-first (rather than body by
  // body) means the tightest, most certain pairings claim their request before a
  // looser one can take it. Ties keep encounter order, so simultaneous calls fall back
  // to the order Claude Code logged them in.
  private static void claimNearestRequests(
      List<LogRecord> bodyLogs, List<IssuedApiRequest> requests, Map<LogRecord, String> requestIdByBodyLog) {
    List<BodyRequestPairing> candidates = new ArrayList<>();
    for (LogRecord bodyLog : bodyLogs) {
      for (IssuedApiRequest request : requests) {
        long offsetMillis = Math.abs(Duration.between(request.issuedAt(), bodyLog.getTimestamp()).toMillis());
        if (offsetMillis <= BODY_PAIRING_TOLERANCE_MILLIS) {
          candidates.add(new BodyRequestPairing(bodyLog, request.requestId(), offsetMillis));
        }
      }
    }
    candidates.sort(Comparator.comparingLong(BodyRequestPairing::offsetMillis));

    Set<String> claimedRequestIds = new HashSet<>();
    for (BodyRequestPairing candidate : candidates) {
      if (!requestIdByBodyLog.containsKey(candidate.bodyLog()) && claimedRequestIds.add(candidate.requestId())) {
        requestIdByBodyLog.put(candidate.bodyLog(), candidate.requestId());
      }
    }
  }

  // Body-to-request pairing only ever compares calls from the same turn on the same
  // model. prompt.id alone is too coarse -- one turn routinely fans out to several
  // models at once -- and model is the one discriminator both sides always carry.
  private static String bodyPairingPartitionKey(Map<String, Object> attributes, String promptIdAttribute) {
    String promptId = stringAttribute(attributes, promptIdAttribute);
    String model = stringAttribute(attributes, MODEL_ATTRIBUTE);
    if (promptId == null || model == null) {
      return null;
    }
    return promptId + PARTITION_KEY_SEPARATOR + model;
  }

  // One api_request reduced to what the pairing needs: the request id a body log will
  // borrow, and the instant the call was issued (its completion time less its duration).
  private record IssuedApiRequest(String requestId, Instant issuedAt) { }

  // A candidate body-to-request pairing, ranked by how far apart the two instants sit.
  private record BodyRequestPairing(LogRecord bodyLog, String requestId, long offsetMillis) { }

  private static Long longAttribute(Map<String, Object> attributes, String key) {
    Object value = attributes.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  // Maps a correlation-key value to the span id of the named leaf span carrying it.
  // Only spans of spanName are indexed: the tool_use_id key lives on both the
  // claude_code.tool wrapper and its claude_code.tool.execution child, and we want
  // logs to attach to the execution leaf, not the wrapper. First write wins on the
  // rare duplicate key.
  private static Map<String, String> indexSpanIdByAttribute(
      List<SpanEntity> spans, String spanName, String attributeKey) {
    Map<String, String> spanIdByAttributeValue = new HashMap<>();
    for (SpanEntity span : spans) {
      if (!spanName.equals(span.getName()) || span.getAttributes() == null) {
        continue;
      }
      String attributeValue = stringAttribute(span.getAttributes(), attributeKey);
      if (attributeValue != null) {
        spanIdByAttributeValue.putIfAbsent(attributeValue, span.getSpanId());
      }
    }
    return spanIdByAttributeValue;
  }

  private static String stringAttribute(Map<String, Object> attributes, String key) {
    Object value = attributes.get(key);
    return value instanceof String text && !text.isEmpty() ? text : null;
  }

  // Full prompt timeline for one session (the Sessions grid's expandable row).
  // Not window-scoped; result size is clamped the same way PageBounds.MAXIMUM_PAGE_SIZE
  // clamps every other list endpoint so a pathologically long session can't
  // return an unbounded number of rows.
  //
  // Each prompt is additionally enriched with per-turn rollups (model, costUsd,
  // tokens, tools).
  //
  // Model, cost and tokens come from the turn's OWN api_request logs wherever
  // they exist, joined by prompt id — exact per-call figures, grouped by the turn
  // that actually issued each request. Turns with no such logs (event logging
  // disabled, an older CLI, or rows predating prompt-id stamping) fall back to
  // the older interval attribution below, and say so via SessionPrompt's
  // attribution field rather than quietly reporting a different kind of number.
  // The fallback is per-turn, not per-session: a session that gained api_request
  // logs partway through reports each turn however that turn can be measured.
  //
  // Tools still use interval attribution unconditionally — tool_result events
  // carry no prompt id, so there is nothing exact to join them on.
  //
  // Interval attribution: turn i spans [prompt_i.timestamp,
  // prompt_{i+1}.timestamp), and the last turn is open-ended -- UNLESS this
  // session has more than MAXIMUM_PAGE_SIZE prompts, in which case the "+1 probe"
  // row fetched below closes it instead, so events belonging to a truncated
  // (not-returned) 501st+ turn are never misattributed to the 500th.
  public List<SessionPrompt> promptsForSession(String sessionId) {
    List<Object[]> probeRows = logRecordRepository.findPromptsForSession(
        sessionId,
        tuningProperties.getUserPromptEventName(),
        tuningProperties.getPromptAttribute(),
        tuningProperties.getPromptIdAttribute(),
        PageBounds.MAXIMUM_PAGE_SIZE + 1);
    if (probeRows.isEmpty()) {
      return List.of();
    }

    boolean truncated = probeRows.size() > PageBounds.MAXIMUM_PAGE_SIZE;
    List<Object[]> promptRows = truncated ? probeRows.subList(0, PageBounds.MAXIMUM_PAGE_SIZE) : probeRows;
    Instant turnsEndBoundary = truncated ? (Instant) probeRows.get(PageBounds.MAXIMUM_PAGE_SIZE)[0] : null;

    List<Instant> turnStartTimestamps = promptRows.stream().map(row -> (Instant) row[0]).toList();
    TurnTokenRollup turnTokenRollup = resolveModelAndTokensPerTurn(sessionId, turnStartTimestamps, turnsEndBoundary);
    Map<Integer, Double> costByTurn = resolveCostPerTurn(sessionId, turnStartTimestamps, turnsEndBoundary);
    applyTraceCorrelatedCosts(promptRows, costByTurn);
    Map<Integer, List<SessionPromptToolCount>> toolsByTurn =
        resolveToolsPerTurn(sessionId, turnStartTimestamps, turnsEndBoundary);
    Map<String, ApiRequestTurnRollup> requestRollupsByPromptId = resolveApiRequestTurns(sessionId);

    List<SessionPrompt> prompts = new ArrayList<>(promptRows.size());
    for (int turnIndex = 0; turnIndex < promptRows.size(); turnIndex++) {
      Object[] row = promptRows.get(turnIndex);
      String promptId = (String) row[PROMPT_ROW_PROMPT_ID];
      ApiRequestTurnRollup requestRollup =
          promptId == null ? null : requestRollupsByPromptId.get(promptId);
      prompts.add(requestRollup != null
          ? new SessionPrompt(
              (Instant) row[0],
              (String) row[1],
              (String) row[2],
              requestRollup.model(),
              requestRollup.costUsd(),
              requestRollup.tokens(),
              toolsByTurn.getOrDefault(turnIndex, List.of()),
              promptId,
              requestRollup.requestCount(),
              SessionPrompt.TurnAttribution.REQUEST)
          : new SessionPrompt(
              (Instant) row[0],
              (String) row[1],
              (String) row[2],
              turnTokenRollup.modelByTurn().get(turnIndex),
              costByTurn.get(turnIndex),
              turnTokenRollup.tokensByTurn().get(turnIndex),
              toolsByTurn.getOrDefault(turnIndex, List.of()),
              promptId,
              0L,
              SessionPrompt.TurnAttribution.INTERVAL));
    }
    return prompts;
  }

  // Exact per-turn rollup summed from a session's api_request logs, keyed by the
  // prompt id of the turn that issued them.
  private record ApiRequestTurnRollup(
      long requestCount, String model, Double costUsd, SessionTokenBreakdown tokens, long durationMs) {}

  private Map<String, ApiRequestTurnRollup> resolveApiRequestTurns(String sessionId) {
    List<Object[]> rows = logRecordRepository.aggregateApiRequestTurnsForSession(
        sessionId,
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getPromptIdAttribute(),
        tuningProperties.getApiRequestCostAttribute(),
        tuningProperties.getModelAttribute());

    Map<String, ApiRequestTurnRollup> rollupsByPromptId = new HashMap<>();
    for (Object[] row : rows) {
      rollupsByPromptId.put((String) row[0], new ApiRequestTurnRollup(
          ((Number) row[1]).longValue(),
          (String) row[8],
          row[6] == null ? null : ((Number) row[6]).doubleValue(),
          new SessionTokenBreakdown(
              ((Number) row[2]).longValue(),
              ((Number) row[3]).longValue(),
              ((Number) row[4]).longValue(),
              ((Number) row[5]).longValue()),
          ((Number) row[7]).longValue()));
    }
    return rollupsByPromptId;
  }

  // Individual LLM requests for one session, oldest first — the prompt
  // timeline's per-turn drill-down. Not window-scoped, matching
  // promptsForSession; clamped by the same shared page ceiling so a long session
  // cannot return an unbounded list.
  public List<SessionApiRequest> requestsForSession(String sessionId) {
    List<Object[]> rows = logRecordRepository.findApiRequestsForSession(
        sessionId,
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getPromptIdAttribute(),
        tuningProperties.getRequestIdAttribute(),
        tuningProperties.getApiRequestCostAttribute(),
        tuningProperties.getModelAttribute(),
        PageBounds.MAXIMUM_PAGE_SIZE);

    List<SessionApiRequest> requests = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      requests.add(new SessionApiRequest(
          (String) row[0],
          (Instant) row[1],
          (String) row[2],
          (String) row[3],
          new SessionTokenBreakdown(
              ((Number) row[4]).longValue(),
              ((Number) row[5]).longValue(),
              ((Number) row[6]).longValue(),
              ((Number) row[7]).longValue()),
          row[8] == null ? null : ((Number) row[8]).doubleValue(),
          row[9] == null ? null : ((Number) row[9]).longValue(),
          (String) row[10],
          (String) row[11],
          (String) row[12]));
    }
    return requests;
  }

  // Turn attribution for the prompt-timeline rollups: assigns eventTimestamp to
  // the zero-based index of the turn whose interval
  // [turnStartTimestamps.get(i), turnStartTimestamps.get(i + 1)) contains it. The
  // last turn is open-ended unless turnsEndBoundary is non-null (see
  // promptsForSession), in which case it closes the last turn there instead.
  // Returns -1 for a timestamp before the first turn or at/after
  // turnsEndBoundary -- callers drop those rows rather than attribute them. The
  // three enrichment queries (findCostPointsForSession, findTokenPointsForSession,
  // findToolEventsForSession) already filter to this same
  // [turnStartTimestamps.get(0), turnsEndBoundary) range in SQL, so in practice
  // neither -1 branch below is reached for rows coming from them; both stay as a
  // defensive backstop (and turnIndexForTimestamp has no other caller today) for
  // the mixed-precision/boundary case rather than trusting the SQL bound alone.
  //
  // turnStartTimestamps can contain duplicate timestamps if two user_prompt
  // events land at the identical instant (findPromptsForSession orders by
  // timestamp ASC, id ASC to make that case deterministic); Collections.binarySearch
  // does not document which duplicate it returns, so an event exactly on a
  // duplicated boundary attributes to *some* one of those duplicate turns rather
  // than a specific one -- rare enough (sub-millisecond-identical prompts) that a
  // full dedupe pass isn't worth it here.
  private static int turnIndexForTimestamp(
      List<Instant> turnStartTimestamps, Instant turnsEndBoundary, Instant eventTimestamp) {
    if (eventTimestamp.isBefore(turnStartTimestamps.get(0))) {
      return -1;
    }
    if (turnsEndBoundary != null && !eventTimestamp.isBefore(turnsEndBoundary)) {
      return -1;
    }
    int searchIndex = Collections.binarySearch(turnStartTimestamps, eventTimestamp);
    return searchIndex >= 0 ? searchIndex : -searchIndex - 2;
  }

  // Column indexes on a findPromptsForSession row.
  private static final int PROMPT_ROW_TRACE_ID = 2;
  private static final int PROMPT_ROW_PROMPT_ID = 3;

  // Overrides the metric-bucketed per-turn cost with the turn trace's own cost
  // wherever the turn has a trace id that the trace_costs view knows.
  //
  // Both numbers describe the same requests, but they bucket them differently:
  // resolveCostPerTurn assigns a cost point to whichever turn interval its
  // timestamp falls in, so a request that lands after the next prompt was typed
  // is billed to that next turn, while the trace id says which turn actually
  // issued it. That boundary skew is what made a prompt's cost disagree with the
  // cost shown on the trace it links to (locally: exact agreement on ~8 of 10
  // turns, off by a whole request on the rest). Correlating by trace id removes
  // the disagreement by construction — same rows, same grouping key, one number.
  //
  // Turn-to-trace is not 1:1: several turns in a row can share one trace (a
  // bare slash command immediately followed by its real prompt, both landing
  // on the same claude_code.interaction trace before Claude Code closes it).
  // Billing every one of those turns the trace's full cost would double- (or
  // triple-) count it, so each trace is billed exactly once, to the FIRST
  // (earliest / lowest turnIndex) turn that carries it — promptRows is already
  // ordered oldest-first (findPromptsForSession: timestamp ASC, id ASC). Later
  // turns sharing the trace have their cost explicitly cleared rather than
  // left at whatever resolveCostPerTurn's time-bucketing assigned them --
  // leaving that fallback value in place would silently reintroduce the same
  // double-count through the other rollup. This restores the invariant that
  // summing per-turn costs over a session equals summing trace_costs over the
  // session's distinct traces.
  //
  // Turns predating trace correlation (no trace id, or no api_request log
  // carrying one) keep the metric-bucketed value: there is no trace to
  // contradict, so the existing behaviour is left alone rather than zeroed.
  private void applyTraceCorrelatedCosts(List<Object[]> promptRows, Map<Integer, Double> costByTurn) {
    List<String> traceIds = promptRows.stream()
        .map(row -> (String) row[PROMPT_ROW_TRACE_ID])
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (traceIds.isEmpty()) {
      return;
    }
    Map<String, Double> costByTraceId = new HashMap<>();
    for (Object[] costRow : logRecordRepository.findCostByTraceIds(traceIds)) {
      if (costRow[1] != null) {
        costByTraceId.put((String) costRow[0], ((Number) costRow[1]).doubleValue());
      }
    }
    Set<String> traceIdsAlreadyBilled = new HashSet<>();
    for (int turnIndex = 0; turnIndex < promptRows.size(); turnIndex++) {
      String traceId = (String) promptRows.get(turnIndex)[PROMPT_ROW_TRACE_ID];
      Double traceCost = traceId == null ? null : costByTraceId.get(traceId);
      if (traceCost == null) {
        continue;
      }
      if (traceIdsAlreadyBilled.add(traceId)) {
        costByTurn.put(turnIndex, traceCost);
      } else {
        costByTurn.remove(turnIndex);
      }
    }
  }

  // Per-turn cost rollup: SUM(value_delta) of the configured cost-usage metric
  // for this session, bucketed into the turn whose interval contains each point.
  // Superseded per-turn by applyTraceCorrelatedCosts wherever a trace exists.
  private Map<Integer, Double> resolveCostPerTurn(
      String sessionId, List<Instant> turnStartTimestamps, Instant turnsEndBoundary) {
    List<Object[]> costPointRows = metricPointRepository.findCostPointsForSession(
        tuningProperties.getCostUsageMetric(), sessionId, turnStartTimestamps.get(0), turnsEndBoundary);

    Map<Integer, Double> costByTurn = new HashMap<>();
    for (Object[] row : costPointRows) {
      int turnIndex = turnIndexForTimestamp(turnStartTimestamps, turnsEndBoundary, (Instant) row[0]);
      if (turnIndex < 0 || row[1] == null) {
        continue;
      }
      costByTurn.merge(turnIndex, ((Number) row[1]).doubleValue(), Double::sum);
    }
    return costByTurn;
  }

  // Per-turn model dominance AND token-type breakdown, from one fetch of this
  // session's token points (carrying model, type, and value_delta per row) so the
  // two rollups share a single query rather than fetching the same rows twice.
  private record TurnTokenRollup(
      Map<Integer, String> modelByTurn, Map<Integer, SessionTokenBreakdown> tokensByTurn) {}

  // Sums token value_delta per (turn, model) to pick the model with the largest
  // sum per turn, AND per (turn, type) to build the turn's four-way token
  // breakdown -- both from the rows landing in that turn's interval. A turn with
  // no token points has no entry in either map (the caller falls back to null).
  private TurnTokenRollup resolveModelAndTokensPerTurn(
      String sessionId, List<Instant> turnStartTimestamps, Instant turnsEndBoundary) {
    List<Object[]> tokenPointRows = metricPointRepository.findTokenPointsForSession(
        tuningProperties.getTokenUsageMetric(), sessionId, MODEL_ATTRIBUTE, tuningProperties.getTokenTypeAttribute(),
        turnStartTimestamps.get(0), turnsEndBoundary);

    Map<Integer, Map<String, Long>> tokensByModelPerTurn = new HashMap<>();
    Map<Integer, long[]> tokenTypeTotalsPerTurn = new HashMap<>();
    for (Object[] row : tokenPointRows) {
      int turnIndex = turnIndexForTimestamp(turnStartTimestamps, turnsEndBoundary, (Instant) row[0]);
      if (turnIndex < 0) {
        continue;
      }
      String model = (String) row[1];
      String tokenType = (String) row[2];
      long tokenValueDelta = row[3] == null ? 0L : ((Number) row[3]).longValue();

      tokensByModelPerTurn
          .computeIfAbsent(turnIndex, key -> new HashMap<>())
          .merge(model, tokenValueDelta, Long::sum);

      long[] typeTotals = tokenTypeTotalsPerTurn.computeIfAbsent(turnIndex, key -> new long[TOKEN_BREAKDOWN_KIND_COUNT]);
      switch (tokenType == null ? "" : tokenType) {
        case INPUT_TOKEN_TYPE -> typeTotals[0] += tokenValueDelta;
        case OUTPUT_TOKEN_TYPE -> typeTotals[1] += tokenValueDelta;
        case CACHE_CREATION_TOKEN_TYPE -> typeTotals[2] += tokenValueDelta;
        case CACHE_READ_TOKEN_TYPE -> typeTotals[3] += tokenValueDelta;
        default -> {
          /* unrecognized/missing type -- excluded from the breakdown, same trade-off
           * as the session row's tokenBreakdown */
        }
      }
    }

    Map<Integer, String> modelByTurn = new HashMap<>();
    for (Map.Entry<Integer, Map<String, Long>> turnEntry : tokensByModelPerTurn.entrySet()) {
      turnEntry.getValue().entrySet().stream()
          .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
          .ifPresent(dominantModel -> modelByTurn.put(turnEntry.getKey(), dominantModel.getKey()));
    }

    Map<Integer, SessionTokenBreakdown> tokensByTurn = new HashMap<>();
    for (Map.Entry<Integer, long[]> turnEntry : tokenTypeTotalsPerTurn.entrySet()) {
      long[] typeTotals = turnEntry.getValue();
      tokensByTurn.put(turnEntry.getKey(),
          new SessionTokenBreakdown(typeTotals[0], typeTotals[1], typeTotals[2], typeTotals[3]));
    }
    return new TurnTokenRollup(modelByTurn, tokensByTurn);
  }

  // Per-turn tool-call counts: groups tool_result events by tool name within each
  // turn's interval, ordered by count descending then name ascending for
  // deterministic output.
  private Map<Integer, List<SessionPromptToolCount>> resolveToolsPerTurn(
      String sessionId, List<Instant> turnStartTimestamps, Instant turnsEndBoundary) {
    List<Object[]> toolEventRows = logRecordRepository.findToolEventsForSession(
        sessionId, tuningProperties.getToolEventName(), tuningProperties.getToolAttribute(),
        turnStartTimestamps.get(0), turnsEndBoundary);

    Map<Integer, Map<String, Long>> countsByToolPerTurn = new HashMap<>();
    for (Object[] row : toolEventRows) {
      int turnIndex = turnIndexForTimestamp(turnStartTimestamps, turnsEndBoundary, (Instant) row[0]);
      if (turnIndex < 0) {
        continue;
      }
      String toolName = (String) row[1];
      countsByToolPerTurn
          .computeIfAbsent(turnIndex, key -> new HashMap<>())
          .merge(toolName, 1L, Long::sum);
    }

    Map<Integer, List<SessionPromptToolCount>> toolsByTurn = new HashMap<>();
    for (Map.Entry<Integer, Map<String, Long>> turnEntry : countsByToolPerTurn.entrySet()) {
      List<SessionPromptToolCount> toolCounts = turnEntry.getValue().entrySet().stream()
          .map(toolEntry -> new SessionPromptToolCount(toolEntry.getKey(), toolEntry.getValue()))
          .sorted(Comparator.comparingLong(SessionPromptToolCount::count).reversed()
              .thenComparing(SessionPromptToolCount::name))
          .toList();
      toolsByTurn.put(turnEntry.getKey(), toolCounts);
    }
    return toolsByTurn;
  }

  public List<String> availableAttributePairs(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return logRecordRepository.findDistinctAttributePairs(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<String> availableAttributeKeys(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return logRecordRepository.findDistinctAttributeKeys(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<String> availableAttributeValues(
      String key, List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return logRecordRepository.findDistinctAttributeValuesForKey(
        key, toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<ToolCallCount> aggregateToolCalls(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateToolCalls(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since);
    return mapToolCallCounts(rows);
  }

  public List<ToolCallCount> aggregateToolCallsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolCallsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapToolCallCounts(rows);
  }

  public List<ToolPerformance> aggregateToolPerformanceInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolPerformanceInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapToolPerformance(rows);
  }

  private static List<ToolPerformance> mapToolPerformance(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new ToolPerformance(
            (String) row[0],
            ((Number) row[1]).longValue(),
            row[2] == null ? 0L : ((Number) row[2]).longValue(),
            row[3] == null ? 0L : ((Number) row[3]).longValue(),
            row[4] == null ? 0L : ((Number) row[4]).longValue()))
        .toList();
  }

  public List<ToolFailure> aggregateToolFailuresInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolFailuresInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapToolFailures(rows);
  }

  public List<IdentifierUsageCount> aggregateSkillUsage(int minutes) {
    Instant end = Instant.now();
    return aggregateSkillUsageInRange(end.minus(Duration.ofMinutes(minutes)), end);
  }

  public List<IdentifierUsageCount> aggregateSkillUsageInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateSkillInvocationsByModelInRange(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        tuningProperties.getModelAttribute(),
        start,
        end);
    return mapIdentifierUsageCounts(rows);
  }

  public List<IdentifierUsageCount> aggregateSubagentUsage(int minutes) {
    Instant end = Instant.now();
    return aggregateSubagentUsageInRange(end.minus(Duration.ofMinutes(minutes)), end);
  }

  public List<IdentifierUsageCount> aggregateSubagentUsageInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolInvocationsByInnerAttributeAndModelInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getModelAttribute(),
        tuningProperties.getAgentNameAttribute(),
        start,
        end);
    return mapIdentifierUsageCounts(rows);
  }

  private static List<ToolCallCount> mapToolCallCounts(List<Object[]> rows) {
    return rows.stream()
        .map(row -> ToolCallCount.builder()
            .tool((String) row[0])
            .calls(((Number) row[1]).longValue())
            .build())
        .toList();
  }

  // Pivots (identifier, model, calls) rows into one IdentifierUsageCount per
  // identifier. The "tool" field carries the skill or subagent identifier so the
  // frontend can render both views with the same components. The query already
  // groups every row of one identifier together and sorts identifiers by their
  // total call count descending, so a LinkedHashMap preserves that order and the
  // per-model maps stay sorted by call count within each row.
  private static List<IdentifierUsageCount> mapIdentifierUsageCounts(List<Object[]> rows) {
    Map<String, Map<String, Long>> callsByIdentifierAndModel = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String identifier = (String) row[0];
      String model = (String) row[1];
      long calls = ((Number) row[2]).longValue();
      callsByIdentifierAndModel
          .computeIfAbsent(identifier, key -> new LinkedHashMap<>())
          .merge(model, calls, Long::sum);
    }
    return callsByIdentifierAndModel.entrySet().stream()
        .map(entry -> new IdentifierUsageCount(
            entry.getKey(),
            entry.getValue().values().stream().mapToLong(Long::longValue).sum(),
            entry.getValue()))
        .toList();
  }

  public List<ToolFailureRate> aggregateToolFailureRates(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateToolFailureRates(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since);
    return mapToolFailureRates(rows);
  }

  public List<ToolFailureRate> aggregateToolFailureRatesInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolFailureRatesInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapToolFailureRates(rows);
  }

  private static List<ToolFailureRate> mapToolFailureRates(List<Object[]> rows) {
    return rows.stream()
        .map(row -> ToolFailureRate.of(
            (String) row[0],
            ((Number) row[1]).longValue(),
            row[2] == null ? 0L : ((Number) row[2]).longValue()))
        .toList();
  }

  public List<ToolContextFootprint> aggregateToolContextFootprint(int minutes) {
    Instant since = Instant.now();
    return aggregateToolContextFootprintInRange(since.minus(Duration.ofMinutes(minutes)), since);
  }

  public List<ToolContextFootprint> aggregateToolContextFootprintInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolContextFootprintInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapToolContextFootprint(rows);
  }

  /**
   * Same aggregation as {@link #aggregateToolContextFootprintInRange}, minus the rows the tuning
   * report has no rule to offer for: externally determined tools and image reads. Used by the
   * report only — the dashboard card deliberately keeps those rows (see the repository comment).
   */
  public List<ToolContextFootprint> aggregateTunableToolContextFootprintInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateTunableToolContextFootprintInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getExternallyDeterminedTools(),
        start,
        end);
    return mapToolContextFootprint(rows);
  }

  private static List<ToolContextFootprint> mapToolContextFootprint(List<Object[]> rows) {
    return rows.stream()
        .map(row -> {
          long totalBytes = row[2] == null ? 0L : ((Number) row[2]).longValue();
          return new ToolContextFootprint(
              (String) row[0],
              ((Number) row[1]).longValue(),
              totalBytes,
              totalBytes / BYTES_PER_ESTIMATED_TOKEN,
              row[3] == null ? 0L : ((Number) row[3]).longValue());
        })
        .toList();
  }

  public List<ToolDenialCount> aggregateToolDenials(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateToolDenials(
        tuningProperties.getToolDecisionEventName(), since);
    return mapToolDenials(rows);
  }

  public List<ToolDenialCount> aggregateToolDenialsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolDenialsInRange(
        tuningProperties.getToolDecisionEventName(), start, end);
    return mapToolDenials(rows);
  }

  private static List<ToolDenialCount> mapToolDenials(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new ToolDenialCount(
            (String) row[0],
            (String) row[1],
            ((Number) row[2]).longValue()))
        .toList();
  }

  public List<HookExecutionSummary> aggregateHookExecutions(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateHookExecutions(
        tuningProperties.getHookExecutionEventName(), since);
    return mapHookExecutions(rows);
  }

  public List<HookExecutionSummary> aggregateHookExecutionsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateHookExecutionsInRange(
        tuningProperties.getHookExecutionEventName(), start, end);
    return mapHookExecutions(rows);
  }

  private static List<HookExecutionSummary> mapHookExecutions(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new HookExecutionSummary(
            (String) row[0],
            (String) row[1],
            ((Number) row[2]).longValue(),
            ((Number) row[3]).longValue(),
            ((Number) row[4]).longValue(),
            ((Number) row[5]).longValue(),
            ((Number) row[6]).longValue()))
        .toList();
  }

  private static List<ToolFailure> mapToolFailures(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new ToolFailure(
            (String) row[0],
            (String) row[1],
            (String) row[2],
            row[3] == null ? "" : (String) row[3],
            row[4] == null ? "" : (String) row[4],
            ((Number) row[5]).longValue()))
        .toList();
  }

  public List<BashCommandHotspot> aggregateBashCommandHotspotsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateBashCommandHotspotsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        BASH_HOTSPOT_LIMIT);
    return mapBashCommandHotspots(rows);
  }

  private static List<BashCommandHotspot> mapBashCommandHotspots(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new BashCommandHotspot(
            (String) row[0],
            ((Number) row[1]).longValue(),
            row[2] == null ? 0L : ((Number) row[2]).longValue(),
            row[3] == null ? 0L : ((Number) row[3]).longValue(),
            row[4] == null ? 0L : ((Number) row[4]).longValue()))
        .toList();
  }

  public List<OversizedToolResult> aggregateOversizedToolResultsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateOversizedToolResultsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getExternallyDeterminedTools(),
        start,
        end,
        OVERSIZED_RESULT_LIMIT);
    return mapOversizedToolResults(rows);
  }

  private static List<OversizedToolResult> mapOversizedToolResults(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new OversizedToolResult(
            (String) row[0],
            row[1] == null ? "" : (String) row[1],
            ((Number) row[2]).longValue(),
            ((Number) row[3]).longValue()))
        .toList();
  }

  public List<RedundantFileRead> aggregateRedundantFileReadsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateRedundantFileReadsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        REDUNDANT_READ_LIMIT);
    return mapRedundantFileReads(rows);
  }

  private static List<RedundantFileRead> mapRedundantFileReads(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new RedundantFileRead(
            (String) row[0],
            (String) row[1],
            ((Number) row[2]).longValue(),
            row[3] == null ? 0L : ((Number) row[3]).longValue(),
            row[4] == null ? 0L : ((Number) row[4]).longValue()))
        .toList();
  }

  public List<EditFailureLoop> aggregateEditFailureLoopsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateEditFailureLoopsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        EDIT_FAILURE_LOOP_LIMIT);
    return mapEditFailureLoops(rows);
  }

  private static List<EditFailureLoop> mapEditFailureLoops(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new EditFailureLoop(
            (String) row[0],
            (String) row[1],
            ((Number) row[2]).longValue()))
        .toList();
  }

  public List<SlowAndLargeCall> aggregateSlowAndLargeCallsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateSlowAndLargeCallsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getExternallyDeterminedTools(),
        start,
        end,
        SLOW_AND_LARGE_MIN_DURATION_MS,
        SLOW_AND_LARGE_MIN_BYTES,
        SLOW_AND_LARGE_LIMIT);
    return mapSlowAndLargeCalls(rows);
  }

  private static List<SlowAndLargeCall> mapSlowAndLargeCalls(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new SlowAndLargeCall(
            (String) row[0],
            row[1] == null ? "" : (String) row[1],
            ((Number) row[2]).longValue(),
            ((Number) row[3]).longValue()))
        .toList();
  }

  public List<ToolRepeatStat> aggregateToolRepeats(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateToolRepeats(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        TOOL_REPEAT_LIMIT);
    return mapToolRepeats(rows);
  }

  public List<ToolRepeatStat> aggregateToolRepeatsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolRepeatsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        TOOL_REPEAT_LIMIT);
    return mapToolRepeats(rows);
  }

  private static List<ToolRepeatStat> mapToolRepeats(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new ToolRepeatStat(
            (String) row[0],
            row[1] == null ? "" : (String) row[1],
            row[2] == null ? 0L : ((Number) row[2]).longValue(),
            row[3] == null ? 0L : ((Number) row[3]).longValue(),
            row[4] == null ? 0L : ((Number) row[4]).longValue()))
        .toList();
  }

  public BashCommandCoverage bashCommandCoverageInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.bashCommandCoverageInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapBashCommandCoverage(rows);
  }

  private static BashCommandCoverage mapBashCommandCoverage(List<Object[]> rows) {
    Object[] row = rows.stream().findFirst().orElse(new Object[] { 0L, 0L, 0L });
    return new BashCommandCoverage(
        ((Number) row[0]).longValue(),
        ((Number) row[1]).longValue(),
        ((Number) row[2]).longValue());
  }

  // Pairs each (session, path) Read failure with the closest path the SAME
  // session read successfully. A small edit distance between the two almost
  // always means the agent retyped a path (UUID-heavy scratchpad dirs are the
  // usual victim) rather than probed a genuinely missing file. Distance 0 is
  // skipped on purpose: fail-then-succeed on the identical path is a
  // read-before-create race, not a typo.
  public List<PathNearMiss> findReadPathNearMissesInRange(Instant start, Instant end) {
    List<Object[]> failedRows = logRecordRepository.aggregateFailedReadPathsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        FAILED_READ_PATH_LIMIT);
    if (failedRows.isEmpty()) {
      return List.of();
    }
    List<Object[]> successfulRows = logRecordRepository.distinctSuccessfulReadPathsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        SUCCESSFUL_READ_PATH_LIMIT);

    Map<String, List<String>> successfulPathsBySession = new HashMap<>();
    for (Object[] successfulRow : successfulRows) {
      successfulPathsBySession
          .computeIfAbsent((String) successfulRow[0], sessionId -> new ArrayList<>())
          .add((String) successfulRow[1]);
    }

    List<PathNearMiss> nearMisses = new ArrayList<>();
    for (Object[] failedRow : failedRows) {
      String sessionId = (String) failedRow[0];
      String failedPath = (String) failedRow[1];
      long failures = ((Number) failedRow[2]).longValue();
      List<String> candidatePaths = successfulPathsBySession.getOrDefault(sessionId, List.of());

      String nearestPath = null;
      int nearestDistance = Integer.MAX_VALUE;
      for (String candidatePath : candidatePaths) {
        int distance = BoundedEditDistance.compute(failedPath, candidatePath, NEAR_MISS_MAX_EDIT_DISTANCE);
        if (distance > 0 && distance < nearestDistance) {
          nearestDistance = distance;
          nearestPath = candidatePath;
        }
      }
      if (nearestPath != null) {
        nearMisses.add(new PathNearMiss(sessionId, failedPath, nearestPath, nearestDistance, failures));
      }
    }
    nearMisses.sort(Comparator.comparingLong(PathNearMiss::failures).reversed());
    return nearMisses;
  }

  public ToolCallTimeseries aggregateToolCallsTimeseries(int minutes, int topTools) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    long bucketSeconds = bucketWidthSeconds(minutes);

    List<Object[]> rawRows = logRecordRepository.aggregateToolCallsTimeseries(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        bucketSeconds);
    return buildToolCallTimeseries(rawRows, bucketSeconds, topTools);
  }

  public ToolCallTimeseries aggregateToolCallsTimeseriesInRange(
      Instant start, Instant end, int topTools) {
    long windowSeconds = Math.max(1L, Duration.between(start, end).getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);

    List<Object[]> rawRows = logRecordRepository.aggregateToolCallsTimeseriesInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        bucketSeconds);
    return buildToolCallTimeseries(rawRows, bucketSeconds, topTools);
  }

  private static ToolCallTimeseries buildToolCallTimeseries(
      List<Object[]> rawRows, long bucketSeconds, int topTools) {
    int topN = topTools <= 0 ? DEFAULT_TIMESERIES_TOP_N : topTools;

    Map<String, Long> totalByTool = new HashMap<>();
    for (Object[] row : rawRows) {
      String tool = (String) row[1];
      totalByTool.merge(tool, ((Number) row[2]).longValue(), (existing, addition) -> existing + addition);
    }

    List<String> topTopologicalTools = totalByTool.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(topN)
        .map(Map.Entry::getKey)
        .toList();

    // Tool name -> column index in each point's counts array. "Other" is always
    // last when
    // any tool overflowed the top-N cap.
    LinkedHashMap<String, Integer> columnIndexByTool = new LinkedHashMap<>();
    for (String tool : topTopologicalTools) {
      columnIndexByTool.put(tool, columnIndexByTool.size());
    }
    boolean hasOverflow = totalByTool.size() > columnIndexByTool.size();
    int otherIndex = hasOverflow ? columnIndexByTool.size() : -1;

    List<String> toolColumns = new ArrayList<>(columnIndexByTool.keySet());
    if (hasOverflow) {
      toolColumns.add(OTHER_BUCKET_LABEL);
    }

    LinkedHashMap<Instant, long[]> countsByBucket = new LinkedHashMap<>();
    for (Object[] row : rawRows) {
      Instant bucket = (Instant) row[0];
      String tool = (String) row[1];
      long calls = ((Number) row[2]).longValue();
      long[] counts = countsByBucket.computeIfAbsent(bucket, key -> new long[toolColumns.size()]);
      Integer columnIndex = columnIndexByTool.get(tool);
      if (columnIndex != null) {
        counts[columnIndex] += calls;
      } else if (otherIndex >= 0) {
        counts[otherIndex] += calls;
      }
    }

    List<ToolCallTimeseries.Point> points = countsByBucket.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .map(entry -> {
          List<Long> boxed = new ArrayList<>(entry.getValue().length);
          for (long count : entry.getValue()) {
            boxed.add(count);
          }
          return new ToolCallTimeseries.Point(entry.getKey(), boxed);
        })
        .toList();

    return new ToolCallTimeseries(bucketSeconds, toolColumns, points);
  }

  private static long bucketWidthSeconds(int minutes) {
    long windowSeconds = (long) minutes * SECONDS_PER_MINUTE;
    return Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);
  }

  private static String[] toFilterArray(List<String> activeFilters) {
    return activeFilters == null ? new String[0] : activeFilters.toArray(new String[0]);
  }

  // =========================================================================
  // Logs-page aggregations: histogram, facets, cursor paging, offset paging
  // =========================================================================

  /**
   * Bucket-width ladder in seconds — "nice" steps matching the LogsPage
   * frontend's NICE array (which tops out at 2 days, unlike the Traces one).
   */
  private static final long[] HISTOGRAM_LADDER_SECONDS = {
      60L, 120L, 300L, 600L, 900L, 1800L, 3600L, 7200L, 10800L, 21600L, 43200L, 86400L, 172800L
  };

  private static final int FACET_VALUE_CAP = 50;

  private static final String SEVERITY_ERROR = "ERROR";
  private static final String SEVERITY_WARN = "WARN";
  private static final String SEVERITY_INFO = "INFO";
  private static final String SEVERITY_DEBUG = "DEBUG";

  public LogHistogram histogram(LogQueryCriteria criteria, int targetBuckets) {
    Instant windowStart = criteria.startTimestamp();
    Instant windowEnd = criteria.endTimestamp();
    long bucketSeconds = HistogramBucketing.pickBucketSeconds(
        windowStart, windowEnd, targetBuckets, HISTOGRAM_LADDER_SECONDS);
    long bucketMs = Duration.ofSeconds(bucketSeconds).toMillis();

    List<Object[]> rows = logRecordRepository.histogramBuckets(
        windowStart,
        windowEnd,
        bucketSeconds,
        criteria.filters(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery());

    Map<Instant, Object[]> rowByBucket = new LinkedHashMap<>();
    for (Object[] row : rows) {
      rowByBucket.put((Instant) row[0], row);
    }

    List<HistogramBucket> buckets = zeroFillHistogram(
        windowStart, windowEnd, bucketSeconds, rowByBucket);
    return new LogHistogram(bucketMs, buckets);
  }

  /** Produces a zero-filled list of buckets covering [windowStart, windowEnd]. */
  private static List<HistogramBucket> zeroFillHistogram(
      Instant windowStart,
      Instant windowEnd,
      long bucketSeconds,
      Map<Instant, Object[]> rowByBucket) {

    List<HistogramBucket> buckets = new ArrayList<>();
    Instant bucketStart = windowStart;
    while (!bucketStart.isAfter(windowEnd)) {
      Instant bucketEnd = bucketStart.plusSeconds(bucketSeconds);
      Object[] row = rowByBucket.get(bucketStart);
      long errorCount = row != null && row[1] != null ? ((Number) row[1]).longValue() : 0L;
      long warnCount = row != null && row[2] != null ? ((Number) row[2]).longValue() : 0L;
      long infoCount = row != null && row[3] != null ? ((Number) row[3]).longValue() : 0L;
      long debugCount = row != null && row[4] != null ? ((Number) row[4]).longValue() : 0L;
      buckets.add(new HistogramBucket(bucketStart, bucketEnd, errorCount, warnCount, infoCount, debugCount));
      bucketStart = bucketEnd;
    }
    return buckets;
  }

  public LogFacets facets(LogQueryCriteria criteria) {
    List<FacetValue> severityFacets = buildSeverityFacets(criteria);
    List<FacetValue> eventFacets = buildEventFacets(criteria);
    List<FacetValue> toolFacets = buildToolFacets(criteria);
    return new LogFacets(severityFacets, eventFacets, toolFacets);
  }

  private List<FacetValue> buildSeverityFacets(LogQueryCriteria criteria) {
    List<Object[]> rows = logRecordRepository.facetSeverity(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery());

    Map<String, Long> countsBySeverity = new HashMap<>();
    for (Object[] row : rows) {
      String severityLabel = (String) row[0];
      long count = ((Number) row[1]).longValue();
      countsBySeverity.put(severityLabel, count);
    }

    // Always return all four in canonical order, zero-filled.
    return List.of(
        new FacetValue(SEVERITY_ERROR, countsBySeverity.getOrDefault(SEVERITY_ERROR, 0L)),
        new FacetValue(SEVERITY_WARN, countsBySeverity.getOrDefault(SEVERITY_WARN, 0L)),
        new FacetValue(SEVERITY_INFO, countsBySeverity.getOrDefault(SEVERITY_INFO, 0L)),
        new FacetValue(SEVERITY_DEBUG, countsBySeverity.getOrDefault(SEVERITY_DEBUG, 0L)));
  }

  private List<FacetValue> buildEventFacets(LogQueryCriteria criteria) {
    List<Object[]> rows = logRecordRepository.facetEvent(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        FACET_VALUE_CAP);
    return toFacetValues(rows);
  }

  private List<FacetValue> buildToolFacets(LogQueryCriteria criteria) {
    List<Object[]> rows = logRecordRepository.facetTool(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        FACET_VALUE_CAP);
    return toFacetValues(rows);
  }

  private static List<FacetValue> toFacetValues(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
        .toList();
  }

  /**
   * Cursor-mode entry point for the logs endpoint. Selects the scroll-back
   * ({@code before}), live-tail ({@code after}), or initial-load variant from
   * which cursor param is present and parses the raw "ts,id" cursor string, so
   * the controller stays free of cursor semantics. {@code before} wins when
   * both are supplied, matching the documented one-cursor-per-request contract.
   */
  public LogCursorPage cursorPage(LogQueryCriteria criteria, String before, String after, int limit) {
    if (before != null) {
      return cursorPageBefore(criteria, parseCursor(before), limit);
    }
    if (after != null) {
      return cursorPageAfter(criteria, parseCursor(after), limit);
    }
    return cursorPageFirst(criteria, limit);
  }

  /**
   * Cursor page — initial load (no before/after cursor). Returns the newest
   * {@code limit} rows and the totalCount for the full window.
   *
   * <p>
   * Fetches {@code limit + 1} rows so {@code hasMore} can be determined
   * exactly without a second COUNT query (the "+1 probe" pattern).
   */
  public LogCursorPage cursorPageFirst(LogQueryCriteria criteria, int limit) {
    int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);
    long totalCount = logRecordRepository.countFiltered(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery());

    List<LogRecord> probeItems = logRecordMapper.toLogRecords(logRecordRepository.cursorFirst(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        resolvedLimit + 1));

    return buildCursorPage(probeItems, resolvedLimit, totalCount);
  }

  /**
   * Cursor page — scroll-back (before=ts,id). Returns rows strictly older than
   * the cursor.
   *
   * <p>{@code totalCount} is {@link PageBounds#CONTINUATION_PAGE_TOTAL_COUNT}: the client
   * reads the total only from the initial page, so continuation pages skip the
   * expensive filtered COUNT.
   */
  public LogCursorPage cursorPageBefore(LogQueryCriteria criteria, LogCursor cursor, int limit) {
    int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);

    List<LogRecord> probeItems = logRecordMapper.toLogRecords(logRecordRepository.cursorBefore(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        cursor.ts(),
        cursor.id(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        resolvedLimit + 1));

    return buildCursorPage(probeItems, resolvedLimit, PageBounds.CONTINUATION_PAGE_TOTAL_COUNT);
  }

  /**
   * Cursor page — live tail (after=ts,id). Returns rows strictly newer than the
   * cursor; returns an empty page when nothing new has arrived.
   *
   * <p>{@code totalCount} is {@link PageBounds#CONTINUATION_PAGE_TOTAL_COUNT}. The tail
   * poll runs every 1.5 seconds while live tail is on and the client maintains
   * its own running total from the initial page, so recounting the full window
   * on every poll was the most frequently executed expensive query in the app.
   */
  public LogCursorPage cursorPageAfter(LogQueryCriteria criteria, LogCursor cursor, int limit) {
    int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);

    List<LogRecord> probeItems = logRecordMapper.toLogRecords(logRecordRepository.cursorAfter(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        cursor.ts(),
        cursor.id(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        resolvedLimit + 1));

    return buildCursorPage(probeItems, resolvedLimit, PageBounds.CONTINUATION_PAGE_TOTAL_COUNT);
  }

  /**
   * Builds a {@link LogCursorPage} from a +1-probe result list. Trims the list to
   * {@code limit} items; {@code hasMore} is true only when the probe returned
   * more than {@code limit} rows (meaning at least one row exists beyond the
   * page).
   */
  private static LogCursorPage buildCursorPage(
      List<LogRecord> probeItems, int limit, long totalCount) {
    boolean hasMore = probeItems.size() > limit;
    List<LogRecord> items = hasMore ? probeItems.subList(0, limit) : probeItems;
    LogRecord lastItem = items.isEmpty() ? null : items.get(items.size() - 1);
    LogCursor nextCursor = lastItem != null
        ? new LogCursor(lastItem.getTimestamp(), lastItem.getId())
        : null;
    return new LogCursorPage(items, nextCursor, hasMore, totalCount);
  }

  /**
   * Parses a "ts,id" cursor string (as sent by the frontend in before=/after=
   * query params) into a {@link LogCursor}. Splitting happens on the first comma
   * only so nanosecond-precision ISO-8601 timestamps are not truncated.
   */
  public LogCursor parseCursor(String cursorString) {
    int commaIndex = cursorString.indexOf(',');
    if (commaIndex < 0) {
      throw new IllegalArgumentException("Cursor must be in ts,id format: " + cursorString);
    }
    Instant cursorTs = Instant.parse(cursorString.substring(0, commaIndex));
    long cursorId = Long.parseLong(cursorString.substring(commaIndex + 1));
    return new LogCursor(cursorTs, cursorId);
  }

  /**
   * Offset-paged table query. Sort column and direction are fixed to timestamp
   * DESC, id DESC.
   */
  public LogPage offsetPage(LogQueryCriteria criteria, int page, int size) {
    long totalCount = logRecordRepository.countFiltered(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery());

    int resolvedSize = PageBounds.clampPageSize(size, PageBounds.DEFAULT_OFFSET_PAGE_SIZE);
    int resolvedPage = Math.max(0, page);
    int pageOffset = PageBounds.computeOffset(resolvedPage, resolvedSize);
    List<LogRecord> items = logRecordMapper.toLogRecords(logRecordRepository.offsetPage(
        criteria.startTimestamp(),
        criteria.endTimestamp(),
        criteria.filters(),
        criteria.severities(),
        criteria.events(),
        criteria.tools(),
        tuningProperties.getToolAttribute(),
        criteria.fullTextQuery(),
        resolvedSize,
        pageOffset));

    return new LogPage(items, totalCount);
  }

}
