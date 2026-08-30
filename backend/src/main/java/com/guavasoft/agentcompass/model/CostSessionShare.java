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

@Schema(name = "CostSessionShare", description = "One session in the Cost page's biggest-line-items "
        + "ranking, sorted by spend descending.")
public record CostSessionShare(
        @Schema(description = "Session identifier") String sessionId,
        @Schema(description = "Cost in USD attributed to this session in the window", example = "9.42") double costUsd,
        @Schema(description = "api_request rows for this session in the window", example = "214") long requests,
        @Schema(description = "The session's first human-authored prompt, whitespace-collapsed and "
                + "truncated to 200 characters; null if the session has no user_prompt row in this window",
                example = "Refactor the cost breakdown query to split by session")
                String firstUserPrompt,
        @Schema(description = "Share of this session's cost billed to main-loop requests -- same "
                + "SUBAGENT-beats-SKILL-beats-MAIN_LOOP-beats-AUXILIARY partition as the page-wide "
                + "category breakdown, applied per session", example = "3.10") double mainLoopCostUsd,
        @Schema(description = "Share of this session's cost billed to subagent requests", example = "5.80")
                double subagentCostUsd,
        @Schema(description = "Share of this session's cost billed to skill requests", example = "0.40")
                double skillCostUsd,
        @Schema(description = "Share of this session's cost billed to auxiliary requests (compact, "
                + "generate_session_title, ...)", example = "0.12") double auxiliaryCostUsd) {
}
