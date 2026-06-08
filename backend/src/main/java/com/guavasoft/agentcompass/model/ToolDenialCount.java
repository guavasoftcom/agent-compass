package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolDenialCount", description = "Number of times a tool was denied execution, broken down by the source of the "
        + "denial. Source values: 'config' (settings.json rule), 'hook' (hook script returned "
        + "non-zero), 'user_permanent' / 'user_temporary' (user declined in the UI), "
        + "'user_abort' / 'user_reject' (user explicitly blocked the call).")
public record ToolDenialCount(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Source of the denial decision", example = "config") String source,
        @Schema(description = "Number of denial events for this tool/source pair in the window", example = "12") long count) {
}
