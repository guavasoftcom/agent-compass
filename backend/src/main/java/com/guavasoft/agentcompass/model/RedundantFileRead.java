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

@Schema(name = "RedundantFileRead", description = "A file that was read more than once in the same session, with timing context")
public record RedundantFileRead(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Absolute path that was read multiple times", example = "src/api.ts") String filePath,
        @Schema(description = "Number of Read calls against this file in the session", example = "4") long reads,
        @Schema(description = "Wall-clock minutes between the first and last Read of this file in this session",
                example = "13") long spanMinutes,
        @Schema(description = "Largest gap between consecutive Reads (minutes); small max-gap + many reads = hunting loop",
                example = "5") long maxGapMinutes) {
}
