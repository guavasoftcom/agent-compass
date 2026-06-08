package com.guavasoft.agentcompass.otlp.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.otlp.mapper.OtlpTraceMapper;
import com.guavasoft.agentcompass.repository.SpanRepository;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtlpTraceService {

  private final OtlpTraceMapper mapper;
  private final SpanRepository repository;

  @Transactional
  public int ingestProtobuf(byte[] body) throws InvalidProtocolBufferException {
    ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
    List<SpanEntity> spans = mapper.toSpans(request, Instant.now());
    repository.saveAll(spans);

    log.debug("Ingested {} spans from OTLP/HTTP protobuf", spans.size());
    return spans.size();
  }
}
