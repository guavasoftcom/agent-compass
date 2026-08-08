package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SessionCacheEfficiency",
        description = "One session in the worst-cache-efficiency ranking. cacheEfficiency is "
                + "cacheRead / (input + cacheCreation + cacheRead) — the share of the session's "
                + "input-side tokens that were served from the prompt cache. Output tokens are "
                + "generated rather than sent, so they are excluded from the ratio but still "
                + "counted in totalTokens. Only sessions at or above the configured input-side "
                + "token floor are ranked, so a two-call session cannot appear at 0%.")
public record SessionCacheEfficiency(
        @Schema(description = "Session identifier (the session.id attribute value)",
                example = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b") String sessionId,

        @Schema(description = "cacheRead / (input + cacheCreation + cacheRead), 0..1. Never null: "
                + "the token floor guarantees a non-zero denominator.", example = "0.41") double cacheEfficiency,

        @Schema(description = "Tokens served from the prompt cache in the window", example = "410000") long cacheReadTokens,

        @Schema(description = "The ratio's denominator — input + cacheCreation + cacheRead over the window",
                example = "1000000") long inputSideTokens,

        @Schema(description = "All four token kinds summed, including output. Scale hint for the "
                + "ranked list; not the ratio's denominator.", example = "1024000") long totalTokens,

        @Schema(description = "Whole-session spend in USD, matching the Sessions grid's Cost column "
                + "(not clipped to the window). 0 when the session emitted no cost metric.",
                example = "4.12") double costUsd) {
}
