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

import java.util.Locale;

/**
 * Shared cardinality-to-health classification, extracted from {@code MetricService
 * #aggregateMetricCatalog} so the Metrics Explorer's per-metric card
 * ({@code GET /api/metrics/series}) and the metric catalog
 * ({@code GET /api/metrics/catalog}) apply the identical thresholds to whatever
 * distinct-attribute-combination count each caller computes.
 */
public final class CardinalityHealth {

  private static final long CARDINALITY_WARN_THRESHOLD = 1_000L;
  private static final long CARDINALITY_BAD_THRESHOLD = 5_000L;

  private static final String HEALTH_OK = "ok";
  private static final String HEALTH_WARN = "warn";
  private static final String HEALTH_BAD = "bad";

  private CardinalityHealth() {
  }

  /** Classifies a distinct-attribute-combination count into an "ok" / "warn" / "bad" health bucket. */
  public static String cardinalityHealth(long cardinality) {
    if (cardinality > CARDINALITY_BAD_THRESHOLD) {
      return HEALTH_BAD;
    }
    if (cardinality >= CARDINALITY_WARN_THRESHOLD) {
      return HEALTH_WARN;
    }
    return HEALTH_OK;
  }

  /** Compact display form: {@code "1.2K"} / {@code "3.4M"} / a plain integer below 1,000. */
  public static String formatCardinality(long cardinality) {
    if (cardinality >= 1_000_000L) {
      return String.format(Locale.US, "%.1fM", cardinality / 1_000_000.0);
    }
    if (cardinality >= 1_000L) {
      return String.format(Locale.US, "%.1fK", cardinality / 1_000.0);
    }
    return String.valueOf(cardinality);
  }
}
