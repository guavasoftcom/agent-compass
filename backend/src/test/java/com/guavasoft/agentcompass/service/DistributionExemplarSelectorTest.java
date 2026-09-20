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

import com.guavasoft.agentcompass.model.DistributionPoint;
import com.guavasoft.agentcompass.service.DistributionExemplarSelector.RequestPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistributionExemplarSelectorTest {

  private static final Instant START = Instant.parse("2026-09-19T00:00:00Z");

  @Test
  void noRequestsYieldNoPoints() {
    assertThat(DistributionExemplarSelector.toPoints(List.of())).isEmpty();
  }

  @Test
  void pointsKeepTheInputOrderTimestampsAndValues() {
    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(List.of(
        request(0, 5.0, null, false), request(1, 1.0, null, false), request(2, 9.0, null, false)));

    assertThat(points).extracting(DistributionPoint::ts).containsExactly(START, START.plusSeconds(60), START.plusSeconds(120));
    assertThat(points).extracting(DistributionPoint::value).containsExactly(5.0, 1.0, 9.0);
  }

  @Test
  void whenNoRequestHasATraceIdNoPointIsAnExemplar() {
    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(List.of(
        request(0, 5.0, null, true), request(1, 1.0, null, false), request(2, 9.0, null, false)));

    assertThat(points).extracting(DistributionPoint::traceId).containsOnlyNulls();
  }

  @Test
  void aLoneTracedRequestIsEveryRoleAtOnceAndStaysASingleExemplar() {
    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(List.of(
        request(0, 5.0, null, false), request(1, 7.0, "trace-1", true), request(2, 9.0, null, false)));

    assertThat(points).extracting(DistributionPoint::traceId).containsExactly(null, "trace-1", null);
  }

  @Test
  void picksTheMaximumTheRequestsNearestEachPercentileAndTheHighestFailedOne() {
    // Values 1..20 (index i is i + 1); p50 = 10.5, p95 = 19.05, p99 = 19.81. Untraced rows are never
    // picked however close they sit, and unremarkable traced rows stay null.
    List<RequestPoint> requests = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      boolean traced = List.of(1, 2, 9, 13, 17, 18, 19).contains(index);
      boolean failed = index == 1 || index == 4;
      requests.add(request(index, index + 1.0, traced ? "trace-" + index : null, failed));
    }

    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(requests);

    // max = index 19; p99 (19.81) -> 19; p95 (19.05) -> 18 (value 19); p50 (10.5) -> 9 (value 10; the
    // untraced index 10, value 11, is exactly as near but ineligible); failed: index 4 is untraced,
    // so the failed exemplar is index 1.
    assertThat(exemplarIndexes(points)).containsExactly(1, 9, 18, 19);
    assertThat(points.get(9).traceId()).isEqualTo("trace-9");
  }

  @Test
  void aRequestWinningSeveralRolesIsOneExemplarAndTheTotalNeverExceedsFive() {
    List<RequestPoint> requests = new ArrayList<>();
    for (int index = 0; index < 1_000; index++) {
      requests.add(request(index, index + 1.0, "trace-" + index, true));
    }

    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(requests);

    // Every request is traced and failed; max = failed max = 999, so at most max + p50 + p95 + p99.
    assertThat(exemplarIndexes(points)).hasSizeLessThanOrEqualTo(5).contains(999);
    assertThat(exemplarIndexes(points)).doesNotHaveDuplicates();
  }

  @Test
  void aTiePicksTheNewestRequest() {
    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(List.of(
        request(0, 10.0, "older", false), request(1, 10.0, "newer", false)));

    assertThat(points).extracting(DistributionPoint::traceId).containsExactly(null, "newer");
  }

  @Test
  void aFailedRequestWithoutATraceIdIsNeverAnExemplarEvenAtTheMedian() {
    // Values 1, 50, 100: the median (50) is exactly the failed, untraced request, which has nothing to
    // click through to, so the p50 exemplar falls to the nearest traced one (1, |49| away).
    List<DistributionPoint> points = DistributionExemplarSelector.toPoints(List.of(
        request(0, 1.0, "low", false), request(1, 50.0, null, true), request(2, 100.0, "high", false)));

    assertThat(points).extracting(DistributionPoint::traceId).containsExactly("low", null, "high");
  }

  private static RequestPoint request(int index, double value, String traceId, boolean failed) {
    return new RequestPoint(START.plusSeconds(60L * index), value, traceId, "request-" + index, failed);
  }

  private static List<Integer> exemplarIndexes(List<DistributionPoint> points) {
    List<Integer> indexes = new ArrayList<>();
    for (int index = 0; index < points.size(); index++) {
      if (points.get(index).traceId() != null) {
        indexes.add(index);
      }
    }
    return indexes;
  }
}
