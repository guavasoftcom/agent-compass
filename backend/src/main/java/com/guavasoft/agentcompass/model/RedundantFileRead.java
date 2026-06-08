package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RedundantFileRead", description = "A file that was read more than once in the same session, with timing context")
public record RedundantFileRead(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Absolute path that was read multiple times", example = "src/api.ts") String filePath,
        @Schema(description = "Number of Read calls against this file in the session", example = "4") long reads,
        @Schema(description = "Wall-clock minutes between the first and last Read of this file in this session",
                example = "13") long spanMinutes,
        @Schema(description = "Largest gap between consecutive Reads (minutes); small max-gap + many reads = hunting loop",
                example = "5") long maxGapMinutes) {
}
