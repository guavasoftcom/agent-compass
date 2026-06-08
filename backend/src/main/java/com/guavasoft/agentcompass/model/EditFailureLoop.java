package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EditFailureLoop", description = "A (session, file_path) pair where Edit failed multiple times — "
        + "a signal the agent is hunting for the right old_string")
public record EditFailureLoop(
        @Schema(description = "session.id attribute (truncated for display)", example = "7af9c1…") String sessionId,
        @Schema(description = "Absolute path the agent kept trying to edit", example = "src/api.ts") String filePath,
        @Schema(description = "Number of failed Edit calls in the session against this file", example = "3") long failures) {
}
