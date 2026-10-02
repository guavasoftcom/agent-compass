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
package com.guavasoft.agentcompass.otlp.mapper;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.otlp.model.ResourceContext;
import com.guavasoft.agentcompass.otlp.util.OtlpAttributeUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class OtlpLogMapper {

  private final AgentProfileResolver agentProfileResolver;

  public List<LogRecordEntity> toLogRecords(ExportLogsServiceRequest request, Instant receivedAt) {
    return request.getResourceLogsList().stream()
        .flatMap(resourceLogs -> entitiesFromResourceLogs(resourceLogs, receivedAt))
        .toList();
  }

  private Stream<LogRecordEntity> entitiesFromResourceLogs(ResourceLogs resourceLogs, Instant receivedAt) {
    Map<String, Object> resourceAttributes = OtlpAttributeUtils.toMap(resourceLogs.getResource().getAttributesList());
    // Resolved once per resource block, not per record — every log under one resource came from the
    // same exporting process, so the profile is the same for all of them.
    ResourceContext resourceContext =
        new ResourceContext(resourceAttributes, agentProfileResolver.resolve(resourceAttributes));
    return resourceLogs.getScopeLogsList().stream()
        .flatMap(scopeLogs -> entitiesFromScopeLogs(scopeLogs, resourceContext, receivedAt));
  }

  private Stream<LogRecordEntity> entitiesFromScopeLogs(
      ScopeLogs scopeLogs, ResourceContext resourceContext, Instant receivedAt) {
    String scopeName = scopeLogs.getScope().getName();
    Map<String, Object> scopeAttributes = OtlpAttributeUtils.toMap(scopeLogs.getScope().getAttributesList());
    return scopeLogs.getLogRecordsList().stream()
        .map(logRecord -> toEntity(logRecord, scopeName, resourceContext, scopeAttributes, receivedAt));
  }

  private LogRecordEntity toEntity(LogRecord logRecord, String scopeName,
      ResourceContext resourceContext, Map<String, Object> scopeAttributes, Instant receivedAt) {
    LogRecordEntity entity = new LogRecordEntity();
    long timestampNanos = logRecord.getTimeUnixNano() > 0
        ? logRecord.getTimeUnixNano()
        : logRecord.getObservedTimeUnixNano();
    entity.setTimestamp(OtlpAttributeUtils.nanosToInstant(timestampNanos));
    if (logRecord.getObservedTimeUnixNano() > 0) {
      entity.setObservedTimestamp(OtlpAttributeUtils.nanosToInstant(logRecord.getObservedTimeUnixNano()));
    }
    if (logRecord.getSeverityNumberValue() != 0) {
      entity.setSeverityNumber(logRecord.getSeverityNumberValue());
    }
    if (!logRecord.getSeverityText().isEmpty()) {
      entity.setSeverityText(logRecord.getSeverityText());
    }
    if (logRecord.hasBody()) {
      entity.setBody(OtlpAttributeUtils.flattenToString(logRecord.getBody()));
    }
    entity.setScopeName(scopeName);
    entity.setTraceId(OtlpAttributeUtils.toHexOrNull(logRecord.getTraceId()));
    entity.setSpanId(OtlpAttributeUtils.toHexOrNull(logRecord.getSpanId()));
    entity.setAttributes(OtlpAttributeUtils.toMap(logRecord.getAttributesList()));
    entity.setResourceAttributes(resourceContext.resourceAttributes());
    entity.setScopeAttributes(scopeAttributes);
    entity.setReceivedAt(receivedAt);
    entity.setAgentProfile(resourceContext.agentProfile());
    return entity;
  }
}
