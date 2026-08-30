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

@Schema(name = "ModelTokenShare", description = "Per-model token breakdown for the Token Usage page 'Token sum by model' card")
public record ModelTokenShare(
        @Schema(description = "Model identifier as stored in the attributes column",
                example = "claude-opus-4") String model,
        @Schema(description = "Formatted total token count for this model in the window",
                example = "7.8M") String tokens,
        @Schema(description = "Percentage share of total window tokens (0–100)",
                example = "64") int share,
        @Schema(description = "Chart palette index for consistent colour assignment, 0 = highest token count",
                example = "0") int colorIndex) {
}
