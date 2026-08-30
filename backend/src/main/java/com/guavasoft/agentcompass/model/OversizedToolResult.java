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

@Schema(name = "OversizedToolResult",
        description = "Tool calls whose tool_result_size_bytes was among the largest in the window; "
                + "identical (tool, scope, bytes) calls are grouped with an occurrence count")
public record OversizedToolResult(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Scope hint — file_path or command — used to identify what produced the large result",
                example = "npm test") String scope,
        @Schema(description = "Result size in bytes of one such call", example = "184320") long bytes,
        @Schema(description = "How many calls returned this exact payload; total context cost is bytes × occurrences",
                example = "9") long occurrences) {
}
