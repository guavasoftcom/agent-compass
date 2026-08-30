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

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricSplitRow;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link MetricSeriesService} against a real Postgres so the reset-aware,
 * full-attribute increment SQL behind {@code GET /api/metrics/series} is validated
 * end-to-end — the {@code MetricsControllerTest} mocks the service and can't catch
 * SQL-level mistakes (concurrent-stream merging, counter resets, split grouping).
 */
@SpringBootTest
@Testcontainers
class MetricSeriesQueryIntegrationTest {

  private static final String TOKEN_METRIC = "claude_code.token.usage";
  private static final String DECISION_METRIC = "claude_code.code_edit_tool.decision";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  MetricSeriesService metricSeriesService;

  private Instant base;
  private final List<Long> seededMetricPointIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    metricPointRepository.deleteAll();
    seededMetricPointIds.clear();
    base = Instant.now().minus(20, ChronoUnit.MINUTES);

    // token.usage — three concurrent cumulative streams (distinct full attribute
    // sets). Reset-aware increments telescope to each stream's final value:
    //   sonnet/input:     200 -> 500   => 500
    //   opus/input:       1500          => 1500
    //   sonnet/cacheRead: 3000          => 3000
    // total 5000; Model: sonnet 3500 / opus 1500; Type: cacheRead 3000 / input 2000.
    saveToken("sonnet", "input", 200, base);
    saveToken("sonnet", "input", 500, base.plusSeconds(60));
    saveToken("opus", "input", 1500, base.plusSeconds(30));
    saveToken("sonnet", "cacheRead", 3000, base.plusSeconds(45));

    // code_edit_tool.decision — accept stream that RESETS (a re-spawned run starts
    // low again), exercising the CASE reset handling:
    //   accept: 5 -> 8 -> (reset) 2  => 5 + 3 + 2 = 10
    //   reject: 1                     => 1
    saveDecision("accept", 5, base);
    saveDecision("accept", 8, base.plusSeconds(60));
    saveDecision("accept", 2, base.plusSeconds(120));
    saveDecision("reject", 1, base.plusSeconds(30));

