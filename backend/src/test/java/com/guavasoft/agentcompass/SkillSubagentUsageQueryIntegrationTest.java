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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.IdentifierUsageCount;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.LogService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Exercises the skill-usage and subagent-usage aggregations against a real
 * Postgres instance, because both now carry a per-model split that only SQL can
 * get wrong: the skill side reads the model straight off the api_request row,
 * while the subagent side resolves it through a lateral walk back to the last
 * main-loop api_request in the same session. The controller-layer
 * {@code ToolActivityControllerTest} mocks {@link LogService} and cannot catch
 * either.
 *
 * <p>The seed models one session: a main-loop turn on model A dispatches an
 * Explore subagent, the subagent's own turns run on model B, then a later
 * main-loop turn on model B dispatches another Explore. Attribution must follow
 * the dispatching turn, not the subagent's turns.
 *
 * <p>The skill side additionally seeds a chatty invocation — several api_request
 * rows under one prompt id, one of them from inside a subagent the skill spawned
 * — because a skill invocation is one prompt that entered the skill, not one
 * api_request. Counting rows there inflated real totals by up to 46x.
 */
@SpringBootTest
@Testcontainers
class SkillSubagentUsageQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository repository;

    @Autowired
    SpanRepository spanRepository;

    @Autowired
    LogService service;

    /** Window spanning 60 minutes; rows are seeded at fixed offsets inside it. */
    private Instant windowStart;
    private Instant windowEnd;

    private static final String ATTR_EVENT_NAME    = "event.name";
    private static final String ATTR_TOOL_NAME     = "tool_name";
    private static final String ATTR_TOOL_INPUT    = "tool_input";
    private static final String ATTR_SESSION_ID    = "session.id";
    private static final String ATTR_MODEL         = "model";
    private static final String ATTR_AGENT_NAME    = "agent.name";
    private static final String ATTR_SKILL_NAME    = "skill.name";
    private static final String ATTR_SUBAGENT_TYPE = "subagent_type";
    private static final String ATTR_PROMPT_ID     = "prompt.id";
    private static final String ATTR_COST_USD      = "cost_usd";
    private static final String ATTR_TOOL_USE_ID   = "tool_use_id";
    private static final String ATTR_REQUEST_ID    = "request_id";

    private static final String EVENT_API_REQUEST = "api_request";
    private static final String EVENT_TOOL_RESULT = "tool_result";

    private static final String TOOL_AGENT = "Agent";

    // Span names/shape mirror LogsQueryIntegrationTest's
    // logsForTraceRepointsSubagentLogsOffTheDispatchingTaskSpan: root -> tool wrapper
    // -> tool.execution (the dispatch) -> child llm_request carrying request_id.
    private static final String SPAN_NAME_ROOT           = "claude_code.interaction";
    private static final String SPAN_NAME_TOOL_WRAPPER   = "claude_code.tool";
    private static final String SPAN_NAME_TOOL_EXECUTION = "claude_code.tool.execution";
    private static final String SPAN_NAME_LLM_REQUEST    = "claude_code.llm_request";

    private static final String SESSION_ID = "session-1";

    private static final String MODEL_OPUS   = "claude-opus-4-8";
    private static final String MODEL_SONNET = "claude-sonnet-4-6";
    private static final String MODEL_HAIKU  = "claude-haiku-4-5-20251001";

    private static final String SUBAGENT_EXPLORE         = "Explore";
    private static final String SUBAGENT_GENERAL_PURPOSE = "general-purpose";
    private static final String SKILL_SHIP               = "ship";
    private static final String SKILL_VERIFY             = "verify";

    // Two invocations of the ship skill, plus one of verify. The first ship
    // invocation is deliberately chatty: several turns share PROMPT_FIRST_SHIP.
    private static final String PROMPT_FIRST_SHIP  = "prompt-ship-1";
    private static final String PROMPT_SECOND_SHIP = "prompt-ship-2";
    private static final String PROMPT_VERIFY      = "prompt-verify";

    // Turns inside a spawned subagent get their own prompt id rather than the
    // dispatching prompt's — observed in real data, and the reason deduplicating
    // by prompt id is not on its own enough to keep them out of the count.
    private static final String PROMPT_SHIP_SUBAGENT = "prompt-ship-1-subagent";

    // Row offsets in seconds from windowStart, in dispatch order.
    private static final int OFFSET_MAIN_LOOP_TURN_ON_OPUS      = 60;
    private static final int OFFSET_SUBAGENT_TURN_ON_SONNET     = 120;
    private static final int OFFSET_FIRST_EXPLORE_DISPATCH      = 180;
    private static final int OFFSET_MAIN_LOOP_TURN_ON_SONNET    = 240;
    private static final int OFFSET_SECOND_EXPLORE_DISPATCH     = 300;
    private static final int OFFSET_UNTYPED_AGENT_DISPATCH      = 360;
    private static final int OFFSET_SHIP_SKILL_ON_OPUS          = 420;
    private static final int OFFSET_SHIP_SKILL_LATER_TURN       = 430;
    private static final int OFFSET_SHIP_SKILL_SUBAGENT_TURN    = 440;
    private static final int OFFSET_SHIP_SKILL_ON_SONNET        = 480;
    private static final int OFFSET_VERIFY_SKILL_ON_OPUS        = 540;

    // cost_usd values for the seeded api_request rows. Chosen so ship's skill-cost
    // total (below) is easy to hand-verify against the sum of these four turns.
    private static final double COST_MAIN_LOOP_ON_OPUS       = 0.10;
    private static final double COST_SUBAGENT_TURN_ON_SONNET = 0.05;
    private static final double COST_MAIN_LOOP_ON_SONNET     = 0.08;
    private static final double COST_SHIP_ON_OPUS            = 0.20;
    private static final double COST_SHIP_LATER_TURN         = 0.15;
    private static final double COST_SHIP_SUBAGENT_TURN      = 0.05;
    private static final double COST_SHIP_ON_SONNET          = 0.30;
    private static final double COST_VERIFY_ON_OPUS          = 0.50;

    // Sum of every api_request row carrying skill.name=ship, including the one made
    // from inside the subagent it spawned — see
    // skillCostSumsAllTurnsIncludingTheOneMadeInsideASpawnedSubagent.
    private static final double SHIP_SKILL_TOTAL_COST_USD =
            COST_SHIP_ON_OPUS + COST_SHIP_LATER_TURN + COST_SHIP_SUBAGENT_TURN + COST_SHIP_ON_SONNET;

    private static final double COST_ASSERTION_TOLERANCE = 0.0001;

    // Offsets/values for the span-correlated subagent-cost tests below.
    private static final int OFFSET_COST_CORRELATED_DISPATCH = 600;
    private static final int OFFSET_ORPHAN_DISPATCHES         = 660;
    private static final double COST_CORRELATED_SUBAGENT_CALL = 1.23;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        spanRepository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        // Main-loop turn on Opus — the turn that dispatches the first Explore.
        saveLog(OFFSET_MAIN_LOOP_TURN_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_COST_USD, COST_MAIN_LOOP_ON_OPUS));

        // A turn from inside the subagent run, on a different model. It is nearer
        // in time to the tool_result below than the dispatching turn is, so it
        // would win a naive "last api_request" lookup — agent.name must exclude it.
        saveLog(OFFSET_SUBAGENT_TURN_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_AGENT_NAME, "custom",
                ATTR_COST_USD, COST_SUBAGENT_TURN_ON_SONNET));

        saveLog(OFFSET_FIRST_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, SUBAGENT_EXPLORE));

        saveLog(OFFSET_MAIN_LOOP_TURN_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_COST_USD, COST_MAIN_LOOP_ON_SONNET));

        // Identifier carried inside tool_input rather than as a flat attribute —
        // the COALESCE fallback has to find it.
        saveLog(OFFSET_SECOND_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_TOOL_INPUT, "{\"" + ATTR_SUBAGENT_TYPE + "\": \"" + SUBAGENT_EXPLORE + "\"}"));

        // No subagent type anywhere — the dispatch ran on the default agent, so
        // it must land on that identifier rather than an 'unknown' bucket.
        saveLog(OFFSET_UNTYPED_AGENT_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT));

        // First ship invocation, earliest turn — this is the turn whose model the
        // whole invocation is attributed to.
        saveLog(OFFSET_SHIP_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_FIRST_SHIP,
                ATTR_COST_USD, COST_SHIP_ON_OPUS));

        // Same invocation, later turn, different model. It must not add a second
        // call, and must not move the invocation into the Sonnet bucket.
        saveLog(OFFSET_SHIP_SKILL_LATER_TURN, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_FIRST_SHIP,
                ATTR_COST_USD, COST_SHIP_LATER_TURN));

        // A turn from inside a subagent the skill spawned. It inherits skill.name,
        // reports the subagent's model, and carries a prompt id of its own — so
        // only the agent.name check keeps it from counting as a third invocation.
        // Its cost DOES still belong to the skill's cost total (unlike its call
        // count) — see skillCostSumsAllTurnsIncludingTheOneMadeInsideASpawnedSubagent.
        saveLog(OFFSET_SHIP_SKILL_SUBAGENT_TURN, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_HAIKU,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_SHIP_SUBAGENT,
                ATTR_AGENT_NAME, "custom",
                ATTR_COST_USD, COST_SHIP_SUBAGENT_TURN));

        // Second ship invocation — a separate prompt, so a separate call.
        saveLog(OFFSET_SHIP_SKILL_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_SECOND_SHIP,
                ATTR_COST_USD, COST_SHIP_ON_SONNET));

        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_VERIFY,
                ATTR_PROMPT_ID, PROMPT_VERIFY,
                ATTR_COST_USD, COST_VERIFY_ON_OPUS));
    }

    @Test
    void skillUsageSplitsCallsByTheModelThatRanTheSkill() {
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).containsExactly(SKILL_SHIP, SKILL_VERIFY);
        assertThat(rows.get(0).calls()).isEqualTo(2L);
        assertThat(rows.get(0).byModel()).containsOnlyKeys(MODEL_OPUS, MODEL_SONNET);
        assertThat(rows.get(0).byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);
        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_OPUS, 1L));
    }

    @Test
    void skillUsageCountsOnePromptOnceHoweverManyTurnsItTakes() {
        // The ship seed spends four api_request rows on two invocations. Counting
        // rows would report 4 (or 3 after dropping the subagent turn); only the
        // prompt-level dedup gives the 2 the page is supposed to show.
        long shipTurnsInWindow = repository.findAll().stream()
                .filter(row -> SKILL_SHIP.equals(row.getAttributes().get(ATTR_SKILL_NAME)))
                .count();
        assertThat(shipTurnsInWindow).isEqualTo(4L);

        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(2L));
    }

    @Test
    void skillUsageExcludesTurnsMadeInsideSubagentsTheSkillSpawned() {
        // That turn carries its own prompt id, so the prompt-level dedup does not
        // absorb it: without the agent.name check ship gains a third call and a
        // Haiku bucket it never ran on.
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.calls()).isEqualTo(2L);
                    assertThat(row.byModel()).doesNotContainKey(MODEL_HAIKU);
                });
    }

    @Test
    void skillInvocationSpanningModelsIsAttributedToItsEarliestTurn() {
        // PROMPT_FIRST_SHIP starts on Opus and later runs on Sonnet. Attributing
        // it to the latest turn instead would read {Sonnet: 2} and lose Opus.
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.byModel()).containsEntry(MODEL_OPUS, 1L));
    }

    @Test
    void skillTurnsWithNoPromptIdCountIndividuallyRatherThanCollapsing() {
        // Falling back to a constant would fold every prompt-less turn of one
        // skill into a single invocation; the fallback is the row's primary key.
        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, "unprompted"));
        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, "unprompted"));

        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> "unprompted".equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(2L));
    }

    @Test
    void skillCostSumsAllTurnsIncludingTheOneMadeInsideASpawnedSubagent() {
        // The direct opposite of skillUsageExcludesTurnsMadeInsideSubagentsTheSkillSpawned
        // just above: that test asserts the subagent-spawned turn does NOT add a call and
        // does NOT contribute a Haiku bucket to calls-by-model, because a skill
        // "invocation" is one prompt, and that turn is not a new prompt entering the
        // skill. Cost has no such notion of "one invocation" -- every api_request row
        // that ran while the skill was active spent real money, including that one -- so
        // the two queries deliberately disagree about which turns they cover. calls()
        // stays 2 for ship; costUsd sums all four of its turns.
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        IdentifierUsageCount ship = rows.stream()
                .filter(row -> SKILL_SHIP.equals(row.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(ship.calls()).isEqualTo(2L);
        assertThat(ship.costUsd()).isCloseTo(SHIP_SKILL_TOTAL_COST_USD, offset(COST_ASSERTION_TOLERANCE));
        // Unlike byModel (which excludes Haiku entirely), costByModel DOES carry the
        // subagent-spawned turn's model, because that turn's cost is real spend.
        assertThat(ship.costByModel()).containsEntry(MODEL_HAIKU, COST_SHIP_SUBAGENT_TURN);
        assertThat(ship.costByModel().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(SHIP_SKILL_TOTAL_COST_USD, offset(COST_ASSERTION_TOLERANCE));

        IdentifierUsageCount verify = rows.stream()
                .filter(row -> SKILL_VERIFY.equals(row.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(verify.costUsd()).isCloseTo(COST_VERIFY_ON_OPUS, offset(COST_ASSERTION_TOLERANCE));
    }

    @Test
    void subagentUsageAttributesCallsToTheDispatchingMainLoopTurnNotTheSubagentsOwnTurns() {
        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool)
                .containsExactly(SUBAGENT_EXPLORE, SUBAGENT_GENERAL_PURPOSE);

        IdentifierUsageCount explore = rows.get(0);
        assertThat(explore.calls()).isEqualTo(2L);
        // The Sonnet turn between the Opus dispatch and its tool_result carries
        // agent.name, so it must not claim the first call.
        assertThat(explore.byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);

        // The dispatch that named no subagent type — it ran on the default agent.
        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_SONNET, 1L));
    }

    @Test
    void subagentDispatchWithNoTypeIsCreditedToTheDefaultAgentNotAnUnknownBucket() {
        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).doesNotContain("unknown");
        assertThat(rows)
                .filteredOn(row -> SUBAGENT_GENERAL_PURPOSE.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(1L));
    }

    @Test
    void perModelCallsAlwaysSumToTheRowTotal() {
        List<IdentifierUsageCount> rows = new ArrayList<>(service.aggregateSkillUsageInRange(windowStart, windowEnd));
        rows.addAll(service.aggregateSubagentUsageInRange(windowStart, windowEnd));

        assertThat(rows).isNotEmpty().allSatisfy(row ->
                assertThat(row.byModel().values().stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(row.calls()));
    }

    @Test
    void subagentCallsWithNoPrecedingMainLoopTurnBucketUnderUnknownModel() {
        // A second session whose only rows are the dispatch itself — no api_request
        // exists at or before it, so the lateral lookup finds nothing.
        saveLog(OFFSET_FIRST_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, "session-with-no-turns",
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, "orphaned"));

        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> "orphaned".equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.byModel())
                        .containsExactlyInAnyOrderEntriesOf(Map.of("unknown", 1L)));
    }

    @Test
    void subagentCostAttributesToTheDispatchedSubagentTypeNotTheDispatchingTurnsModel() {
        // Unlike calls (resolved by walking back to the last main-loop api_request in
        // the session), cost is resolved by span correlation: the dispatching
        // tool_result's own execution span, and the llm_request span(s) directly
        // beneath it. This seeds that span tree -- root -> tool wrapper -> tool.execution
        // (the dispatch) -> child llm_request carrying request_id -- mirroring
        // LogsQueryIntegrationTest#logsForTraceRepointsSubagentLogsOffTheDispatchingTaskSpan.
        String traceId = "trace-cost-correlated";
        String toolUseId = "toolu_cost_dispatch";
        String requestId = "req_cost_dispatch";
        String subagentType = "cost-correlated-agent";
        String costCorrelatedSession = "session-cost-correlated";

        // Dispatching main-loop turn, deliberately on a DIFFERENT model than the
        // subagent's own priced call below -- proves cost is not read off this turn,
        // the way the invocation count's model is.
        saveLog(OFFSET_COST_CORRELATED_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, costCorrelatedSession,
                ATTR_MODEL, MODEL_OPUS));

        saveLog(OFFSET_COST_CORRELATED_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, costCorrelatedSession,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, subagentType,
                ATTR_TOOL_USE_ID, toolUseId));

        String rootSpanId = "r000000000000001";
        String toolWrapperSpanId = "w000000000000001";
        String toolExecutionSpanId = "e000000000000001";
        String llmRequestSpanId = "l000000000000001";
        saveSpan(traceId, rootSpanId, null, SPAN_NAME_ROOT, Map.of());
        saveSpan(traceId, toolWrapperSpanId, rootSpanId, SPAN_NAME_TOOL_WRAPPER,
                Map.of(ATTR_TOOL_USE_ID, toolUseId));
        saveSpan(traceId, toolExecutionSpanId, toolWrapperSpanId, SPAN_NAME_TOOL_EXECUTION,
                Map.of(ATTR_TOOL_USE_ID, toolUseId));
        saveSpan(traceId, llmRequestSpanId, toolExecutionSpanId, SPAN_NAME_LLM_REQUEST,
                Map.of(ATTR_REQUEST_ID, requestId));

        // The subagent's own priced LLM call, correlated purely by request_id.
        saveLog(OFFSET_COST_CORRELATED_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_REQUEST_ID, requestId,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_COST_USD, COST_CORRELATED_SUBAGENT_CALL));

        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        IdentifierUsageCount subagent = rows.stream()
                .filter(row -> subagentType.equals(row.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(subagent.costUsd()).isCloseTo(COST_CORRELATED_SUBAGENT_CALL, offset(COST_ASSERTION_TOLERANCE));
        assertThat(subagent.costByModel())
                .containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_SONNET, COST_CORRELATED_SUBAGENT_CALL));
        // The dispatching turn's own model must not appear -- that would mean cost fell
        // back to the "last main-loop turn" heuristic instead of the span correlation.
        assertThat(subagent.costByModel()).doesNotContainKey(MODEL_OPUS);
    }

    @Test
    void subagentDispatchWithNoMatchingExecutionSpanReportsZeroCostRatherThanBeingDropped() {
        String subagentTypeNoSpanAtAll = "orphan-no-execution-span";
        String subagentTypeNoChildLlmSpan = "orphan-no-child-llm-span";
        String orphanSession = "session-orphan-dispatches";

        // Dispatch 1: its tool_use_id never matches any span at all.
        saveLog(OFFSET_ORPHAN_DISPATCHES, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, orphanSession,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, subagentTypeNoSpanAtAll,
                ATTR_TOOL_USE_ID, "toolu_orphan_no_span"));

        // Dispatch 2: its execution span DOES exist, but has no child llm_request span
        // beneath it -- a subagent that made zero billed LLM calls.
        saveLog(OFFSET_ORPHAN_DISPATCHES, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, orphanSession,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, subagentTypeNoChildLlmSpan,
                ATTR_TOOL_USE_ID, "toolu_orphan_childless"));

        String traceId = "trace-orphan-dispatches";
        saveSpan(traceId, "r000000000000002", null, SPAN_NAME_ROOT, Map.of());
        saveSpan(traceId, "w000000000000002", "r000000000000002", SPAN_NAME_TOOL_WRAPPER,
                Map.of(ATTR_TOOL_USE_ID, "toolu_orphan_childless"));
        saveSpan(traceId, "e000000000000002", "w000000000000002", SPAN_NAME_TOOL_EXECUTION,
                Map.of(ATTR_TOOL_USE_ID, "toolu_orphan_childless"));
        // Deliberately no llm_request span beneath e000000000000002.

        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> subagentTypeNoSpanAtAll.equals(row.tool()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.costUsd()).isEqualTo(0.0);
                    assertThat(row.costByModel()).isEmpty();
                });
        assertThat(rows)
                .filteredOn(row -> subagentTypeNoChildLlmSpan.equals(row.tool()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.costUsd()).isEqualTo(0.0);
                    assertThat(row.costByModel()).isEmpty();
                });
    }

    @Test
    void emptyWindowReturnsNoRowsRatherThanAnUnknownBucket() {
        Instant beforeAnySeededRow = windowStart.minus(10, ChronoUnit.MINUTES);

        assertThat(service.aggregateSkillUsageInRange(beforeAnySeededRow, windowStart)).isEmpty();
        assertThat(service.aggregateSubagentUsageInRange(beforeAnySeededRow, windowStart)).isEmpty();
    }

    private void saveLog(int offsetSeconds, Map<String, Object> attributes) {
        Instant timestamp = windowStart.plusSeconds(offsetSeconds);
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("anthropic.api");
        entity.setBody("seeded row");
        entity.setAttributes(new HashMap<>(attributes));
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        repository.save(entity);
    }

    // Mirrors LogsQueryIntegrationTest's saveSpan helper.
    private void saveSpan(String traceId, String spanId, String parentSpanId, String name, Map<String, Object> attributes) {
        SpanEntity entity = new SpanEntity();
        entity.setTraceId(traceId);
        entity.setSpanId(spanId);
        entity.setParentSpanId(parentSpanId);
        entity.setName(name);
        entity.setStartTimestamp(windowStart);
        entity.setEndTimestamp(windowStart.plusSeconds(1));
        entity.setReceivedAt(Instant.now());
        entity.setAttributes(new HashMap<>(attributes));
        spanRepository.save(entity);
    }
}
