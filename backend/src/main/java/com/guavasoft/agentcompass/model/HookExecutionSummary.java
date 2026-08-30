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

@Schema(name = "HookExecutionSummary", description = "Aggregated outcome counters for a (hookEvent, hookName) pair over the window. "
        + "blockingErrors is the key signal — these are hooks that actively prevented a tool from "
        + "running. nonBlockingErrors indicate hook failures that were logged but did not stop "
        + "execution.")
public record HookExecutionSummary(
        @Schema(description = "Hook lifecycle event (e.g. PreToolUse, PostToolUse)", example = "PreToolUse") String hookEvent,
        @Schema(description = "Full hook name including matcher (e.g. PreToolUse:Write)", example = "PreToolUse:Write") String hookName,
        @Schema(description = "Total hook_execution_complete events for this pair", example = "45") long total,
        @Schema(description = "Sum of num_success across all events", example = "40") long successes,
        @Schema(description = "Sum of num_blocking — hooks that blocked tool execution. "
                + "Non-zero rows are the highest-priority fix targets.", example = "3") long blockingErrors,
        @Schema(description = "Sum of num_non_blocking_error — hook failures that did not block", example = "2") long nonBlockingErrors,
        @Schema(description = "Sum of num_cancelled", example = "0") long cancelled) {
}
