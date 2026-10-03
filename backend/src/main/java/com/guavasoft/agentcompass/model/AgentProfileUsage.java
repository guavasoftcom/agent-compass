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
import java.time.Instant;

@Schema(description = "One agent profile seen in telemetry, unioned across spans / log_records / "
        + "metric_points and re-aggregated by agent_profile. Unlike the repository list there is no "
        + "synthetic 'all' entry: agent profiles are a hard partition, so the frontend never offers "
        + "to blend them.")
public record AgentProfileUsage(
        @Schema(description = "The agent_profile value stamped at ingest",
                example = "claude-code") String agentProfile,

        @Schema(description = "Newest timestamp across all three tables for this profile",
                example = "2026-09-14T11:47:29Z") Instant lastSeen,

        @Schema(description = "Total row count across spans, log_records, and metric_points for this "
                + "profile", example = "18420") long count) {
}
