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

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional attribute filter on {@code GET /api/metrics/series}: one or more {@code key:value}
 * pairs, ANDed together, applied to the rows of ONE metric ({@code metricId}, a series id such as
 * {@code token}) and to no other, so a {@code model:...} chip cannot zero out a metric that has no
 * model attribute.
 *
 * <p>Either the metric id and at least one pair are both set or neither is; {@link #of} enforces that
 * and treats a blank metric id as absent, so the rest of the code only ever sees {@link #NONE} or a
 * fully populated filter.
 */
public record MetricSeriesFilter(String metricId, List<AttributeMatch> matches) {

  /** One {@code key:value} requirement: the row's attribute {@code key}, read as text, equals {@code value}. */
  public record AttributeMatch(String key, String value) {
  }

  /** No filter: every metric is aggregated over all of its rows. */
  public static final MetricSeriesFilter NONE = new MetricSeriesFilter(null, List.of());

  /** What the SQL clause binds when no filter is active: an empty match list, vacuously satisfied. */
  private static final String EMPTY_MATCHES_JSON = "[]";

  private static final char KEY_VALUE_SEPARATOR = ':';
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  /**
   * Builds a filter from the raw request parameters. Each {@code rawFilters} entry is split on its
   * FIRST {@code ':'} only, so a value may itself contain colons.
   *
   * @throws IllegalArgumentException when a pair has no {@code ':'} or an empty key, or when the
   *     metric id and the pairs are not supplied together (mapped to a 400)
   */
  public static MetricSeriesFilter of(String metricId, List<String> rawFilters) {
    String normalizedMetricId = metricId == null || metricId.isBlank() ? null : metricId;
    List<String> filterEntries = rawFilters == null ? List.of() : rawFilters;
    if (normalizedMetricId == null && filterEntries.isEmpty()) {
      return NONE;
    }
    if (normalizedMetricId == null || filterEntries.isEmpty()) {
      throw new IllegalArgumentException("filterMetricId and filter must be supplied together or not at all");
    }
    List<AttributeMatch> parsedMatches = new ArrayList<>(filterEntries.size());
    for (String filterEntry : filterEntries) {
      parsedMatches.add(parseMatch(filterEntry));
    }
    return new MetricSeriesFilter(normalizedMetricId, List.copyOf(parsedMatches));
  }

  public boolean isActive() {
    return metricId != null;
  }

  /**
   * The matches as a JSON array of {@code {"key": ..., "value": ...}} objects, built by Jackson so a
   * quote or backslash in a value can never break out of the document. An array rather than an object
   * so that two pairs naming the same key are both enforced instead of the second silently replacing
   * the first. {@code "[]"} when no filter is active.
   */
  public String matchesJson() {
    return matches.isEmpty() ? EMPTY_MATCHES_JSON : JSON_MAPPER.writeValueAsString(matches);
  }

  private static AttributeMatch parseMatch(String filterEntry) {
    int separatorIndex = filterEntry.indexOf(KEY_VALUE_SEPARATOR);
    if (separatorIndex < 0) {
      throw new IllegalArgumentException("filter '" + filterEntry + "' must be written key:value");
    }
    String key = filterEntry.substring(0, separatorIndex);
    if (key.isBlank()) {
      throw new IllegalArgumentException("filter '" + filterEntry + "' has an empty key; expected key:value");
    }
    return new AttributeMatch(key, filterEntry.substring(separatorIndex + 1));
  }
}
