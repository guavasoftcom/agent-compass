package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Per-session cost and active-time totals over the window. cost.usage and "
        + "active_time.total are emitted by Claude Code as cumulative gauges split by (model, "
        + "query_source) — we take MAX per (session, model, query_source) to collapse each cumulative "
        + "stream to its last value, then SUM the per-stream maxima for the session. Wall-clock "
        + "duration is the span between the first and last cost/active-time emission carrying this "
        + "session id and may exceed the active-time total when the user steps away.")
public record SessionSummary(
        @Schema(description = "Session identifier (the session.id attribute value)",
                example = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b") String sessionId,

        @Schema(description = "Total cost in USD, summed across (model, query_source) streams",
                example = "12.34") double costUsd,

        @Schema(description = "Active-time seconds, summed across (model, query_source) streams",
                example = "1500.5") double activeTimeSeconds,

        @Schema(description = "Timestamp of the earliest cost / active-time emission for this session",
                example = "2026-05-27T00:04:31.320Z") Instant startTimestamp,

        @Schema(description = "Timestamp of the latest cost / active-time emission for this session",
                example = "2026-05-27T01:07:15.208Z") Instant endTimestamp,

        @Schema(description = "Wall-clock seconds between startTimestamp and endTimestamp",
                example = "3764") long wallSeconds,

        @Schema(description = "Total number of tool_result log records for this session",
                example = "42") long toolCallCount,

        @Schema(description = "Number of tool_decision log records where decision = 'reject' for this session",
                example = "3") long denialCount,

        @Schema(description = "Total tokens for this session over the window (reset-aware sum of "
                + "claude_code.token.usage across all token types), raw — the client formats M/K",
                example = "5400000") long tokens,

        @Schema(description = "Terminal kind from the session's session.count points, normalized to "
                + "interactive | non-interactive", example = "non-interactive") String terminalType,

        @Schema(description = "How the session began, from session.count start_type",
                example = "resume") String startType,

        @Schema(description = "The session's first meaningful user_prompt event body (bare slash commands are "
                + "skipped in favor of the first real prompt, unless every prompt in the session is a slash "
                + "command), whitespace-collapsed and truncated to 200 characters. Null if the session has no "
                + "user_prompt events.",
                example = "Add user-prompt context to the sessions API") String firstUserPrompt,

        @Schema(description = "Total number of user_prompt log records for this session",
                example = "7") long userPromptCount,

        @Schema(description = "This session's tokens split by type over the window. tokens is computed as the "
                + "sum of these four fields (single source, so the two always reconcile within this response). "
                + "Not guaranteed to equal the sum of the per-turn tokens on GET /api/sessions/{id}/prompts: this "
                + "field is window-scoped while the prompt timeline is whole-session and drops points outside "
                + "any captured turn's interval (pre-first-prompt, or beyond the 500-turn cap) -- the same "
                + "accepted trade-off as costUsd there.")
        SessionTokenBreakdown tokenBreakdown) {
}
