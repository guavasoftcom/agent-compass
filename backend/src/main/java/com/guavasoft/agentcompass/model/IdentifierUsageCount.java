package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(name = "IdentifierUsageCount", description = "Invocation count and cost for one skill or subagent over the "
        + "requested window, split by the model that made the call. The 'tool' field carries the skill or subagent "
        + "identifier so the frontend can render both breakdowns with the same components. Calls and cost are "
        + "computed by separate queries and can disagree in which turns they cover -- see costUsd.")
public record IdentifierUsageCount(
        @Schema(description = "Skill or subagent identifier", example = "pdf-extract") String tool,
        @Schema(description = "Total invocations observed in the window", example = "132") long calls,
        @Schema(description = "Invocations split by model id. Values sum to calls; models with no calls are "
                + "omitted rather than sent as 0. Keys match the model attribute values used by "
                + "/api/sessions/token-usage's byModel rows.",
                example = "{\"claude-sonnet-4-6\": 90, \"claude-opus-4-8\": 42}") Map<String, Long> byModel,
        @Schema(description = "Total cost in USD attributed to this identifier in the window, summed from the "
                + "cost_usd attribute on the underlying api_request log records. Always present, COALESCEd to "
                + "0.0 rather than null when no cost could be attributed. For a skill this sums every api_request "
                + "row that ran while it was active, including turns made inside a subagent it spawned -- a "
                + "broader population than 'calls' counts. For a subagent this is the sum of the LLM calls made "
                + "directly inside its own dispatch, resolved by span correlation rather than read off the "
                + "dispatching tool_result (which carries no cost of its own).",
                example = "4.82") Double costUsd,
        @Schema(description = "costUsd split by model id. Same 'omit zero, never send an explicit 0' convention "
                + "as byModel, and does not necessarily sum call-for-call with byModel since the two are computed "
                + "by different queries.",
                example = "{\"claude-sonnet-4-6\": 3.10, \"claude-opus-4-8\": 1.72}") Map<String, Double> costByModel) {
}
