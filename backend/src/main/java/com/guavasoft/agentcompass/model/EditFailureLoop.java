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

@Schema(name = "EditFailureLoop", description = "A (session, file_path) pair where Edit failed multiple times — "
        + "a signal the agent is hunting for the right old_string")
public record EditFailureLoop(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Absolute path the agent kept trying to edit", example = "src/api.ts") String filePath,
        @Schema(description = "Number of failed Edit calls in the session against this file", example = "3") long failures) {
}
