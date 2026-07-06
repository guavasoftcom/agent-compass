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
import com.guavasoft.agentcompass.model.LogCursor;
import com.guavasoft.agentcompass.model.LogCursorPage;
import com.guavasoft.agentcompass.model.LogFacets;
import com.guavasoft.agentcompass.model.LogHistogram;
import com.guavasoft.agentcompass.model.LogPage;
import com.guavasoft.agentcompass.model.LogQueryCriteria;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.RedundantFileRead;
import com.guavasoft.agentcompass.model.SessionPrompt;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolCallTimeseries;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailure;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolPerformance;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
  private static final int REDUNDANT_READ_LIMIT = 10;
  private static final int EDIT_FAILURE_LOOP_LIMIT = 10;
  private static final int SLOW_AND_LARGE_LIMIT = 10;
  private static final int TOOL_REPEAT_LIMIT = 15;
  private static final long SLOW_AND_LARGE_MIN_DURATION_MS = 1_000L;
  private static final long SLOW_AND_LARGE_MIN_BYTES = 4_000L;

  private final LogRecordRepository logRecordRepository;
  private final SpanRepository spanRepository;
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
  // api_request* land on the interaction-root span rather than on the
  // tool.execution / llm_request span that did the work (tool_decision is the
  // exception — it correctly lands on its tool.blocked_on_user span). Both signals
  // carry exact correlation keys, so we re-point each root-stranded log onto its
  // true leaf span: tool logs via tool_use_id, LLM logs via request_id. Logs
  // already on a non-root span (e.g. tool_decision) and logs with no matching key
  // (hooks, user_prompt) keep their original span id.
  private void resolveLeafSpans(List<LogRecord> logRecords, List<SpanEntity> traceSpans) {
    Set<String> rootSpanIds = traceSpans.stream()
        .filter(span -> span.getParentSpanId() == null)
        .map(SpanEntity::getSpanId)
        .collect(Collectors.toSet());

    String toolCallIdAttribute = tuningProperties.getToolCallIdAttribute();
    String requestIdAttribute = tuningProperties.getRequestIdAttribute();
    Map<String, String> spanIdByToolCallId = indexSpanIdByAttribute(
        traceSpans, tuningProperties.getToolExecutionSpanName(), toolCallIdAttribute);
    Map<String, String> spanIdByRequestId = indexSpanIdByAttribute(
        traceSpans, tuningProperties.getLlmRequestSpanName(), requestIdAttribute);
    Map<String, TreeMap<Long, String>> requestIdBySequencePerPrompt = indexApiRequestsByPrompt(logRecords);

    for (LogRecord logRecord : logRecords) {
      if (logRecord.getSpanId() != null && !rootSpanIds.contains(logRecord.getSpanId())) {
        continue;
      }
      Map<String, Object> attributes = logRecord.getAttributes();
      if (attributes == null) {
        continue;
      }
      String resolvedSpanId = spanIdByToolCallId.get(stringAttribute(attributes, toolCallIdAttribute));
      if (resolvedSpanId == null) {
        resolvedSpanId = spanIdByRequestId.get(stringAttribute(attributes, requestIdAttribute));
      }
      if (resolvedSpanId == null) {
        resolvedSpanId = spanIdByRequestId.get(
            pairedRequestIdForBody(attributes, requestIdBySequencePerPrompt));
      }
      if (resolvedSpanId != null) {
        logRecord.setSpanId(resolvedSpanId);
      }
    }
  }

  // Indexes the request_id of every api_request log by prompt.id, keyed on its
  // event.sequence. Drives the api_request_body pairing below: a body log carries no
  // request_id of its own, so it borrows the request_id of the api_request that
  // immediately follows it in sequence order within the same prompt.
  private Map<String, TreeMap<Long, String>> indexApiRequestsByPrompt(List<LogRecord> logRecords) {
    String eventNameAttribute = tuningProperties.getEventNameAttribute();
    String apiRequestEventName = tuningProperties.getApiRequestEventName();
    String promptIdAttribute = tuningProperties.getPromptIdAttribute();
    String requestIdAttribute = tuningProperties.getRequestIdAttribute();
    String eventSequenceAttribute = tuningProperties.getEventSequenceAttribute();

    Map<String, TreeMap<Long, String>> requestIdBySequencePerPrompt = new HashMap<>();
    for (LogRecord logRecord : logRecords) {
      Map<String, Object> attributes = logRecord.getAttributes();
      if (attributes == null || !apiRequestEventName.equals(attributes.get(eventNameAttribute))) {
        continue;
      }
      String promptId = stringAttribute(attributes, promptIdAttribute);
      String requestId = stringAttribute(attributes, requestIdAttribute);
      Long sequence = longAttribute(attributes, eventSequenceAttribute);
      if (promptId == null || requestId == null || sequence == null) {
        continue;
      }
      requestIdBySequencePerPrompt
          .computeIfAbsent(promptId, key -> new TreeMap<>())
          .putIfAbsent(sequence, requestId);
    }
    return requestIdBySequencePerPrompt;
  }

  // Recovers the request_id for an api_request_body log by finding the api_request
  // that immediately follows it in event.sequence order within the same prompt
  // (Claude Code emits each LLM call as an ordered triplet: body, request, response).
  // Returns null for any other event, or when no following request exists.
  private String pairedRequestIdForBody(
      Map<String, Object> attributes, Map<String, TreeMap<Long, String>> requestIdBySequencePerPrompt) {
    if (!tuningProperties.getApiRequestBodyEventName().equals(
        attributes.get(tuningProperties.getEventNameAttribute()))) {
      return null;
    }
    String promptId = stringAttribute(attributes, tuningProperties.getPromptIdAttribute());
    Long sequence = longAttribute(attributes, tuningProperties.getEventSequenceAttribute());
    if (promptId == null || sequence == null) {
      return null;
    }
    TreeMap<Long, String> requestIdBySequence = requestIdBySequencePerPrompt.get(promptId);
    if (requestIdBySequence == null) {
      return null;
    }
    Map.Entry<Long, String> followingRequest = requestIdBySequence.higherEntry(sequence);
    return followingRequest == null ? null : followingRequest.getValue();
  }

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
  public List<SessionPrompt> promptsForSession(String sessionId) {
    List<Object[]> rows = logRecordRepository.findPromptsForSession(
        sessionId,
        tuningProperties.getUserPromptEventName(),
        tuningProperties.getPromptAttribute(),
        PageBounds.MAXIMUM_PAGE_SIZE);
    return rows.stream()
        .map(row -> new SessionPrompt((Instant) row[0], (String) row[1], (String) row[2]))
        .toList();
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
    return mapIdentifierCounts(rows);
  }

  public List<ToolCallCount> aggregateToolCallsInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolCallsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return mapIdentifierCounts(rows);
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

  public List<ToolCallCount> aggregateSkillUsage(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateSkillInvocations(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        since);
    return mapIdentifierCounts(rows);
  }

  public List<ToolCallCount> aggregateSkillUsageInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateSkillInvocationsInRange(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        start,
        end);
    return mapIdentifierCounts(rows);
  }

  public List<ToolCallCount> aggregateSubagentUsage(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = logRecordRepository.aggregateToolInvocationsByInnerAttribute(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        since);
    return mapIdentifierCounts(rows);
  }

  public List<ToolCallCount> aggregateSubagentUsageInRange(Instant start, Instant end) {
    List<Object[]> rows = logRecordRepository.aggregateToolInvocationsByInnerAttributeInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        start,
        end);
    return mapIdentifierCounts(rows);
  }

  // Identifier counts reuse the ToolCallCount shape: the "tool" field carries the
  // skill or
  // subagent identifier so the frontend can render both views with the same
  // components.
  private static List<ToolCallCount> mapIdentifierCounts(List<Object[]> rows) {
    return rows.stream()
        .map(row -> ToolCallCount.builder()
            .tool((String) row[0])
            .calls(((Number) row[1]).longValue())
            .build())
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
            row[2] == null ? "" : (String) row[2],
            row[3] == null ? "" : (String) row[3],
            ((Number) row[4]).longValue()))
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
            ((Number) row[2]).longValue()))
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
    Object[] row = rows.stream().findFirst().orElse(new Object[] { 0L, 0L });
    return new BashCommandCoverage(
        ((Number) row[0]).longValue(),
        ((Number) row[1]).longValue());
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
