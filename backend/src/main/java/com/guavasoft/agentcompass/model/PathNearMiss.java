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

@Schema(name = "PathNearMiss",
        description = "A failed Read whose path sits within a small edit distance of a path the same session "
                + "read successfully — almost always a retyped (typo'd) path")
public record PathNearMiss(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Path the Read calls failed against", example = "/tmp/scratch/ee81…fecbb/review-diff.patch")
        String failedPath,
        @Schema(description = "Closest path the same session read successfully",
                example = "/tmp/scratch/ee81…fcdbb/review-diff.patch") String nearestSuccessfulPath,
        @Schema(description = "Levenshtein distance between the two paths", example = "2") long editDistance,
        @Schema(description = "Number of failed Read calls against the failed path", example = "7") long failures) {
}
