package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolLatency", description = "Per-tool latency percentiles computed from tool-scope span durations")
public record ToolLatency(
        @Schema(description = "Tool name (span name in the tool instrumentation scope)", example = "Bash") String tool,

        @Schema(description = "Number of tool spans observed in the window", example = "84") long calls,

        @Schema(description = "Median latency in milliseconds", example = "120.5") double p50Ms,

        @Schema(description = "95th percentile latency in milliseconds", example = "540.1") double p95Ms) {
}
