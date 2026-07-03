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
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraceService {

  private static final double NANOS_PER_MILLI = 1_000_000.0d;

  private final SpanRepository spanRepository;
  private final SpanMapper spanMapper;
  private final TuningProperties tuningProperties;

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
}
