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

@Schema(name = "CostModelShare", description = "Per-model cost breakdown for the cost summary panel")
public record CostModelShare(
        @Schema(description = "Model identifier as stored in the attributes column", example = "claude-opus-4") String model,
        @Schema(description = "Formatted USD spend for this model in the window", example = "$720") String usd,
        @Schema(description = "Percentage share of total window spend (0–100)", example = "56") int share,
        @Schema(description = "Chart palette index for consistent colour assignment", example = "1") int colorIndex) {
}
