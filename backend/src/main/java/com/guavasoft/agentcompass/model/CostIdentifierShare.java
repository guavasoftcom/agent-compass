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

@Schema(name = "CostIdentifierShare", description = "One named identifier's share of its parent "
        + "CostCategoryShare's drilldown (a skill or subagent identifier). share is percent of the "
        + "category's identifiedCostUsd, not of the page total.")
public record CostIdentifierShare(
        @Schema(description = "Skill or subagent identifier", example = "Explore") String identifier,
        @Schema(description = "Cost in USD attributed to this identifier in the window", example = "22.04") double costUsd,
        @Schema(description = "Percentage share of the parent category's identifiedCostUsd (0-100)", example = "27") int share) {
}
