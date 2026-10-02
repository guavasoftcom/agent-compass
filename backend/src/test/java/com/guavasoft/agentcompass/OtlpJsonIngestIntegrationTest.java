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
import com.google.protobuf.util.JsonFormat;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
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
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the OTLP/JSON ingest variant added for GitHub Copilot CLI, whose default
 * {@code OTEL_EXPORTER_OTLP_PROTOCOL} is {@code http/json} rather than {@code http/protobuf} (VS
 * Code's Copilot extension defaults the other way, and already worked against the protobuf-only
 * endpoints before this). See
 * {@code .design-docs/copilot-telemetry-ingest-plan.md}, Step 1.
 *
 * <p>Each signal's test builds ONE protobuf request, derives its JSON encoding from the exact same
 * message via {@link JsonFormat.Printer#print}, and posts both to the respective endpoints,
 * asserting the resulting rows are identical. This is deliberately not "construct two separate
 * fixtures that happen to look the same" — a drift between the two fixtures would be a false pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OtlpJsonIngestIntegrationTest {

  private static final String SCOPE_NAME = "agent-compass.test";
  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

  @LocalServerPort
  int port;

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  SpanRepository spanRepository;

  @Autowired
  MetricPointRepository metricPointRepository;

  @BeforeEach
  void clearSignals() {
    logRecordRepository.deleteAll();
    spanRepository.deleteAll();
    metricPointRepository.deleteAll();
  }

  @Test
  void jsonLogIngestProducesTheSameRowAsProtobuf() throws Exception {
    long timeUnixNanos = Instant.now().toEpochMilli() * 1_000_000L;
    ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder()
            .setResource(Resource.newBuilder()
                .addAttributes(stringAttr(SERVICE_NAME_ATTRIBUTE, "claude-code"))
                .build())
            .addScopeLogs(ScopeLogs.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addLogRecords(LogRecord.newBuilder()
                    .setTimeUnixNano(timeUnixNanos)
                    .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                    .addAttributes(stringAttr("event.name", "tool_result"))
                    .addAttributes(stringAttr("tool_name", "Edit"))
                    .build())
                .build())
            .build())
        .build();

    postProtobuf("/v1/logs", request.toByteArray());
    LogRecordEntity fromProtobuf = onlyRow(logRecordRepository.findAll());
    logRecordRepository.deleteAll();

    postJson("/v1/logs", JsonFormat.printer().print(request));
    LogRecordEntity fromJson = onlyRow(logRecordRepository.findAll());

    assertThat(fromJson.getSeverityNumber()).isEqualTo(fromProtobuf.getSeverityNumber());
    assertThat(fromJson.getAttributes()).isEqualTo(fromProtobuf.getAttributes());
    assertThat(fromJson.getResourceAttributes()).isEqualTo(fromProtobuf.getResourceAttributes());
    assertThat(fromJson.getAgentProfile()).isEqualTo(fromProtobuf.getAgentProfile());
  }

  @Test
  void jsonTraceIngestProducesTheSameRowAsProtobuf() throws Exception {
    long start = Instant.now().toEpochMilli() * 1_000_000L;
    long end = start + 25_000_000L;
    ByteString traceId = ByteString.copyFrom(new byte[] {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10
    });
    ByteString spanId = ByteString.copyFrom(new byte[] { 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18 });

    ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .setResource(Resource.newBuilder()
                .addAttributes(stringAttr(SERVICE_NAME_ATTRIBUTE, "claude-code"))
                .build())
            .addScopeSpans(ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addSpans(Span.newBuilder()
                    .setTraceId(traceId)
                    .setSpanId(spanId)
                    .setName("claude_code.tool")
                    .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
                    .setStartTimeUnixNano(start)
                    .setEndTimeUnixNano(end)
                    .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                    .addAttributes(stringAttr("command", "ls -la"))
                    .build())
                .build())
            .build())
        .build();

    postProtobuf("/v1/traces", request.toByteArray());
    SpanEntity fromProtobuf = onlyRow(spanRepository.findAll());
    spanRepository.deleteAll();

    postJson("/v1/traces", JsonFormat.printer().print(request));
    SpanEntity fromJson = onlyRow(spanRepository.findAll());

    assertThat(fromJson.getTraceId()).isEqualTo(fromProtobuf.getTraceId());
    assertThat(fromJson.getSpanId()).isEqualTo(fromProtobuf.getSpanId());
    assertThat(fromJson.getName()).isEqualTo(fromProtobuf.getName());
    assertThat(fromJson.getStatusCode()).isEqualTo(fromProtobuf.getStatusCode());
    assertThat(fromJson.getDurationNanos()).isEqualTo(fromProtobuf.getDurationNanos());
    assertThat(fromJson.getAttributes()).isEqualTo(fromProtobuf.getAttributes());
    assertThat(fromJson.getAgentProfile()).isEqualTo(fromProtobuf.getAgentProfile());
  }

  @Test
  void jsonMetricIngestProducesTheSameRowAsProtobufIncludingStreamId() throws Exception {
    long timeUnixNanos = Instant.now().toEpochMilli() * 1_000_000L;
    ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(ResourceMetrics.newBuilder()
            .setResource(Resource.newBuilder()
                .addAttributes(stringAttr(SERVICE_NAME_ATTRIBUTE, "claude-code"))
                .build())
            .addScopeMetrics(ScopeMetrics.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addMetrics(Metric.newBuilder()
                    .setName("json.ingest.parity")
                    .setUnit("tokens")
                    .setSum(Sum.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder()
                            .setTimeUnixNano(timeUnixNanos)
                            .setAsInt(100)
                            .addAttributes(stringAttr("type", "input"))
                            .build())
                        .build())
                    .build())
                .build())
            .build())
        .build();

    postProtobuf("/v1/metrics", request.toByteArray());
    MetricPointEntity fromProtobuf = onlyRow(metricPointRepository.findAll());
    metricPointRepository.deleteAll();

    postJson("/v1/metrics", JsonFormat.printer().print(request));
    MetricPointEntity fromJson = onlyRow(metricPointRepository.findAll());

    assertThat(fromJson.getMetricName()).isEqualTo(fromProtobuf.getMetricName());
    assertThat(fromJson.getValueLong()).isEqualTo(fromProtobuf.getValueLong());
    assertThat(fromJson.getAttributes()).isEqualTo(fromProtobuf.getAttributes());
    assertThat(fromJson.getAgentProfile()).isEqualTo(fromProtobuf.getAgentProfile());
    // The JSON path must feed the same value into the stream_id generated column as the protobuf
    // path -- a resolver or mapper bug that only shows up on one of the two entry points would
    // otherwise split one logical stream's deltas across two stream_ids.
    assertThat(fromJson.getStreamId()).isEqualTo(fromProtobuf.getStreamId());
    assertThat(fromJson.getValueDelta()).isEqualTo(fromProtobuf.getValueDelta());
  }

  @Test
  void malformedJsonIsRejectedAsAPartialSuccessNotA500() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    // RestTemplate's default error handler throws on any 4xx/5xx rather than returning it as a
    // ResponseEntity -- the exception IS the response here, same shape as every other RestTemplate
    // call in this suite that expects a 2xx, just inverted.
    HttpClientErrorException thrown = org.junit.jupiter.api.Assertions.assertThrows(
        HttpClientErrorException.class,
        () -> new RestTemplate().exchange(
            baseUrl() + "/v1/logs",
            HttpMethod.POST,
            new HttpEntity<>("{ this is not valid json", headers),
            String.class));

    assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(thrown.getResponseBodyAsString()).contains("partialSuccess", "errorMessage");
  }

  private static <T> T onlyRow(java.util.List<T> rows) {
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  private String baseUrl() {
    return "http://localhost:" + port;
  }

  private void postProtobuf(String path, byte[] body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_PROTOBUF);
    ResponseEntity<byte[]> response = new RestTemplate().exchange(
        baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
  }

  private void postJson(String path, String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response = new RestTemplate().exchange(
        baseUrl() + path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    assertThat(response.getStatusCode().is2xxSuccessful())
        .as("JSON ingest response: %s", response.getBody())
        .isTrue();
  }

  private static KeyValue stringAttr(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value).build())
        .build();
  }
}
