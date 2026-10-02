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

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.otlp.model.ResourceContext;
import com.guavasoft.agentcompass.otlp.model.SpanEventField;
import com.guavasoft.agentcompass.otlp.model.SpanKindLabel;
import com.guavasoft.agentcompass.otlp.util.OtlpAttributeUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class OtlpTraceMapper {

  private final AgentProfileResolver agentProfileResolver;

  public List<SpanEntity> toSpans(ExportTraceServiceRequest request, Instant receivedAt) {
    return request.getResourceSpansList().stream()
        .flatMap(resourceSpans -> entitiesFromResourceSpans(resourceSpans, receivedAt))
        .toList();
  }

  private Stream<SpanEntity> entitiesFromResourceSpans(ResourceSpans resourceSpans, Instant receivedAt) {
    Map<String, Object> resourceAttributes = OtlpAttributeUtils.toMap(resourceSpans.getResource().getAttributesList());
    // Resolved once per resource block, not per span. Spans carry no per-record equivalent of the
    // identifying attribute, which is part of why resolution reads the resource block at all.
    ResourceContext resourceContext =
        new ResourceContext(resourceAttributes, agentProfileResolver.resolve(resourceAttributes));
    return resourceSpans.getScopeSpansList().stream()
        .flatMap(scopeSpans -> entitiesFromScopeSpans(scopeSpans, resourceContext, receivedAt));
  }

  private Stream<SpanEntity> entitiesFromScopeSpans(
      ScopeSpans scopeSpans, ResourceContext resourceContext, Instant receivedAt) {
    String scopeName = scopeSpans.getScope().getName();
    Map<String, Object> scopeAttributes = OtlpAttributeUtils.toMap(scopeSpans.getScope().getAttributesList());
    return scopeSpans.getSpansList().stream()
        .map(span -> toEntity(span, scopeName, resourceContext, scopeAttributes, receivedAt));
  }

  private SpanEntity toEntity(Span span, String scopeName,
      ResourceContext resourceContext, Map<String, Object> scopeAttributes, Instant receivedAt) {
    SpanEntity entity = new SpanEntity();
    entity.setTraceId(OtlpAttributeUtils.toHexOrNull(span.getTraceId()));
    entity.setSpanId(OtlpAttributeUtils.toHexOrNull(span.getSpanId()));
    entity.setParentSpanId(OtlpAttributeUtils.toHexOrNull(span.getParentSpanId()));
    entity.setName(span.getName());
    entity.setKind(SpanKindLabel.labelFor(span.getKind()));
    entity.setStartTimestamp(OtlpAttributeUtils.nanosToInstant(span.getStartTimeUnixNano()));
    entity.setEndTimestamp(OtlpAttributeUtils.nanosToInstant(span.getEndTimeUnixNano()));
    entity.setDurationNanos(span.getEndTimeUnixNano() - span.getStartTimeUnixNano());
    if (span.hasStatus()) {
      entity.setStatusCode(statusToString(span.getStatus()));
      if (!span.getStatus().getMessage().isEmpty()) {
        entity.setStatusMessage(span.getStatus().getMessage());
      }
    }
    entity.setScopeName(scopeName);
    entity.setAttributes(OtlpAttributeUtils.toMap(span.getAttributesList()));
    entity.setEvents(toEventList(span.getEventsList()));
    entity.setResourceAttributes(resourceContext.resourceAttributes());
    entity.setScopeAttributes(scopeAttributes);
    entity.setReceivedAt(receivedAt);
    entity.setAgentProfile(resourceContext.agentProfile());
    return entity;
  }

  private List<Map<String, Object>> toEventList(List<Span.Event> events) {
    if (events.isEmpty()) {
      return null;
    }
    return events.stream()
        .map(SpanEventField::toJsonMap)
        .toList();
  }

  private String statusToString(Status status) {
    return switch (status.getCode()) {
      case STATUS_CODE_OK -> "ok";
      case STATUS_CODE_ERROR -> "error";
      default -> null;
    };
  }
}
