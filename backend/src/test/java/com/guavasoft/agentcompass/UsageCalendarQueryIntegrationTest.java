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
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.UsageCalendarDaily;
import com.guavasoft.agentcompass.model.UsageCalendarDay;
import com.guavasoft.agentcompass.model.UsageCalendarHour;
import com.guavasoft.agentcompass.model.UsageCalendarModelCost;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.UsageCalendarService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Exercises the Usage Calendar daily rollup against a real Postgres, because everything it can get
 * wrong is in SQL the controller test mocks away: which LOCAL day a row lands on (a row at 23:30
 * Chicago time is the next UTC day), that daily figures are the counter's reset-aware increments
 * rather than its cumulative values, and that the two log-side counts follow the same population rules
 * as the skill and subagent usage endpoints.
 *
 * <p>Fixtures use America/Chicago: CDT (UTC-5) on 2026-09-01..03, and the 25-hour fall-back day
 * 2026-11-01, which a fixed 86400 s bucket could not represent.
 */
@SpringBootTest
@Testcontainers
class UsageCalendarQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MetricPointRepository metricPointRepository;

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    UsageCalendarService usageCalendarService;

    private static final String COST_METRIC = "claude_code.cost.usage";
    private static final String TOKEN_METRIC = "claude_code.token.usage";
    private static final String ACTIVE_TIME_METRIC = "claude_code.active_time.total";
    private static final String COMMIT_METRIC = "claude_code.commit.count";
    private static final String PULL_REQUEST_METRIC = "claude_code.pull_request.count";
    private static final String LINES_METRIC = "claude_code.lines_of_code.count";
    private static final String DECISION_METRIC = "claude_code.code_edit_tool.decision";

    private static final String ATTR_SESSION_ID = "session.id";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_MODEL = "model";
    private static final String ATTR_DECISION = "decision";
    private static final String ATTR_REPOSITORY_URL = "vcs.repository.url.full";
    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_TOOL_NAME = "tool_name";
    private static final String ATTR_SKILL_NAME = "skill.name";
    private static final String ATTR_PROMPT_ID = "prompt.id";
    private static final String ATTR_AGENT_NAME = "agent.name";

    private static final String CHICAGO = "America/Chicago";
    private static final String REPOSITORY_A = "https://github.com/example/repo-a";

    // Local days Sep 1..3 in CDT: midnight is 05:00Z, so [from, to) covers three whole days.
    private static final Instant SEPTEMBER_FROM = Instant.parse("2026-09-01T05:00:00Z");
    private static final Instant SEPTEMBER_TO = Instant.parse("2026-09-04T05:00:00Z");
    private static final LocalDate SEPTEMBER_FIRST = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEPTEMBER_SECOND = LocalDate.of(2026, 9, 2);
    private static final LocalDate SEPTEMBER_THIRD = LocalDate.of(2026, 9, 3);

    private static final double MONEY_TOLERANCE = 0.0001;

    private final List<Long> seededMetricPointIds = new ArrayList<>();

    @BeforeEach
    void clearTables() {
        metricPointRepository.deleteAll();
        logRecordRepository.deleteAll();
        seededMetricPointIds.clear();
    }

    @Test
    void returnsExactlyOneZeroFilledRowPerLocalDayInTheRange() {
        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        assertThat(daily.days()).extracting(UsageCalendarDay::date)
                .containsExactly(SEPTEMBER_FIRST, SEPTEMBER_SECOND, SEPTEMBER_THIRD);
        assertThat(daily.days()).allSatisfy(day -> {
            assertThat(day.costUsd()).isZero();
            assertThat(day.tokens()).isZero();
            assertThat(day.skillCalls()).isZero();
            assertThat(day.sessions()).isZero();
        });
    }

    @Test
    void bucketsByTheCallersLocalDayNotTheUtcDay() {
        // 04:30Z on Sep 2 is 23:30 CDT on Sep 1: local Sep 1, UTC Sep 2.
        saveCounter(COST_METRIC, 2.0, Instant.parse("2026-09-02T04:30:00Z"), Map.of(ATTR_SESSION_ID, "late"));
        // 05:00Z on Sep 2 is exactly local midnight, so it starts Sep 2.
        saveCounter(COST_METRIC, 5.0, Instant.parse("2026-09-02T05:00:00Z"), Map.of(ATTR_SESSION_ID, "midnight"));
        recomputeDeltas();

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        assertThat(dayOf(daily, SEPTEMBER_FIRST).costUsd()).isEqualTo(2.0, offset(MONEY_TOLERANCE));
        assertThat(dayOf(daily, SEPTEMBER_SECOND).costUsd()).isEqualTo(5.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void aCumulativeStreamContributesItsIncrementToEachDayNotItsRunningTotal() {
        Map<String, Object> stream = Map.of(ATTR_SESSION_ID, "session-a");
        saveCounter(COST_METRIC, 10.0, Instant.parse("2026-09-01T12:00:00Z"), stream);
        saveCounter(COST_METRIC, 25.0, Instant.parse("2026-09-02T12:00:00Z"), stream);
        recomputeDeltas();

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        assertThat(dayOf(daily, SEPTEMBER_FIRST).costUsd()).isEqualTo(10.0, offset(MONEY_TOLERANCE));
        assertThat(dayOf(daily, SEPTEMBER_SECOND).costUsd()).isEqualTo(15.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void reportsTokensSessionsAndActiveTimeAndExcludesRowsOutsideTheRange() {
        Instant firstDayNoon = Instant.parse("2026-09-01T12:00:00Z");
        saveCounter(TOKEN_METRIC, 1000.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "session-a", ATTR_TYPE, "input"));
        saveCounter(TOKEN_METRIC, 4000.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "session-a", ATTR_TYPE, "cacheRead"));
        saveCounter(ACTIVE_TIME_METRIC, 600.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "session-a"));
        saveCounter(COST_METRIC, 1.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "session-a"));
        saveCounter(COST_METRIC, 1.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "session-b"));
        // One second before the range starts, and exactly at its (exclusive) end.
        saveCounter(COST_METRIC, 50.0, SEPTEMBER_FROM.minusSeconds(1), Map.of(ATTR_SESSION_ID, "before"));
        saveCounter(COST_METRIC, 60.0, SEPTEMBER_TO, Map.of(ATTR_SESSION_ID, "after"));
        recomputeDeltas();

        UsageCalendarDay firstDay = dayOf(
                usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false), SEPTEMBER_FIRST);

        assertThat(firstDay.tokens()).isEqualTo(5000L);
        assertThat(firstDay.activeSeconds()).isEqualTo(600L);
        // session-a (cost + active time) and session-b (cost), each once.
        assertThat(firstDay.sessions()).isEqualTo(2L);
        assertThat(firstDay.costUsd()).isEqualTo(2.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void splitsEachDaysCostByModelLargestFirstAndTheModelsSumToTheDaysCost() {
        Instant firstDayNoon = Instant.parse("2026-09-01T12:00:00Z");
        saveCounter(COST_METRIC, 6.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_MODEL, "claude-haiku-3-5"));
        saveCounter(COST_METRIC, 30.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_MODEL, "claude-opus-4"));
        saveCounter(COST_METRIC, 4.0, firstDayNoon, Map.of(ATTR_SESSION_ID, "s"));
        // A different day's model must not leak into the first day's list.
        saveCounter(COST_METRIC, 9.0, Instant.parse("2026-09-02T12:00:00Z"),
                Map.of(ATTR_SESSION_ID, "s", ATTR_MODEL, "claude-sonnet-4"));
        recomputeDeltas();

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        UsageCalendarDay firstDay = dayOf(daily, SEPTEMBER_FIRST);
        assertThat(firstDay.costByModel()).extracting(UsageCalendarModelCost::model)
                .containsExactly("claude-opus-4", "claude-haiku-3-5", "unknown");
        assertThat(firstDay.costByModel().stream().mapToDouble(UsageCalendarModelCost::costUsd).sum())
                .isEqualTo(firstDay.costUsd(), offset(MONEY_TOLERANCE));
        assertThat(dayOf(daily, SEPTEMBER_SECOND).costByModel()).extracting(UsageCalendarModelCost::model)
                .containsExactly("claude-sonnet-4");
        assertThat(dayOf(daily, SEPTEMBER_THIRD).costByModel()).isEmpty();
    }

    @Test
    void splitsLinesOfCodeAndEditDecisionsByTheirAttributeAndCountsCommitsAndPullRequests() {
        Instant secondDayNoon = Instant.parse("2026-09-02T12:00:00Z");
        saveCounter(LINES_METRIC, 30.0, secondDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_TYPE, "added"));
        saveCounter(LINES_METRIC, 10.0, secondDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_TYPE, "removed"));
        saveCounter(DECISION_METRIC, 3.0, secondDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_DECISION, "accept"));
        saveCounter(DECISION_METRIC, 1.0, secondDayNoon, Map.of(ATTR_SESSION_ID, "s", ATTR_DECISION, "reject"));
        Instant thirdDayNoon = Instant.parse("2026-09-03T12:00:00Z");
        saveCounter(COMMIT_METRIC, 2.0, thirdDayNoon, Map.of(ATTR_SESSION_ID, "s"));
        saveCounter(PULL_REQUEST_METRIC, 1.0, thirdDayNoon, Map.of(ATTR_SESSION_ID, "s"));
        recomputeDeltas();

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        UsageCalendarDay secondDay = dayOf(daily, SEPTEMBER_SECOND);
        assertThat(secondDay.linesAdded()).isEqualTo(30L);
        assertThat(secondDay.linesRemoved()).isEqualTo(10L);
        assertThat(secondDay.decisionsAccepted()).isEqualTo(3L);
        assertThat(secondDay.decisionsRejected()).isEqualTo(1L);
        UsageCalendarDay thirdDay = dayOf(daily, SEPTEMBER_THIRD);
        assertThat(thirdDay.commits()).isEqualTo(2L);
        assertThat(thirdDay.pullRequests()).isEqualTo(1L);
        // The split metrics do not leak into the other days.
        assertThat(dayOf(daily, SEPTEMBER_FIRST).linesAdded()).isZero();
    }

    @Test
    void scopesEveryFigureToTheRequestedRepository() {
        Instant thirdDayNoon = Instant.parse("2026-09-03T12:00:00Z");
        saveCounter(COST_METRIC, 7.0, thirdDayNoon, Map.of(ATTR_SESSION_ID, "in-repo", ATTR_REPOSITORY_URL, REPOSITORY_A));
        saveCounter(COST_METRIC, 100.0, thirdDayNoon, Map.of(ATTR_SESSION_ID, "unattributed"));
        recomputeDeltas();

        UsageCalendarDay unscoped = dayOf(
                usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false), SEPTEMBER_THIRD);
        UsageCalendarDay scoped = dayOf(
                usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, REPOSITORY_A, false), SEPTEMBER_THIRD);

        assertThat(unscoped.costUsd()).isEqualTo(107.0, offset(MONEY_TOLERANCE));
        assertThat(scoped.costUsd()).isEqualTo(7.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void countsOneSkillInvocationPerPromptDatedByItsEarliestTurnAndIgnoresSubagentTurns() {
        // One invocation of "ship" whose turns straddle local midnight: 23:00 CDT Sep 1 and 01:00 CDT Sep 2.
        saveSkillTurn("ship", "prompt-1", Instant.parse("2026-09-02T04:00:00Z"), false);
        saveSkillTurn("ship", "prompt-1", Instant.parse("2026-09-02T06:00:00Z"), false);
        // A second, separate invocation on Sep 1 and one on Sep 2 of another skill.
        saveSkillTurn("ship", "prompt-2", Instant.parse("2026-09-01T15:00:00Z"), false);
        saveSkillTurn("verify", "prompt-3", Instant.parse("2026-09-02T15:00:00Z"), false);
        // A turn made from inside a spawned subagent never counts as an invocation.
        saveSkillTurn("ship", "prompt-1-subagent", Instant.parse("2026-09-02T16:00:00Z"), true);

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        assertThat(dayOf(daily, SEPTEMBER_FIRST).skillCalls()).isEqualTo(2L);
        assertThat(dayOf(daily, SEPTEMBER_SECOND).skillCalls()).isEqualTo(1L);
    }

    @Test
    void countsSubagentDispatchesPerLocalDayFromAgentToolResults() {
        saveToolResult("Agent", Instant.parse("2026-09-02T12:00:00Z"));
        saveToolResult("Agent", Instant.parse("2026-09-02T13:00:00Z"));
        saveToolResult("Read", Instant.parse("2026-09-02T14:00:00Z"));

        UsageCalendarDaily daily = usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false);

        assertThat(dayOf(daily, SEPTEMBER_SECOND).subagentCalls()).isEqualTo(2L);
        assertThat(dayOf(daily, SEPTEMBER_FIRST).subagentCalls()).isZero();
    }

    @Test
    void aDaylightSavingFallBackDayIsTwentyFiveHoursLong() {
        // Chicago falls back on 2026-11-01: local Nov 1 runs 05:00Z .. 06:00Z on Nov 2.
        Instant rangeStart = Instant.parse("2026-11-01T05:00:00Z");
        Instant rangeEnd = Instant.parse("2026-11-03T06:00:00Z");
        // 05:30Z Nov 2 is 23:30 CST Nov 1 -- still Nov 1, though 24 h after the range start has passed.
        saveCounter(COST_METRIC, 3.0, Instant.parse("2026-11-02T05:30:00Z"), Map.of(ATTR_SESSION_ID, "extra-hour"));
        // 06:00Z Nov 2 is local midnight, the first instant of Nov 2.
        saveCounter(COST_METRIC, 4.0, Instant.parse("2026-11-02T06:00:00Z"), Map.of(ATTR_SESSION_ID, "next-day"));
        recomputeDeltas();

        UsageCalendarDaily daily = usageCalendarService.daily(rangeStart, rangeEnd, CHICAGO, null, false);

        assertThat(daily.days()).extracting(UsageCalendarDay::date)
                .containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        assertThat(dayOf(daily, LocalDate.of(2026, 11, 1)).costUsd())
                .isEqualTo(3.0, offset(MONEY_TOLERANCE));
        assertThat(dayOf(daily, LocalDate.of(2026, 11, 2)).costUsd())
                .isEqualTo(4.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void hourlyBucketsAreOmittedUnlessRequested() {
        saveCounter(COST_METRIC, 2.0, Instant.parse("2026-09-01T12:00:00Z"), Map.of(ATTR_SESSION_ID, "session-a"));
        recomputeDeltas();

        assertThat(usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, false).days())
                .allSatisfy(day -> assertThat(day.hourly()).isNull());
    }

    @Test
    void splitsEachDayIntoTwentyFourLocalHourBucketsThatSumToTheDaysFigures() {
        // 13:30Z is 08:30 CDT; 19:10Z and 19:20Z are 14:10 and 14:20 CDT; 04:30Z Sep 2 is 23:30 CDT Sep 1.
        saveCounter(COST_METRIC, 2.0, Instant.parse("2026-09-01T13:30:00Z"), Map.of(ATTR_SESSION_ID, "morning"));
        saveCounter(COST_METRIC, 3.0, Instant.parse("2026-09-01T19:10:00Z"), Map.of(ATTR_SESSION_ID, "afternoon-a"));
        saveCounter(COST_METRIC, 4.0, Instant.parse("2026-09-01T19:20:00Z"), Map.of(ATTR_SESSION_ID, "afternoon-b"));
        saveCounter(COST_METRIC, 1.5, Instant.parse("2026-09-02T04:30:00Z"), Map.of(ATTR_SESSION_ID, "late"));
        saveCounter(TOKEN_METRIC, 800.0, Instant.parse("2026-09-01T19:15:00Z"),
                Map.of(ATTR_SESSION_ID, "afternoon-a", ATTR_TYPE, "input"));
        saveCounter(ACTIVE_TIME_METRIC, 300.0, Instant.parse("2026-09-01T13:35:00Z"), Map.of(ATTR_SESSION_ID, "morning"));
        recomputeDeltas();

        UsageCalendarDay firstDay = dayOf(
                usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, true), SEPTEMBER_FIRST);

        assertThat(firstDay.hourly()).extracting(UsageCalendarHour::hour)
                .containsExactlyElementsOf(IntStream.range(0, 24).boxed().toList());
        assertThat(firstDay.hourly().get(8).costUsd()).isEqualTo(2.0, offset(MONEY_TOLERANCE));
        assertThat(firstDay.hourly().get(8).activeSeconds()).isEqualTo(300L);
        assertThat(firstDay.hourly().get(14).costUsd()).isEqualTo(7.0, offset(MONEY_TOLERANCE));
        assertThat(firstDay.hourly().get(14).tokens()).isEqualTo(800L);
        // The local 23:30 row belongs to hour 23 of Sep 1, not to hour 4 of the UTC day it falls on.
        assertThat(firstDay.hourly().get(23).costUsd()).isEqualTo(1.5, offset(MONEY_TOLERANCE));
        assertThat(firstDay.hourly().get(0).costUsd()).isZero();
        assertThat(firstDay.hourly().stream().mapToDouble(UsageCalendarHour::costUsd).sum())
                .isEqualTo(firstDay.costUsd(), offset(MONEY_TOLERANCE));
        assertThat(firstDay.hourly().stream().mapToLong(UsageCalendarHour::tokens).sum()).isEqualTo(firstDay.tokens());
        assertThat(firstDay.hourly().stream().mapToLong(UsageCalendarHour::activeSeconds).sum())
                .isEqualTo(firstDay.activeSeconds());
    }

    @Test
    void datesAHourlySkillInvocationByItsEarliestTurnsHour() {
        // One invocation whose turns straddle an hour: 14:50 and 15:10 CDT. It counts once, at hour 14.
        saveSkillTurn("ship", "prompt-1", Instant.parse("2026-09-01T19:50:00Z"), false);
        saveSkillTurn("ship", "prompt-1", Instant.parse("2026-09-01T20:10:00Z"), false);
        saveSkillTurn("verify", "prompt-2", Instant.parse("2026-09-01T13:00:00Z"), false);
        saveSkillTurn("ship", "prompt-1-subagent", Instant.parse("2026-09-01T20:20:00Z"), true);

        UsageCalendarDay firstDay = dayOf(
                usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, CHICAGO, null, true), SEPTEMBER_FIRST);

        assertThat(firstDay.hourly().get(14).skillCalls()).isEqualTo(1L);
        assertThat(firstDay.hourly().get(15).skillCalls()).isZero();
        assertThat(firstDay.hourly().get(8).skillCalls()).isEqualTo(1L);
        assertThat(firstDay.hourly().stream().mapToLong(UsageCalendarHour::skillCalls).sum())
                .isEqualTo(firstDay.skillCalls());
    }

    @Test
    void theFallBackDaysRepeatedHourMergesIntoOneBucket() {
        // Chicago falls back on 2026-11-01: 01:30 happens twice, at 06:30Z (CDT) and 07:30Z (CST).
        Instant rangeStart = Instant.parse("2026-11-01T05:00:00Z");
        Instant rangeEnd = Instant.parse("2026-11-02T06:00:00Z");
        saveCounter(COST_METRIC, 1.0, Instant.parse("2026-11-01T06:30:00Z"), Map.of(ATTR_SESSION_ID, "first-pass"));
        saveCounter(COST_METRIC, 2.0, Instant.parse("2026-11-01T07:30:00Z"), Map.of(ATTR_SESSION_ID, "second-pass"));
        recomputeDeltas();

        UsageCalendarDay fallBackDay = usageCalendarService.daily(rangeStart, rangeEnd, CHICAGO, null, true)
                .days().get(0);

        assertThat(fallBackDay.hourly()).hasSize(24);
        assertThat(fallBackDay.hourly().get(1).costUsd()).isEqualTo(3.0, offset(MONEY_TOLERANCE));
    }

    @Test
    void rejectsAnUnknownTimeZoneAndAnEmptyRange() {
        assertThatThrownBy(() -> usageCalendarService.daily(SEPTEMBER_FROM, SEPTEMBER_TO, "Not/AZone", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not/AZone");
        assertThatThrownBy(() -> usageCalendarService.daily(SEPTEMBER_TO, SEPTEMBER_FROM, CHICAGO, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UsageCalendarDay dayOf(UsageCalendarDaily daily, LocalDate date) {
        return daily.days().stream().filter(day -> day.date().equals(date)).findFirst().orElseThrow();
    }

    private void saveCounter(String metricName, double value, Instant timestamp, Map<String, Object> attributes) {
        MetricPointEntity entity = new MetricPointEntity();
        entity.setMetricName(metricName);
        entity.setTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setValueDouble(value);
        entity.setValueKind("double");
        entity.setAttributes(new HashMap<>(attributes));
        seededMetricPointIds.add(metricPointRepository.save(entity).getId());
    }

    // Fixtures bypass OtlpMetricService, so value_delta starts NULL; run the same recompute the real
    // ingest path does so the rollup sees the deltas it would see in production.
    private void recomputeDeltas() {
        metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
    }

    private void saveSkillTurn(String skillName, String promptId, Instant timestamp, boolean insideSubagent) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, "api_request");
        attributes.put(ATTR_SKILL_NAME, skillName);
        attributes.put(ATTR_PROMPT_ID, promptId);
        if (insideSubagent) {
            attributes.put(ATTR_AGENT_NAME, "custom");
        }
        saveLog(timestamp, attributes);
    }

    private void saveToolResult(String toolName, Instant timestamp) {
        saveLog(timestamp, Map.of(ATTR_EVENT_NAME, "tool_result", ATTR_TOOL_NAME, toolName));
    }

    private void saveLog(Instant timestamp, Map<String, Object> attributes) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("anthropic.api");
        entity.setBody("seeded row");
        entity.setAttributes(new HashMap<>(attributes));
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }
}
