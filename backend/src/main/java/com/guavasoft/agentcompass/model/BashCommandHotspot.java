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

@Schema(name = "BashCommandHotspot", description = "Aggregate over Bash tool calls grouped by command prefix")
public record BashCommandHotspot(
        @Schema(description = "First whitespace-delimited token of the command", example = "grep") String commandPrefix,
        @Schema(description = "Number of Bash calls with this prefix", example = "12") long calls,
        @Schema(description = "Average duration in milliseconds", example = "230") long avgDurationMs,
        @Schema(description = "95th-percentile duration in milliseconds", example = "752") long p95DurationMs,
        @Schema(description = "Average tool_result_size_bytes for successful calls", example = "436") long avgResultBytes) {
}
