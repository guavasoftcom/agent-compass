package com.guavasoft.agentcompass.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One time bucket in the log severity histogram. JSON keys are uppercase
 * ({@code ERROR}/{@code WARN}/{@code INFO}/{@code DEBUG}) to match the
 * {@code HistogramBucket} TypeScript interface in {@code logsApi.ts}.
 */
@Schema(description = "One time-aligned bucket of the log severity histogram")
public record HistogramBucket(
        @Schema(description = "Inclusive bucket start (UTC)", example = "2026-06-07T00:00:00Z")
        Instant t0,

        @Schema(description = "Exclusive bucket end (UTC)", example = "2026-06-07T00:30:00Z")
        Instant t1,

        @JsonProperty("ERROR")
        @Schema(description = "Count of ERROR-severity records in this bucket", example = "2")
        long error,

        @JsonProperty("WARN")
        @Schema(description = "Count of WARN-severity records in this bucket", example = "5")
        long warn,

        @JsonProperty("INFO")
        @Schema(description = "Count of INFO-severity records in this bucket", example = "41")
        long info,

        @JsonProperty("DEBUG")
        @Schema(description = "Count of DEBUG-severity records in this bucket", example = "18")
        long debug) {
}
