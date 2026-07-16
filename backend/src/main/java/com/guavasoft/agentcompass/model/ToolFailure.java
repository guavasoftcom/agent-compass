package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolFailure",
        description = "Failed-tool-call breakdown by tool, error_type, derived root-cause signature, and an example scope/message")
public record ToolFailure(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "error_type attribute from the tool_result event", example = "ShellError") String errorType,
        @Schema(description = "Root cause derived from the error message; 'other' means the bucket needs manual triage",
                example = "missing-path") String errorSignature,
        @Schema(description = "Example scope (file_path or command) from one failing call", example = "./mvnw -q test") String exampleScope,
        @Schema(description = "Example error message from one failing call (may be blank)",
                example = "Shell command failed") String exampleMessage,
        @Schema(description = "Number of failed calls with this (tool, errorType, errorSignature) group",
                example = "3") long count) {
}
