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
import com.guavasoft.agentcompass.model.TrendsResponse;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.TrendService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises GET /api/trends' repository queries (MetricPointRepository /
 * LogRecordRepository trend-report additions) and TrendService's end-to-end
 * before/after computation against a real Postgres.
 */
@SpringBootTest
@Testcontainers
class TrendsQueryIntegrationTest {

  private static final String COST_METRIC = "claude_code.cost.usage";
  private static final String TOOL_RESULT_EVENT = "tool_result";
  private static final String ATTR_REPOSITORY_URL = "vcs.repository.url.full";
  private static final String REPOSITORY_A = "https://github.com/guavasoftcom/coding-agent-tuning";
  private static final String REPOSITORY_B = "https://github.com/guavasoftcom/spring-batch-dashboard";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  TrendService trendService;

  private final List<Long> seededMetricPointIds = new ArrayList<>();

  private Instant from;
  private Instant to;
  private Instant priorFrom;

  @BeforeEach
  void resetTables() {
    metricPointRepository.deleteAll();
    logRecordRepository.deleteAll();
    seededMetricPointIds.clear();

    to = Instant.now();
    from = to.minus(60, ChronoUnit.MINUTES);
    priorFrom = from.minus(60, ChronoUnit.MINUTES);
  }

  @Test
  void sessionFailuresCurrentAndPriorRespectTheHalfOpenBoundary() {
    // Exactly on the shared boundary: counts once, in the current period only.
    saveToolResult("session-boundary", from, false);
    // Just inside the prior half.
    saveToolResult("session-prior", from.minusSeconds(1), false);

    Object[] row = logRecordRepository.aggregateSessionFailuresCurrentAndPrior(
        TOOL_RESULT_EVENT, "success", from, to, priorFrom, null).get(0);

    long currentFailures = ((Number) row[0]).longValue();
    long priorFailures = ((Number) row[1]).longValue();

    assertThat(currentFailures).isEqualTo(1L);
    assertThat(priorFailures).isEqualTo(1L);
  }

  @Test
  void costPerSessionDoesNotDivideByZeroWhenThePriorPeriodHasNoSessions() {
    // Only current-period data -- the prior half is empty.
    saveCost("session-A", "opus", 12.0, from.plusSeconds(300));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    TrendsResponse response = trendService.costTrendsInRange(from, to, null);
    TrendsResponse.MetricTrend costPerSession = response.metrics().get("cost_per_session");

    assertThat(costPerSession.before()).isZero();
    assertThat(costPerSession.after()).isEqualTo(12.0);
    assertThat(costPerSession.beforeSeries()).hasSize(7);
    assertThat(costPerSession.afterSeries()).hasSize(7);
    assertThat(costPerSession.beforeSeries()).allMatch(value -> Double.isFinite(value));
    assertThat(costPerSession.afterSeries()).allMatch(value -> Double.isFinite(value));
  }

  @Test
  void trendsInRangeReconcilesTotalCostAndSessionFailuresEndToEnd() {
    // Current window: two sessions totalling $18.
    saveCost("session-A", "opus", 10.0, from.plusSeconds(300));
    saveCost("session-B", "sonnet", 8.0, from.plusSeconds(600));
    // Prior window: one session totalling $4.
    saveCost("session-C", "opus", 4.0, priorFrom.plusSeconds(300));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    // Current window: one failed tool_result (session-A), one successful (session-B).
    saveToolResult("session-A", from.plusSeconds(400), false);
    saveToolResult("session-B", from.plusSeconds(700), true);
    // Prior window: no failures.
    saveToolResult("session-C", priorFrom.plusSeconds(400), true);

    TrendsResponse costResponse = trendService.costTrendsInRange(from, to, null);

    assertThat(costResponse.current().start()).isEqualTo(from);
    assertThat(costResponse.current().end()).isEqualTo(to);
    assertThat(costResponse.previous().start()).isEqualTo(priorFrom);
    assertThat(costResponse.previous().end()).isEqualTo(from);

    assertThat(costResponse.metrics()).containsOnlyKeys("total_cost", "cost_per_session", "blended_rate_per_1m");

    TrendsResponse.MetricTrend totalCost = costResponse.metrics().get("total_cost");
    assertThat(totalCost.after()).isEqualTo(18.0);
    assertThat(totalCost.before()).isEqualTo(4.0);
    assertThat(totalCost.beforeSeries()).hasSize(7);
    assertThat(totalCost.afterSeries()).hasSize(7);
    assertThat(totalCost.directionIsGoodWhen()).isEqualTo("down");

    TrendsResponse reliabilityResponse = trendService.reliabilityTrendsInRange(from, to, null);

    assertThat(reliabilityResponse.metrics()).containsOnlyKeys("tool_errors", "error_rate_pct", "session_failures");

    TrendsResponse.MetricTrend sessionFailures = reliabilityResponse.metrics().get("session_failures");
    assertThat(sessionFailures.after()).isEqualTo(1.0);
    assertThat(sessionFailures.before()).isZero();

    TrendsResponse activityResponse = trendService.activityTrendsInRange(from, to, null);

    assertThat(activityResponse.metrics()).containsOnlyKeys("sessions", "avg_duration_min");

    TrendsResponse.MetricTrend sessions = activityResponse.metrics().get("sessions");
    assertThat(sessions.after()).isEqualTo(2.0);
    assertThat(sessions.before()).isEqualTo(1.0);

    for (TrendsResponse.MetricTrend metricTrend : costResponse.metrics().values()) {
      assertThat(metricTrend.beforeSeries()).hasSize(7);
      assertThat(metricTrend.afterSeries()).hasSize(7);
    }
    for (TrendsResponse.MetricTrend metricTrend : reliabilityResponse.metrics().values()) {
      assertThat(metricTrend.beforeSeries()).hasSize(7);
      assertThat(metricTrend.afterSeries()).hasSize(7);
    }
    for (TrendsResponse.MetricTrend metricTrend : activityResponse.metrics().values()) {
      assertThat(metricTrend.beforeSeries()).hasSize(7);
      assertThat(metricTrend.afterSeries()).hasSize(7);
    }
  }

