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

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "MetricSeries",
        description = "One claude_code.* metric over the selected window: headline stats, a per-bucket "
                + "trend, and any attribute splits. Powers the Metrics page master-detail.")
public record MetricSeries(
        @Schema(description = "Stable short id used as the React key", example = "token") String id,
        @Schema(description = "Fully-qualified OTLP metric name", example = "claude_code.token.usage") String name,
        @Schema(description = "OTLP instrument type", example = "counter") String type,
        @Schema(description = "Unit label", example = "tokens") String unit,
        @Schema(description = "Pre-formatted headline total for the window", example = "13.0M") String sum,
        @Schema(description = "Caption for the headline stat", example = "Sum (24h)") String sumLabel,
        @Schema(description = "Pre-formatted per-hour rate", example = "542K") String rate,
        @Schema(description = "Suffix for the rate", example = "/h") String rateUnit,
        @Schema(description = "Pre-formatted peak bucket value", example = "820K") String peak,
        @Schema(description = "Signed change vs. the previous equal window", example = "+18.3%") String delta,
        @Schema(description = "Direction of the change", example = "up") String dir,
        @Schema(description = "One-line plain-text description") String description,
        @Schema(description = "Per-bucket trend values for the window (raw numbers, newest last). Per-bucket "
                + "sums, except for the one metric named by aggMetricId, whose buckets follow the requested agg")
        List<Double> trend,
        @Schema(description = "Attribute breakdowns keyed by split name (Model, Type, …); empty when none")
        Map<String, List<MetricSplitRow>> splits,
        @Schema(description = "Distinct attribute label-sets (streams) seen in the window. For the one metric an "
                + "attribute filter targets, only label-sets with non-zero activity are counted, so it can be "
                + "lower than the unfiltered figure", example = "1234")
        long cardinality,
        @Schema(description = "Server-computed cardinality health: ok / warn / bad", example = "ok",
                allowableValues = {"ok", "warn", "bad"})
        String health,
        @Schema(description = "True when GET /api/metrics/distribution can serve this metric (token and cost)",
                example = "true") boolean hasDistribution) {

        /** This series with only its trend replaced; every headline figure, split and cardinality is kept. */
        public MetricSeries withTrend(List<Double> replacementTrend) {
                return new MetricSeries(id, name, type, unit, sum, sumLabel, rate, rateUnit, peak, delta, dir,
                        description, replacementTrend, splits, cardinality, health, hasDistribution);
        }
}
