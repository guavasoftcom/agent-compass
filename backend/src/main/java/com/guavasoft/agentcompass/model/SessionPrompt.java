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
        List<SessionPromptToolCount> tools,

        @Schema(description = "Identifier of this turn, shared with the api_request logs it issued. It is the join "
                + "key for GET /api/sessions/{id}/requests: filtering that endpoint's rows to this value yields "
                + "exactly the requests summed into this turn's figures. Null on turns predating prompt-id "
                + "stamping, which are also the turns reporting attribution=INTERVAL.",
                example = "9a7ac484-195b-4a74-a78d-1cf67c973af5", nullable = true)
        String promptId,

        @Schema(description = "How many api_request logs were correlated to this turn by prompt id. 0 means none "
                + "were found, which is also when attribution falls back to INTERVAL.", example = "11")
        long requestCount,

        @Schema(description = "How this turn's model / costUsd / tokens were derived. REQUEST means they are the "
                + "exact per-call figures summed over the turn's own api_request logs, joined by prompt id. "
                + "INTERVAL means no such logs exist for the turn (event logging disabled, an older CLI, or a "
                + "turn predating prompt-id stamping) and the values were bucketed from cumulative metric "
                + "counters by timestamp interval instead — the older, approximate attribution. Clients should "
                + "present INTERVAL figures as approximate and must not treat a 0 request count as zero spend.")
        TurnAttribution attribution,

        @Schema(description = "Portion of costUsd billed to this turn's trace AFTER the trace's own "
                + "claude_code.interaction root span closed -- e.g. a fire-and-forget subagent dispatch (an "
                + "Agent tool call whose own span closes immediately) that kept issuing requests long after "
                + "this turn ended and the next prompt was typed. costUsd already INCLUDES this amount (it is "
                + "the turn's trace total, matching what GET /api/traces/{traceId}/summary reports for the "
                + "same trace) -- this field exists so a reader can see why a turn cost more than what "
                + "happened while it was the active turn, not to be summed on top of costUsd. Null when the "
                + "turn has no trace, or its trace has no activity after the root span closed.",
                example = "9.99", nullable = true)
        Double backgroundCostUsd,

        @Schema(description = "Tool calls attributed to this turn's trace but occurring AFTER the trace's own "
                + "claude_code.interaction root span closed -- the background counterpart to "
                + "backgroundCostUsd. The tools list above already includes these calls (it too is the "
                + "turn's trace total); this is the subset that ran as background/detached work. Empty "
                + "(never null) when none.")
        List<SessionPromptToolCount> backgroundTools) {

    /** Where a turn's per-turn rollups came from. */
    @Schema(name = "SessionPrompt.TurnAttribution")
    public enum TurnAttribution {
        /** Exact: summed from the turn's own api_request logs, joined by prompt id. */
        REQUEST,
        /** Approximate: bucketed from cumulative metric counters by timestamp interval. */
        INTERVAL
    }

    /**
     * Compatibility constructor for the original three-field shape, defaulting the additive
     * model / costUsd / tokens / tools fields to their "nothing attributed" values.
     */
    public SessionPrompt(Instant timestamp, String prompt, String traceId) {
        this(timestamp, prompt, traceId, null, null, null, List.of(), null, 0L, TurnAttribution.INTERVAL,
                null, List.of());
    }
}
