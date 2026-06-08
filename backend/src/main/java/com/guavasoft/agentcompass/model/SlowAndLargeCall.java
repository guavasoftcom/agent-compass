package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SlowAndLargeCall", description = "Single tool call that is simultaneously in the slow tail and the oversized-result set")
public record SlowAndLargeCall(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Scope hint — file_path or command", example = "./mvnw test") String scope,
        @Schema(description = "Wall-clock duration in milliseconds", example = "28018") long durationMs,
        @Schema(description = "Result size in bytes", example = "11755") long bytes) {
}
