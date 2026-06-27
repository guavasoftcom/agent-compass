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
