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
package com.guavasoft.agentcompass.service;

import org.junit.jupiter.api.Test;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.SubagentCostBreakdown;
import com.guavasoft.agentcompass.model.TraceCostBreakdown;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the extracted per-subagent cost attribution -- the standalone, structured
 * counterpart of what {@link TraceAnalysisPromptBuilderTest}'s
 * {@code subagentCallsAreMarkedAndTheDispatchLineCarriesItsRunsTotals} already pins via the prose
 * {@code Cost:} line. These exercise {@link SubagentCostAttributor#costBreakdown} directly, without
 * ever building a prompt, since the whole point of the extraction is that this data is available
 * without an Ollama analysis.
 */
class SubagentCostAttributorTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";

    private final SubagentCostAttributor subagentCostAttributor = new SubagentCostAttributor(new TuningProperties());

    @Test
    void splitsCostBetweenTheMainLoopAndASubagentDispatchByRequestId() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"),
                subagentToolSpan("Grep", "use-2", "exec-1"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90));

        TraceCostBreakdown breakdown = subagentCostAttributor.costBreakdown(spans, logs);

        assertThat(breakdown.mainLoopCostUsd()).isEqualTo(0.10);
        assertThat(breakdown.mainLoopModelCallCount()).isEqualTo(1);
        assertThat(breakdown.auxiliaryCostUsd()).isZero();
        assertThat(breakdown.measuredCostUsd()).isEqualTo(1.00);
        assertThat(breakdown.subagentCosts()).containsExactly(
                new SubagentCostBreakdown("Explore", 2, 0.90, 1, 1));
    }

    @Test
    void returnsAnEmptySubagentListWhenNothingWasDispatched() {
        List<Span> spans = List.of(modelCall("llm-1", null, "req-1"));
        List<LogRecord> logs = List.of(apiRequestLog("req-1", "sdk", 0.05));

        TraceCostBreakdown breakdown = subagentCostAttributor.costBreakdown(spans, logs);

        assertThat(breakdown.subagentCosts()).isEmpty();
        assertThat(breakdown.mainLoopCostUsd()).isEqualTo(0.05);
        assertThat(breakdown.measuredCostUsd()).isEqualTo(0.05);
    }

    @Test
    void separatesAuxiliaryRequestsFromTheMainLoop() {
        List<Span> spans = List.of(modelCall("llm-1", null, "req-1"));
        List<LogRecord> logs = List.of(apiRequestLog("req-1", "generate_session_title", 0.01));

        TraceCostBreakdown breakdown = subagentCostAttributor.costBreakdown(spans, logs);

        assertThat(breakdown.mainLoopCostUsd()).isZero();
        assertThat(breakdown.mainLoopModelCallCount()).isZero();
        assertThat(breakdown.auxiliaryCostUsd()).isEqualTo(0.01);
        assertThat(breakdown.auxiliaryModelCallCount()).isEqualTo(1);
    }

    private static Span toolSpan(String toolName, String toolUseId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool_name", toolName);
        attributes.put("tool_use_id", toolUseId);
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(toolUseId)
                .name("claude_code.tool")
                .statusCode("ok")
                .durationNanos(100_000_000L)
                .attributes(attributes)
                .build();
    }

    private static Span subagentToolSpan(String toolName, String toolUseId, String parentSpanId) {
        Span span = toolSpan(toolName, toolUseId);
        span.setParentSpanId(parentSpanId);
        return span;
    }

    private static Span executionSpan(String spanId, String parentSpanId) {
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .name("claude_code.tool.execution")
                .statusCode("ok")
                .durationNanos(30_000_000_000L)
                .attributes(new HashMap<>())
                .build();
    }

    private static Span modelCall(String spanId, String parentSpanId, String requestId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("request_id", requestId);
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .name("claude_code.llm_request")
                .statusCode("ok")
                .durationNanos(1_000_000_000L)
                .attributes(attributes)
                .build();
    }

    private static LogRecord apiRequestLog(String requestId, String querySource, double costUsd) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "api_request");
        attributes.put("request_id", requestId);
        attributes.put("query_source", querySource);
        attributes.put("cost_usd", costUsd);
        return LogRecord.builder().traceId(TRACE_ID).attributes(attributes).build();
    }
}
