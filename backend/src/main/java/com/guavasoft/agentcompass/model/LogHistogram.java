package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for {@code GET /api/logs/histogram}. Zero-filled buckets cover
 * the entire requested window; no bucket is omitted.
 */
@Schema(description = "Server-bucketed log severity histogram for the requested window")
public record LogHistogram(
        @Schema(description = "Width of every bucket in milliseconds", example = "1800000")
        long bucketMs,

        @Schema(description = "Time-aligned buckets, oldest first, zero-filled for the full window")
        List<HistogramBucket> buckets) {
}
