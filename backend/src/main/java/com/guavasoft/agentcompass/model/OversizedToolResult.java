package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OversizedToolResult",
        description = "Tool calls whose tool_result_size_bytes was among the largest in the window; "
                + "identical (tool, scope, bytes) calls are grouped with an occurrence count")
public record OversizedToolResult(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Scope hint — file_path or command — used to identify what produced the large result",
                example = "npm test") String scope,
        @Schema(description = "Result size in bytes of one such call", example = "184320") long bytes,
        @Schema(description = "How many calls returned this exact payload; total context cost is bytes × occurrences",
                example = "9") long occurrences) {
}
