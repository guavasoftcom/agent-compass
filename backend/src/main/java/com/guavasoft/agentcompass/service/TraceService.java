package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.mapper.SpanMapper;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.ToolLatency;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.repository.TraceRootProjection;
import com.guavasoft.agentcompass.repository.TraceSummaryProjection;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraceService {

  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final double NANOS_PER_MILLI = 1_000_000.0d;

  private final SpanRepository spanRepository;
  private final SpanMapper spanMapper;
  private final TuningProperties tuningProperties;

  public List<TraceSummary> recentTraces(Integer minutes) {
    Instant since = sinceFromMinutes(minutes);
    PageRequest pageRequest = PageRequest.of(0, DEFAULT_PAGE_SIZE);
    List<TraceSummaryProjection> aggregations = since == null
        ? spanRepository.findTraceSummaries(pageRequest)
        : spanRepository.findTraceSummariesSince(since, pageRequest);
    return hydrateTraceSummaries(aggregations);
  }

  public List<TraceSummary> recentTracesInRange(Instant start, Instant end) {
    PageRequest pageRequest = PageRequest.of(0, DEFAULT_PAGE_SIZE);
    List<TraceSummaryProjection> traceSummaryProjections = spanRepository.findTraceSummariesInRange(start, end,
        pageRequest);
    return hydrateTraceSummaries(traceSummaryProjections);
  }

  public long countTraces(Integer minutes) {
    Instant since = sinceFromMinutes(minutes);
    return since == null
        ? spanRepository.countDistinctTraces()
        : spanRepository.countDistinctTracesSince(since);
  }

  public long countTracesInRange(Instant start, Instant end) {
    return spanRepository.countDistinctTracesInRange(start, end);
  }

  private List<TraceSummary> hydrateTraceSummaries(List<TraceSummaryProjection> aggregations) {
    if (aggregations.isEmpty()) {
      return List.of();
    }
    List<String> traceIds = aggregations.stream().map(TraceSummaryProjection::getTraceId).toList();
    List<TraceRootProjection> rootSpans = spanRepository.findRootSpansByTraceIds(traceIds);
    Map<String, String> rootSpanNamesByTraceId = rootSpans.stream()
        .collect(Collectors.toUnmodifiableMap(
            TraceRootProjection::getTraceId,
            TraceRootProjection::getName,
            (existing, replacement) -> existing));
    Map<String, String> sessionIdsByTraceId = rootSpans.stream()
        .filter(projection -> projection.getSessionId() != null)
        .collect(Collectors.toUnmodifiableMap(
            TraceRootProjection::getTraceId,
            TraceRootProjection::getSessionId,
            (existing, replacement) -> existing));
    Map<String, String> rootSpanIdsByTraceId = rootSpans.stream()
        .filter(projection -> projection.getSpanId() != null)
        .collect(Collectors.toUnmodifiableMap(
            TraceRootProjection::getTraceId,
            TraceRootProjection::getSpanId,
            (existing, replacement) -> existing));
    return aggregations.stream()
        .map(aggregation -> toTraceSummary(
            aggregation,
            rootSpanNamesByTraceId.get(aggregation.getTraceId()),
            sessionIdsByTraceId.get(aggregation.getTraceId()),
            rootSpanIdsByTraceId.get(aggregation.getTraceId())))
        .toList();
  }

  private static Instant sinceFromMinutes(Integer minutes) {
    if (minutes == null || minutes <= 0) {
      return null;
    }
    return Instant.now().minus(Duration.ofMinutes(minutes));
  }

  public List<Span> spansForTrace(String traceId) {
    List<SpanEntity> spanEntities = spanRepository.findByTraceIdOrderByStartTimestampAsc(traceId);
    return spanMapper.toSpans(spanEntities);
  }

  public List<ToolLatency> aggregateToolLatency(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    List<Object[]> rows = spanRepository.aggregateToolLatency(
        tuningProperties.getToolSpanScope(),
        tuningProperties.getToolSpanName(),
        tuningProperties.getToolAttribute(),
        since);
    return rows.stream()
        .map(TraceService::toToolLatency)
        .toList();
  }

  public List<ToolLatency> aggregateToolLatencyInRange(Instant start, Instant end) {
    List<Object[]> rows = spanRepository.aggregateToolLatencyInRange(
        tuningProperties.getToolSpanScope(),
        tuningProperties.getToolSpanName(),
        tuningProperties.getToolAttribute(),
        start,
        end);
    return rows.stream()
        .map(TraceService::toToolLatency)
        .toList();
  }

  private static ToolLatency toToolLatency(Object[] row) {
    String tool = (String) row[0];
    long calls = ((Number) row[1]).longValue();
    double p50Ms = ((Number) row[2]).doubleValue() / NANOS_PER_MILLI;
    double p95Ms = ((Number) row[3]).doubleValue() / NANOS_PER_MILLI;
    return new ToolLatency(tool, calls, p50Ms, p95Ms);
  }

  private static TraceSummary toTraceSummary(
      TraceSummaryProjection aggregation, String rootSpanName, String sessionId, String rootSpanId) {
    long durationNanos = Duration.between(aggregation.getMinStart(), aggregation.getMaxEnd()).toNanos();
    return TraceSummary.builder()
        .traceId(aggregation.getTraceId())
        .rootSpanName(rootSpanName)
        .sessionId(sessionId)
        .rootSpanId(rootSpanId)
        .spanCount(aggregation.getSpanCount())
        .startTimestamp(aggregation.getMinStart())
        .endTimestamp(aggregation.getMaxEnd())
        .durationNanos(durationNanos)
        .errorCount(aggregation.getErrorCount())
        .build();
  }
}
