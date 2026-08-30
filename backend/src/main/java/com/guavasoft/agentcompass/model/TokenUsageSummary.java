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

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "TokenUsageSummary", description = "Window-wide token totals split by type plus a bucketed time series for the "
        + "trend chart. cacheReadRatio = cacheRead / (input + cacheCreation + cacheRead) — the "
        + "window-level form of the per-session cache efficiency the Sessions grid sorts and "
        + "renders; ≥0.85 is healthy, 0.6–0.85 is mixed, <0.6 means most prompts are paying "
        + "full freight.")
public record TokenUsageSummary(
        @Schema(description = "Sum of value over the window for type='input'", example = "12450") long inputTokens,
        @Schema(description = "Sum of value over the window for type='output'", example = "8240") long outputTokens,
        @Schema(description = "Sum of value over the window for type='cacheCreation'", example = "65120") long cacheCreationTokens,
        @Schema(description = "Sum of value over the window for type='cacheRead'", example = "412300") long cacheReadTokens,
        @Schema(description = "cacheRead / (input + cacheCreation + cacheRead); 0.0 when the window "
                + "recorded no input-side tokens. Output tokens are generated rather than sent, so "
                + "they are excluded from the denominator.", example = "0.864") double cacheReadRatio,
        @Schema(description = "Bucket width used to produce the time series, in seconds", example = "900") long bucketSeconds,
        @ArraySchema(schema = @Schema(implementation = Point.class)) List<Point> points,
        @ArraySchema(schema = @Schema(implementation = ModelTokenShare.class),
                arraySchema = @Schema(description = "Per-model token breakdown sorted by total tokens descending"))
        List<ModelTokenShare> byModel,
        @Schema(description = "Cost summary for the window. Only spend24h/deltaPct/burnRate/"
                + "projected30d/costPer1k are populated here -- trend and byModel are always empty, "
                + "since this endpoint's only caller (the Tokens page) doesn't render them; the full "
                + "breakdown is served by GET /api/metrics/cost.")
        CostSummary cost) {

    @Schema(name = "TokenUsageSummary.Point", description = "Per-bucket token totals split by type")
    public record Point(
            @Schema(description = "Start of the bucket (ISO-8601)", example = "2026-05-25T18:00:00Z") Instant timestamp,
            @Schema(example = "320") long input,
            @Schema(example = "210") long output,
            @Schema(example = "1500") long cacheCreation,
            @Schema(example = "9800") long cacheRead) {
    }
}