  @Test
  void repositoryUrlNullBehavesIdenticallyToBeforeRepositoryAttributionExisted() {
    // None of these rows carry vcs.repository.url.full, so repositoryUrl = null must read exactly
    // what the unfiltered window already returns -- the behavior-preservation guarantee the
    // repository_url column was designed around.
    saveCost("session-A", "opus", 10.0, from.plusSeconds(300));
    saveToolResult("session-A", from.plusSeconds(400), false);
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    TrendsResponse costResponse = trendService.costTrendsInRange(from, to, null);
    TrendsResponse reliabilityResponse = trendService.reliabilityTrendsInRange(from, to, null);
    TrendsResponse activityResponse = trendService.activityTrendsInRange(from, to, null);
    TrendsResponse tokenEfficiencyResponse = trendService.tokenEfficiencyTrendsInRange(from, to, null);

    assertThat(costResponse.metrics().get("total_cost").after()).isEqualTo(10.0);
    assertThat(reliabilityResponse.metrics().get("tool_errors").after()).isEqualTo(1.0);
    assertThat(activityResponse.metrics().get("sessions").after()).isEqualTo(1.0);
    assertThat(tokenEfficiencyResponse.metrics()).containsKeys(
        "cache_read_ratio_pct", "tokens_total", "tokens_per_session");
  }

  @Test
  void costTrendsScopeToTheirOwnRepositoryAndExcludeAnotherRepositorysCurrentAndPriorRows() {
    // Both the current and prior period carry a row for each repository, proving the before/after
    // diff stays comparing like-for-like once scoped rather than an unscoped prior against a
    // scoped current -- an unscoped prior would produce a nonsensical delta.
    saveCost("session-a-current", "opus", 10.0, from.plusSeconds(300), REPOSITORY_A);
    saveCost("session-a-prior", "opus", 4.0, priorFrom.plusSeconds(300), REPOSITORY_A);
    saveCost("session-b-current", "opus", 7.0, from.plusSeconds(300), REPOSITORY_B);
    saveCost("session-b-prior", "opus", 3.0, priorFrom.plusSeconds(300), REPOSITORY_B);
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    TrendsResponse scopedToRepositoryA = trendService.costTrendsInRange(from, to, REPOSITORY_A);
    TrendsResponse scopedToRepositoryB = trendService.costTrendsInRange(from, to, REPOSITORY_B);
    TrendsResponse unscoped = trendService.costTrendsInRange(from, to, null);

    TrendsResponse.MetricTrend totalCostA = scopedToRepositoryA.metrics().get("total_cost");
    assertThat(totalCostA.after()).isEqualTo(10.0);
    assertThat(totalCostA.before()).isEqualTo(4.0);

    TrendsResponse.MetricTrend totalCostB = scopedToRepositoryB.metrics().get("total_cost");
    assertThat(totalCostB.after()).isEqualTo(7.0);
    assertThat(totalCostB.before()).isEqualTo(3.0);

    TrendsResponse.MetricTrend totalCostUnscoped = unscoped.metrics().get("total_cost");
    assertThat(totalCostUnscoped.after()).isEqualTo(17.0);
    assertThat(totalCostUnscoped.before()).isEqualTo(7.0);
  }

