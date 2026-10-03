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

import org.junit.jupiter.api.Test;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.AgentProfiles;
import com.guavasoft.agentcompass.model.TokenTypes;
import com.guavasoft.agentcompass.otlp.normalize.CopilotCostRows;
import com.guavasoft.agentcompass.otlp.normalize.CopilotTelemetryNormalizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotTelemetryNormalizerTest {

  private static final String INPUT_METRIC = "gen_ai.client.inference.usage.input_tokens";
  private static final String OUTPUT_METRIC = "gen_ai.client.inference.usage.output_tokens";
  private static final String REASONING_METRIC = "gen_ai.client.inference.usage.reasoning.output_tokens";
  private static final String CACHE_READ_METRIC = "gen_ai.client.inference.usage.cache_read.input_tokens";
  private static final String CACHE_WRITE_METRIC = "gen_ai.client.inference.usage.cache_write.input_tokens";
  private static final String CANONICAL_METRIC = "claude_code.token.usage";
  private static final String RESPONSE_MODEL = "gen_ai.response.model";
  private static final String REQUEST_MODEL = "gen_ai.request.model";
  private static final String TYPE_ATTRIBUTE = "type";
  private static final String MODEL_ATTRIBUTE = "model";
  private static final String NORMALIZED_FROM = "agent_compass.normalized_from";
  private static final Instant POINT_TIMESTAMP = Instant.parse("2026-10-03T10:00:00Z");
  private static final Instant POINT_START = Instant.parse("2026-10-03T09:00:00Z");
  private static final long MEASURED_INPUT = 460_280L;
  private static final long MEASURED_CACHE_READ = 389_930L;
  private static final long MEASURED_CACHE_WRITE = 70_329L;
  private static final long MEASURED_UNCACHED_INPUT = 21L;
  private static final String NANO_AIU_ATTRIBUTE = "github.copilot.nano_aiu";
  private static final String EXTENSION_NANO_AIU_ATTRIBUTE = "copilot_chat.copilot_usage_nano_aiu";
  private static final String COST_METRIC = "claude_code.cost.usage";
  private static final String SOURCE_CALL_ID = "agent_compass.source_call_id";
  private static final String QUERY_SOURCE = "query_source";
  private static final long MEASURED_NANO_AIU = 1_296_022_500L;
  private static final double MEASURED_COST_USD = 0.012960225;
  private static final String AGENT_HOST_SERVICE = "github-copilot";
  private static final String EXTENSION_SERVICE = "copilot-chat";

  private final CopilotTelemetryNormalizer normalizer = new CopilotTelemetryNormalizer(new TuningProperties());

  @Test
  void toolSpanBecomesAToolResultLog() {
    SpanEntity span = toolSpan(AgentProfiles.GITHUB_COPILOT, "ok");

    List<LogRecordEntity> logs = normalizer.toolResultLogsFrom(List.of(span));

    assertThat(logs).singleElement().satisfies(log -> {
      assertThat(log.getTimestamp()).isEqualTo(span.getEndTimestamp());
      assertThat(log.getTraceId()).isEqualTo("trace-1");
      assertThat(log.getSpanId()).isEqualTo("span-1");
      assertThat(log.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
      assertThat(log.getResourceAttributes()).isEqualTo(span.getResourceAttributes());
      assertThat(log.getReceivedAt()).isEqualTo(span.getReceivedAt());
      assertThat(log.getScopeName()).isEqualTo("copilot.scope");
      assertThat(log.getAttributes())
          .containsEntry("event.name", "tool_result")
          .containsEntry("tool_name", "read_file")
          .containsEntry("success", "true")
          .containsEntry("duration_ms", 250L)
          .containsEntry("tool_use_id", "call-1")
          .containsEntry("session.id", "conversation-1")
          .containsEntry(NORMALIZED_FROM, "span:execute_tool")
          .doesNotContainKey("error");
    });
  }

  @Test
  void failedToolSpanCarriesSuccessFalseAndTheErrorType() {
    SpanEntity span = toolSpan(AgentProfiles.GITHUB_COPILOT, "error");
    span.getAttributes().put("error.type", "ToolTimeout");

    List<LogRecordEntity> logs = normalizer.toolResultLogsFrom(List.of(span));

    assertThat(logs).singleElement().satisfies(log -> assertThat(log.getAttributes())
        .containsEntry("success", "false")
        .containsEntry("error", "ToolTimeout"));
  }

  @Test
  void toolNameFallsBackToTheSpanNameSuffix() {
    SpanEntity span = toolSpan(AgentProfiles.GITHUB_COPILOT, "ok");
    span.getAttributes().remove("gen_ai.tool.name");

    assertThat(normalizer.toolResultLogsFrom(List.of(span))).singleElement()
        .satisfies(log -> assertThat(log.getAttributes()).containsEntry("tool_name", "read_file"));
  }

  @Test
  void copilotSpansThatAreNotToolCallsAreIgnored() {
    SpanEntity chatSpan = toolSpan(AgentProfiles.GITHUB_COPILOT, "ok");
    chatSpan.setName("chat gpt-5");

    assertThat(normalizer.toolResultLogsFrom(List.of(chatSpan))).isEmpty();
  }

  @Test
  void nonCopilotProfileIsANoOpForSpans() {
    SpanEntity span = toolSpan(AgentProfiles.CLAUDE_CODE, "ok");

    assertThat(normalizer.toolResultLogsFrom(List.of(span))).isEmpty();
  }

  @Test
  void nonCopilotProfileIsANoOpForTokenPoints() {
    List<MetricPointEntity> points = measuredCounters(AgentProfiles.CLAUDE_CODE, "gpt-5");

    assertThat(normalizer.canonicalTokenPointsFrom(points)).isEmpty();
  }

  @Test
  void cachedTokensAreSubtractedFromInput() {
    List<MetricPointEntity> points = measuredCounters(AgentProfiles.GITHUB_COPILOT, "gpt-5");

    List<MetricPointEntity> canonical = normalizer.canonicalTokenPointsFrom(points);

    assertThat(canonical).hasSize(4).allSatisfy(point -> {
      assertThat(point.getMetricName()).isEqualTo(CANONICAL_METRIC);
      assertThat(point.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
      assertThat(point.getValueKind()).isEqualTo("long");
      assertThat(point.getTimestamp()).isEqualTo(POINT_TIMESTAMP);
      assertThat(point.getStartTimestamp()).isEqualTo(POINT_START);
      assertThat(point.getAttributes()).containsEntry(MODEL_ATTRIBUTE, "gpt-5").containsKey(NORMALIZED_FROM);
    });
    assertThat(valueOfType(canonical, TokenTypes.INPUT)).isEqualTo(MEASURED_UNCACHED_INPUT);
    assertThat(valueOfType(canonical, TokenTypes.OUTPUT)).isEqualTo(1_000L);
    assertThat(valueOfType(canonical, TokenTypes.CACHE_READ)).isEqualTo(MEASURED_CACHE_READ);
    assertThat(valueOfType(canonical, TokenTypes.CACHE_CREATION)).isEqualTo(MEASURED_CACHE_WRITE);
    assertThat(provenanceOfType(canonical, TokenTypes.INPUT)).isEqualTo(INPUT_METRIC);
    assertThat(provenanceOfType(canonical, TokenTypes.CACHE_READ)).isEqualTo(CACHE_READ_METRIC);
  }

  @Test
  void anAbsentCacheCounterCountsAsZero() {
    List<MetricPointEntity> points = new ArrayList<>(List.of(
        counter(INPUT_METRIC, 500L, "gpt-5"),
        counter(OUTPUT_METRIC, 40L, "gpt-5"),
        counter(CACHE_READ_METRIC, 200L, "gpt-5")));

    List<MetricPointEntity> canonical = normalizer.canonicalTokenPointsFrom(points);

    assertThat(valueOfType(canonical, TokenTypes.INPUT)).isEqualTo(300L);
    assertThat(valueOfType(canonical, TokenTypes.CACHE_CREATION)).isZero();
  }

  @Test
  void aNegativeDerivedInputSkipsTheWholeGroup() {
    List<MetricPointEntity> points = new ArrayList<>(List.of(
        counter(INPUT_METRIC, 100L, "gpt-5"),
        counter(OUTPUT_METRIC, 40L, "gpt-5"),
        counter(CACHE_READ_METRIC, 80L, "gpt-5"),
        counter(CACHE_WRITE_METRIC, 50L, "gpt-5")));

    assertThat(normalizer.canonicalTokenPointsFrom(points)).isEmpty();
  }

  @Test
  void aGroupWithoutTheInputCounterEmitsNothing() {
    List<MetricPointEntity> points = new ArrayList<>(List.of(
        counter(OUTPUT_METRIC, 40L, "gpt-5"),
        counter(CACHE_READ_METRIC, 80L, "gpt-5")));

    assertThat(normalizer.canonicalTokenPointsFrom(points)).isEmpty();
  }

  @Test
  void reasoningTokensAreNotMapped() {
    List<MetricPointEntity> points = measuredCounters(AgentProfiles.GITHUB_COPILOT, "gpt-5");
    points.add(counter(REASONING_METRIC, 777L, "gpt-5"));

    List<MetricPointEntity> canonical = normalizer.canonicalTokenPointsFrom(points);

    assertThat(canonical).hasSize(4);
    assertThat(canonical).noneMatch(point -> Long.valueOf(777L).equals(point.getValueLong()));
    assertThat(valueOfType(canonical, TokenTypes.OUTPUT)).isEqualTo(1_000L);
  }

  @Test
  void twoModelsInOneBatchProduceIndependentGroups() {
    List<MetricPointEntity> points = new ArrayList<>();
    points.addAll(List.of(
        counter(INPUT_METRIC, 1_000L, "model-a"),
        counter(CACHE_READ_METRIC, 400L, "model-a"),
        counter(OUTPUT_METRIC, 10L, "model-a")));
    points.addAll(List.of(
        counter(INPUT_METRIC, 90L, "model-b"),
        counter(OUTPUT_METRIC, 5L, "model-b")));

    List<MetricPointEntity> canonical = normalizer.canonicalTokenPointsFrom(points);

    assertThat(canonical).hasSize(8);
    assertThat(canonicalFor(canonical, "model-a", TokenTypes.INPUT)).isEqualTo(600L);
    assertThat(canonicalFor(canonical, "model-a", TokenTypes.CACHE_READ)).isEqualTo(400L);
    assertThat(canonicalFor(canonical, "model-b", TokenTypes.INPUT)).isEqualTo(90L);
    assertThat(canonicalFor(canonical, "model-b", TokenTypes.CACHE_READ)).isZero();
  }

  @Test
  void modelFallsBackToTheRequestModelWhenThereIsNoResponseModel() {
    MetricPointEntity inputPoint = counter(INPUT_METRIC, 10L, null);
    inputPoint.getAttributes().put(REQUEST_MODEL, "requested-model");

    List<MetricPointEntity> canonical = normalizer.canonicalTokenPointsFrom(List.of(inputPoint));

    assertThat(canonical).hasSize(4)
        .allSatisfy(point -> assertThat(point.getAttributes()).containsEntry(MODEL_ATTRIBUTE, "requested-model"));
  }

  @Test
  void nanoAiuIsConvertedToUsdAtTheConfiguredRate() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).singleElement()
        .satisfies(log -> assertThat(log.getAttributes()).containsEntry("cost_usd", MEASURED_COST_USD));
    assertThat(rows.costPoints()).singleElement().satisfies(point -> {
      assertThat(point.getValueDouble()).isEqualTo(MEASURED_COST_USD);
      assertThat(point.getValueKind()).isEqualTo("double");
    });
  }

  @Test
  void aCustomRateChangesTheConvertedCost() {
    TuningProperties tuningProperties = new TuningProperties();
    tuningProperties.setCopilotUsdPerNanoAiu(2e-11);
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, 1_000_000_000L);

    CopilotCostRows rows = new CopilotTelemetryNormalizer(tuningProperties).costRowsFrom(List.of(span));

    assertThat(rows.costPoints()).singleElement()
        .satisfies(point -> assertThat(point.getValueDouble()).isEqualTo(0.02));
  }

  @Test
  void aZeroPricedExtensionCallKeepsCostUsdZeroOnTheLogButEmitsNoCounterPoint() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, EXTENSION_SERVICE, "response-1");
    span.getAttributes().put(EXTENSION_NANO_AIU_ATTRIBUTE, 0L);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).singleElement()
        .satisfies(log -> assertThat(log.getAttributes()).containsEntry("cost_usd", 0.0));
    assertThat(rows.costPoints()).isEmpty();
  }

  @Test
  void aCallWithNoNanoAiuAttributeOmitsCostUsdAndEmitsNoCounterPoint() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).singleElement()
        .satisfies(log -> assertThat(log.getAttributes()).doesNotContainKey("cost_usd"));
    assertThat(rows.costPoints()).isEmpty();
  }

  @Test
  void theMultiplierAttributeIsNeverReadAsMoney() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put("github.copilot.cost", 1.0);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).singleElement()
        .satisfies(log -> assertThat(log.getAttributes()).doesNotContainKey("cost_usd"));
    assertThat(rows.costPoints()).isEmpty();
  }

  @Test
  void theFirstNanoAiuAttributePresentWins() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);
    span.getAttributes().put(EXTENSION_NANO_AIU_ATTRIBUTE, 0L);

    assertThat(normalizer.costRowsFrom(List.of(span)).costPoints()).singleElement()
        .satisfies(point -> assertThat(point.getValueDouble()).isEqualTo(MEASURED_COST_USD));
  }

  @Test
  void invokeAgentRollupSpansAreIgnoredSoNothingIsCountedTwice() {
    SpanEntity rollup = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    rollup.setName("invoke_agent GitHub Copilot Chat");
    rollup.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);
    SpanEntity chat = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-2");
    chat.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(rollup, chat));

    assertThat(rows.apiRequestLogs()).hasSize(1);
    assertThat(rows.costPoints()).hasSize(1);
  }

  @Test
  void spanNamesThatMerelyStartWithChatAreNotModelCalls() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.setName("chatty_helper");

    assertThat(normalizer.costRowsFrom(List.of(span)).apiRequestLogs()).isEmpty();
  }

  @Test
  void perCallInputSubtractsBothCacheCounters() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put("gen_ai.usage.input_tokens", MEASURED_INPUT);
    span.getAttributes().put("gen_ai.usage.output_tokens", 1_000L);
    span.getAttributes().put("gen_ai.usage.cache_read.input_tokens", MEASURED_CACHE_READ);
    span.getAttributes().put("gen_ai.usage.cache_write.input_tokens", MEASURED_CACHE_WRITE);

    assertThat(normalizer.costRowsFrom(List.of(span)).apiRequestLogs()).singleElement()
        .satisfies(log -> assertThat(log.getAttributes())
            .containsEntry("input_tokens", MEASURED_UNCACHED_INPUT)
            .containsEntry("output_tokens", 1_000L)
            .containsEntry("cache_read_tokens", MEASURED_CACHE_READ)
            .containsEntry("cache_creation_tokens", MEASURED_CACHE_WRITE));
  }

  @Test
  void aNegativeDerivedInputSkipsTheWholeCall() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put("gen_ai.usage.input_tokens", 100L);
    span.getAttributes().put("gen_ai.usage.cache_read.input_tokens", 80L);
    span.getAttributes().put("gen_ai.usage.cache_write.input_tokens", 50L);
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).isEmpty();
    assertThat(rows.costPoints()).isEmpty();
  }

  @Test
  void aChatSpanWithNoUsageIsNotAModelCall() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().remove("gen_ai.usage.input_tokens");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).isEmpty();
    assertThat(rows.costPoints()).isEmpty();
  }

  @Test
  void agentHostCallsAreMainLoopOnTheLogAndMainOnTheCounter() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs().get(0).getAttributes()).containsEntry(QUERY_SOURCE, "sdk");
    assertThat(rows.costPoints().get(0).getAttributes()).containsEntry(QUERY_SOURCE, "main");
  }

  @Test
  void extensionCallsAreAuxiliaryOnBothPipelines() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, EXTENSION_SERVICE, "response-1");
    span.getAttributes().put(EXTENSION_NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);
    TuningProperties tuningProperties = new TuningProperties();

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    String logQuerySource = (String) rows.apiRequestLogs().get(0).getAttributes().get(QUERY_SOURCE);
    assertThat(logQuerySource).isEqualTo("copilot_extension")
        .isNotIn(tuningProperties.getMainLoopQuerySources())
        .doesNotStartWith(tuningProperties.getSubagentQuerySourcePrefix());
    assertThat(rows.costPoints().get(0).getAttributes()).containsEntry(QUERY_SOURCE, "auxiliary");
  }

  @Test
  void synthesizedRowsNeverCarryTheSubagentOrPromptMarkers() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs().get(0).getAttributes()).doesNotContainKeys("agent.name", "prompt.id");
    assertThat(rows.costPoints().get(0).getAttributes()).doesNotContainKeys("agent.name", "prompt.id");
  }

  @Test
  void theLogIsBuiltFromTheChatSpanTheSameWayTheToolResultIs() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    LogRecordEntity log = normalizer.costRowsFrom(List.of(span)).apiRequestLogs().get(0);

    assertThat(log.getTimestamp()).isEqualTo(span.getEndTimestamp());
    assertThat(log.getTraceId()).isEqualTo("trace-1");
    assertThat(log.getSpanId()).isEqualTo("chat-span-1");
    assertThat(log.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
    assertThat(log.getResourceAttributes()).isEqualTo(span.getResourceAttributes());
    assertThat(log.getScopeName()).isEqualTo("copilot.scope");
    assertThat(log.getAttributes())
        .containsEntry("event.name", "api_request")
        .containsEntry("model", "gpt-5")
        .containsEntry("request_id", "response-1")
        .containsEntry("session.id", "conversation-1")
        .containsEntry("duration_ms", 250L)
        .containsEntry(NORMALIZED_FROM, "span:chat");
  }

  @Test
  void modelFallsBackToTheRequestModelOnTheSpan() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().remove(RESPONSE_MODEL);
    span.getAttributes().put(REQUEST_MODEL, "requested-model");

    assertThat(normalizer.costRowsFrom(List.of(span)).apiRequestLogs().get(0).getAttributes())
        .containsEntry("model", "requested-model");
  }

  @Test
  void theCounterPointIsBuiltFromTheSpanWithAPerCallDiscriminator() {
    SpanEntity span = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    MetricPointEntity point = normalizer.costRowsFrom(List.of(span)).costPoints().get(0);

    assertThat(point.getMetricName()).isEqualTo(COST_METRIC);
    assertThat(point.getTimestamp()).isEqualTo(span.getEndTimestamp());
    assertThat(point.getStartTimestamp()).isEqualTo(span.getStartTimestamp());
    assertThat(point.getAgentProfile()).isEqualTo(AgentProfiles.GITHUB_COPILOT);
    assertThat(point.getResourceAttributes()).isEqualTo(span.getResourceAttributes());
    assertThat(point.getAttributes())
        .containsEntry("session.id", "conversation-1")
        .containsEntry(MODEL_ATTRIBUTE, "gpt-5")
        .containsEntry(NORMALIZED_FROM, "span:chat")
        .containsEntry(SOURCE_CALL_ID, "response-1");
  }

  @Test
  void everyCallGetsAUniqueStreamAttributeSet() {
    SpanEntity first = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-1");
    SpanEntity second = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, "response-2");
    SpanEntity withoutResponseId = chatSpan(AgentProfiles.GITHUB_COPILOT, AGENT_HOST_SERVICE, null);
    withoutResponseId.setSpanId("chat-span-3");
    List<SpanEntity> spans = List.of(first, second, withoutResponseId);
    spans.forEach(span -> span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU));

    List<MetricPointEntity> points = normalizer.costRowsFrom(spans).costPoints();

    assertThat(points).hasSize(3);
    assertThat(points.stream().map(MetricPointEntity::getAttributes).distinct()).hasSize(3);
    assertThat(points.get(2).getAttributes()).containsEntry(SOURCE_CALL_ID, "chat-span-3");
  }

  @Test
  void nonCopilotProfileIsANoOpForCost() {
    SpanEntity span = chatSpan(AgentProfiles.CLAUDE_CODE, AGENT_HOST_SERVICE, "response-1");
    span.getAttributes().put(NANO_AIU_ATTRIBUTE, MEASURED_NANO_AIU);

    CopilotCostRows rows = normalizer.costRowsFrom(List.of(span));

    assertThat(rows.apiRequestLogs()).isEmpty();
    assertThat(rows.costPoints()).isEmpty();
  }

  private static SpanEntity chatSpan(String agentProfile, String serviceName, String responseId) {
    SpanEntity span = new SpanEntity();
    span.setTraceId("trace-1");
    span.setSpanId("chat-span-1");
    span.setName("chat gpt-5");
    span.setStartTimestamp(POINT_TIMESTAMP.minusMillis(250));
    span.setEndTimestamp(POINT_TIMESTAMP);
    span.setDurationNanos(250_000_000L);
    span.setStatusCode("ok");
    span.setScopeName("copilot.scope");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("gen_ai.operation.name", "chat");
    attributes.put(RESPONSE_MODEL, "gpt-5");
    attributes.put("gen_ai.conversation.id", "conversation-1");
    if (responseId != null) {
      attributes.put("gen_ai.response.id", responseId);
    }
    attributes.put("gen_ai.usage.input_tokens", 500L);
    attributes.put("gen_ai.usage.output_tokens", 40L);
    span.setAttributes(attributes);
    span.setResourceAttributes(Map.of("service.name", serviceName));
    span.setScopeAttributes(Map.of());
    span.setReceivedAt(POINT_TIMESTAMP.plusSeconds(1));
    span.setAgentProfile(agentProfile);
    return span;
  }

  private static long valueOfType(List<MetricPointEntity> points, String tokenType) {
    return points.stream()
        .filter(point -> tokenType.equals(point.getAttributes().get(TYPE_ATTRIBUTE)))
        .findFirst().orElseThrow().getValueLong();
  }

  private static Object provenanceOfType(List<MetricPointEntity> points, String tokenType) {
    return points.stream()
        .filter(point -> tokenType.equals(point.getAttributes().get(TYPE_ATTRIBUTE)))
        .findFirst().orElseThrow().getAttributes().get(NORMALIZED_FROM);
  }

  private static long canonicalFor(List<MetricPointEntity> points, String model, String tokenType) {
    return points.stream()
        .filter(point -> model.equals(point.getAttributes().get(MODEL_ATTRIBUTE)))
        .filter(point -> tokenType.equals(point.getAttributes().get(TYPE_ATTRIBUTE)))
        .findFirst().orElseThrow().getValueLong();
  }

  private static List<MetricPointEntity> measuredCounters(String agentProfile, String model) {
    List<MetricPointEntity> points = new ArrayList<>(List.of(
        counter(INPUT_METRIC, MEASURED_INPUT, model),
        counter(OUTPUT_METRIC, 1_000L, model),
        counter(CACHE_READ_METRIC, MEASURED_CACHE_READ, model),
        counter(CACHE_WRITE_METRIC, MEASURED_CACHE_WRITE, model)));
    points.forEach(point -> point.setAgentProfile(agentProfile));
    return points;
  }

  private static MetricPointEntity counter(String metricName, long value, String responseModel) {
    MetricPointEntity point = new MetricPointEntity();
    point.setMetricName(metricName);
    point.setUnit("{token}");
    point.setScopeName("copilot.scope");
    point.setTimestamp(POINT_TIMESTAMP);
    point.setStartTimestamp(POINT_START);
    point.setValueLong(value);
    point.setValueKind("long");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("gen_ai.provider.name", "github");
    if (responseModel != null) {
      attributes.put(RESPONSE_MODEL, responseModel);
    }
    point.setAttributes(attributes);
    point.setResourceAttributes(Map.of("service.name", "github-copilot"));
    point.setReceivedAt(POINT_TIMESTAMP);
    point.setAgentProfile(AgentProfiles.GITHUB_COPILOT);
    return point;
  }

  private static SpanEntity toolSpan(String agentProfile, String statusCode) {
    SpanEntity span = new SpanEntity();
    span.setTraceId("trace-1");
    span.setSpanId("span-1");
    span.setName("execute_tool read_file");
    span.setStartTimestamp(POINT_TIMESTAMP.minusMillis(250));
    span.setEndTimestamp(POINT_TIMESTAMP);
    span.setDurationNanos(250_000_000L);
    span.setStatusCode(statusCode);
    span.setScopeName("copilot.scope");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("gen_ai.tool.name", "read_file");
    attributes.put("gen_ai.tool.call.id", "call-1");
    attributes.put("gen_ai.conversation.id", "conversation-1");
    attributes.put("gen_ai.operation.name", "execute_tool");
    span.setAttributes(attributes);
    span.setResourceAttributes(Map.of("service.name", "github-copilot"));
    span.setScopeAttributes(Map.of());
    span.setReceivedAt(POINT_TIMESTAMP.plusSeconds(1));
    span.setAgentProfile(agentProfile);
    return span;
  }
}
