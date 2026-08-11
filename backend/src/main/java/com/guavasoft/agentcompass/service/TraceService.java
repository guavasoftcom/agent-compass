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
import java.util.function.Function;

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
    applySpanEfforts(traceId, spans);
    return spans;
  }

  // One grouped query for the whole trace instead of one correlated subquery
  // per span -- see SpanRepository#findSpanCostsForTrace.
  private void applySpanCosts(String traceId, List<Span> spans) {
    Map<String, Double> costBySpanId =
        mapBySpanId(spanRepository.findSpanCostsForTrace(traceId), row -> ((Number) row[1]).doubleValue());
    for (Span span : spans) {
      span.setCostUsd(costBySpanId.getOrDefault(span.getSpanId(), DEFAULT_SPAN_COST_USD));
    }
  }

  // Same one-grouped-query-per-trace shape as applySpanCosts. Deliberately has
  // no default: a span missing from span_efforts stays null, because "no effort
  // was recorded for this call" and "this call ran at the default effort" are
  // different facts and the UI needs to be able to tell them apart.
  private void applySpanEfforts(String traceId, List<Span> spans) {
    Map<String, String> effortBySpanId =
        mapBySpanId(spanRepository.findSpanEffortsForTrace(traceId), row -> (String) row[1]);
    for (Span span : spans) {
      span.setEffort(effortBySpanId.get(span.getSpanId()));
    }
  }

  // Shared by applySpanCosts and applySpanEfforts: both queries return rows
  // keyed by span_id (column 0) and differ only in the value type held in
  // column 1 and how a missing key is handled by the caller.
  private static <T> Map<String, T> mapBySpanId(List<Object[]> rows, Function<Object[], T> valueExtractor) {
    Map<String, T> valueBySpanId = new HashMap<>();
    for (Object[] row : rows) {
      valueBySpanId.put((String) row[0], valueExtractor.apply(row));
    }
    return valueBySpanId;
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
