package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolPerformance", description = "Per-tool latency and output size aggregates over the requested window")
public record ToolPerformance(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Total tool_result events observed", example = "369") long calls,
        @Schema(description = "Mean duration in milliseconds across all calls", example = "1858") long avgDurationMs,
        @Schema(description = "95th-percentile duration in milliseconds", example = "3942") long p95DurationMs,
        @Schema(description = "Mean tool_result_size_bytes across successful calls", example = "725") long avgResultBytes) {
}
