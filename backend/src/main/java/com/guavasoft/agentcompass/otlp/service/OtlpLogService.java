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