  @Test
  void tokenEfficiencyTrendsScopeToTheirOwnRepository() {
    saveCost("session-a", "opus", 10.0, from.plusSeconds(300), REPOSITORY_A);
    saveCost("session-b", "opus", 7.0, from.plusSeconds(300), REPOSITORY_B);
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    TrendsResponse scopedToRepositoryA = trendService.tokenEfficiencyTrendsInRange(from, to, REPOSITORY_A);
    TrendsResponse unscoped = trendService.tokenEfficiencyTrendsInRange(from, to, null);

    assertThat(scopedToRepositoryA.metrics()).containsKeys(
        "cache_read_ratio_pct", "tokens_total", "tokens_per_session");
    assertThat(unscoped.metrics()).containsKeys("cache_read_ratio_pct", "tokens_total", "tokens_per_session");
  }

  @Test
  void reliabilityTrendsScopeToTheirOwnRepositoryAndExcludeAnotherRepositorysFailures() {
    saveToolResult("session-a", from.plusSeconds(400), false, REPOSITORY_A);
    saveToolResult("session-b", from.plusSeconds(400), false, REPOSITORY_B);
    saveToolResult("session-b", from.plusSeconds(450), true, REPOSITORY_B);

    TrendsResponse scopedToRepositoryA = trendService.reliabilityTrendsInRange(from, to, REPOSITORY_A);
    TrendsResponse scopedToRepositoryB = trendService.reliabilityTrendsInRange(from, to, REPOSITORY_B);
    TrendsResponse unscoped = trendService.reliabilityTrendsInRange(from, to, null);

    assertThat(scopedToRepositoryA.metrics().get("tool_errors").after()).isEqualTo(1.0);
    assertThat(scopedToRepositoryB.metrics().get("tool_errors").after()).isEqualTo(1.0);
    assertThat(scopedToRepositoryB.metrics().get("error_rate_pct").after()).isEqualTo(50.0);
    assertThat(unscoped.metrics().get("tool_errors").after()).isEqualTo(2.0);
  }

  @Test
  void activityTrendsScopeToTheirOwnRepositoryAndExcludeAnotherRepositorysSessions() {
    saveCost("session-a", "opus", 10.0, from.plusSeconds(300), REPOSITORY_A);
    saveCost("session-b", "opus", 7.0, from.plusSeconds(300), REPOSITORY_B);
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    TrendsResponse scopedToRepositoryA = trendService.activityTrendsInRange(from, to, REPOSITORY_A);
    TrendsResponse scopedToRepositoryB = trendService.activityTrendsInRange(from, to, REPOSITORY_B);
    TrendsResponse unscoped = trendService.activityTrendsInRange(from, to, null);

    assertThat(scopedToRepositoryA.metrics().get("sessions").after()).isEqualTo(1.0);
    assertThat(scopedToRepositoryB.metrics().get("sessions").after()).isEqualTo(1.0);
    assertThat(unscoped.metrics().get("sessions").after()).isEqualTo(2.0);
  }

  private void saveCost(String sessionId, String model, double value, Instant timestamp) {
    saveCost(sessionId, model, value, timestamp, null);
  }

  private void saveCost(String sessionId, String model, double value, Instant timestamp, String repositoryUrl) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(COST_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("session.id", sessionId);
    attributes.put("model", model);
    attributes.put("query_source", "main");
    if (repositoryUrl != null) {
      attributes.put(ATTR_REPOSITORY_URL, repositoryUrl);
    }
    entity.setAttributes(attributes);
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }

  private void saveToolResult(String sessionId, Instant timestamp, boolean success) {
    saveToolResult(sessionId, timestamp, success, null);
  }

  private void saveToolResult(String sessionId, Instant timestamp, boolean success, String repositoryUrl) {
    LogRecordEntity entity = new LogRecordEntity();
    entity.setTimestamp(timestamp);
    entity.setObservedTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());

    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", TOOL_RESULT_EVENT);
    attributes.put("session.id", sessionId);
    attributes.put("success", String.valueOf(success));
    if (repositoryUrl != null) {
      attributes.put(ATTR_REPOSITORY_URL, repositoryUrl);
    }
    entity.setAttributes(attributes);
    logRecordRepository.save(entity);
  }
}
