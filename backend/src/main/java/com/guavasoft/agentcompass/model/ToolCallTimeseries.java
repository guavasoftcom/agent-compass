package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "ToolCallTimeseries", description = "Bucketed tool call counts. Each point carries one count per tool in the "
        + "same order as the top-level tools array; tools beyond the top-N are folded into "
        + "a synthetic 'Other' entry.")
public record ToolCallTimeseries(
        @Schema(description = "Width of each bucket in seconds", example = "1800") long bucketSeconds,

        @Schema(description = "Tool names, in the same column order as every point's counts array") List<String> tools,

        @Schema(description = "One point per bucket, oldest first; gaps are filled with zeros") List<Point> points) {

    @Schema(name = "ToolCallTimeseriesPoint")
    public record Point(
            @Schema(description = "Bucket start timestamp (inclusive)") Instant timestamp,

            @Schema(description = "Per-tool call count for this bucket, aligned with the tools array") List<Long> counts) {
    }
}
