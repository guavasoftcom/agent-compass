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
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.AgentProfiles;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.MetricService;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Posts real OTLP protobuf for a {@code github-copilot} resource and asserts that the ingest-time
 * normalizer adds Claude-Code-shaped rows beside Copilot's raw ones, so the existing tool-activity
 * and token queries return data under the Copilot profile.
 *
 * <p>The mirror-image tests post the identical payload as {@code claude-code} and assert nothing is
 * synthesized: the normalizer must be a strict no-op for every other agent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CopilotNormalizationIngestIntegrationTest {

  private static final String COPILOT_SERVICE_NAME = "github-copilot";
  private static final String CLAUDE_CODE_SERVICE_NAME = "claude-code";
  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";
  private static final String SCOPE_NAME = "copilot.test";
  private static final String CANONICAL_TOKEN_METRIC = "claude_code.token.usage";
  private static final String INPUT_METRIC = "gen_ai.client.inference.usage.input_tokens";
  private static final String OUTPUT_METRIC = "gen_ai.client.inference.usage.output_tokens";
  private static final String REASONING_METRIC = "gen_ai.client.inference.usage.reasoning.output_tokens";
  private static final String CACHE_READ_METRIC = "gen_ai.client.inference.usage.cache_read.input_tokens";
  private static final String CACHE_WRITE_METRIC = "gen_ai.client.inference.usage.cache_write.input_tokens";
  private static final String MODEL = "gpt-5";
  private static final long INPUT_TOKENS = 460_280L;
  private static final long OUTPUT_TOKENS = 5_000L;
  private static final long REASONING_TOKENS = 3_000L;
  private static final long CACHE_READ_TOKENS = 389_930L;
  private static final long CACHE_WRITE_TOKENS = 70_329L;
  private static final long UNCACHED_INPUT_TOKENS = 21L;
  private static final int WINDOW_MINUTES = 120;
  private static final long NANOS_PER_MILLISECOND = 1_000_000L;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final long TOOL_DURATION_MILLISECONDS = 40L;

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

  @Autowired
  LogService logService;

  @Autowired
  MetricService metricService;

  @Autowired
  JdbcTemplate jdbcTemplate;

  private long baseTimeUnixNanos;

  @BeforeEach
  void clearSignals() {
    metricPointRepository.deleteAll();
    logRecordRepository.deleteAll();
    spanRepository.deleteAll();
    baseTimeUnixNanos = Instant.now().minusSeconds(600).toEpochMilli() * NANOS_PER_MILLISECOND;
  }

  @Test
  void copilotToolSpansAreVisibleToTheToolQueriesAndRawSpansAreKept() {
    postToolSpans(COPILOT_SERVICE_NAME);

    assertThat(spanRepository.count()).isEqualTo(3);
    List<ToolCallCount> calls = logService.aggregateToolCalls(WINDOW_MINUTES, null, AgentProfiles.GITHUB_COPILOT);
    assertThat(calls).extracting(ToolCallCount::getTool, ToolCallCount::getCalls)
        .containsExactlyInAnyOrder(tuple("read_file", 1L),
            tuple("run_in_terminal", 1L));
    List<ToolFailureRate> failureRates =
        logService.aggregateToolFailureRates(WINDOW_MINUTES, null, AgentProfiles.GITHUB_COPILOT);
    assertThat(failureRates).extracting(ToolFailureRate::tool, ToolFailureRate::calls, ToolFailureRate::failures)
        .containsExactlyInAnyOrder(tuple("read_file", 1L, 0L),
            tuple("run_in_terminal", 1L, 1L));
    // Claude Code's own profile must not see Copilot's synthesized calls.
    assertThat(logService.aggregateToolCalls(WINDOW_MINUTES, null, AgentProfiles.CLAUDE_CODE)).isEmpty();
  }

  @Test
  void synthesizedToolResultLogsFillTheGeneratedColumnsAndRequiredFields() {
    postToolSpans(COPILOT_SERVICE_NAME);

    List<LogRecordEntity> logs = logRecordRepository.findAll();
    assertThat(logs).hasSize(2).allSatisfy(log -> {
      assertThat(log.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
      assertThat(log.getReceivedAt()).isNotNull();
      assertThat(log.getTraceId()).isNotBlank();
      assertThat(log.getSpanId()).isNotBlank();
      assertThat(log.getResourceAttributes()).containsEntry(SERVICE_NAME_ATTRIBUTE, COPILOT_SERVICE_NAME);
      assertThat(log.getAttributes()).containsEntry("agent_compass.normalized_from", "span:execute_tool");
    });
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT event_name, tool_name, derived_severity, session_id FROM log_records ORDER BY tool_name");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("event_name", "tool_result")
        .containsEntry("tool_name", "read_file")
        .containsEntry("derived_severity", "INFO")
        .containsEntry("session_id", "conversation-1");
    assertThat(rows.get(1)).containsEntry("tool_name", "run_in_terminal")
        .containsEntry("derived_severity", "ERROR")
        .containsEntry("session_id", "conversation-1");
    Map<String, Object> failedAttributes = logRecordRepository.findAll().stream()
        .filter(log -> "run_in_terminal".equals(log.getAttributes().get("tool_name")))
        .findFirst().orElseThrow().getAttributes();
    assertThat(failedAttributes).containsEntry("success", "false")
        .containsEntry("error", "ToolFailed")
        .containsEntry("tool_use_id", "call-run_in_terminal");
    assertThat(((Number) failedAttributes.get("duration_ms")).longValue()).isEqualTo(TOOL_DURATION_MILLISECONDS);
  }

  @Test
  void theSameSpansFromClaudeCodeSynthesizeNothing() {
    postToolSpans(CLAUDE_CODE_SERVICE_NAME);

    assertThat(spanRepository.count()).isEqualTo(3);
    assertThat(logRecordRepository.count()).isZero();
  }

  @Test
  void copilotTokenCountersBecomeTheFourCanonicalTypesWithCachedInputSubtracted() {
    postTokenCounters(COPILOT_SERVICE_NAME, baseTimeUnixNanos);

    // Raw rows stay: five Copilot counters plus reasoning, as stored today.
    assertThat(rawPoints()).hasSize(5);
    assertThat(canonicalPoints()).hasSize(4);
    TokenUsageSummary summary = metricService.aggregateTokenUsage(WINDOW_MINUTES, null, AgentProfiles.GITHUB_COPILOT);
    assertThat(summary.inputTokens()).isEqualTo(UNCACHED_INPUT_TOKENS);
    assertThat(summary.outputTokens()).isEqualTo(OUTPUT_TOKENS);
    assertThat(summary.cacheReadTokens()).isEqualTo(CACHE_READ_TOKENS);
    assertThat(summary.cacheCreationTokens()).isEqualTo(CACHE_WRITE_TOKENS);
    assertThat(summary.byModel()).singleElement().satisfies(share -> assertThat(share.model()).isEqualTo(MODEL));
    // The default (Claude Code) profile is untouched by Copilot's tokens.
    assertThat(metricService.aggregateTokenUsage(WINDOW_MINUTES, null, AgentProfiles.CLAUDE_CODE).inputTokens())
        .isZero();
  }

  @Test
  void theSameCountersFromClaudeCodeSynthesizeNothing() {
    postTokenCounters(CLAUDE_CODE_SERVICE_NAME, baseTimeUnixNanos);

    assertThat(metricPointRepository.count()).isEqualTo(5);
    assertThat(canonicalPoints()).isEmpty();
  }

  @Test
  void repostingTheSameCumulativeCountersYieldsAZeroDeltaOnTheSecondCanonicalPoint() {
    postTokenCounters(COPILOT_SERVICE_NAME, baseTimeUnixNanos);
    postTokenCounters(COPILOT_SERVICE_NAME, baseTimeUnixNanos + 60 * NANOS_PER_SECOND);

    List<MetricPointEntity> canonical = canonicalPoints();
    assertThat(canonical).hasSize(8);
    assertThat(canonical.stream().map(MetricPointEntity::getStreamId).distinct()).hasSize(4);
    List<MetricPointEntity> inputPoints = canonical.stream()
        .filter(point -> "input".equals(point.getAttributes().get("type")))
        .sorted(Comparator.comparing(MetricPointEntity::getTimestamp))
        .toList();
    assertThat(inputPoints).extracting(MetricPointEntity::getValueDelta)
        .containsExactly((double) UNCACHED_INPUT_TOKENS, 0.0);
    assertThat(canonical).filteredOn(point -> point.getTimestamp().isAfter(inputPoints.get(0).getTimestamp()))
        .hasSize(4)
        .allSatisfy(point -> assertThat(point.getValueDelta()).isZero());
  }

  private List<MetricPointEntity> rawPoints() {
    return metricPointRepository.findAll().stream()
        .filter(point -> !CANONICAL_TOKEN_METRIC.equals(point.getMetricName()))
        .toList();
  }

  private List<MetricPointEntity> canonicalPoints() {
    return metricPointRepository.findAll().stream()
        .filter(point -> CANONICAL_TOKEN_METRIC.equals(point.getMetricName()))
        .toList();
  }

  private void postToolSpans(String serviceName) {
    long endNanos = baseTimeUnixNanos + TOOL_DURATION_MILLISECONDS * NANOS_PER_MILLISECOND;
    ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder()
            .setResource(resourceOf(serviceName))
            .addScopeSpans(ScopeSpans.newBuilder()
                .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build())
                .addSpans(toolSpan("read_file", 1, endNanos, false))
                .addSpans(toolSpan("run_in_terminal", 2, endNanos, true))
                .addSpans(Span.newBuilder()
                    .setTraceId(idBytes(16, 9))
                    .setSpanId(idBytes(8, 3))
                    .setName("chat gpt-5")
                    .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                    .setStartTimeUnixNano(baseTimeUnixNanos)
                    .setEndTimeUnixNano(endNanos)
                    .build())
                .build())
            .build())
        .build();
    post("/v1/traces", request.toByteArray());
  }

  private Span toolSpan(String toolName, int spanNumber, long endNanos, boolean failed) {
    Span.Builder span = Span.newBuilder()
        .setTraceId(idBytes(16, 9))
        .setSpanId(idBytes(8, spanNumber))
        .setName("execute_tool " + toolName)
        .setKind(Span.SpanKind.SPAN_KIND_INTERNAL)
        .setStartTimeUnixNano(baseTimeUnixNanos)
        .setEndTimeUnixNano(endNanos)
        .addAttributes(stringAttribute("gen_ai.tool.name", toolName))
        .addAttributes(stringAttribute("gen_ai.tool.call.id", "call-" + toolName))
        .addAttributes(stringAttribute("gen_ai.tool.type", "function"))
        .addAttributes(stringAttribute("gen_ai.operation.name", "execute_tool"))
        .addAttributes(stringAttribute("gen_ai.conversation.id", "conversation-1"));
    if (failed) {
      span.setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build());
      span.addAttributes(stringAttribute("error.type", "ToolFailed"));
    }
    return span.build();
  }

  private void postTokenCounters(String serviceName, long timeUnixNanos) {
    ScopeMetrics.Builder scopeMetrics = ScopeMetrics.newBuilder()
        .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build());
    // Microsecond-different timestamps, as in a real export: grouping must not rely on equality.
    scopeMetrics.addMetrics(counter(INPUT_METRIC, INPUT_TOKENS, timeUnixNanos));
    scopeMetrics.addMetrics(counter(OUTPUT_METRIC, OUTPUT_TOKENS, timeUnixNanos + 1_000L));
    scopeMetrics.addMetrics(counter(REASONING_METRIC, REASONING_TOKENS, timeUnixNanos + 2_000L));
    scopeMetrics.addMetrics(counter(CACHE_READ_METRIC, CACHE_READ_TOKENS, timeUnixNanos + 3_000L));
    scopeMetrics.addMetrics(counter(CACHE_WRITE_METRIC, CACHE_WRITE_TOKENS, timeUnixNanos + 4_000L));
    ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(ResourceMetrics.newBuilder()
            .setResource(resourceOf(serviceName))
            .addScopeMetrics(scopeMetrics.build())
            .build())
        .build();
    post("/v1/metrics", request.toByteArray());
  }

  private Metric counter(String metricName, long value, long timeUnixNanos) {
    return Metric.newBuilder()
        .setName(metricName)
        .setUnit("{token}")
        .setSum(Sum.newBuilder()
            .addDataPoints(NumberDataPoint.newBuilder()
                .setStartTimeUnixNano(baseTimeUnixNanos - 60 * NANOS_PER_SECOND)
                .setTimeUnixNano(timeUnixNanos)
                .setAsInt(value)
                .addAttributes(stringAttribute("gen_ai.provider.name", "github"))
                .addAttributes(stringAttribute("gen_ai.request.model", MODEL))
                .addAttributes(stringAttribute("gen_ai.response.model", MODEL))
                .addAttributes(stringAttribute("gen_ai.operation.name", "chat"))
                .addAttributes(stringAttribute("gen_ai.token.modality", "text"))
                .build())
            .build())
        .build();
  }

  private static ByteString idBytes(int length, int seed) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) (seed + i);
    }
    return ByteString.copyFrom(bytes);
  }

  private static Resource resourceOf(String serviceName) {
    return Resource.newBuilder().addAttributes(stringAttribute(SERVICE_NAME_ATTRIBUTE, serviceName)).build();
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

  private static KeyValue stringAttribute(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value).build())
        .build();
  }
}
