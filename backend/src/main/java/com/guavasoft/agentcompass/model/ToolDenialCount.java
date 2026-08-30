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

@Schema(name = "ToolDenialCount", description = "Number of times a tool was denied execution, broken down by the source of the "
        + "denial. Source values: 'config' (settings.json rule), 'hook' (hook script returned "
        + "non-zero), 'user_permanent' / 'user_temporary' (user declined in the UI), "
        + "'user_abort' / 'user_reject' (user explicitly blocked the call).")
public record ToolDenialCount(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Source of the denial decision", example = "config") String source,
        @Schema(description = "Number of denial events for this tool/source pair in the window", example = "12") long count) {
}
