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
package com.guavasoft.agentcompass.otlp.normalize;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.TokenTypes;
import com.guavasoft.agentcompass.otlp.model.MetricValueKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds Claude-Code-shaped canonical rows beside GitHub Copilot's raw telemetry, so every existing
 * dashboard query lights up for the Copilot profile without being changed.
 *
 * <p>Copilot's raw rows are kept exactly as stored; this class only <em>produces additional</em>
 * entities. It is a strict no-op for any row whose resolved {@code agent_profile} is not
 * {@code tuning.copilot-agent-profile}, which is what keeps Claude Code ingest byte-for-byte
 * unchanged. The profile is read off the rows the resolver already stamped rather than re-resolved
 * here, so there is exactly one place that decides which agent a resource belongs to.
 *
 * <p>Two normalizations, both at ingest:
 * <ul>
 *   <li><b>Tool calls.</b> Copilot has no {@code tool_result} log; each call is an
 *       {@code execute_tool <name>} span. {@link #toolResultLogsFrom} turns each into the log
 *       Claude Code would have written, which is what the Tool Calls, Tool Reliability and
 *       failure-rate queries read.</li>
 *   <li><b>Token counts.</b> Copilot reports five separate cumulative counters. {@link
 *       #canonicalTokenPointsFrom} folds them into the four-typed {@code claude_code.token.usage}
 *       counter. Copilot's {@code input} <em>includes</em> cached tokens while Claude Code's
 *       excludes them (the dashboards sum input, cache creation and cache read), so the canonical
 *       input is {@code input - cache_read - cache_write}; a plain rename would count the cached
 *       portion twice. {@code github.copilot.cost} is a premium-request count, not dollars, and is
 *       deliberately never mapped to cost.</li>
 *   <li><b>Cost.</b> Copilot has no {@code api_request} log and no cost counter; each model call is
 *       a {@code chat} span carrying its cost in nano AI credits. {@link #costRowsFrom} turns every
 *       such span into the {@code api_request} log the Cost page and the {@code trace_costs} view
 *       read, and into a one-point {@code claude_code.cost.usage} stream the counter-backed pages
 *       read. Only {@code chat} spans are read: {@code invoke_agent} carries a rollup of its chat
 *       children, so reading it too would count every dollar twice.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CopilotTelemetryNormalizer {

  // The canonical tool_result vocabulary below is the one Claude Code emits and the dashboard SQL
  // hardcodes as literals (the session_id generated column, 'duration_ms', 'error'), so these are
  // not configurable on TuningProperties: they are the target shape, not a source name.
  private static final String SESSION_ID_ATTRIBUTE = "session.id";
  private static final String DURATION_MS_ATTRIBUTE = "duration_ms";
  private static final String ERROR_ATTRIBUTE = "error";
  private static final String SPAN_PROVENANCE_PREFIX = "span:";
  private static final String STATUS_CODE_ERROR = "error";
  private static final String SUCCESS_TRUE = "true";
  private static final String SUCCESS_FALSE = "false";
  private static final long NANOS_PER_MILLISECOND = 1_000_000L;

  // The api_request attribute names below are hardcoded in the Cost page's SQL ('input_tokens' and
  // friends), so like the tool_result vocabulary above they are the target shape, not a source name.
  private static final String INPUT_TOKENS_ATTRIBUTE = "input_tokens";
  private static final String OUTPUT_TOKENS_ATTRIBUTE = "output_tokens";
  private static final String CACHE_READ_TOKENS_ATTRIBUTE = "cache_read_tokens";
  private static final String CACHE_CREATION_TOKENS_ATTRIBUTE = "cache_creation_tokens";
  private static final String SPAN_NAME_SEPARATOR = " ";
  // Used only when an operator empties tuning.main-loop-query-sources, so there is nothing to pick.
  private static final String FALLBACK_MAIN_LOOP_QUERY_SOURCE = "sdk";

  // The cost counter's query_source vocabulary (main / auxiliary / subagent) is a different one from
  // the log's, and nothing on the read side filters on it, so these are constants rather than
  // properties. SOURCE_CALL_ID_ATTRIBUTE is what makes every call its own metric stream (V38's
  // stream_id hashes the attribute set), which is the whole cost-counter design.
  private static final String COUNTER_MAIN_QUERY_SOURCE = "main";
  private static final String COUNTER_AUXILIARY_QUERY_SOURCE = "auxiliary";
  private static final String SOURCE_CALL_ID_ATTRIBUTE = "agent_compass.source_call_id";
  private static final String COST_UNIT = "USD";

  private final TuningProperties tuningProperties;

  /**
   * One canonical {@code tool_result} log per Copilot {@code execute_tool*} span in {@code spans}.
   * Spans of any other profile, and Copilot spans that are not tool calls, produce nothing.
   */
  public List<LogRecordEntity> toolResultLogsFrom(List<SpanEntity> spans) {
    List<LogRecordEntity> toolResultLogs = new ArrayList<>();
    for (SpanEntity span : spans) {
      if (isCopilotToolSpan(span)) {
        LogRecordEntity toolResultLog = toToolResultLog(span);
        if (toolResultLog != null) {
          toolResultLogs.add(toolResultLog);
        }
      }
    }
    return toolResultLogs;
  }

  /**
   * The cost rows for every Copilot {@code chat} span in {@code spans}: one {@code api_request} log
   * per usage-bearing call, plus one {@code claude_code.cost.usage} point per call that cost
   * something. Spans of any other profile, and every Copilot span that is not a model call
   * (including {@code invoke_agent}, a rollup of its children), produce nothing.
   *
   * <p>A chat span with no input-token attribute is not a usage-bearing call and is skipped, and so
   * is one whose cache counters exceed its input (the same negative-input rule the token counters
   * follow, with a warning). A call whose span carries no nano-AIU attribute at all gets a log
   * without {@code cost_usd} and no point: an unpriced call is not a free one. A call priced at
   * exactly 0 keeps {@code cost_usd = 0} on its log, so the request still counts, but gets no point:
   * a zero-increment stream changes no rollup and would only add a series per call.
   */
  public CopilotCostRows costRowsFrom(List<SpanEntity> spans) {
    List<LogRecordEntity> apiRequestLogs = new ArrayList<>();
    List<MetricPointEntity> costPoints = new ArrayList<>();
    for (SpanEntity span : spans) {
      if (!isCopilotChatSpan(span)) {
        continue;
      }
      CopilotCall call = callOf(span);
      if (call == null) {
        continue;
      }
      apiRequestLogs.add(toApiRequestLog(call));
      if (call.costUsd() != null && call.costUsd().signum() != 0) {
        costPoints.add(toCostPoint(call));
      }
    }
    return new CopilotCostRows(apiRequestLogs, costPoints);
  }

  /**
   * Canonical token points for the Copilot counters in {@code resourceBlockPoints}, which must be
   * the points of a <em>single</em> OTLP resource block.
   *
   * <p>Grouped by data-point attribute set rather than by timestamp: Copilot's five counters arrive
   * as separate data points whose timestamps differ by microseconds. A group is emitted only when
   * it contains the input counter. A counter missing from the group counts as zero, because a
   * cumulative stream does not exist until its first increment and is re-exported every cycle
   * after that. A group whose derived input would be negative is skipped with a warning rather
   * than inventing a figure.
   */
  public List<MetricPointEntity> canonicalTokenPointsFrom(List<MetricPointEntity> resourceBlockPoints) {
    Map<Map<String, Object>, Map<String, MetricPointEntity>> groups = new LinkedHashMap<>();
    for (MetricPointEntity point : resourceBlockPoints) {
      if (isCopilotTokenPoint(point)) {
        Map<String, MetricPointEntity> group =
            groups.computeIfAbsent(attributesOf(point), attributes -> new HashMap<>());
        group.merge(point.getMetricName(), point, CopilotTelemetryNormalizer::newerOf);
      }
    }
    List<MetricPointEntity> canonicalPoints = new ArrayList<>();
    for (Map<String, MetricPointEntity> group : groups.values()) {
      canonicalPoints.addAll(canonicalPointsForGroup(group));
    }
    return canonicalPoints;
  }

  private boolean isCopilotToolSpan(SpanEntity span) {
    return isCopilotProfile(span.getAgentProfile())
        && span.getName() != null
        && span.getName().startsWith(tuningProperties.getCopilotToolSpanNamePrefix());
  }

  private boolean isCopilotChatSpan(SpanEntity span) {
    String chatPrefix = tuningProperties.getCopilotChatSpanNamePrefix();
    return isCopilotProfile(span.getAgentProfile())
        && span.getName() != null
        && (span.getName().equals(chatPrefix) || span.getName().startsWith(chatPrefix + SPAN_NAME_SEPARATOR));
  }

  private boolean isCopilotTokenPoint(MetricPointEntity point) {
    String metricName = point.getMetricName();
    return isCopilotProfile(point.getAgentProfile())
        && (metricName.equals(tuningProperties.getCopilotInputTokensMetric())
            || metricName.equals(tuningProperties.getCopilotOutputTokensMetric())
            || metricName.equals(tuningProperties.getCopilotCacheReadTokensMetric())
            || metricName.equals(tuningProperties.getCopilotCacheWriteTokensMetric()));
  }

  private boolean isCopilotProfile(String agentProfile) {
    return tuningProperties.getCopilotAgentProfile().equals(agentProfile);
  }

  private LogRecordEntity toToolResultLog(SpanEntity span) {
    Map<String, Object> spanAttributes = attributesOf(span);
    String toolName = toolNameOf(span, spanAttributes);
    if (toolName == null) {
      return null;
    }
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(tuningProperties.getEventNameAttribute(), tuningProperties.getToolEventName());
    attributes.put(tuningProperties.getToolAttribute(), toolName);
    attributes.put(tuningProperties.getSuccessAttribute(),
        STATUS_CODE_ERROR.equals(span.getStatusCode()) ? SUCCESS_FALSE : SUCCESS_TRUE);
    if (span.getDurationNanos() != null) {
      attributes.put(DURATION_MS_ATTRIBUTE, Math.round((double) span.getDurationNanos() / NANOS_PER_MILLISECOND));
    }
    putIfPresent(attributes, tuningProperties.getToolCallIdAttribute(),
        spanAttributes.get(tuningProperties.getCopilotToolCallIdAttribute()));
    putIfPresent(attributes, SESSION_ID_ATTRIBUTE,
        spanAttributes.get(tuningProperties.getCopilotConversationIdAttribute()));
    putIfPresent(attributes, ERROR_ATTRIBUTE, spanAttributes.get(tuningProperties.getCopilotErrorTypeAttribute()));
    attributes.put(tuningProperties.getNormalizedFromAttribute(),
        SPAN_PROVENANCE_PREFIX + tuningProperties.getCopilotToolSpanNamePrefix());

    LogRecordEntity toolResultLog = new LogRecordEntity();
    // A tool_result is logged on completion, so the span's end is the matching instant.
    toolResultLog.setTimestamp(span.getEndTimestamp());
    toolResultLog.setScopeName(span.getScopeName());
    toolResultLog.setTraceId(span.getTraceId());
    toolResultLog.setSpanId(span.getSpanId());
    toolResultLog.setAttributes(attributes);
    toolResultLog.setResourceAttributes(span.getResourceAttributes());
    toolResultLog.setScopeAttributes(span.getScopeAttributes());
    toolResultLog.setReceivedAt(span.getReceivedAt());
    toolResultLog.setAgentProfile(span.getAgentProfile());
    return toolResultLog;
  }

  /** The tool attribute, falling back to what follows the span-name prefix ({@code execute_tool Bash}). */
  private String toolNameOf(SpanEntity span, Map<String, Object> spanAttributes) {
    Object attributeValue = spanAttributes.get(tuningProperties.getCopilotToolNameAttribute());
    if (attributeValue != null && !attributeValue.toString().isBlank()) {
      return attributeValue.toString();
    }
    String nameSuffix = span.getName().substring(tuningProperties.getCopilotToolSpanNamePrefix().length()).trim();
    return nameSuffix.isEmpty() ? null : nameSuffix;
  }

  /** One Copilot model call, with the cache subtraction already applied. */
  private record CopilotCall(
      SpanEntity span,
      String conversationId,
      String responseId,
      String model,
      long uncachedInputTokens,
      long outputTokens,
      long cacheReadTokens,
      long cacheWriteTokens,
      BigDecimal costUsd,
      boolean fromAgentHost) {
  }

  private CopilotCall callOf(SpanEntity span) {
    Map<String, Object> spanAttributes = attributesOf(span);
    BigDecimal inputTokens = numberOf(spanAttributes.get(tuningProperties.getCopilotUsageInputTokensAttribute()));
    if (inputTokens == null) {
      return null;
    }
    long totalInputTokens = inputTokens.longValue();
    long cacheReadTokens = tokenCountOf(spanAttributes, tuningProperties.getCopilotUsageCacheReadAttribute());
    long cacheWriteTokens = tokenCountOf(spanAttributes, tuningProperties.getCopilotUsageCacheWriteAttribute());
    long uncachedInputTokens = totalInputTokens - cacheReadTokens - cacheWriteTokens;
    if (uncachedInputTokens < 0) {
      log.warn("Skipping Copilot cost normalization for span {}: input {} is smaller than cache read {} "
          + "plus cache write {}", span.getSpanId(), totalInputTokens, cacheReadTokens, cacheWriteTokens);
      return null;
    }
    Object conversationId = spanAttributes.get(tuningProperties.getCopilotConversationIdAttribute());
    Object responseId = spanAttributes.get(tuningProperties.getCopilotResponseIdAttribute());
    return new CopilotCall(
        span,
        conversationId == null ? null : conversationId.toString(),
        responseId == null ? null : responseId.toString(),
        modelOf(spanAttributes),
        uncachedInputTokens,
        tokenCountOf(spanAttributes, tuningProperties.getCopilotUsageOutputTokensAttribute()),
        cacheReadTokens,
        cacheWriteTokens,
        costUsdOf(spanAttributes),
        isAgentHost(span));
  }

  /** The first configured nano-AIU attribute the span carries, converted to USD; null if it has none. */
  private BigDecimal costUsdOf(Map<String, Object> spanAttributes) {
    for (String nanoAiuAttribute : tuningProperties.getCopilotNanoAiuAttributes()) {
      BigDecimal nanoAiu = numberOf(spanAttributes.get(nanoAiuAttribute));
      if (nanoAiu != null) {
        // BigDecimal keeps 1_296_022_500 * 1e-11 at exactly 0.012960225 rather than a binary-float
        // neighbour of it, so the counter and log totals agree to the last digit.
        return nanoAiu.multiply(BigDecimal.valueOf(tuningProperties.getCopilotUsdPerNanoAiu()));
      }
    }
    return null;
  }

  private boolean isAgentHost(SpanEntity span) {
    Map<String, Object> resourceAttributes =
        span.getResourceAttributes() == null ? Map.of() : span.getResourceAttributes();
    return tuningProperties.getCopilotAgentHostServiceName()
        .equals(resourceAttributes.get(tuningProperties.getAgentProfileResourceAttribute()));
  }

  private LogRecordEntity toApiRequestLog(CopilotCall call) {
    SpanEntity span = call.span();
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(tuningProperties.getEventNameAttribute(), tuningProperties.getApiRequestEventName());
    putIfPresent(attributes, tuningProperties.getModelAttribute(), call.model());
    attributes.put(INPUT_TOKENS_ATTRIBUTE, call.uncachedInputTokens());
    attributes.put(OUTPUT_TOKENS_ATTRIBUTE, call.outputTokens());
    attributes.put(CACHE_READ_TOKENS_ATTRIBUTE, call.cacheReadTokens());
    attributes.put(CACHE_CREATION_TOKENS_ATTRIBUTE, call.cacheWriteTokens());
    if (call.costUsd() != null) {
      attributes.put(tuningProperties.getApiRequestCostAttribute(), call.costUsd().doubleValue());
    }
    if (span.getDurationNanos() != null) {
      attributes.put(tuningProperties.getApiRequestDurationAttribute(),
          Math.round((double) span.getDurationNanos() / NANOS_PER_MILLISECOND));
    }
    putIfPresent(attributes, tuningProperties.getRequestIdAttribute(), call.responseId());
    putIfPresent(attributes, SESSION_ID_ATTRIBUTE, call.conversationId());
    attributes.put(tuningProperties.getQuerySourceAttribute(), logQuerySourceOf(call));
    attributes.put(tuningProperties.getNormalizedFromAttribute(), provenanceOf());
    // Deliberately absent: agent.name, whose presence marks a subagent turn on the read side, and
    // prompt.id, which has no user_prompt log to join to.

    // trace_id and span_id are copied from the chat span, as for tool_result. V14's trace_costs keys
    // on trace_id alone, so the invoke_agent rollup span is never counted; span_costs keys on the
    // span_id, so each chat span carries its own cost.
    LogRecordEntity apiRequestLog = new LogRecordEntity();
    apiRequestLog.setTimestamp(span.getEndTimestamp());
    apiRequestLog.setScopeName(span.getScopeName());
    apiRequestLog.setTraceId(span.getTraceId());
    apiRequestLog.setSpanId(span.getSpanId());
    apiRequestLog.setAttributes(attributes);
    apiRequestLog.setResourceAttributes(span.getResourceAttributes());
    apiRequestLog.setScopeAttributes(span.getScopeAttributes());
    apiRequestLog.setReceivedAt(span.getReceivedAt());
    apiRequestLog.setAgentProfile(span.getAgentProfile());
    return apiRequestLog;
  }

  private MetricPointEntity toCostPoint(CopilotCall call) {
    SpanEntity span = call.span();
    // The attribute set is unique to this call, so V38's stream_id gives it a stream of its own, and
    // a stream's first row has no predecessor: its value_delta is exactly its value. Copilot has no
    // cumulative cost counter, and building one across export batches would need stored state.
    Map<String, Object> attributes = new LinkedHashMap<>();
    putIfPresent(attributes, SESSION_ID_ATTRIBUTE, call.conversationId());
    putIfPresent(attributes, tuningProperties.getModelAttribute(), call.model());
    attributes.put(tuningProperties.getQuerySourceAttribute(),
        call.fromAgentHost() ? COUNTER_MAIN_QUERY_SOURCE : COUNTER_AUXILIARY_QUERY_SOURCE);
    attributes.put(tuningProperties.getNormalizedFromAttribute(), provenanceOf());
    // A response id is the natural discriminator; the span id is unique per call too, and keeps a
    // call whose provider sent no response id from collapsing into a sibling's stream.
    attributes.put(SOURCE_CALL_ID_ATTRIBUTE, call.responseId() != null ? call.responseId() : span.getSpanId());

    MetricPointEntity costPoint = new MetricPointEntity();
    costPoint.setMetricName(tuningProperties.getCostUsageMetric());
    costPoint.setUnit(COST_UNIT);
    costPoint.setScopeName(span.getScopeName());
    costPoint.setTimestamp(span.getEndTimestamp());
    costPoint.setStartTimestamp(span.getStartTimestamp());
    costPoint.setValueDouble(call.costUsd().doubleValue());
    costPoint.setValueKind(MetricValueKind.DOUBLE.key());
    costPoint.setAttributes(attributes);
    costPoint.setResourceAttributes(span.getResourceAttributes());
    costPoint.setScopeAttributes(span.getScopeAttributes());
    costPoint.setReceivedAt(span.getReceivedAt());
    costPoint.setAgentProfile(span.getAgentProfile());
    return costPoint;
  }

  private String provenanceOf() {
    return SPAN_PROVENANCE_PREFIX + tuningProperties.getCopilotChatSpanNamePrefix();
  }

  /** The first main-loop source for the agent host, the auxiliary one for every other Copilot service. */
  private String logQuerySourceOf(CopilotCall call) {
    if (!call.fromAgentHost()) {
      return tuningProperties.getCopilotAuxiliaryQuerySource();
    }
    List<String> mainLoopQuerySources = tuningProperties.getMainLoopQuerySources();
    return mainLoopQuerySources.isEmpty() ? FALLBACK_MAIN_LOOP_QUERY_SOURCE : mainLoopQuerySources.get(0);
  }

  private static long tokenCountOf(Map<String, Object> spanAttributes, String attributeName) {
    BigDecimal tokenCount = numberOf(spanAttributes.get(attributeName));
    return tokenCount == null ? 0L : tokenCount.longValue();
  }

  /** A numeric attribute value, whether the sender wrote it as a number or a string; null otherwise. */
  private static BigDecimal numberOf(Object attributeValue) {
    if (attributeValue instanceof Long || attributeValue instanceof Integer) {
      return BigDecimal.valueOf(((Number) attributeValue).longValue());
    }
    if (attributeValue instanceof Number number) {
      double doubleValue = number.doubleValue();
      return Double.isFinite(doubleValue) ? BigDecimal.valueOf(doubleValue) : null;
    }
    if (attributeValue instanceof String text) {
      try {
        return new BigDecimal(text.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private List<MetricPointEntity> canonicalPointsForGroup(Map<String, MetricPointEntity> group) {
    MetricPointEntity inputPoint = group.get(tuningProperties.getCopilotInputTokensMetric());
    if (inputPoint == null) {
      return List.of();
    }
    long inputTokens = valueOf(inputPoint);
    long cacheReadTokens = valueOf(group.get(tuningProperties.getCopilotCacheReadTokensMetric()));
    long cacheWriteTokens = valueOf(group.get(tuningProperties.getCopilotCacheWriteTokensMetric()));
    long outputTokens = valueOf(group.get(tuningProperties.getCopilotOutputTokensMetric()));
    long uncachedInputTokens = inputTokens - cacheReadTokens - cacheWriteTokens;
    if (uncachedInputTokens < 0) {
      log.warn("Skipping Copilot token normalization for attributes {}: input {} is smaller than cache read {} "
          + "plus cache write {}", inputPoint.getAttributes(), inputTokens, cacheReadTokens, cacheWriteTokens);
      return List.of();
    }
    String model = modelOf(inputPoint);
    String sourceMetric = inputPoint.getMetricName();
    return List.of(
        canonicalPoint(inputPoint, TokenTypes.INPUT, uncachedInputTokens, model, sourceMetric),
        canonicalPoint(inputPoint, TokenTypes.OUTPUT, outputTokens, model,
            tuningProperties.getCopilotOutputTokensMetric()),
        canonicalPoint(inputPoint, TokenTypes.CACHE_READ, cacheReadTokens, model,
            tuningProperties.getCopilotCacheReadTokensMetric()),
        canonicalPoint(inputPoint, TokenTypes.CACHE_CREATION, cacheWriteTokens, model,
            tuningProperties.getCopilotCacheWriteTokensMetric()));
  }

  private MetricPointEntity canonicalPoint(MetricPointEntity inputPoint, String tokenType, long tokenCount,
      String model, String sourceMetric) {
    // Each (type, model) pair is a distinct, stable attribute set, so V38's stream_id gives it its
    // own stream and the ordinary reset-aware value_delta logic applies to it unchanged.
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(tuningProperties.getTokenTypeAttribute(), tokenType);
    if (model != null) {
      attributes.put(tuningProperties.getModelAttribute(), model);
    }
    attributes.put(tuningProperties.getNormalizedFromAttribute(), sourceMetric);

    MetricPointEntity canonicalPoint = new MetricPointEntity();
    canonicalPoint.setMetricName(tuningProperties.getTokenUsageMetric());
    canonicalPoint.setUnit(inputPoint.getUnit());
    canonicalPoint.setScopeName(inputPoint.getScopeName());
    canonicalPoint.setTimestamp(inputPoint.getTimestamp());
    canonicalPoint.setStartTimestamp(inputPoint.getStartTimestamp());
    canonicalPoint.setValueLong(tokenCount);
    canonicalPoint.setValueKind(MetricValueKind.LONG.key());
    canonicalPoint.setAttributes(attributes);
    canonicalPoint.setResourceAttributes(inputPoint.getResourceAttributes());
    canonicalPoint.setScopeAttributes(inputPoint.getScopeAttributes());
    canonicalPoint.setReceivedAt(inputPoint.getReceivedAt());
    canonicalPoint.setAgentProfile(inputPoint.getAgentProfile());
    return canonicalPoint;
  }

  private String modelOf(MetricPointEntity point) {
    return modelOf(attributesOf(point));
  }

  private String modelOf(Map<String, Object> attributes) {
    Object responseModel = attributes.get(tuningProperties.getCopilotResponseModelAttribute());
    if (responseModel != null && !responseModel.toString().isBlank()) {
      return responseModel.toString();
    }
    Object requestModel = attributes.get(tuningProperties.getCopilotRequestModelAttribute());
    if (requestModel != null && !requestModel.toString().isBlank()) {
      return requestModel.toString();
    }
    return null;
  }

  /** A counter absent from the batch counts as zero; a double-valued one is rounded to whole tokens. */
  private static long valueOf(MetricPointEntity point) {
    if (point == null) {
      return 0L;
    }
    if (point.getValueLong() != null) {
      return point.getValueLong();
    }
    if (point.getValueDouble() != null) {
      return Math.round(point.getValueDouble());
    }
    return 0L;
  }

  private static MetricPointEntity newerOf(MetricPointEntity first, MetricPointEntity second) {
    Instant firstTimestamp = first.getTimestamp();
    Instant secondTimestamp = second.getTimestamp();
    return secondTimestamp != null && (firstTimestamp == null || secondTimestamp.isAfter(firstTimestamp))
        ? second
        : first;
  }

  private static void putIfPresent(Map<String, Object> attributes, String key, Object value) {
    if (value != null) {
      attributes.put(key, value);
    }
  }

  private static Map<String, Object> attributesOf(SpanEntity span) {
    return span.getAttributes() == null ? Map.of() : span.getAttributes();
  }

  private static Map<String, Object> attributesOf(MetricPointEntity point) {
    return point.getAttributes() == null ? Map.of() : point.getAttributes();
  }
}
