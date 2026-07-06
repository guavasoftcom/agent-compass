package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "One user_prompt event in a session's prompt timeline, full untruncated text")
public record SessionPrompt(
        @Schema(description = "Timestamp of the user_prompt event", example = "2026-05-27T00:05:12.100Z")
        Instant timestamp,

        @Schema(description = "Full prompt text as submitted by the user", example = "Refactor the SessionSummary "
                + "record to include prompt context")
        String prompt,

        @Schema(description = "Hex-encoded OTLP trace ID of the claude_code.interaction root span this prompt "
                + "belongs to, for cross-signal correlation. Null for prompts emitted before tracing was enabled "
                + "(older sessions carry no trace_id, an empty string, or an all-zero trace_id).",
                example = "0102030405060708090a0b0c0d0e0f10")
        String traceId) {
}
