package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(name = "IdentifierUsageCount", description = "Invocation count for one skill or subagent over the requested window, "
        + "split by the model that made the call. The 'tool' field carries the skill or subagent identifier so the "
        + "frontend can render both breakdowns with the same components.")
public record IdentifierUsageCount(
        @Schema(description = "Skill or subagent identifier", example = "pdf-extract") String tool,
        @Schema(description = "Total invocations observed in the window", example = "132") long calls,
        @Schema(description = "Invocations split by model id. Values sum to calls; models with no calls are "
                + "omitted rather than sent as 0. Keys match the model attribute values used by "
                + "/api/sessions/token-usage's byModel rows.",
                example = "{\"claude-sonnet-4-6\": 90, \"claude-opus-4-8\": 42}") Map<String, Long> byModel) {
}
