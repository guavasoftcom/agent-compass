package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BashCommandHotspot", description = "Aggregate over Bash tool calls grouped by command prefix")
public record BashCommandHotspot(
        @Schema(description = "First whitespace-delimited token of the command", example = "grep") String commandPrefix,
        @Schema(description = "Number of Bash calls with this prefix", example = "12") long calls,
        @Schema(description = "Average duration in milliseconds", example = "230") long avgDurationMs,
        @Schema(description = "95th-percentile duration in milliseconds", example = "752") long p95DurationMs,
        @Schema(description = "Average tool_result_size_bytes for successful calls", example = "436") long avgResultBytes) {
}
