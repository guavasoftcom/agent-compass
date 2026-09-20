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

@Schema(name = "UsageCalendarHour",
        description = "One local hour-of-day bucket within a UsageCalendarDay. Same sources as the day's own "
                + "figures (counters for cost, tokens and active time; log records for skill calls), so the 24 "
                + "buckets of a day sum to it. On a daylight-saving day the repeated hour is merged into one "
                + "bucket and the skipped hour is all zeros.")
public record UsageCalendarHour(
        @Schema(description = "Local hour of day, 0-23", example = "14") int hour,
        @Schema(description = "Counter-derived spend in USD", example = "4.1") double costUsd,
        @Schema(description = "Counter-derived tokens across every token type", example = "28100") long tokens,
        @Schema(description = "Skill invocations dated by their earliest turn", example = "1") long skillCalls,
        @Schema(description = "Active engagement time in seconds", example = "1260") long activeSeconds) {
}
