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

@Schema(name = "SessionApiRequest",
        description = "One LLM request issued during a session, with the exact token counts, cost, "
                + "and duration Claude Code reported for it. Unlike the counter-derived per-turn "
                + "figures, these are per-call and exactly attributed: promptId names the turn that "
                + "issued the request, so a response that arrived after the user typed their next "
                + "prompt is still billed to the turn that asked for it.")
public record SessionApiRequest(
        @Schema(description = "Claude Code's request identifier", example = "req_011CdqUicd2Zjki38GaLv3VN")
        String requestId,

        @Schema(description = "When the request was logged (ISO-8601)", example = "2026-08-08T14:58:46.404Z")
        Instant timestamp,

        @Schema(description = "Identifier of the turn that issued this request. Groups requests into "
                + "turns; null on rows predating prompt-id stamping.",
                example = "9a7ac484-195b-4a74-a78d-1cf67c973af5") String promptId,

        @Schema(description = "Model that served this request", example = "claude-opus-5") String model,

        @Schema(description = "Exact four-way token split for this one request") SessionTokenBreakdown tokens,

        @Schema(description = "Reported cost of this request in USD", example = "0.1661315") Double costUsd,

        @Schema(description = "Wall-clock duration of the request in milliseconds", example = "16495")
        Long durationMs,

        @Schema(description = "Reasoning-effort setting in force. Absent on roughly 7% of rows — "
                + "render it as unknown rather than assuming a default.", example = "high") String effort,

        @Schema(description = "Speed setting in force", example = "normal") String speed,

        @Schema(description = "Trace the request was issued under; null when it predates tracing")
        String traceId) {
}
