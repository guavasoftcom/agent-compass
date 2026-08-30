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
import com.guavasoft.agentcompass.model.EventRow;
import com.guavasoft.agentcompass.model.MetricPage;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link MetricService#recentEvents} against a real Postgres so the
 * offset-paged, filtered {@code metric_points} query behind {@code GET /api/metrics}
 * is validated end-to-end (B1: replaces the former unbounded
 * {@code findAllMatchingFilters}). {@code MetricsControllerTest} mocks the service
 * and can't catch SQL-level bugs (filter matching, ordering, LIMIT/OFFSET clamping).
 */
@SpringBootTest
@Testcontainers
class MetricPageQueryIntegrationTest {

  private static final String METRIC_NAME = "claude_code.cost.usage";
  private static final int SEEDED_ROW_COUNT = 8;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  MetricService metricService;

  private Instant base;

  @BeforeEach
  void seed() {
    metricPointRepository.deleteAll();
    base = Instant.now().minus(1, ChronoUnit.HOURS);

    // Eight rows, newest last-seeded first in query order (timestamp DESC): id 7
    // (t+7m) is newest, id 0 (t+0m) is oldest. Half carry method=GET so the filter
    // tests have a strict subset to narrow to.
    for (int rowIndex = 0; rowIndex < SEEDED_ROW_COUNT; rowIndex++) {
      Map<String, Object> attributes = rowIndex % 2 == 0
          ? Map.of("method", "GET", "status", "200")
          : Map.of("method", "POST", "status", "500");
      save(attributes, base.plus(rowIndex, ChronoUnit.MINUTES));
    }
  }

  private void save(Map<String, Object> attributes, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(METRIC_NAME);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(1.0);
    entity.setValueKind("double");
    entity.setAttributes(attributes);
    metricPointRepository.save(entity);
  }

  @Test
  void firstPageReturnsNewestRowsFirstAndFullTotalCount() {
    MetricPage firstPage = metricService.recentEvents(List.of(), null, null, 0, 5);

    assertThat(firstPage.totalCount()).isEqualTo(SEEDED_ROW_COUNT);
    assertThat(firstPage.items()).hasSize(5);
    // Newest-first: row 7 (base + 7m) sorts ahead of row 6 (base + 6m).
    assertThat(firstPage.items().get(0).getTimestamp()).isAfter(firstPage.items().get(1).getTimestamp());
  }

  @Test
  void secondPageContinuesWhereFirstPageLeftOffWithoutOverlap() {
    MetricPage firstPage = metricService.recentEvents(List.of(), null, null, 0, 5);
    MetricPage secondPage = metricService.recentEvents(List.of(), null, null, 1, 5);

    assertThat(secondPage.totalCount()).isEqualTo(SEEDED_ROW_COUNT);
    assertThat(secondPage.items()).hasSize(3);

    List<Long> firstPageIds = firstPage.items().stream().map(EventRow::getId).toList();
    List<Long> secondPageIds = secondPage.items().stream().map(EventRow::getId).toList();
    assertThat(secondPageIds).doesNotContainAnyElementsOf(firstPageIds);
  }

  @Test
  void filterNarrowsToMatchingAttributePairsOnly() {
    MetricPage filteredPage = metricService.recentEvents(List.of("method=GET"), null, null, 0, 50);

    assertThat(filteredPage.totalCount()).isEqualTo(SEEDED_ROW_COUNT / 2);
    assertThat(filteredPage.items()).hasSize(SEEDED_ROW_COUNT / 2);
    assertThat(filteredPage.items())
        .extracting(row -> row.getAttributes().get("method"))
        .containsOnly("GET");
  }

  @Test
  void pageSizeIsClampedInsteadOfFetchingUnboundedRows() {
    // size is attacker-chosen and huge; must be clamped, not passed straight to SQL LIMIT.
    MetricPage page = metricService.recentEvents(List.of(), null, null, 0, 2_000_000_000);

    assertThat(page.totalCount()).isEqualTo(SEEDED_ROW_COUNT);
    assertThat(page.items()).hasSize(SEEDED_ROW_COUNT);
  }

  @Test
  void pageBeyondLastRowReturnsEmptyItemsButPreservesTotalCount() {
    MetricPage page = metricService.recentEvents(List.of(), null, null, 5, 5);

    assertThat(page.totalCount()).isEqualTo(SEEDED_ROW_COUNT);
    assertThat(page.items()).isEmpty();
  }
}
