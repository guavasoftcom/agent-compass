package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.mapper.SpanMapper;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.ToolLatency;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraceService {

  private static final double NANOS_PER_MILLI = 1_000_000.0d;

  // Default Span.costUsd for a span no api_request log was ever stamped
  // against -- matches the COALESCE(..., 0) the removed @Formula used.
  private static final double DEFAULT_SPAN_COST_USD = 0.0d;

  private final SpanRepository spanRepository;
  private final SpanMapper spanMapper;
  private final TuningProperties tuningProperties;

  public List<Span> spansForTrace(String traceId) {
    List<SpanEntity> spanEntities = spanRepository.findByTraceIdOrderByStartTimestampAsc(traceId);
    List<Span> spans = spanMapper.toSpans(spanEntities);
    applySpanCosts(traceId, spans);
    return spans;
  }

  // One grouped query for the whole trace instead of one correlated subquery
  // per span -- see SpanRepository#findSpanCostsForTrace.
  private void applySpanCosts(String traceId, List<Span> spans) {
    Map<String, Double> costBySpanId = new HashMap<>();
    for (Object[] costRow : spanRepository.findSpanCostsForTrace(traceId)) {
      costBySpanId.put((String) costRow[0], ((Number) costRow[1]).doubleValue());
    }
    for (Span span : spans) {
      span.setCostUsd(costBySpanId.getOrDefault(span.getSpanId(), DEFAULT_SPAN_COST_USD));
    }
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
}
