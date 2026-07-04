package com.guavasoft.agentcompass.otlp.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.otlp.mapper.OtlpMetricMapper;
import com.guavasoft.agentcompass.repository.MetricPointRepository;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtlpMetricService {

  private final OtlpMetricMapper mapper;
  private final MetricPointRepository repository;

  @Transactional
  public int ingestProtobuf(byte[] body) throws InvalidProtocolBufferException {
    ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(body);
    List<MetricPointEntity> points = mapper.toMetricPoints(request, Instant.now());
    List<MetricPointEntity> savedPoints = repository.saveAll(points);

    // IDENTITY generation means every row above is already persisted, so the ids
    // are populated here. Compute each row's reset-aware value_delta now, in the
    // same transaction, against its true previous same-stream row (see
    // MetricPointRepository#recomputeValueDeltas) — this is the one-time cost
    // that lets every dashboard aggregation read value_delta as a plain SUM
    // instead of re-deriving it with a window function per query.
    List<Long> metricPointIds = savedPoints.stream().map(MetricPointEntity::getId).toList();
    if (!metricPointIds.isEmpty()) {
      repository.recomputeValueDeltas(metricPointIds);
    }

    log.debug("Ingested {} metric data points from OTLP/HTTP protobuf", points.size());
    return points.size();
  }
}
