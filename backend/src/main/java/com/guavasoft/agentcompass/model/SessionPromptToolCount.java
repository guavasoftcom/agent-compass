package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One tool's invocation count within a single prompt turn, derived from tool_result events "
        + "whose timestamp falls in the turn's interval")
public record SessionPromptToolCount(
        @Schema(description = "Tool name (the configured toolAttribute value on the tool_result event)",
                example = "Read")
        String name,

        @Schema(description = "Number of invocations of this tool within the turn", example = "4")
        long count) {
}
