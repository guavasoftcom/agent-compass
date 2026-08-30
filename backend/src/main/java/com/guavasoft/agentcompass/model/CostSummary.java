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

@Schema(name = "CostSummary", description = "Aggregated cost data for the Metrics cost panel. All currency values are "
        + "formatted strings ready for display; raw numeric forms are not included so the frontend "
        + "never needs to re-format.")
public record CostSummary(
        @Schema(description = "Total spend in the window, formatted", example = "$1,284") String spend24h,
        @Schema(description = "Percentage change vs. the equal prior window; '+' prefix when positive",
                example = "+22.4%") String deltaPct,
        @Schema(description = "Spend divided by window hours, formatted", example = "$53/h") String burnRate,
        @Schema(description = "burnRate × 720, formatted", example = "$38.5K") String projected30d,
        @Schema(description = "Spend per 1 000 tokens, formatted", example = "$0.099") String costPer1k,
        @Schema(description = "14-bucket per-session cost SUM trend across the window") List<Double> trend,
        @Schema(description = "Per-model cost breakdown, sorted by spend descending") List<CostModelShare> byModel,
        @Schema(description = "Optional operator note; empty string when nothing to report") String note) {
}
