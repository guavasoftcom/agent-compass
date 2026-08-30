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

@Schema(name = "ToolLatency", description = "Per-tool latency percentiles computed from tool-scope span durations")
public record ToolLatency(
        @Schema(description = "Tool name (span name in the tool instrumentation scope)", example = "Bash") String tool,

        @Schema(description = "Number of tool spans observed in the window", example = "84") long calls,

        @Schema(description = "Median latency in milliseconds", example = "120.5") double p50Ms,

        @Schema(description = "95th percentile latency in milliseconds", example = "540.1") double p95Ms) {
}
