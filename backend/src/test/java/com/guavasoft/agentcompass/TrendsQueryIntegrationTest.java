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

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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
        TOOL_RESULT_EVENT, "success", from, to, priorFrom).get(0);

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

    TrendsResponse response = trendService.trendsInRange(from, to);
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

    TrendsResponse response = trendService.trendsInRange(from, to);

    assertThat(response.current().start()).isEqualTo(from);
    assertThat(response.current().end()).isEqualTo(to);
    assertThat(response.previous().start()).isEqualTo(priorFrom);
    assertThat(response.previous().end()).isEqualTo(from);

    assertThat(response.metrics()).containsOnlyKeys(
        "total_cost", "cost_per_session", "blended_rate_per_1m", "cache_read_ratio_pct",
        "tokens_total", "tokens_per_session", "tool_errors", "error_rate_pct",
        "session_failures", "sessions", "avg_duration_min");

    TrendsResponse.MetricTrend totalCost = response.metrics().get("total_cost");
    assertThat(totalCost.after()).isEqualTo(18.0);
    assertThat(totalCost.before()).isEqualTo(4.0);
    assertThat(totalCost.beforeSeries()).hasSize(7);
    assertThat(totalCost.afterSeries()).hasSize(7);
    assertThat(totalCost.directionIsGoodWhen()).isEqualTo("down");

    TrendsResponse.MetricTrend sessionFailures = response.metrics().get("session_failures");
    assertThat(sessionFailures.after()).isEqualTo(1.0);
    assertThat(sessionFailures.before()).isZero();

    TrendsResponse.MetricTrend sessions = response.metrics().get("sessions");
    assertThat(sessions.after()).isEqualTo(2.0);
    assertThat(sessions.before()).isEqualTo(1.0);

    for (TrendsResponse.MetricTrend metricTrend : response.metrics().values()) {
      assertThat(metricTrend.beforeSeries()).hasSize(7);
      assertThat(metricTrend.afterSeries()).hasSize(7);
    }
  }

  private void saveCost(String sessionId, String model, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(COST_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(Map.of(
        "session.id", sessionId,
        "model", model,
        "query_source", "main"));
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }

  private void saveToolResult(String sessionId, Instant timestamp, boolean success) {
    LogRecordEntity entity = new LogRecordEntity();
    entity.setTimestamp(timestamp);
    entity.setObservedTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());

    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", TOOL_RESULT_EVENT);
    attributes.put("session.id", sessionId);
    attributes.put("success", String.valueOf(success));
    entity.setAttributes(attributes);
    logRecordRepository.save(entity);
  }
}
