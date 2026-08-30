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

@Schema(name = "BashCommandCoverage",
        description = "How many Bash tool_result rows carried a parseable command vs total Bash calls in the window")
public record BashCommandCoverage(
        @Schema(description = "Bash calls whose tool_input.command was captured", example = "60") long withCommand,
        @Schema(description = "Total Bash calls in the window", example = "152") long total,
        @Schema(description = "Bash calls whose command leads with `cd …` — stripped before hotspot bucketing",
                example = "40") long cdPrefixed) {
}
