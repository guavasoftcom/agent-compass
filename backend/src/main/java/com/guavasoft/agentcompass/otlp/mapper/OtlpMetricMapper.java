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

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.otlp.normalize.CopilotTelemetryNormalizer;
import com.guavasoft.agentcompass.otlp.model.MetricValueKind;
import com.guavasoft.agentcompass.otlp.model.ResourceContext;
import com.guavasoft.agentcompass.otlp.util.OtlpAttributeUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class OtlpMetricMapper {

  private final AgentProfileResolver agentProfileResolver;
  private final CopilotTelemetryNormalizer copilotTelemetryNormalizer;

  public List<MetricPointEntity> toMetricPoints(ExportMetricsServiceRequest request, Instant receivedAt) {
    return request.getResourceMetricsList().stream()
        .flatMap(resourceMetrics -> entitiesFromResourceMetrics(resourceMetrics, receivedAt))
        .toList();
  }

  private Stream<MetricPointEntity> entitiesFromResourceMetrics(
      ResourceMetrics resourceMetrics, Instant receivedAt) {
    Map<String, Object> resourceAttributes = OtlpAttributeUtils
        .toMap(resourceMetrics.getResource().getAttributesList());
    // Resolved once per resource block, not per datapoint. This matters more here than on the other
    // two signals: a single export carries thousands of points, and the profile also feeds the
    // stream_id generated column (V38), so it must be set before saveAll rather than patched after.
    ResourceContext resourceContext =
        new ResourceContext(resourceAttributes, agentProfileResolver.resolve(resourceAttributes));
    List<MetricPointEntity> resourceBlockPoints = resourceMetrics.getScopeMetricsList().stream()
        .flatMap(scopeMetrics -> entitiesFromScopeMetrics(scopeMetrics, resourceContext, receivedAt))
        .toList();
    // Canonical points are appended to the same output stream rather than saved separately, so they
    // go through OtlpMetricService's lockStreamsForIngest / recomputeValueDeltas like every other
    // row; saved on their own they would never receive a value_delta. A no-op for any resource that
    // is not Copilot's, so every other agent's output is exactly the raw points.
    List<MetricPointEntity> canonicalPoints =
        copilotTelemetryNormalizer.canonicalTokenPointsFrom(resourceBlockPoints);
    return Stream.concat(resourceBlockPoints.stream(), canonicalPoints.stream());
  }

  private Stream<MetricPointEntity> entitiesFromScopeMetrics(
      ScopeMetrics scopeMetrics, ResourceContext resourceContext, Instant receivedAt) {
    String scopeName = scopeMetrics.getScope().getName();
    Map<String, Object> scopeAttributes = OtlpAttributeUtils.toMap(scopeMetrics.getScope().getAttributesList());
    return scopeMetrics.getMetricsList().stream()
        .flatMap(metric -> entitiesFromMetric(
            metric, scopeName, resourceContext, scopeAttributes, receivedAt));
  }

  private Stream<MetricPointEntity> entitiesFromMetric(
      Metric metric, String scopeName,
      ResourceContext resourceContext, Map<String, Object> scopeAttributes, Instant receivedAt) {
    return numberPointsOf(metric).stream()
        .map(numberDataPoint -> toEntity(
            metric, numberDataPoint, scopeName, resourceContext, scopeAttributes, receivedAt));
  }

  private MetricPointEntity toEntity(Metric metric, NumberDataPoint numberDataPoint, String scopeName,
      ResourceContext resourceContext, Map<String, Object> scopeAttributes, Instant receivedAt) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(metric.getName());
    entity.setUnit(metric.getUnit());
    entity.setDescription(metric.getDescription());
    entity.setScopeName(scopeName);
    entity.setTimestamp(OtlpAttributeUtils.nanosToInstant(numberDataPoint.getTimeUnixNano()));
    if (numberDataPoint.getStartTimeUnixNano() > 0) {
      entity.setStartTimestamp(OtlpAttributeUtils.nanosToInstant(numberDataPoint.getStartTimeUnixNano()));
    }
    switch (numberDataPoint.getValueCase()) {
      case AS_DOUBLE -> {
        entity.setValueDouble(numberDataPoint.getAsDouble());
        entity.setValueKind(MetricValueKind.DOUBLE.key());
      }
      case AS_INT -> {
        entity.setValueLong(numberDataPoint.getAsInt());
        entity.setValueKind(MetricValueKind.LONG.key());
      }
      default -> entity.setValueKind(MetricValueKind.UNKNOWN.key());
    }
    entity.setAttributes(OtlpAttributeUtils.toMap(numberDataPoint.getAttributesList()));
    entity.setResourceAttributes(resourceContext.resourceAttributes());
    entity.setScopeAttributes(scopeAttributes);
    entity.setReceivedAt(receivedAt);
    entity.setAgentProfile(resourceContext.agentProfile());
    return entity;
  }

  private List<NumberDataPoint> numberPointsOf(Metric metric) {
    return switch (metric.getDataCase()) {
      case SUM -> metric.getSum().getDataPointsList();
      case GAUGE -> metric.getGauge().getDataPointsList();
      default -> List.of();
    };
  }
}
