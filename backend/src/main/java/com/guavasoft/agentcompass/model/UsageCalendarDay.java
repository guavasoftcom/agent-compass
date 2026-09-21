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
import java.time.LocalDate;
import java.util.List;

@Schema(name = "UsageCalendarDay",
        description = "One local calendar day of usage. Cost, tokens, sessions, active time, lines of code, "
                + "commits, pull requests and edit decisions come from the cumulative counters "
                + "(SUM of the reset-aware value_delta), the same pipeline as the Tokens and Sessions pages; "
                + "skill and subagent calls come from log records. A day with no activity is all zeros.")
public record UsageCalendarDay(
        @Schema(description = "The local calendar day in the requested time zone", example = "2026-09-01")
        LocalDate date,
        @Schema(description = "Counter-derived spend in USD", example = "73.2") double costUsd,
        @Schema(description = "Counter-derived tokens across every token type, cache reads included",
                example = "369400") long tokens,
        @Schema(description = "Skill invocations: one per prompt that entered a skill, not one per model call",
                example = "5") long skillCalls,
        @Schema(description = "Subagent dispatches (Agent tool calls)", example = "2") long subagentCalls,
        @Schema(description = "Sessions with cost or active-time activity that day", example = "4") long sessions,
        @Schema(description = "Active engagement time in seconds", example = "7860") long activeSeconds,
        @Schema(description = "Lines added by edit tools", example = "284") long linesAdded,
        @Schema(description = "Lines removed by edit tools", example = "99") long linesRemoved,
        @Schema(description = "Git commits created", example = "0") long commits,
        @Schema(description = "Pull requests opened", example = "1") long pullRequests,
        @Schema(description = "Edit-tool permission decisions accepted", example = "11") long decisionsAccepted,
        @Schema(description = "Edit-tool permission decisions rejected", example = "1") long decisionsRejected,
        @Schema(description = "The day's spend split by model, largest first; empty on a day with no cost. "
                + "Same counter pipeline as costUsd, so the parts sum to it")
        List<UsageCalendarModelCost> costByModel,
        @Schema(description = "The day split into 24 local hour-of-day buckets, hour 0 first. Present only "
                + "when the request asked for granularity=hourly; null otherwise",
                nullable = true)
        List<UsageCalendarHour> hourly) {
}
