package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolFailureRate", description = "Per-tool execution failure rate. Counts only tool_result events that actually "
        + "fired — tools denied at the hook layer never appear here, so this is the in-flight "
        + "failure rate, not total exposure.")
public record ToolFailureRate(
        @Schema(description = "Tool name as reported by the agent", example = "Bash") String tool,
        @Schema(description = "Total tool_result events seen for this tool in the window", example = "37") long calls,
        @Schema(description = "Subset of calls whose success attribute was false", example = "9") long failures,
        @Schema(description = "failures / calls, in [0.0, 1.0]. Tools with >0.2–0.3 are prime AGENTS.md "
                + "tuning targets.", example = "0.2432") double failureRate) {

    public static ToolFailureRate of(String tool, long calls, long failures) {
        double rate = calls == 0L ? 0.0 : (double) failures / (double) calls;
        return new ToolFailureRate(tool, calls, failures, rate);
    }
}
