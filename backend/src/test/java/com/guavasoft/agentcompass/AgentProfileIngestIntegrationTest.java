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
package com.guavasoft.agentcompass;

import com.google.protobuf.ByteString;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises agent-profile stamping ({@code V37}) and the stream-identity rebuild ({@code V38})
 * end-to-end through the real OTLP/HTTP path, rather than seeding rows directly, so the actual
 * resolution wiring — once per resource block, before {@code saveAll}, early enough to feed the
 * {@code stream_id} generated column — is what is under test.
 *
 * <p>The two stream-separation tests at the bottom are the ones that matter most. They are the
 * regression net under {@code V38}: before it, {@code stream_id} was
 * {@code md5(metric_name || '|' || attributes::text)} with no resource identity at all, so two
 * concurrent emitters of one counter interleaved into a single stream and each row's
 * {@code value_delta} was computed against the other emitter's value.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AgentProfileIngestIntegrationTest {

  private static final String CLAUDE_CODE_PROFILE = "claude-code";
  private static final String UNKNOWN_PROFILE = "unknown";
  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";
  private static final String SESSION_ID_ATTRIBUTE = "session.id";
  private static final String SCOPE_NAME = "agent-compass.test";
  private static final long SPAN_DURATION_NANOS = 25_000_000L;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

  @LocalServerPort
  int port;

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  SpanRepository spanRepository;

  private long baseTimeUnixNanos;

  @BeforeEach
  void clearSignals() {
    metricPointRepository.deleteAll();
    logRecordRepository.deleteAll();
    spanRepository.deleteAll();
    baseTimeUnixNanos = Instant.now().minusSeconds(3_600).toEpochMilli() * 1_000_000L;
  }

  @Test
  void allThreeSignalsStampTheResolvedProfile() {
    Map<String, String> resource = Map.of(SERVICE_NAME_ATTRIBUTE, "claude-code");

    postMetricBatch(resource, "profile.all.signals", List.of(dataPoint(100, baseTimeUnixNanos)));
    postLogBatch(resource);
    postSpanBatch(resource);

    assertThat(metricPointRepository.findAll()).singleElement()
        .extracting(MetricPointEntity::getAgentProfile).isEqualTo(CLAUDE_CODE_PROFILE);
    assertThat(logRecordRepository.findAll()).singleElement()
        .extracting(entity -> entity.getAgentProfile()).isEqualTo(CLAUDE_CODE_PROFILE);
    assertThat(spanRepository.findAll()).singleElement()
        .extracting(entity -> entity.getAgentProfile()).isEqualTo(CLAUDE_CODE_PROFILE);
  }

  @Test
  void desktopVariantFoldsIntoTheSameProfile() {
    // Both service.name values are the same agent, and the live database holds both — so the
    // resolution map is many-to-one on purpose. An identity mapping would quarantine these rows.
    postMetricBatch(
        Map.of(SERVICE_NAME_ATTRIBUTE, "claude-code-desktop"),
        "profile.desktop",
        List.of(dataPoint(100, baseTimeUnixNanos)));

    assertThat(metricPointRepository.findAll()).singleElement()
        .extracting(MetricPointEntity::getAgentProfile).isEqualTo(CLAUDE_CODE_PROFILE);
  }

  @Test
  void unrecognizedAgentIsQuarantinedRatherThanCreditedToClaudeCode() {
    // The whole point of the unknown profile: a second agent's telemetry must be stored and
    // queryable without being folded into Claude Code's rollups, where its figures would be
    // silently wrong rather than merely mixed.
    postMetricBatch(
        Map.of(SERVICE_NAME_ATTRIBUTE, "some-other-agent"),
        "profile.unrecognized",
        List.of(dataPoint(100, baseTimeUnixNanos)));

    assertThat(metricPointRepository.findAll()).singleElement()
        .extracting(MetricPointEntity::getAgentProfile)
        .isEqualTo(UNKNOWN_PROFILE)
        .isNotEqualTo(CLAUDE_CODE_PROFILE);
  }

  @Test
  void resourceWithNoIdentifyingAttributeIsQuarantined() {
    postMetricBatch(Map.of(), "profile.no.service.name", List.of(dataPoint(100, baseTimeUnixNanos)));

    assertThat(metricPointRepository.findAll()).singleElement()
        .extracting(MetricPointEntity::getAgentProfile).isEqualTo(UNKNOWN_PROFILE);
  }

  @Test
  void streamsDifferingOnlyByResourceSessionIdAreNotMerged() {
    // Copilot's shape: same agent, same metric, identical DATAPOINT attributes, and identity
    // carried in the resource block (one session.id per VS Code window). Before V38 these hashed
    // identically and the two windows' counters were diffed against each other.
    String metricName = "profile.resource.session.split";
    Map<String, String> windowOne = orderedResource("claude-code", "window-1");
    Map<String, String> windowTwo = orderedResource("claude-code", "window-2");

    postMetricBatch(windowOne, metricName, List.of(dataPoint(100, baseTimeUnixNanos)));
    postMetricBatch(windowTwo, metricName, List.of(dataPoint(5_000, baseTimeUnixNanos)));
    postMetricBatch(windowOne, metricName, List.of(dataPoint(150, baseTimeUnixNanos + secondsToNanos(60))));
    postMetricBatch(windowTwo, metricName, List.of(dataPoint(5_300, baseTimeUnixNanos + secondsToNanos(60))));

    List<MetricPointEntity> rows = metricPointRepository.findAll();
    assertThat(rows).hasSize(4);
    assertThat(rows.stream().map(MetricPointEntity::getStreamId).distinct()).hasSize(2);

    // Each window's deltas are its own increments. Merged into one stream they would instead
    // read as a sawtooth of differences between two unrelated counters.
    assertThat(deltasForWindow(rows, "window-1")).containsExactly(100.0, 50.0);
    assertThat(deltasForWindow(rows, "window-2")).containsExactly(5_000.0, 300.0);
  }

  @Test
  void streamsDifferingOnlyByAgentProfileAreNotMerged() {
    // The cross-agent half of the same guarantee: no resource session.id anywhere, identical
    // metric name and datapoint attributes, separated purely by the resolved profile.
    String metricName = "profile.cross.agent.split";
    Map<String, String> claudeCode = Map.of(SERVICE_NAME_ATTRIBUTE, "claude-code");
    Map<String, String> otherAgent = Map.of(SERVICE_NAME_ATTRIBUTE, "some-other-agent");

    postMetricBatch(claudeCode, metricName, List.of(dataPoint(100, baseTimeUnixNanos)));
    postMetricBatch(otherAgent, metricName, List.of(dataPoint(5_000, baseTimeUnixNanos)));
    postMetricBatch(claudeCode, metricName, List.of(dataPoint(150, baseTimeUnixNanos + secondsToNanos(60))));
    postMetricBatch(otherAgent, metricName, List.of(dataPoint(5_300, baseTimeUnixNanos + secondsToNanos(60))));

    List<MetricPointEntity> rows = metricPointRepository.findAll();
    assertThat(rows).hasSize(4);
    assertThat(rows.stream().map(MetricPointEntity::getStreamId).distinct()).hasSize(2);
    assertThat(deltasForProfile(rows, CLAUDE_CODE_PROFILE)).containsExactly(100.0, 50.0);
    assertThat(deltasForProfile(rows, UNKNOWN_PROFILE)).containsExactly(5_000.0, 300.0);
  }

  private static List<Double> deltasForWindow(List<MetricPointEntity> rows, String sessionId) {
    return rows.stream()
        .filter(row -> sessionId.equals(row.getResourceAttributes().get(SESSION_ID_ATTRIBUTE)))
        .sorted(Comparator.comparing(MetricPointEntity::getTimestamp).thenComparing(MetricPointEntity::getId))
        .map(MetricPointEntity::getValueDelta)
        .toList();
  }

  private static List<Double> deltasForProfile(List<MetricPointEntity> rows, String agentProfile) {
    return rows.stream()
        .filter(row -> agentProfile.equals(row.getAgentProfile()))
        .sorted(Comparator.comparing(MetricPointEntity::getTimestamp).thenComparing(MetricPointEntity::getId))
        .map(MetricPointEntity::getValueDelta)
        .toList();
  }

  /** Resource attributes in a defined order, so the two windows differ only in the session id. */
  private static Map<String, String> orderedResource(String serviceName, String sessionId) {
    Map<String, String> resourceAttributes = new LinkedHashMap<>();
    resourceAttributes.put(SERVICE_NAME_ATTRIBUTE, serviceName);
    resourceAttributes.put(SESSION_ID_ATTRIBUTE, sessionId);
    return resourceAttributes;
  }

  private static long secondsToNanos(long seconds) {
    return seconds * 1_000_000_000L;
  }

  private static NumberDataPoint dataPoint(long value, long timeUnixNanos) {
    return NumberDataPoint.newBuilder()
        .setTimeUnixNano(timeUnixNanos)
        .setAsInt(value)
        .addAttributes(stringAttr("type", "input"))
        .build();
  }

  private void postMetricBatch(
      Map<String, String> resourceAttributes, String metricName, List<NumberDataPoint> dataPoints) {
    Sum.Builder sum = Sum.newBuilder();
    dataPoints.forEach(sum::addDataPoints);
    ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(ResourceMetrics.newBuilder()
            .setResource(resourceOf(resourceAttributes))
            .addScopeMetrics(ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addMetrics(Metric.newBuilder()
                    .setName(metricName)
                    .setUnit("tokens")
                    .setSum(sum.build())
                    .build())
                .build())
            .build())
        .build();
    post("/v1/metrics", request.toByteArray());
  }

  private void postLogBatch(Map<String, String> resourceAttributes) {
    ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder()
            .setResource(resourceOf(resourceAttributes))
            .addScopeLogs(ScopeLogs.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addLogRecords(LogRecord.newBuilder()
                    .setTimeUnixNano(baseTimeUnixNanos)
                    .setSeverityNumber(io.opentelemetry.proto.logs.v1.SeverityNumber.SEVERITY_NUMBER_INFO)
                    .addAttributes(stringAttr("event.name", "tool_result"))
                    .build())
                .build())
            .build())
        .build();
    post("/v1/logs", request.toByteArray());
  }

  private void postSpanBatch(Map<String, String> resourceAttributes) {
    ByteString traceId = ByteString.copyFrom(new byte[] {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
    });
    ByteString spanId = ByteString.copyFrom(new byte[] { 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18 });
    ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .setResource(resourceOf(resourceAttributes))
            .addScopeSpans(ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addSpans(Span.newBuilder()
                    .setTraceId(traceId)
                    .setSpanId(spanId)
                    .setName("claude_code.tool")
                    .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                    .setStartTimeUnixNano(baseTimeUnixNanos)
                    .setEndTimeUnixNano(baseTimeUnixNanos + SPAN_DURATION_NANOS)
                    .build())
                .build())
            .build())
        .build();
    post("/v1/traces", request.toByteArray());
  }

  private static Resource resourceOf(Map<String, String> resourceAttributes) {
    Resource.Builder resource = Resource.newBuilder();
    resourceAttributes.forEach((key, value) -> resource.addAttributes(stringAttr(key, value)));
    return resource.build();
  }

  private void post(String path, byte[] body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.valueOf("application/x-protobuf"));
    ResponseEntity<byte[]> response = new RestTemplate().exchange(
        "http://localhost:" + port + path,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        byte[].class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
  }

  private static KeyValue stringAttr(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value).build())
        .build();
  }
}
