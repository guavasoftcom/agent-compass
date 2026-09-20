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
package com.guavasoft.agentcompass.model;

/**
 * The optional aggregation request on {@code GET /api/metrics/series}: ONE metric ({@code metricId},
 * a series id such as {@code token}) whose trend array is aggregated with {@code aggregation}
 * instead of summed. Every other metric, and every non-trend figure of this one, is unaffected.
 *
 * <p>Both parts are set or neither are; {@link #of} enforces that and treats blank strings as
 * absent, so the rest of the code only ever sees {@link #NONE} or a fully populated request.
 * {@link MetricAggregation#SUM} is a valid request that changes nothing.
 */
public record MetricSeriesAggregation(String metricId, MetricAggregation aggregation) {

  /** No aggregation requested: every trend is the per-bucket sum. */
  public static final MetricSeriesAggregation NONE = new MetricSeriesAggregation(null, null);

  /**
   * Builds the request from the two raw parameters.
   *
   * @throws IllegalArgumentException when only one of the two is supplied, or {@code agg} is not a
   *     supported value (mapped to a 400)
   */
  public static MetricSeriesAggregation of(String metricId, String agg) {
    String normalizedMetricId = blankToNull(metricId);
    String normalizedAgg = blankToNull(agg);
    if (normalizedMetricId == null && normalizedAgg == null) {
      return NONE;
    }
    if (normalizedMetricId == null || normalizedAgg == null) {
      throw new IllegalArgumentException("aggMetricId and agg must be supplied together or not at all");
    }
    return new MetricSeriesAggregation(normalizedMetricId, MetricAggregation.of(normalizedAgg));
  }

  public boolean isActive() {
    return metricId != null;
  }

  private static String blankToNull(String candidate) {
    return candidate == null || candidate.isBlank() ? null : candidate;
  }
}
