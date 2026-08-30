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

import java.time.Instant;
import java.util.Map;

@Schema(name = "CostTrendPoint", description = "One bucket of the Cost page's stacked spend-over-time "
        + "chart. costByCategory keys are the same four category names as CostCategoryShare.category; a "
        + "category with zero spend in this bucket is omitted rather than sent as 0.")
public record CostTrendPoint(
        @Schema(description = "Inclusive start of this bucket") Instant timestamp,
        @Schema(description = "Cost in USD per category for this bucket",
                example = "{\"MAIN_LOOP\": 12.40, \"SUBAGENT\": 3.10}") Map<String, Double> costByCategory) {
}
