package com.guavasoft.agentcompass.otlp.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.otlp.mapper.OtlpLogMapper;
import com.guavasoft.agentcompass.repository.LogRecordRepository;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtlpLogService {

  private final OtlpLogMapper mapper;
  private final LogRecordRepository repository;

  @Transactional
  public int ingestProtobuf(byte[] body) throws InvalidProtocolBufferException {
    ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(body);
    List<LogRecordEntity> records = mapper.toLogRecords(request, Instant.now());
    repository.saveAll(records);

    log.debug("Ingested {} log records from OTLP/HTTP protobuf", records.size());
    return records.size();
  }
}
