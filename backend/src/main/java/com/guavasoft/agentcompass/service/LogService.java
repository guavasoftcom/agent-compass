package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.mapper.LogRecordMapper;
import com.guavasoft.agentcompass.model.BashCommandCoverage;
import com.guavasoft.agentcompass.model.BashCommandHotspot;
import com.guavasoft.agentcompass.model.EditFailureLoop;
import com.guavasoft.agentcompass.model.HookExecutionSummary;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.RedundantFileRead;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolCallTimeseries;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailure;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolPerformance;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.repository.LogRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LogQueryService {

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

  private final LogRecordRepository repository;
  private final LogRecordMapper mapper;
  private final TuningProperties tuningProperties;

  public List<LogRecord> recentLogs(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return mapper.toLogRecords(repository.findAllMatchingFilters(
        toFilterArray(activeFilters), startTimestamp, endTimestamp));
  }

  public List<LogRecord> logsForTrace(String traceId) {
    return mapper.toLogRecords(repository.findByTraceIdOrderByTimestampAsc(traceId));
  }

  public List<String> availableAttributePairs(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return repository.findDistinctAttributePairs(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<String> availableAttributeKeys(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return repository.findDistinctAttributeKeys(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<String> availableAttributeValues(
      String key, List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return repository.findDistinctAttributeValuesForKey(
        key, toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public List<ToolCallCount> aggregateToolCalls(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapIdentifierCounts(repository.aggregateToolCalls(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since));
  }

  public List<ToolCallCount> aggregateToolCallsInRange(Instant start, Instant end) {
    return mapIdentifierCounts(repository.aggregateToolCallsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end));
  }

  public List<ToolPerformance> aggregateToolPerformance(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapToolPerformance(repository.aggregateToolPerformance(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since));
  }

  public List<ToolPerformance> aggregateToolPerformanceInRange(Instant start, Instant end) {
    return mapToolPerformance(repository.aggregateToolPerformanceInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end));
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

  public List<ToolFailure> aggregateToolFailures(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapToolFailures(repository.aggregateToolFailures(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since));
  }

  public List<ToolFailure> aggregateToolFailuresInRange(Instant start, Instant end) {
    return mapToolFailures(repository.aggregateToolFailuresInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end));
  }

  public List<ToolCallCount> aggregateSkillUsage(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapIdentifierCounts(repository.aggregateSkillInvocations(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        since));
  }

  public List<ToolCallCount> aggregateSkillUsageInRange(Instant start, Instant end) {
    return mapIdentifierCounts(repository.aggregateSkillInvocationsInRange(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        start,
        end));
  }

  public List<ToolCallCount> aggregateSubagentUsage(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapIdentifierCounts(repository.aggregateToolInvocationsByInnerAttribute(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        since));
  }

  public List<ToolCallCount> aggregateSubagentUsageInRange(Instant start, Instant end) {
    return mapIdentifierCounts(repository.aggregateToolInvocationsByInnerAttributeInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        start,
        end));
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
    return mapToolFailureRates(repository.aggregateToolFailureRates(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since));
  }

  public List<ToolFailureRate> aggregateToolFailureRatesInRange(Instant start, Instant end) {
    return mapToolFailureRates(repository.aggregateToolFailureRatesInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end));
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
    return mapToolDenials(repository.aggregateToolDenials(
        tuningProperties.getToolDecisionEventName(), since));
  }

  public List<ToolDenialCount> aggregateToolDenialsInRange(Instant start, Instant end) {
    return mapToolDenials(repository.aggregateToolDenialsInRange(
        tuningProperties.getToolDecisionEventName(), start, end));
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
    return mapHookExecutions(repository.aggregateHookExecutions(
        tuningProperties.getHookExecutionEventName(), since));
  }

  public List<HookExecutionSummary> aggregateHookExecutionsInRange(Instant start, Instant end) {
    return mapHookExecutions(repository.aggregateHookExecutionsInRange(
        tuningProperties.getHookExecutionEventName(), start, end));
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

  public List<BashCommandHotspot> aggregateBashCommandHotspots(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapBashCommandHotspots(repository.aggregateBashCommandHotspots(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        BASH_HOTSPOT_LIMIT));
  }

  public List<BashCommandHotspot> aggregateBashCommandHotspotsInRange(Instant start, Instant end) {
    return mapBashCommandHotspots(repository.aggregateBashCommandHotspotsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        BASH_HOTSPOT_LIMIT));
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

  public List<OversizedToolResult> aggregateOversizedToolResults(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapOversizedToolResults(repository.aggregateOversizedToolResults(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        OVERSIZED_RESULT_LIMIT));
  }

  public List<OversizedToolResult> aggregateOversizedToolResultsInRange(Instant start, Instant end) {
    return mapOversizedToolResults(repository.aggregateOversizedToolResultsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        OVERSIZED_RESULT_LIMIT));
  }

  private static List<OversizedToolResult> mapOversizedToolResults(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new OversizedToolResult(
            (String) row[0],
            row[1] == null ? "" : (String) row[1],
            ((Number) row[2]).longValue()))
        .toList();
  }

  public List<RedundantFileRead> aggregateRedundantFileReads(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapRedundantFileReads(repository.aggregateRedundantFileReads(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        REDUNDANT_READ_LIMIT));
  }

  public List<RedundantFileRead> aggregateRedundantFileReadsInRange(Instant start, Instant end) {
    return mapRedundantFileReads(repository.aggregateRedundantFileReadsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        REDUNDANT_READ_LIMIT));
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

  public List<EditFailureLoop> aggregateEditFailureLoops(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapEditFailureLoops(repository.aggregateEditFailureLoops(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        EDIT_FAILURE_LOOP_LIMIT));
  }

  public List<EditFailureLoop> aggregateEditFailureLoopsInRange(Instant start, Instant end) {
    return mapEditFailureLoops(repository.aggregateEditFailureLoopsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        EDIT_FAILURE_LOOP_LIMIT));
  }

  private static List<EditFailureLoop> mapEditFailureLoops(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new EditFailureLoop(
            (String) row[0],
            (String) row[1],
            ((Number) row[2]).longValue()))
        .toList();
  }

  public List<SlowAndLargeCall> aggregateSlowAndLargeCalls(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapSlowAndLargeCalls(repository.aggregateSlowAndLargeCalls(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        SLOW_AND_LARGE_MIN_DURATION_MS,
        SLOW_AND_LARGE_MIN_BYTES,
        SLOW_AND_LARGE_LIMIT));
  }

  public List<SlowAndLargeCall> aggregateSlowAndLargeCallsInRange(Instant start, Instant end) {
    return mapSlowAndLargeCalls(repository.aggregateSlowAndLargeCallsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        SLOW_AND_LARGE_MIN_DURATION_MS,
        SLOW_AND_LARGE_MIN_BYTES,
        SLOW_AND_LARGE_LIMIT));
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
    return mapToolRepeats(repository.aggregateToolRepeats(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since,
        TOOL_REPEAT_LIMIT));
  }

  public List<ToolRepeatStat> aggregateToolRepeatsInRange(Instant start, Instant end) {
    return mapToolRepeats(repository.aggregateToolRepeatsInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end,
        TOOL_REPEAT_LIMIT));
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

  public BashCommandCoverage bashCommandCoverage(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return mapBashCommandCoverage(repository.bashCommandCoverage(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        since));
  }

  public BashCommandCoverage bashCommandCoverageInRange(Instant start, Instant end) {
    return mapBashCommandCoverage(repository.bashCommandCoverageInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        start,
        end));
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

    List<Object[]> rawRows = repository.aggregateToolCallsTimeseries(
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

    List<Object[]> rawRows = repository.aggregateToolCallsTimeseriesInRange(
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
}
