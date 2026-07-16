package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "One user_prompt event (turn) in a session's prompt timeline, full untruncated text, plus "
        + "additive per-turn model / cost / tool-usage rollups attributed by the turn's time interval "
        + "[thisPrompt.timestamp, nextPrompt.timestamp)")
public record SessionPrompt(
        @Schema(description = "Timestamp of the user_prompt event / turn start", example = "2026-05-27T00:05:12.100Z")
        Instant timestamp,

        @Schema(description = "Full prompt text as submitted by the user. Null for pre-capture events "
                + "(OTEL_LOG_USER_PROMPTS disabled) -- the row is still returned, it just carries no text.",
                example = "Refactor the SessionSummary record to include prompt context")
        String prompt,

        @Schema(description = "Hex-encoded OTLP trace ID of the claude_code.interaction root span this prompt "
                + "belongs to, for cross-signal correlation. Null for prompts emitted before tracing was enabled "
                + "(older sessions carry no trace_id, an empty string, or an all-zero trace_id).",
                example = "0102030405060708090a0b0c0d0e0f10")
        String traceId,

        @Schema(description = "Model that served this turn: the model attribute with the largest summed token "
                + "value_delta among the configured token-usage metric's points falling in this turn's interval. "
                + "Null when no token points fall in the turn.",
                example = "claude-sonnet-4-5", nullable = true)
        String model,

        @Schema(description = "Cost (USD) attributed to this turn: SUM(value_delta) of the configured cost-usage "
                + "metric for this session, restricted to points falling in this turn's interval. Null when no "
                + "cost points fall in the turn.",
                example = "0.8", nullable = true)
        Double costUsd,

        @Schema(description = "This turn's claude_code.token.usage split by type, restricted to points falling "
                + "in this turn's interval. Null when no token points fall in the turn (individual kinds within a "
                + "non-null breakdown are 0 when absent). Not guaranteed to sum, across every turn, to the "
                + "session row's tokenBreakdown on GET /api/sessions -- that field is window-scoped while this "
                + "endpoint is whole-session, and turn attribution drops points before the first captured prompt "
                + "or beyond the 500-turn cap, the same accepted trade-off as costUsd above.",
                nullable = true)
        SessionTokenBreakdown tokens,

        @Schema(description = "Tool calls this turn triggered, derived from tool_result events for this session "
                + "within the turn's interval, grouped by tool name and ordered by count descending (ties broken "
                + "by name ascending). Empty (never null) when the turn triggered no tool calls.")
        List<SessionPromptToolCount> tools) {

    /**
     * Compatibility constructor for the original three-field shape, defaulting the additive
     * model / costUsd / tokens / tools fields to their "nothing attributed" values.
     */
    public SessionPrompt(Instant timestamp, String prompt, String traceId) {
        this(timestamp, prompt, traceId, null, null, null, List.of());
    }
}
