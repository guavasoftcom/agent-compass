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
    return ingest(ExportLogsServiceRequest.parseFrom(body));
  }

  /**
   * Same ingest path as {@link #ingestProtobuf}, for a sender whose {@code OTEL_EXPORTER_OTLP_PROTOCOL}
   * is {@code http/json} rather than {@code http/protobuf} -- GitHub Copilot CLI's default. Reuses
   * {@link InvalidProtocolBufferException} as its failure type: {@link JsonFormat.Parser#merge}
   * throws it too, so the controller's existing malformed-payload handling needs no new catch clause.
   */
  @Transactional
  public int ingestJson(String json) throws InvalidProtocolBufferException {
    ExportLogsServiceRequest.Builder builder = ExportLogsServiceRequest.newBuilder();
    JsonFormat.parser().ignoringUnknownFields().merge(json, builder);
    return ingest(builder.build());
  }

  private int ingest(ExportLogsServiceRequest request) {
    List<LogRecordEntity> records = mapper.toLogRecords(request, Instant.now());
    repository.saveAll(records);

    log.debug("Ingested {} log records from OTLP/HTTP", records.size());
    return records.size();
  }
}
