package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "BashCommandCoverage",
        description = "How many Bash tool_result rows carried a parseable command vs total Bash calls in the window")
public record BashCommandCoverage(
        @Schema(description = "Bash calls whose tool_input.command was captured", example = "60") long withCommand,
        @Schema(description = "Total Bash calls in the window", example = "152") long total,
        @Schema(description = "Bash calls whose command leads with `cd …` — stripped before hotspot bucketing",
                example = "40") long cdPrefixed) {
}
