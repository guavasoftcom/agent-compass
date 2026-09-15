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

@Schema(description = "One repository seen in telemetry, unioned across spans / log_records / "
        + "metric_points and re-aggregated by repositoryUrl. Only repositories with a non-null "
        + "repository_url are listed here -- the frontend adds its own synthetic 'All repositories' "
        + "and 'Unattributed' entries around this list rather than this endpoint emitting them.")
public record RepositoryUsage(
        @Schema(description = "The vcs.repository.url.full value stamped by Claude Code",
                example = "https://github.com/guavasoftcom/coding-agent-tuning") String repositoryUrl,

        @Schema(description = "Newest timestamp across all three tables for this repository",
                example = "2026-09-14T11:47:29Z") Instant lastSeen,

        @Schema(description = "Total row count across spans, log_records, and metric_points for this "
                + "repository", example = "18420") long count) {
}
