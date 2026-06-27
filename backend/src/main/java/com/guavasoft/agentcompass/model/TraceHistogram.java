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
