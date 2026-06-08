package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "TokenUsageSummary", description = "Window-wide token totals split by type plus a bucketed time series for the "
        + "trend chart. cacheReadRatio = cacheRead / (cacheRead + cacheCreation); ≥0.7 is "
        + "healthy, 0.4–0.7 is mixed, <0.4 means most prompts are paying full freight.")
public record TokenUsageSummary(
        @Schema(description = "Sum of value over the window for type='input'", example = "12450") long inputTokens,
        @Schema(description = "Sum of value over the window for type='output'", example = "8240") long outputTokens,
        @Schema(description = "Sum of value over the window for type='cacheCreation'", example = "65120") long cacheCreationTokens,
        @Schema(description = "Sum of value over the window for type='cacheRead'", example = "412300") long cacheReadTokens,
        @Schema(description = "cacheRead / (cacheRead + cacheCreation); 0.0 when both are zero", example = "0.864") double cacheReadRatio,
        @Schema(description = "Bucket width used to produce the time series, in seconds", example = "900") long bucketSeconds,
        @ArraySchema(schema = @Schema(implementation = Point.class)) List<Point> points,
        @ArraySchema(schema = @Schema(implementation = ModelTokenShare.class),
                arraySchema = @Schema(description = "Per-model token breakdown sorted by total tokens descending"))
        List<ModelTokenShare> byModel,
        @Schema(description = "Cost summary for the window, including spend, burn rate, per-model breakdown, and trend")
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
