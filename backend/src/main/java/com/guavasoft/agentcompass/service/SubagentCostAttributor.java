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

import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.SubagentCostBreakdown;
import com.guavasoft.agentcompass.model.TraceCostBreakdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Per-call cost, and which sub-agent spent it, resolved in one pass over a trace's spans
 * ({@link #attributeCalls}) -- extracted from {@link TraceAnalysisPromptBuilder}, which was this
 * logic's only caller until the per-subagent cost breakdown needed to be exposed as structured
 * data of its own, independent of ever having generated an Ollama trace analysis.
 *
 * <p>Two facts make this possible without a new query, and one trap makes it necessary. The trap:
 * {@code Span#costUsd} comes from the {@code span_costs} view, which groups request logs by the
 * span that was merely <i>open</i> when the call was issued -- measured over 7 days, every dollar
 * in that view sits on a {@code claude_code.interaction} or {@code claude_code.tool.execution}
 * span, and not one lands on the {@code claude_code.llm_request} span that actually made the
 * call. Money is therefore attributed the way {@code span_efforts} (V15) attributes effort, on
 * the {@code request_id} attribute both signals carry. The first fact: a sub-agent's work lives
 * in the dispatching trace, as spans whose ancestor chain runs through the {@code tool.execution}
 * child of the {@code claude_code.tool} span with {@code tool_name=Agent}. The second: its
 * {@code api_request} logs carry {@code query_source} = {@code agent:builtin:<type>} /
 * {@code agent:custom}. Both are read, for different jobs: ancestry says <b>which dispatch</b> a
 * call belongs to (so three project-local agents in one trace -- all reported as {@code custom} --
 * do not collapse into one bucket), {@code query_source} says <b>which agent</b>. See
 * backend/CLAUDE.md's "Per-call cost, and which sub-agent spent it" section for the full
 * reasoning and measurements; do not violate any of the invariants documented there.
 */
@Component
public class SubagentCostAttributor {

    private static final String ACCEPTED_DECISION = "accept";
    private static final String DECISION_ATTRIBUTE = "decision";
    private static final String TOOL_USE_ID_ATTRIBUTE = "tool_use_id";

    // Capped like maxDepth so a malformed parent chain cannot spin -- see enclosingDispatchChain.
    private static final int MAX_DEPTH_ITERATIONS = 64;

    private static final String COST_FORMAT = "%.4f";
    private static final String BUILTIN_SUBAGENT_QUERY_SOURCE_INFIX = "builtin:";
    private static final String UNNAMED_SUBAGENT = "subagent";
    private static final String MAIN_LOOP_LABEL = "Main loop";
    private static final String MAIN_LOOP_BRANCH_KEY = "main";
    private static final String BRANCH_KEY_SEPARATOR = ">";
    private static final String AUXILIARY_LABEL = "Auxiliary (session titles, compaction, web fetch)";

    private final TuningProperties tuningProperties;

    public SubagentCostAttributor(TuningProperties tuningProperties) {
        this.tuningProperties = tuningProperties;
    }

    /** Everything one Agent dispatch's subagent did, named by the call that started it. */
    static final class SubagentDispatch {

        final int dispatchCallNumber;
        String label = UNNAMED_SUBAGENT;
        int modelCallCount;
        int toolCallCount;
        double costUsd;

        private SubagentDispatch(int dispatchCallNumber) {
            this.dispatchCallNumber = dispatchCallNumber;
        }

        String label() {
            return "Subagent " + label + " dispatched at call " + dispatchCallNumber;
        }

        // Inline form, for naming the branch a call sat on without restating the whole sentence.
        String shortLabel() {
            return label + " (dispatched at call " + dispatchCallNumber + ")";
        }

        // One clause of the summary's Cost line: what this dispatch spent and what it did for it.
        // Carries the dispatch call number so the reader can find the Agent call the spend hangs
        // off, which is the only thing that tells two dispatches of the same agent type apart.
        String summaryLabel() {
            return label + " (dispatched at call " + dispatchCallNumber + ") $"
                    + String.format(COST_FORMAT, costUsd) + " across " + modelCallCount + " model call"
                    + JsonAttributeReaders.plural(modelCallCount) + " and " + toolCallCount + " tool call"
                    + JsonAttributeReaders.plural(toolCallCount);
        }
    }

    /**
     * Per-call cost, the subagent each call belongs to, and the main-loop context curve -- all
     * resolved in one pass so the timeline, the overview and the observations can never disagree
     * about which call number is which.
     */
    record CallAttribution(
            Map<String, SubagentDispatch> dispatchBySpanId,
            Map<String, List<SubagentDispatch>> dispatchChainBySpanId,
            Map<String, SubagentDispatch> dispatchByDispatchSpanId,
            Map<String, Double> costUsdBySpanId,
            List<Long> mainLoopContextTokens,
            double mainLoopCostUsd,
            int mainLoopModelCallCount,
            double auxiliaryCostUsd,
            int auxiliaryModelCallCount,
            int costliestCallNumber,
            double costliestCallCostUsd,
            double measuredCostUsd) {

        // Dispatch order is the order the Agent calls were made -- dispatchByDispatchSpanId is a
        // LinkedHashMap filled in trace order, so the cost split reads down the trace.
        Collection<SubagentDispatch> dispatches() {
            return dispatchByDispatchSpanId.values();
        }

        /**
         * Stable identity for the execution path a span sits on: {@code "main"} for the main loop,
         * otherwise the dispatch call numbers from outermost to innermost ({@code "12"}, or
         * {@code "12>31"} once a subagent dispatches its own). Two calls share a branch only when
         * they ran under exactly the same chain -- which is what makes "the same file twice" mean
         * genuinely repeated work rather than two agents each reading it once in their own context.
         */
        String branchKeyOf(String spanId) {
            List<SubagentDispatch> chain = dispatchChainBySpanId.get(spanId);
            if (chain == null || chain.isEmpty()) {
                return MAIN_LOOP_BRANCH_KEY;
            }
            return chain.stream()
                    .map(dispatch -> String.valueOf(dispatch.dispatchCallNumber))
                    .collect(Collectors.joining(BRANCH_KEY_SEPARATOR));
        }

        /** Human-readable counterpart of {@link #branchKeyOf}, or null for the main loop. */
        String branchLabelOf(String spanId) {
            List<SubagentDispatch> chain = dispatchChainBySpanId.get(spanId);
            if (chain == null || chain.isEmpty()) {
                return null;
            }
            return chain.stream().map(SubagentDispatch::shortLabel).collect(Collectors.joining(" › "));
        }

        List<String> costSplitLines() {
            List<String> lines = new ArrayList<>();
            if (measuredCostUsd <= 0) {
                return lines;
            }
            if (mainLoopModelCallCount > 0) {
                lines.add("- " + MAIN_LOOP_LABEL + ": $" + String.format(COST_FORMAT, mainLoopCostUsd)
                        + " across " + mainLoopModelCallCount + " model calls");
            }
            for (SubagentDispatch dispatch : dispatches()) {
                lines.add("- " + dispatch.label() + ": $" + String.format(COST_FORMAT, dispatch.costUsd)
                        + " across " + dispatch.modelCallCount + " model calls and "
                        + dispatch.toolCallCount + " tool calls");
            }
            if (auxiliaryModelCallCount > 0) {
                lines.add("- " + AUXILIARY_LABEL + ": $" + String.format(COST_FORMAT, auxiliaryCostUsd)
                        + " across " + auxiliaryModelCallCount + " model calls");
            }
            return lines;
        }
    }

    /**
     * Walks the trace once and answers three questions at the same call numbering the trace
     * detail page and {@link TraceAnalysisPromptBuilder}'s timeline both use: which Agent dispatch
     * (if any) each call ran inside, what each model call cost, and how big the main loop's own
     * context was at each of its calls. Indexes {@code logRecords}' {@code api_request} rows by
     * {@code request_id} itself -- see {@link #indexApiRequestsByRequestId} -- so a caller with
     * only the raw span/log lists (no pre-built index) can call this directly.
     *
     * <p>Dispatch membership comes from span ancestry rather than from {@code agent.name}, because
     * only ancestry knows <i>which</i> dispatch: Claude Code collapses every project-local agent to
     * the single name {@code custom}, so a trace that fanned out to three of them would otherwise
     * report one indistinguishable bucket. The agent's identity is then read from the
     * {@code query_source} of its own requests, which is the one place the shipped type
     * ({@code Explore}, {@code Plan}, …) actually appears.
     */
    CallAttribution attributeCalls(List<Span> spans, List<LogRecord> logRecords) {
        return attributeCalls(spans, indexApiRequestsByRequestId(logRecords));
    }

    CallAttribution attributeCalls(List<Span> spans, Map<String, LogRecord> apiRequestsByRequestId) {
        Map<String, String> parentBySpanId = parentBySpanId(spans);
        Map<String, SubagentDispatch> dispatchByDispatchSpanId = new LinkedHashMap<>();

        int callNumber = 0;
        for (Span span : spans) {
            if (!isCall(span)) {
                continue;
            }
            callNumber++;
            if (tuningProperties.getToolSpanName().equals(span.getName())
                    && tuningProperties.getSubagentToolName().equals(toolNameOf(span))
                    && span.getSpanId() != null) {
                dispatchByDispatchSpanId.put(span.getSpanId(), new SubagentDispatch(callNumber));
            }
        }

        Map<String, SubagentDispatch> dispatchBySpanId = new HashMap<>();
        Map<String, List<SubagentDispatch>> dispatchChainBySpanId = new HashMap<>();
        Map<String, Double> costUsdBySpanId = new HashMap<>();
        List<Long> mainLoopContextTokens = new ArrayList<>();
        double mainLoopCostUsd = 0.0;
        int mainLoopModelCallCount = 0;
        double auxiliaryCostUsd = 0.0;
        int auxiliaryModelCallCount = 0;
        int costliestCallNumber = 0;
        double costliestCallCostUsd = 0.0;
        double measuredCostUsd = 0.0;

        callNumber = 0;
        for (Span span : spans) {
            if (!isCall(span)) {
                continue;
            }
            callNumber++;
            List<SubagentDispatch> dispatchChain =
                    enclosingDispatchChain(span, parentBySpanId, dispatchByDispatchSpanId);
            // Aggregation stays on the innermost dispatch -- the subagent that actually made the
            // call is the one whose cost and call counts it belongs to. The chain above it is
            // identity (which path this call sits on), not ownership.
            SubagentDispatch dispatch = dispatchChain.isEmpty()
                    ? null
                    : dispatchChain.get(dispatchChain.size() - 1);
            if (span.getSpanId() != null) {
                if (dispatch != null) {
                    dispatchBySpanId.put(span.getSpanId(), dispatch);
                }
                dispatchChainBySpanId.put(span.getSpanId(), dispatchChain);
            }
            if (!tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                if (dispatch != null) {
                    dispatch.toolCallCount++;
                }
                continue;
            }

            LogRecord apiRequest = apiRequestFor(span, apiRequestsByRequestId);
            double costUsd = apiRequest == null
                    ? 0.0
                    : JsonAttributeReaders.doubleAttribute(apiRequest.getAttributes(), tuningProperties.getApiRequestCostAttribute());
            measuredCostUsd += costUsd;
            if (span.getSpanId() != null && costUsd > 0) {
                costUsdBySpanId.put(span.getSpanId(), costUsd);
            }
            if (costUsd > costliestCallCostUsd) {
                costliestCallCostUsd = costUsd;
                costliestCallNumber = callNumber;
            }

            if (dispatch != null) {
                dispatch.modelCallCount++;
                dispatch.costUsd += costUsd;
                applySubagentLabel(dispatch, apiRequest);
                continue;
            }
            if (isAuxiliaryRequest(apiRequest)) {
                auxiliaryCostUsd += costUsd;
                auxiliaryModelCallCount++;
                continue;
            }
            mainLoopCostUsd += costUsd;
            mainLoopModelCallCount++;
            mainLoopContextTokens.add(JsonAttributeReaders.promptTokensOf(span));
        }

        return new CallAttribution(
                dispatchBySpanId,
                dispatchChainBySpanId,
                dispatchByDispatchSpanId,
                costUsdBySpanId,
                mainLoopContextTokens,
                mainLoopCostUsd,
                mainLoopModelCallCount,
                auxiliaryCostUsd,
                auxiliaryModelCallCount,
                costliestCallNumber,
                costliestCallCostUsd,
                measuredCostUsd);
    }

    /**
     * The per-subagent cost breakdown as a standalone, structured, deterministic result -- no
     * Ollama analysis required, unlike {@link TraceAnalysisPromptBuilder}'s own use of
     * {@link #attributeCalls}. Backs {@code GET /api/traces/{traceId}/cost-breakdown}.
     */
    public TraceCostBreakdown costBreakdown(List<Span> spans, List<LogRecord> logRecords) {
        CallAttribution callAttribution = attributeCalls(spans, logRecords);
        List<SubagentCostBreakdown> subagentCosts = callAttribution.dispatches().stream()
                .map(dispatch -> new SubagentCostBreakdown(
                        dispatch.label,
                        dispatch.dispatchCallNumber,
                        dispatch.costUsd,
                        dispatch.modelCallCount,
                        dispatch.toolCallCount))
                .toList();
        return new TraceCostBreakdown(
                subagentCosts,
                callAttribution.mainLoopCostUsd(),
                callAttribution.mainLoopModelCallCount(),
                callAttribution.auxiliaryCostUsd(),
                callAttribution.auxiliaryModelCallCount(),
                callAttribution.measuredCostUsd());
    }

    // Indexes api_request logs by request_id -- the one signal that lets a model-call span's cost
    // be resolved without joining on span_id, which would attach every request to the span that
    // was merely open when it fired rather than the llm_request span the call actually made. See
    // this class's own javadoc for why span_id cannot be used here.
    private Map<String, LogRecord> indexApiRequestsByRequestId(List<LogRecord> logRecords) {
        Map<String, LogRecord> apiRequestsByRequestId = new HashMap<>();
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getApiRequestEventName())) {
                continue;
            }
            Object requestId = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getRequestIdAttribute());
            if (requestId != null) {
                apiRequestsByRequestId.put(String.valueOf(requestId), logRecord);
            }
        }
        return apiRequestsByRequestId;
    }

    // Delegated rather than reimplemented so the timeline's notion of a call and the callNumber the
    // trace detail page puts on a waterfall row are one definition -- see TraceCallNumbering.
    private boolean isCall(Span span) {
        return TraceCallNumbering.isCall(
                span, tuningProperties.getToolSpanName(), tuningProperties.getLlmRequestSpanName());
    }

    /**
     * Every dispatch a call ran inside, outermost first -- its full path from the main loop down.
     * Empty for a main-loop call. Strictly ancestral: the Agent tool span IS the dispatch and
     * belongs to the main loop that issued it, while everything hanging below it (through the
     * {@code tool.execution} span Claude Code opens for the run) is the subagent's own work. Capped
     * like {@code maxDepth} so a malformed parent chain cannot spin.
     *
     * <p>Returns the whole chain rather than the innermost dispatch -- see
     * {@code TraceAnalysisPromptBuilder}'s original javadoc for why: no dispatch is nested inside
     * another today, but the innermost-only form would silently collapse
     * {@code main → Explore → general-purpose} to {@code general-purpose} the moment it is.
     */
    private static List<SubagentDispatch> enclosingDispatchChain(
            Span span, Map<String, String> parentBySpanId, Map<String, SubagentDispatch> dispatchByDispatchSpanId) {
        List<SubagentDispatch> chain = new ArrayList<>();
        String currentSpanId = span.getParentSpanId();
        for (int depth = 0; depth < MAX_DEPTH_ITERATIONS && currentSpanId != null; depth++) {
            SubagentDispatch dispatch = dispatchByDispatchSpanId.get(currentSpanId);
            if (dispatch != null) {
                chain.add(dispatch);
            }
            currentSpanId = parentBySpanId.get(currentSpanId);
        }
        Collections.reverse(chain);
        return chain;
    }

    // agent:builtin:Explore -> "Explore", agent:custom -> "custom". Set from the first request that
    // carries one and left alone afterwards: every request inside one dispatch shares the value, so
    // re-deriving it per call would only re-do the same string work.
    private void applySubagentLabel(SubagentDispatch dispatch, LogRecord apiRequest) {
        if (!UNNAMED_SUBAGENT.equals(dispatch.label) || apiRequest == null) {
            return;
        }
        String querySource =
                JsonAttributeReaders.stringAttribute(apiRequest.getAttributes(), tuningProperties.getQuerySourceAttribute());
        if (querySource == null || !querySource.startsWith(tuningProperties.getSubagentQuerySourcePrefix())) {
            return;
        }
        String agentType = querySource.substring(tuningProperties.getSubagentQuerySourcePrefix().length());
        if (agentType.startsWith(BUILTIN_SUBAGENT_QUERY_SOURCE_INFIX)) {
            agentType = agentType.substring(BUILTIN_SUBAGENT_QUERY_SOURCE_INFIX.length());
        }
        if (!agentType.isBlank()) {
            dispatch.label = agentType;
        }
    }

    // Everything the harness issues that is neither the conversation nor a subagent: session-title
    // generation, compaction, web-fetch apply. Separated so its (usually tiny) spend never reads as
    // the agent's own work -- and so a compaction pass, which is expensive and says the context
    // filled up, is visible as itself.
    private boolean isAuxiliaryRequest(LogRecord apiRequest) {
        if (apiRequest == null) {
            return false;
        }
        String querySource =
                JsonAttributeReaders.stringAttribute(apiRequest.getAttributes(), tuningProperties.getQuerySourceAttribute());
        return querySource != null
                && !tuningProperties.getMainLoopQuerySources().contains(querySource)
                && !querySource.startsWith(tuningProperties.getSubagentQuerySourcePrefix());
    }

    private LogRecord apiRequestFor(Span span, Map<String, LogRecord> apiRequestsByRequestId) {
        Object requestId = JsonAttributeReaders.attribute(span.getAttributes(), tuningProperties.getRequestIdAttribute());
        return requestId == null ? null : apiRequestsByRequestId.get(String.valueOf(requestId));
    }

    private static Map<String, String> parentBySpanId(List<Span> spans) {
        Map<String, String> parentBySpanId = new HashMap<>();
        for (Span span : spans) {
            if (span.getSpanId() != null) {
                parentBySpanId.put(span.getSpanId(), span.getParentSpanId());
            }
        }
        return parentBySpanId;
    }

    private String toolNameOf(Span span) {
        return JsonAttributeReaders.toolNameOf(span, tuningProperties.getToolAttribute());
    }
}
