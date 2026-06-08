package com.guavasoft.agentcompass.otlp.mapper;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.otlp.model.MetricValueKind;
import com.guavasoft.agentcompass.otlp.util.OtlpAttributeUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class OtlpMetricMapper {

  public List<MetricPointEntity> toMetricPoints(ExportMetricsServiceRequest request, Instant receivedAt) {
    return request.getResourceMetricsList().stream()
        .flatMap(resourceMetrics -> entitiesFromResourceMetrics(resourceMetrics, receivedAt))
        .toList();
  }

  private Stream<MetricPointEntity> entitiesFromResourceMetrics(
      ResourceMetrics resourceMetrics, Instant receivedAt) {
    Map<String, Object> resourceAttributes = OtlpAttributeUtils
        .toMap(resourceMetrics.getResource().getAttributesList());
    return resourceMetrics.getScopeMetricsList().stream()
        .flatMap(scopeMetrics -> entitiesFromScopeMetrics(scopeMetrics, resourceAttributes, receivedAt));
  }

  private Stream<MetricPointEntity> entitiesFromScopeMetrics(
      ScopeMetrics scopeMetrics, Map<String, Object> resourceAttributes, Instant receivedAt) {
    String scopeName = scopeMetrics.getScope().getName();
    Map<String, Object> scopeAttributes = OtlpAttributeUtils.toMap(scopeMetrics.getScope().getAttributesList());
    return scopeMetrics.getMetricsList().stream()
        .flatMap(metric -> entitiesFromMetric(
            metric, scopeName, resourceAttributes, scopeAttributes, receivedAt));
  }

  private Stream<MetricPointEntity> entitiesFromMetric(
      Metric metric, String scopeName,
      Map<String, Object> resourceAttributes, Map<String, Object> scopeAttributes, Instant receivedAt) {
    return numberPointsOf(metric).stream()
        .map(numberDataPoint -> toEntity(
            metric, numberDataPoint, scopeName, resourceAttributes, scopeAttributes, receivedAt));
  }

  private MetricPointEntity toEntity(Metric metric, NumberDataPoint numberDataPoint, String scopeName,
      Map<String, Object> resourceAttributes, Map<String, Object> scopeAttributes, Instant receivedAt) {
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
    entity.setResourceAttributes(resourceAttributes);
    entity.setScopeAttributes(scopeAttributes);
    entity.setReceivedAt(receivedAt);
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
