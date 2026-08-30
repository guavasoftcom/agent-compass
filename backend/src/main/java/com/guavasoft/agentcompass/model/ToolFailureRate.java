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

@Schema(name = "ToolFailureRate", description = "Per-tool execution failure rate. Counts only tool_result events that actually "
        + "fired — tools denied at the hook layer never appear here, so this is the in-flight "
        + "failure rate, not total exposure.")
public record ToolFailureRate(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Total tool_result events seen for this tool in the window", example = "37") long calls,
        @Schema(description = "Subset of calls whose success attribute was false", example = "9") long failures,
        @Schema(description = "failures / calls, in [0.0, 1.0]. Tools with >0.2–0.3 are prime AGENTS.md "
                + "tuning targets.", example = "0.2432") double failureRate) {

    public static ToolFailureRate of(String tool, long calls, long failures) {
        double rate = calls == 0L ? 0.0 : (double) failures / (double) calls;
        return new ToolFailureRate(tool, calls, failures, rate);
    }
}
