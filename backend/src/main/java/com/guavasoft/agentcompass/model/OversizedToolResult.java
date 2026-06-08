package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OversizedToolResult", description = "Single tool call whose tool_result_size_bytes was among the largest in the window")
public record OversizedToolResult(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Scope hint — file_path or command — used to identify what produced the large result",
                example = "npm test") String scope,
        @Schema(description = "Result size in bytes", example = "184320") long bytes) {
}
