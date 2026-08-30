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

@Schema(name = "ToolPerformance", description = "Per-tool latency and output size aggregates over the requested window")
public record ToolPerformance(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Total tool_result events observed", example = "369") long calls,
        @Schema(description = "Mean duration in milliseconds across all calls", example = "1858") long avgDurationMs,
        @Schema(description = "95th-percentile duration in milliseconds", example = "3942") long p95DurationMs,
        @Schema(description = "Mean tool_result_size_bytes across successful calls", example = "725") long avgResultBytes) {
}
