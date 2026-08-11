package com.guavasoft.agentcompass.model;

import com.fasterxml.jackson.annotation.JsonProperty;

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

        @Schema(description = "Non-cached prompt tokens — the part of inputSideTokens that was neither "
                + "read from nor written to the cache. inputTokens + cacheCreationTokens + cacheReadTokens "
                + "always equals inputSideTokens.", example = "61200") long inputTokens,

        @Schema(description = "Tokens written into the prompt cache in the window — the second part of "
                + "inputSideTokens. Kept in the ratio's denominator on purpose: a session that constantly "
                + "rebuilds its cache is paying full freight to do so.", example = "128800") long cacheCreationTokens,

        @Schema(description = "Tokens the model generated in the window. The one kind outside the ratio: "
                + "output is generated rather than sent, so the cache could never have served it. Carried "
                + "explicitly rather than left to a totalTokens - inputSideTokens subtraction on the client.",
                example = "24000") long outputTokens,

        @Schema(description = "Whole-session spend in USD, matching the Sessions grid's Cost column "
                + "(not clipped to the window). 0 when the session emitted no cost metric.",
                example = "4.12") double costUsd) {

    /**
     * The ratio's denominator — input + cacheCreation + cacheRead over the window.
     * Always exactly the sum of the three, so it is derived here rather than
     * carried as its own record component. {@code @JsonProperty} is required for
     * this to serialize like a normal field: unlike canonical record components,
     * Jackson does not auto-detect extra accessor methods on a record.
     */
    @JsonProperty("inputSideTokens")
    @Schema(description = "The ratio's denominator — input + cacheCreation + cacheRead over the window",
            example = "1000000")
    public long inputSideTokens() {
        return cacheReadTokens + inputTokens + cacheCreationTokens;
    }

    /**
     * All four token kinds summed. Derived for the same reason
     * {@link #inputSideTokens()} is: the four kinds are the measurement, and
     * anything that is exactly their sum would only be a second copy that could
     * drift from them.
     */
    @JsonProperty("totalTokens")
    @Schema(description = "All four token kinds summed, including output. Scale hint for the "
            + "ranked list; not the ratio's denominator.", example = "1024000")
    public long totalTokens() {
        return inputSideTokens() + outputTokens;
    }
}
