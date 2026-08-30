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

@Schema(description = "Consecutive same-tool same-scope run-length stats over a window. "
        + "Computed by detecting islands of identical (tool, scope) rows within each session "
        + "ordered by timestamp, taking the longest run per (session, tool, scope), then rolling "
        + "those longest-per-session values up into a median and a max. Sessions whose longest run "
        + "was only one call are excluded from the rollup — they are not repeats. Runs where the "
        + "scope couldn't be determined are also excluded — a shared 'unknown' scope isn't evidence "
        + "the calls repeated the same action.")
public record ToolRepeatStat(
        @Schema(description = "Tool name (matches the tool_name attribute on tool_result events)", example = "Edit") String tool,

        @Schema(description = "Scope key the runs are computed within: file_path for Edit / Write / "
                + "Read / MultiEdit; for Bash, the command with any leading 'cd <path> &&' chain "
                + "stripped, then its first two whitespace-delimited tokens (program plus "
                + "subcommand/flag). Rows for every other tool, and rows where this couldn't be "
                + "resolved, are dropped rather than reported under a shared '(no scope)' bucket.",
                example = "/repo/src/foo.ts") String scope,

        @Schema(description = "Median (P50) of the longest-run-per-session values for this "
                + "(tool, scope). Rounded to the nearest integer.", example = "3") long medianRunLength,

        @Schema(description = "Maximum longest-run observed for this (tool, scope) across any "
                + "single session in the window.", example = "8") long maxRunLength,

        @Schema(description = "Number of distinct sessions that contributed a longest-run-≥2 for "
                + "this (tool, scope).", example = "4") long sessions) {
}
