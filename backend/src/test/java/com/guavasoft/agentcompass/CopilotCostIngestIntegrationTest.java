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
import com.guavasoft.agentcompass.model.CostBreakdown;
import com.guavasoft.agentcompass.model.CostCategoryShare;
import com.guavasoft.agentcompass.model.CostModelEffortCell;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.CostService;
import com.guavasoft.agentcompass.service.TraceExplorerService;
import com.guavasoft.agentcompass.service.TraceService;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Posts real OTLP protobuf for GitHub Copilot's {@code chat} spans and asserts that the ingest-time
 * cost normalization feeds both of Claude Code's cost pipelines: the per-call {@code api_request}
 * logs behind the Cost page and {@code trace_costs}, and the {@code claude_code.cost.usage}
 * counter behind the Usage Calendar and the session figures.
 *
 * <p>The fixture mirrors a measured capture: an {@code invoke_agent} span whose nano-AIU is the
 * rollup of its {@code chat} children, an agent-host resource ({@code github-copilot}) and an
 * extension resource ({@code copilot-chat}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CopilotCostIngestIntegrationTest {

  private static final String AGENT_HOST_SERVICE = "github-copilot";
  private static final String EXTENSION_SERVICE = "copilot-chat";
  private static final String CLAUDE_CODE_SERVICE = "claude-code";
  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";
  private static final String SCOPE_NAME = "copilot.test";
  private static final String COST_METRIC = "claude_code.cost.usage";
  private static final String AGENT_HOST_NANO_AIU = "github.copilot.nano_aiu";
  private static final String EXTENSION_NANO_AIU = "copilot_chat.copilot_usage_nano_aiu";
  private static final String FIRST_MODEL = "gpt-5";
  private static final String SECOND_MODEL = "claude-sonnet-4.5";
  private static final String EXTENSION_MODEL = "gpt-4o-mini";
  private static final long FIRST_CHAT_NANO_AIU = 1_296_022_500L;
  private static final long SECOND_CHAT_NANO_AIU = 500_000_000L;
  private static final long EXTENSION_CHAT_NANO_AIU = 300_000_000L;
  private static final double FIRST_CHAT_USD = 0.012960225;
  private static final double SECOND_CHAT_USD = 0.005;
  private static final double EXTENSION_CHAT_USD = 0.003;
  private static final double AGENT_HOST_TOTAL_USD = FIRST_CHAT_USD + SECOND_CHAT_USD;
  private static final double REQUEST_TOTAL_USD = AGENT_HOST_TOTAL_USD + EXTENSION_CHAT_USD;
  private static final double TOLERANCE = 1e-12;
  private static final int WINDOW_MINUTES = 120;
  private static final long NANOS_PER_MILLISECOND = 1_000_000L;
  private static final long CALL_DURATION_MILLISECONDS = 250L;
  private static final int AGENT_HOST_TRACE_SEED = 1;
  private static final int EXTENSION_TRACE_SEED = 2;
  private static final int TRACE_ID_LENGTH = 16;
  private static final int SPAN_ID_LENGTH = 8;

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
  CostService costService;

  @Autowired
  TraceExplorerService traceExplorerService;

  @Autowired
  TraceService traceService;

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
  void aTraceCostsTheSumOfItsChatCallsNotTheRolledUpInvokeAgentAsWell() {
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);

    TraceSummary summary = traceExplorerService.traceSummary(traceIdHex(AGENT_HOST_TRACE_SEED)).orElseThrow();
    assertThat(summary.getTotalCostUsd()).isCloseTo(AGENT_HOST_TOTAL_USD, within(TOLERANCE));
    // The invoke_agent span carries the rollup of both chats; counting it would double the trace.
    assertThat(summary.getTotalCostUsd()).isLessThan(2 * AGENT_HOST_TOTAL_USD - TOLERANCE);

    List<com.guavasoft.agentcompass.model.Span> spans = traceService.spansForTrace(traceIdHex(AGENT_HOST_TRACE_SEED));
    assertThat(spans).filteredOn(span -> span.getName().startsWith("chat"))
        .extracting(com.guavasoft.agentcompass.model.Span::getCostUsd)
        .containsExactlyInAnyOrder(FIRST_CHAT_USD, SECOND_CHAT_USD);
    assertThat(spans).filteredOn(span -> span.getName().startsWith("invoke_agent"))
        .allSatisfy(span -> assertThat(span.getCostUsd() == null || span.getCostUsd() == 0.0).isTrue());
  }

  @Test
  void theCostPageReturnsTheDollarsByModelAndSplitsAgentHostFromExtensionWork() {
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);

    CostBreakdown breakdown = costService.breakdown(WINDOW_MINUTES, null, AgentProfiles.GITHUB_COPILOT);

    assertThat(breakdown.totalCostUsd()).isCloseTo(REQUEST_TOTAL_USD, within(TOLERANCE));
    assertThat(breakdown.totalRequests()).isEqualTo(4);
    assertThat(costOf(breakdown, "MAIN_LOOP")).isCloseTo(AGENT_HOST_TOTAL_USD, within(TOLERANCE));
    assertThat(costOf(breakdown, "AUXILIARY")).isCloseTo(EXTENSION_CHAT_USD, within(TOLERANCE));
    assertThat(breakdown.categories()).extracting(CostCategoryShare::category)
        .doesNotContain("SUBAGENT", "SKILL");
    assertThat(costByModel(breakdown, FIRST_MODEL)).isCloseTo(FIRST_CHAT_USD, within(TOLERANCE));
    assertThat(costByModel(breakdown, SECOND_MODEL)).isCloseTo(SECOND_CHAT_USD, within(TOLERANCE));
    assertThat(costByModel(breakdown, EXTENSION_MODEL)).isCloseTo(EXTENSION_CHAT_USD, within(TOLERANCE));
    // The first call's input is reported as 1000 including 600 read and 100 written: 300 is uncached.
    assertThat(breakdown.totalInputTokens()).isEqualTo(300L + 200L + 40L + 40L);
    assertThat(breakdown.totalCacheReadTokens()).isEqualTo(600L);
    assertThat(breakdown.totalCacheCreationTokens()).isEqualTo(100L);
    // Claude Code's own profile sees none of it.
    assertThat(costService.breakdown(WINDOW_MINUTES, null, AgentProfiles.CLAUDE_CODE).totalCostUsd()).isZero();
  }

  @Test
  void theCounterTotalEqualsTheRequestTotalAndEveryPointDeltaIsItsOwnCost() {
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);

    List<MetricPointEntity> points = costPoints();
    // The zero-priced extension call has a log but no counter point.
    assertThat(points).hasSize(3);
    assertThat(points.stream().map(MetricPointEntity::getStreamId).distinct()).hasSize(3);
    assertThat(points).allSatisfy(point -> {
      assertThat(point.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
      assertThat(point.getValueDelta()).isCloseTo(point.getValueDouble(), within(TOLERANCE));
    });
    double counterTotal = counterTotalDelta();
    CostBreakdown breakdown = costService.breakdown(WINDOW_MINUTES, null, AgentProfiles.GITHUB_COPILOT);
    assertThat(counterTotal).isCloseTo(REQUEST_TOTAL_USD, within(TOLERANCE));
    assertThat(counterTotal).isCloseTo(breakdown.totalCostUsd(), within(TOLERANCE));
    assertThat(points).extracting(point -> point.getAttributes().get("query_source"))
        .containsExactlyInAnyOrder("main", "main", "auxiliary");
  }

  @Test
  void aSecondPostOfTheSamePayloadAddsZeroToTheCounterBecauseEachCallIsAlreadyItsOwnStream() {
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);

    // Same attribute set, so the same stream_id: the replayed point lands in the existing stream
    // with an equal value, and a reset-aware delta of an unchanged cumulative value is 0.
    List<MetricPointEntity> points = costPoints();
    assertThat(points).hasSize(6);
    assertThat(points.stream().map(MetricPointEntity::getStreamId).distinct()).hasSize(3);
    points.stream().collect(java.util.stream.Collectors.groupingBy(MetricPointEntity::getStreamId))
        .values().forEach(stream -> {
          List<Double> deltas = stream.stream()
              .sorted(Comparator.comparing(MetricPointEntity::getId))
              .map(MetricPointEntity::getValueDelta)
              .toList();
          assertThat(deltas.get(0)).isCloseTo(stream.get(0).getValueDouble(), within(TOLERANCE));
          assertThat(deltas.get(1)).isZero();
        });
    assertThat(counterTotalDelta()).isCloseTo(REQUEST_TOTAL_USD, within(TOLERANCE));
  }

  @Test
  void theSamePayloadFromClaudeCodeSynthesizesNothing() {
    postCopilotTraces(CLAUDE_CODE_SERVICE, CLAUDE_CODE_SERVICE);

    assertThat(spanRepository.count()).isEqualTo(5);
    assertThat(logRecordRepository.count()).isZero();
    assertThat(metricPointRepository.count()).isZero();
  }

  @Test
  void synthesizedLogsCarryTheFieldsTheCostQueriesRequire() {
    postCopilotTraces(AGENT_HOST_SERVICE, EXTENSION_SERVICE);

    List<LogRecordEntity> logs = logRecordRepository.findAll();
    assertThat(logs).hasSize(4).allSatisfy(log -> {
      assertThat(log.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
      assertThat(log.getTraceId()).isNotBlank();
      assertThat(log.getSpanId()).isNotBlank();
      assertThat(log.getAttributes()).containsEntry("agent_compass.normalized_from", "span:chat")
          .doesNotContainKeys("agent.name", "prompt.id");
    });
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT event_name, session_id FROM log_records WHERE attributes ->> 'request_id' = 'response-1'");
    assertThat(rows).singleElement().satisfies(row -> assertThat(row)
        .containsEntry("event_name", "api_request")
        .containsEntry("session_id", "conversation-1"));
  }

  private double counterTotalDelta() {
    Double total = jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(value_delta), 0) FROM metric_points WHERE metric_name = ? AND agent_profile = ?",
        Double.class, COST_METRIC, AgentProfiles.GITHUB_COPILOT);
    return total == null ? 0.0 : total;
  }

  private List<MetricPointEntity> costPoints() {
    return metricPointRepository.findAll().stream()
        .filter(point -> COST_METRIC.equals(point.getMetricName()))
        .toList();
  }

  private static double costOf(CostBreakdown breakdown, String category) {
    return breakdown.categories().stream()
        .filter(share -> category.equals(share.category()))
        .mapToDouble(CostCategoryShare::costUsd)
        .sum();
  }

  private static double costByModel(CostBreakdown breakdown, String model) {
    return breakdown.modelEffort().stream()
        .filter(cell -> model.equals(cell.model()))
        .mapToDouble(CostModelEffortCell::costUsd)
        .sum();
  }

  /**
   * Posts two resource blocks: trace one is an {@code invoke_agent} span rolling up two {@code chat}
   * spans, under {@code agentHostService}; trace two is a priced and a zero-priced {@code chat}
   * span under {@code extensionService}.
   */
  private void postCopilotTraces(String agentHostService, String extensionService) {
    ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(resourceSpans(agentHostService,
            invokeAgentSpan(AGENT_HOST_TRACE_SEED, 1, FIRST_CHAT_NANO_AIU + SECOND_CHAT_NANO_AIU),
            chatSpan(AGENT_HOST_TRACE_SEED, 2, "response-1", FIRST_MODEL, 1_000L, 600L, 100L,
                AGENT_HOST_NANO_AIU, FIRST_CHAT_NANO_AIU, "conversation-1"),
            chatSpan(AGENT_HOST_TRACE_SEED, 3, "response-2", SECOND_MODEL, 200L, 0L, 0L,
                AGENT_HOST_NANO_AIU, SECOND_CHAT_NANO_AIU, "conversation-1")))
        .addResourceSpans(resourceSpans(extensionService,
            chatSpan(EXTENSION_TRACE_SEED, 4, "response-3", EXTENSION_MODEL, 40L, 0L, 0L,
                EXTENSION_NANO_AIU, EXTENSION_CHAT_NANO_AIU, null),
            chatSpan(EXTENSION_TRACE_SEED, 5, "response-4", EXTENSION_MODEL, 40L, 0L, 0L,
                EXTENSION_NANO_AIU, 0L, null)))
        .build();
    post("/v1/traces", request.toByteArray());
  }

  private ResourceSpans resourceSpans(String serviceName, Span... spans) {
    ScopeSpans.Builder scopeSpans = ScopeSpans.newBuilder()
        .setScope(InstrumentationScope.newBuilder().setName(SCOPE_NAME).build());
    for (Span span : spans) {
      scopeSpans.addSpans(span);
    }
    return ResourceSpans.newBuilder()
        .setResource(Resource.newBuilder().addAttributes(stringAttribute(SERVICE_NAME_ATTRIBUTE, serviceName)).build())
        .addScopeSpans(scopeSpans.build())
        .build();
  }

  private Span invokeAgentSpan(int traceSeed, int spanSeed, long rolledUpNanoAiu) {
    return baseSpan(traceSeed, spanSeed, "invoke_agent GitHub Copilot Chat")
        .addAttributes(stringAttribute("gen_ai.operation.name", "invoke_agent"))
        .addAttributes(stringAttribute("gen_ai.conversation.id", "conversation-1"))
        .addAttributes(longAttribute(AGENT_HOST_NANO_AIU, rolledUpNanoAiu))
        .addAttributes(doubleAttribute("github.copilot.cost", 2.0))
        .build();
  }

  private Span chatSpan(int traceSeed, int spanSeed, String responseId, String model, long inputTokens,
      long cacheReadTokens, long cacheWriteTokens, String nanoAiuAttribute, long nanoAiu, String conversationId) {
    Span.Builder span = baseSpan(traceSeed, spanSeed, "chat " + model)
        .addAttributes(stringAttribute("gen_ai.operation.name", "chat"))
        .addAttributes(stringAttribute("gen_ai.response.id", responseId))
        .addAttributes(stringAttribute("gen_ai.response.model", model))
        .addAttributes(stringAttribute("gen_ai.request.model", model))
        .addAttributes(longAttribute("gen_ai.usage.input_tokens", inputTokens))
        .addAttributes(longAttribute("gen_ai.usage.output_tokens", 10L))
        .addAttributes(longAttribute("gen_ai.usage.cache_read.input_tokens", cacheReadTokens))
        .addAttributes(longAttribute("gen_ai.usage.cache_write.input_tokens", cacheWriteTokens))
        .addAttributes(longAttribute(nanoAiuAttribute, nanoAiu))
        .addAttributes(doubleAttribute("github.copilot.cost", 1.0));
    if (conversationId != null) {
      span.addAttributes(stringAttribute("gen_ai.conversation.id", conversationId));
    }
    return span.build();
  }

  private Span.Builder baseSpan(int traceSeed, int spanSeed, String name) {
    return Span.newBuilder()
        .setTraceId(idBytes(TRACE_ID_LENGTH, traceSeed))
        .setSpanId(idBytes(SPAN_ID_LENGTH, spanSeed))
        .setName(name)
        .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
        .setStartTimeUnixNano(baseTimeUnixNanos)
        .setEndTimeUnixNano(baseTimeUnixNanos + CALL_DURATION_MILLISECONDS * NANOS_PER_MILLISECOND);
  }

  private static String traceIdHex(int traceSeed) {
    return HexFormat.of().formatHex(idBytes(TRACE_ID_LENGTH, traceSeed).toByteArray());
  }

  private static ByteString idBytes(int length, int seed) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) (seed + i);
    }
    return ByteString.copyFrom(bytes);
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

  private static KeyValue longAttribute(String key, long value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setIntValue(value).build())
        .build();
  }

  private static KeyValue doubleAttribute(String key, double value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setDoubleValue(value).build())
        .build();
  }
}
