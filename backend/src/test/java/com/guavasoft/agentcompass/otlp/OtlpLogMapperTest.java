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
package com.guavasoft.agentcompass.otlp;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import org.junit.jupiter.api.Test;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.otlp.mapper.OtlpLogMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents how OtlpLogMapper handles the OTLP severity fields. Both
 * severityNumber and
 * severityText are deliberately dropped to null when the OTLP record carries
 * the protobuf
 * defaults (0 / empty string) — those defaults mean "unset" per the OTLP spec,
 * and many
 * agents (Claude Code included) emit event-style logs without setting severity
 * at all.
 * The dashboard's empty Severity column is the visible symptom of that.
 */
class OtlpLogMapperTest {

  private static final long FIXED_TIMESTAMP_NANOS = 1_716_300_000_000_000_000L;
  private final OtlpLogMapper mapper = new OtlpLogMapper();
  private final Instant receivedAt = Instant.parse("2026-05-21T12:00:00Z");

  @Test
  void severityIsPersistedWhenOtlpRecordSetsBothFields() {
    ExportLogsServiceRequest request = wrap(LogRecord.newBuilder()
        .setTimeUnixNano(FIXED_TIMESTAMP_NANOS)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
        .setSeverityText("ERROR")
        .build());

    List<LogRecordEntity> entities = mapper.toLogRecords(request, receivedAt);

    assertThat(entities).hasSize(1);
    LogRecordEntity entity = entities.get(0);
    assertThat(entity.getSeverityNumber()).isEqualTo(SeverityNumber.SEVERITY_NUMBER_ERROR.getNumber());
    assertThat(entity.getSeverityText()).isEqualTo("ERROR");
  }

  @Test
  void severityIsNullWhenOtlpRecordOmitsBothFields() {
    ExportLogsServiceRequest request = wrap(LogRecord.newBuilder()
        .setTimeUnixNano(FIXED_TIMESTAMP_NANOS)
        .build());

    List<LogRecordEntity> entities = mapper.toLogRecords(request, receivedAt);

    assertThat(entities).hasSize(1);
    LogRecordEntity entity = entities.get(0);
    assertThat(entity.getSeverityNumber()).isNull();
    assertThat(entity.getSeverityText()).isNull();
  }

  @Test
  void severityNumberAloneIsPersistedAndTextIsDerivedByTheFrontend() {
    ExportLogsServiceRequest request = wrap(LogRecord.newBuilder()
        .setTimeUnixNano(FIXED_TIMESTAMP_NANOS)
        .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN)
        .build());

    List<LogRecordEntity> entities = mapper.toLogRecords(request, receivedAt);

    LogRecordEntity entity = entities.get(0);
    assertThat(entity.getSeverityNumber()).isEqualTo(SeverityNumber.SEVERITY_NUMBER_WARN.getNumber());
    assertThat(entity.getSeverityText()).isNull();
  }

  private static ExportLogsServiceRequest wrap(LogRecord logRecord) {
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder()
            .addScopeLogs(ScopeLogs.newBuilder()
                .addLogRecords(logRecord)
                .build())
            .build())
        .build();
  }
}
