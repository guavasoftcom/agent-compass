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
    repository.saveAll(points);

    log.debug("Ingested {} metric data points from OTLP/HTTP protobuf", points.size());
    return points.size();
  }
}