    // Fixtures seed rows directly (bypassing OtlpMetricService), so value_delta
    // starts NULL. Replicate the ingest-time computation here — same
    // recomputeValueDeltas call the real ingest path uses after saveAll — so the
    // reset-aware aggregations under test see the same value_delta they would in
    // production.
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  @Test
  void tokenSeriesReconcilesTotalWithModelSplit() {
    MetricSeries token = seriesById("token");

    assertThat(token.name()).isEqualTo(TOKEN_METRIC);
    assertThat(token.type()).isEqualTo("counter");
    assertThat(token.unit()).isEqualTo("tokens");
    assertThat(token.sum()).isEqualTo("5K");
    assertThat(token.trend()).hasSize(24);
    // Trend buckets are the same reset-aware increments, so they sum to the total.
    assertThat(token.trend().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(5000.0);

    List<MetricSplitRow> byModel = token.splits().get("Model");
    assertThat(byModel).extracting(MetricSplitRow::label).containsExactly("sonnet", "opus");
    assertThat(byModel.get(0).value()).isEqualTo("3.5K");
    assertThat(byModel.get(0).pct()).isEqualTo(70);
    assertThat(byModel.get(0).colorIndex()).isEqualTo(0);
    assertThat(byModel.get(1).value()).isEqualTo("1.5K");
    assertThat(byModel.get(1).pct()).isEqualTo(30);

    // Type split removed; only Model split remains
    assertThat(token.splits()).hasSize(1).containsKey("Model");
  }

  @Test
  void decisionSeriesCountsTheResetAsNewUsage() {
    MetricSeries decision = seriesById("decision");

    // 5 + (8-5) + 2 = 10 accepts (reset counted), + 1 reject = 11 total.
    assertThat(decision.sum()).isEqualTo("11");
    List<MetricSplitRow> byDecision = decision.splits().get("Decision");
    assertThat(byDecision).extracting(MetricSplitRow::label).containsExactly("accept", "reject");
    assertThat(byDecision.get(0).value()).isEqualTo("10");
    assertThat(byDecision.get(0).pct()).isEqualTo(91);
    assertThat(byDecision.get(1).value()).isEqualTo("1");
  }

  @Test
  void returnsEveryCuratedMetricEvenWhenUnseeded() {
    List<MetricSeries> series = metricSeriesService.metricSeries(windowStart(), Instant.now());
    assertThat(series).extracting(MetricSeries::id)
        .containsExactly("token", "cost", "session", "active", "loc", "decision", "commit", "pull_request");
    // Metrics with no rows still return a well-formed zero series, no splits populated.
    MetricSeries session = series.stream().filter(s -> "session".equals(s.id())).findFirst().orElseThrow();
    assertThat(session.sum()).isEqualTo("0");
    assertThat(session.splits()).isEmpty();

    MetricSeries commit = series.stream().filter(s -> "commit".equals(s.id())).findFirst().orElseThrow();
    assertThat(commit.name()).isEqualTo("claude_code.commit.count");
    assertThat(commit.splits()).isEmpty();
  }

  @Test
  void appendsAGeneratedCardForAMetricNoSpecCovers() {
    // One cumulative stream, 40 then 100, so the reset-aware sum telescopes to 100.
    saveWithUnit("acme.agent.spend.total", "USD", 40.0, base);
    saveWithUnit("acme.agent.spend.total", "USD", 100.0, base.plusSeconds(60));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    List<MetricSeries> series = metricSeriesService.metricSeries(windowStart(), Instant.now());

    // Curated order is preserved and the unknown metric lands after it.
    assertThat(series).extracting(MetricSeries::id)
        .containsExactly("token", "cost", "session", "active", "loc", "decision", "commit",
            "pull_request", "acme-agent-spend-total");

    MetricSeries discovered = series.get(series.size() - 1);
    assertThat(discovered.name()).isEqualTo("acme.agent.spend.total");
    // The declared unit drives the format, so a discovered money counter still reads as money.
    assertThat(discovered.sum()).isEqualTo("$100.00");
    assertThat(discovered.trend()).hasSize(24);
    assertThat(discovered.splits()).isEmpty();
    assertThat(discovered.description()).contains("not yet curated");
  }

  @Test
  void discoveryIgnoresTheWindowSoCardsDoNotAppearAndVanishAsTheUserPans() {
    saveWithUnit("acme.agent.legacy.count", "", 7.0, base.minus(90, ChronoUnit.DAYS));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    MetricSeries discovered = metricSeriesService.metricSeries(windowStart(), Instant.now()).stream()
        .filter(candidate -> "acme-agent-legacy-count".equals(candidate.id()))
        .findFirst()
        .orElseThrow();

    // Every row it has is far outside the queried window, so it reads zero rather
    // than disappearing -- the same way an unseeded curated metric does.
    assertThat(discovered.sum()).isEqualTo("0");
  }

  private MetricSeries seriesById(String id) {
    return metricSeriesService.metricSeries(windowStart(), Instant.now()).stream()
        .filter(series -> id.equals(series.id()))
        .findFirst()
        .orElseThrow();
  }

  private Instant windowStart() {
    return base.minus(40, ChronoUnit.MINUTES);
  }

  private void saveToken(String model, String type, double value, Instant timestamp) {
    save(TOKEN_METRIC, Map.of("session.id", "s1", "model", model, "type", type), value, timestamp);
  }

  private void saveDecision(String decision, double value, Instant timestamp) {
    save(DECISION_METRIC, Map.of("session.id", "s1", "decision", decision), value, timestamp);
  }

  private void saveWithUnit(String metricName, String unit, double value, Instant timestamp) {
    save(metricName, Map.of("session.id", "s1"), value, timestamp, unit);
  }

  private void save(String metricName, Map<String, Object> attributes, double value, Instant timestamp) {
    save(metricName, attributes, value, timestamp, null);
  }

  private void save(
      String metricName, Map<String, Object> attributes, double value, Instant timestamp, String unit) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(metricName);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(attributes);
    entity.setUnit(unit);
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }
}
