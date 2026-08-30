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
import com.guavasoft.agentcompass.model.CostBreakdown;
import com.guavasoft.agentcompass.model.CostCategoryShare;
import com.guavasoft.agentcompass.model.CostSessionShare;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.service.CostService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Exercises the Cost page's work-category partition against a real Postgres instance. The
 * property under test is the one the whole page design leans on: every {@code api_request} row
 * belongs to exactly one of MAIN_LOOP / SUBAGENT / SKILL / AUXILIARY, in that precedence order, so
 * the four categories' costUsd sums exactly to the page total even when a row is tagged as both a
 * subagent call and a skill invocation (a skill running inside a subagent).
 */
@SpringBootTest
@Testcontainers
class CostBreakdownQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository repository;

    @Autowired
    CostService service;

    private Instant windowStart;
    private Instant windowEnd;

    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_SESSION_ID = "session.id";
    private static final String ATTR_MODEL = "model";
    private static final String ATTR_EFFORT = "effort";
    private static final String ATTR_QUERY_SOURCE = "query_source";
    private static final String ATTR_SKILL_NAME = "skill.name";
    private static final String ATTR_COST_USD = "cost_usd";
    private static final String ATTR_INPUT_TOKENS = "input_tokens";
    private static final String ATTR_OUTPUT_TOKENS = "output_tokens";
    private static final String ATTR_CACHE_CREATION_TOKENS = "cache_creation_tokens";
    private static final String ATTR_CACHE_READ_TOKENS = "cache_read_tokens";

    private static final String EVENT_API_REQUEST = "api_request";

    private static final String MODEL_OPUS = "claude-opus-4-8";
    private static final String MODEL_SONNET = "claude-sonnet-4-6";

    private static final double COST_MAIN_LOOP = 1.00;
    private static final double COST_SUBAGENT = 2.00;
    private static final double COST_SKILL = 0.50;
    private static final double COST_SKILL_INSIDE_SUBAGENT = 0.30;
    private static final double COST_AUXILIARY = 0.20;
    private static final double TOTAL_COST =
            COST_MAIN_LOOP + COST_SUBAGENT + COST_SKILL + COST_SKILL_INSIDE_SUBAGENT + COST_AUXILIARY;

    private static final double COST_ASSERTION_TOLERANCE = 0.0001;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        saveLog(60, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, "session-main",
                ATTR_MODEL, MODEL_SONNET,
                ATTR_EFFORT, "high",
                ATTR_QUERY_SOURCE, "sdk",
                ATTR_COST_USD, COST_MAIN_LOOP,
                ATTR_INPUT_TOKENS, 100,
                ATTR_OUTPUT_TOKENS, 50,
                ATTR_CACHE_CREATION_TOKENS, 10,
                ATTR_CACHE_READ_TOKENS, 20));

        // Subagent call -- no effort recorded, to prove the model x effort grid keeps
        // that as null rather than defaulting it.
        saveLog(120, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, "session-sub",
                ATTR_MODEL, MODEL_OPUS,
                ATTR_QUERY_SOURCE, "agent:builtin:Explore",
                ATTR_COST_USD, COST_SUBAGENT));

        saveLog(180, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, "session-skill",
                ATTR_MODEL, MODEL_SONNET,
                ATTR_QUERY_SOURCE, "sdk",
                ATTR_SKILL_NAME, "ship",
                ATTR_COST_USD, COST_SKILL));

        // Carries BOTH a subagent query_source and a skill.name -- the precedence
        // case: must land in SUBAGENT, not SKILL, and must not be double counted.
        saveLog(240, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, "session-skill-in-subagent",
                ATTR_MODEL, MODEL_OPUS,
                ATTR_QUERY_SOURCE, "agent:custom",
                ATTR_SKILL_NAME, "ship",
                ATTR_COST_USD, COST_SKILL_INSIDE_SUBAGENT));

        saveLog(300, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, "session-aux",
                ATTR_MODEL, MODEL_SONNET,
                ATTR_QUERY_SOURCE, "compact",
                ATTR_COST_USD, COST_AUXILIARY));
    }

    @Test
    void categoriesPartitionEveryRequestExactlyOnceAndSumToTheTotal() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        assertThat(breakdown.totalCostUsd()).isCloseTo(TOTAL_COST, offset(COST_ASSERTION_TOLERANCE));
        assertThat(breakdown.categories().stream().mapToDouble(CostCategoryShare::costUsd).sum())
                .isCloseTo(breakdown.totalCostUsd(), offset(COST_ASSERTION_TOLERANCE));
        assertThat(breakdown.categories().stream().mapToLong(CostCategoryShare::requests).sum())
                .isEqualTo(5L);
    }

    @Test
    void aSkillRunningInsideASubagentIsCreditedToSubagentNotSkill() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        double subagentCost = categoryCost(breakdown, "SUBAGENT");
        double skillCost = categoryCost(breakdown, "SKILL");

        // 2.00 (plain subagent call) + 0.30 (skill-in-subagent) -- the mixed row's
        // cost belongs here, not in SKILL.
        assertThat(subagentCost).isCloseTo(COST_SUBAGENT + COST_SKILL_INSIDE_SUBAGENT, offset(COST_ASSERTION_TOLERANCE));
        // Only the plain skill call -- the mixed row must NOT also appear here,
        // which would double count it against the page total.
        assertThat(skillCost).isCloseTo(COST_SKILL, offset(COST_ASSERTION_TOLERANCE));
    }

    @Test
    void mainLoopAndAuxiliaryCategoriesReadTheirOwnRows() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        assertThat(categoryCost(breakdown, "MAIN_LOOP")).isCloseTo(COST_MAIN_LOOP, offset(COST_ASSERTION_TOLERANCE));
        assertThat(categoryCost(breakdown, "AUXILIARY")).isCloseTo(COST_AUXILIARY, offset(COST_ASSERTION_TOLERANCE));
    }

    @Test
    void subagentDrilldownCanReadLessThanItsCategoryTotalWithoutBeingForcedToMatch() {
        // No tool_result / span correlation was seeded, so aggregateSubagentCostByModelInRange
        // has nothing to attribute to a named subagent identifier -- identifiedCostUsd must
        // read 0 rather than being clamped up to (or silently misreported as) costUsd.
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        CostCategoryShare subagent = breakdown.categories().stream()
                .filter(category -> "SUBAGENT".equals(category.category()))
                .findFirst()
                .orElseThrow();

        assertThat(subagent.drilldown()).isEmpty();
        assertThat(subagent.identifiedCostUsd()).isEqualTo(0.0);
        assertThat(subagent.costUsd()).isGreaterThan(subagent.identifiedCostUsd());
    }

    @Test
    void modelEffortGridSumsToTheSameTotalAndKeepsMissingEffortNull() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        assertThat(breakdown.modelEffort().stream().mapToDouble(cell -> cell.costUsd()).sum())
                .isCloseTo(breakdown.totalCostUsd(), offset(COST_ASSERTION_TOLERANCE));
        assertThat(breakdown.modelEffort())
                .filteredOn(cell -> MODEL_OPUS.equals(cell.model()))
                .allSatisfy(cell -> assertThat(cell.effort()).isNull());
        assertThat(breakdown.modelEffort())
                .filteredOn(cell -> MODEL_SONNET.equals(cell.model()) && "high".equals(cell.effort()))
                .singleElement()
                .satisfies(cell -> assertThat(cell.costUsd()).isCloseTo(COST_MAIN_LOOP, offset(COST_ASSERTION_TOLERANCE)));
    }

    @Test
    void topSessionsAreRankedByCostDescending() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        assertThat(breakdown.topSessions()).isNotEmpty();
        assertThat(breakdown.topSessions().get(0).sessionId()).isEqualTo("session-sub");
        assertThat(breakdown.topSessions().get(0).costUsd())
                .isCloseTo(COST_SUBAGENT, offset(COST_ASSERTION_TOLERANCE));
        assertThat(breakdown.topSessions())
                .isSortedAccordingTo((left, right) -> Double.compare(right.costUsd(), left.costUsd()));
    }

    @Test
    void topSessionsCarryFirstUserPromptAndAPerCategoryCostSplitThatSumsToTheSessionsOwnCost() {
        String promptText = "Refactor the cost breakdown query to split by session";
        saveLog(60, Map.of(
                ATTR_EVENT_NAME, "user_prompt",
                ATTR_SESSION_ID, "session-main",
                "prompt", promptText));

        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        CostSessionShare mainLoopSession = breakdown.topSessions().stream()
                .filter(session -> "session-main".equals(session.sessionId()))
                .findFirst()
                .orElseThrow();
        assertThat(mainLoopSession.firstUserPrompt()).isEqualTo(promptText);
        assertThat(mainLoopSession.mainLoopCostUsd()).isCloseTo(COST_MAIN_LOOP, offset(COST_ASSERTION_TOLERANCE));
        assertThat(mainLoopSession.subagentCostUsd()).isEqualTo(0.0);
        assertThat(mainLoopSession.skillCostUsd()).isEqualTo(0.0);
        assertThat(mainLoopSession.auxiliaryCostUsd()).isEqualTo(0.0);

        CostSessionShare skillInSubagentSession = breakdown.topSessions().stream()
                .filter(session -> "session-skill-in-subagent".equals(session.sessionId()))
                .findFirst()
                .orElseThrow();
        // Mirrors the page-wide precedence case: the mixed row must land entirely in
        // SUBAGENT, not SKILL, for this session too.
        assertThat(skillInSubagentSession.subagentCostUsd())
                .isCloseTo(COST_SKILL_INSIDE_SUBAGENT, offset(COST_ASSERTION_TOLERANCE));
        assertThat(skillInSubagentSession.skillCostUsd()).isEqualTo(0.0);
        assertThat(skillInSubagentSession.firstUserPrompt()).isNull();

        for (CostSessionShare session : breakdown.topSessions()) {
            double categorySum = session.mainLoopCostUsd() + session.subagentCostUsd()
                    + session.skillCostUsd() + session.auxiliaryCostUsd();
            assertThat(categorySum).isCloseTo(session.costUsd(), offset(COST_ASSERTION_TOLERANCE));
        }
    }

    @Test
    void trendBucketsPerCategorySumToTheCategoryTotal() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        double subagentFromTrend = breakdown.trend().stream()
                .mapToDouble(point -> point.costByCategory().getOrDefault("SUBAGENT", 0.0))
                .sum();
        assertThat(subagentFromTrend).isCloseTo(categoryCost(breakdown, "SUBAGENT"), offset(COST_ASSERTION_TOLERANCE));
    }

    @Test
    void deltaVsPriorWindowReadsZeroWhenNoPriorSpendExists() {
        CostBreakdown breakdown = service.breakdownInRange(windowStart, windowEnd);

        assertThat(breakdown.priorCostUsd()).isEqualTo(0.0);
        assertThat(breakdown.deltaPct()).isEqualTo(0.0);
    }

    private static double categoryCost(CostBreakdown breakdown, String category) {
        return breakdown.categories().stream()
                .filter(row -> category.equals(row.category()))
                .findFirst()
                .map(CostCategoryShare::costUsd)
                .orElse(0.0);
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
}
