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

@Schema(name = "UsageCalendarModelCost",
        description = "One model's counter-derived spend on one local calendar day")
public record UsageCalendarModelCost(
        @Schema(description = "Raw model id as Claude Code reports it; 'unknown' when a cost point carries none",
                example = "claude-sonnet-4") String model,
        @Schema(description = "Counter-derived spend in USD attributed to that model that day", example = "41.5")
        double costUsd) {
}
