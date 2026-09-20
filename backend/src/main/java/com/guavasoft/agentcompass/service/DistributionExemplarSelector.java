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

import com.guavasoft.agentcompass.model.DistributionPoint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Turns the per-request rows behind {@code GET /api/metrics/distribution} into the response's point
 * list, choosing which few points carry a trace id (the OTel "exemplar" pattern). Everything else
 * gets {@code null}, and only a request that genuinely has a trace id is ever eligible, so every
 * exemplar is something the frontend can click through to.
 *
 * <p>The exemplars are, at most: the maximum-value request, the requests nearest the p50, p95 and p99
 * values of the whole returned set, and the highest-value failed request. The same request winning
 * two of those roles is one exemplar, so there are never more than five and never a duplicate.
 * Percentiles are linearly interpolated over ALL returned values (traced or not), matching
 * Postgres' {@code percentile_cont}, so the targets describe the plotted distribution rather than
 * only its traced subset. Ties go to the newest request.
 */
final class DistributionExemplarSelector {

  /**
   * One request read from the log; {@code traceId} is null when it has no usable trace id, and
   * {@code requestId} is null when the record carries no request id (the key that ties it to its
   * {@code llm_request} span).
   */
  record RequestPoint(Instant timestamp, double value, String traceId, String requestId, boolean failed) {
  }

  private static final double[] EXEMPLAR_PERCENTILES = {0.50, 0.95, 0.99};

  private DistributionExemplarSelector() {
  }

  static List<DistributionPoint> toPoints(List<RequestPoint> requestsAscending) {
    return toPoints(requestsAscending, request -> null);
  }

  /**
   * @param requestsAscending the requests to plot, oldest first (the order the points are returned in)
   * @param spanIdResolver looks up the {@code llm_request} span id of a request; called only for the
   *     few exemplars (it is a database read), and may return null when the span is not found
   */
  static List<DistributionPoint> toPoints(
      List<RequestPoint> requestsAscending, Function<RequestPoint, String> spanIdResolver) {
    Set<Integer> exemplarIndexes = exemplarIndexes(requestsAscending);
    List<DistributionPoint> points = new ArrayList<>(requestsAscending.size());
    for (int index = 0; index < requestsAscending.size(); index++) {
      RequestPoint request = requestsAscending.get(index);
      boolean isExemplar = exemplarIndexes.contains(index);
      points.add(new DistributionPoint(
          request.timestamp(),
          request.value(),
          isExemplar ? request.traceId() : null,
          isExemplar ? spanIdResolver.apply(request) : null));
    }
    return points;
  }

  private static Set<Integer> exemplarIndexes(List<RequestPoint> requests) {
    List<Integer> candidateIndexes = new ArrayList<>();
    for (int index = 0; index < requests.size(); index++) {
      if (requests.get(index).traceId() != null) {
        candidateIndexes.add(index);
      }
    }
    Set<Integer> exemplarIndexes = new TreeSet<>();
    if (candidateIndexes.isEmpty()) {
      return exemplarIndexes;
    }
    exemplarIndexes.add(highestValue(requests, candidateIndexes));
    double[] sortedValues = requests.stream().mapToDouble(RequestPoint::value).sorted().toArray();
    for (double percentile : EXEMPLAR_PERCENTILES) {
      exemplarIndexes.add(nearestTo(requests, candidateIndexes, interpolatedPercentile(sortedValues, percentile)));
    }
    List<Integer> failedCandidateIndexes = candidateIndexes.stream()
        .filter(index -> requests.get(index).failed())
        .toList();
    if (!failedCandidateIndexes.isEmpty()) {
      exemplarIndexes.add(highestValue(requests, failedCandidateIndexes));
    }
    return exemplarIndexes;
  }

  // ">=" so that among equal values the later (newer) request wins.
  private static int highestValue(List<RequestPoint> requests, List<Integer> candidateIndexes) {
    int highestIndex = candidateIndexes.get(0);
    for (int candidateIndex : candidateIndexes) {
      if (requests.get(candidateIndex).value() >= requests.get(highestIndex).value()) {
        highestIndex = candidateIndex;
      }
    }
    return highestIndex;
  }

  // The candidate whose value is closest to the target; "<=" so that among equally close
  // candidates the later (newer) one wins.
  private static int nearestTo(List<RequestPoint> requests, List<Integer> candidateIndexes, double targetValue) {
    int nearestIndex = candidateIndexes.get(0);
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (int candidateIndex : candidateIndexes) {
      double distance = Math.abs(requests.get(candidateIndex).value() - targetValue);
      if (distance <= nearestDistance) {
        nearestDistance = distance;
        nearestIndex = candidateIndex;
      }
    }
    return nearestIndex;
  }

  // percentile_cont: the value at fractional rank fraction * (n - 1), interpolating linearly.
  private static double interpolatedPercentile(double[] sortedValues, double fraction) {
    double rank = fraction * (sortedValues.length - 1);
    int lowerIndex = (int) Math.floor(rank);
    int upperIndex = (int) Math.ceil(rank);
    double interpolationWeight = rank - lowerIndex;
    return sortedValues[lowerIndex] + (sortedValues[upperIndex] - sortedValues[lowerIndex]) * interpolationWeight;
  }
}
