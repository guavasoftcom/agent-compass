/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.otlp.service;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.otlp.mapper.OtlpTraceMapper;
import com.guavasoft.agentcompass.otlp.normalize.CopilotCostRows;
import com.guavasoft.agentcompass.otlp.normalize.CopilotTelemetryNormalizer;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
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
  private final LogRecordRepository logRecordRepository;
  private final CopilotTelemetryNormalizer copilotTelemetryNormalizer;
  private final OtlpMetricService metricService;

  @Transactional
  public int ingestProtobuf(byte[] body) throws InvalidProtocolBufferException {
    return ingest(ExportTraceServiceRequest.parseFrom(body));
  }

  /**
   * Same ingest path as {@link #ingestProtobuf}, for a sender whose {@code OTEL_EXPORTER_OTLP_PROTOCOL}
   * is {@code http/json} rather than {@code http/protobuf} -- GitHub Copilot CLI's default. Reuses
   * {@link InvalidProtocolBufferException} as its failure type: {@link JsonFormat.Parser#merge}
   * throws it too, so the controller's existing malformed-payload handling needs no new catch clause.
   */
  @Transactional
  public int ingestJson(String json) throws InvalidProtocolBufferException {
    ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
    JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
    return ingest(builder.build());
  }

  private int ingest(ExportTraceServiceRequest request) {
    List<SpanEntity> spans = mapper.toSpans(request, Instant.now());
    repository.saveAll(spans);

    // Copilot records a tool call as a span and never as a tool_result log, which is what every
    // tool-activity query reads. The synthesized logs are saved in this same transaction, so a
    // span is never stored without the log that makes it visible. Empty for any other agent.
    List<LogRecordEntity> toolResultLogs = copilotTelemetryNormalizer.toolResultLogsFrom(spans);
    logRecordRepository.saveAll(toolResultLogs);

    // Copilot also reports each model call's cost on its chat span and nowhere else. Each call
    // becomes an api_request log (the exact per-call pipeline) and a one-point cost counter stream
    // (the counter pipeline). The points must go through the metric service so they get a
    // value_delta; all of it commits with the spans, or none of it does.
    CopilotCostRows costRows = copilotTelemetryNormalizer.costRowsFrom(spans);
    logRecordRepository.saveAll(costRows.apiRequestLogs());
    metricService.persist(costRows.costPoints());

    log.debug("Ingested {} spans, {} synthesized tool results and {} synthesized model calls from OTLP/HTTP",
        spans.size(), toolResultLogs.size(), costRows.apiRequestLogs().size());
    return spans.size();
  }
}
