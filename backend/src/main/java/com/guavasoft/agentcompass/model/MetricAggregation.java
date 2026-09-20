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

import java.util.Locale;

/**
 * How one metric's per-bucket trend value is aggregated on {@code GET /api/metrics/series}: the
 * default {@link #SUM} of increments, or the mean, 95th percentile or count of the individual
 * non-zero increments in each bucket. Only the trend array is affected; header stats, splits and
 * cardinality always stay sum-based.
 */
public enum MetricAggregation {
  SUM,
  AVG,
  P95,
  COUNT;

  /**
   * Parses the {@code agg} request parameter, case-insensitively.
   *
   * @throws IllegalArgumentException for any value other than sum, avg, p95 or count (mapped to a 400)
   */
  public static MetricAggregation of(String value) {
    if (value != null) {
      String normalized = value.strip().toUpperCase(Locale.ROOT);
      for (MetricAggregation aggregation : values()) {
        if (aggregation.name().equals(normalized)) {
          return aggregation;
        }
      }
    }
    throw new IllegalArgumentException("Unsupported agg '" + value + "': expected sum, avg, p95 or count");
  }
}
