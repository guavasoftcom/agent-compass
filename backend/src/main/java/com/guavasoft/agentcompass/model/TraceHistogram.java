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

import java.util.List;

/**
 * Response DTO for {@code GET /api/traces/histogram}.
 * Matches the {@code TraceHistogram} TypeScript interface in {@code tracesApi.ts}.
 */
@Schema(description = "Trace throughput histogram with p95 latency overlay")
public record TraceHistogram(
        @Schema(description = "Bucket width in milliseconds", example = "1800000")
        long bucketMs,

        @Schema(description = "Zero-filled list of throughput buckets covering the full window")
        List<TraceHistogramBucket> buckets,

        @Schema(description = "Window-wide p50 latency in ms", example = "1840")
        double p50Ms,

        @Schema(description = "Window-wide p95 latency in ms", example = "11200")
        double p95Ms,

        @Schema(description = "Total matching traces in the window", example = "540")
        long total,

        @Schema(description = "Number of error traces (errorCount > 0) in the window", example = "28")
        long errorCount) {
}
