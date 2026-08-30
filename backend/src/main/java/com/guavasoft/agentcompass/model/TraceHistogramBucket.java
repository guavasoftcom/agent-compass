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

/**
 * One time-aligned bucket of the trace throughput histogram.
 * Matches the {@code TraceHistogramBucket} TypeScript interface in {@code tracesApi.ts}.
 */
@Schema(description = "One time-aligned bucket of the trace throughput histogram")
public record TraceHistogramBucket(
        @Schema(description = "Inclusive bucket start (UTC)", example = "2026-06-12T00:00:00Z")
        Instant t0,

        @Schema(description = "Exclusive bucket end (UTC)", example = "2026-06-12T00:30:00Z")
        Instant t1,

        @Schema(description = "Count of ok traces (errorCount == 0) starting in this bucket", example = "41")
        long ok,

        @Schema(description = "Count of error traces (errorCount > 0) starting in this bucket", example = "3")
        long error,

        @Schema(description = "p95 latency in ms of traces starting in this bucket; 0 for empty buckets",
                example = "8200")
        double p95Ms) {
}
