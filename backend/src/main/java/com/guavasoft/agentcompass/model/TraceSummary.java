package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "TraceSummary", description = "Aggregated view of a single OTLP trace (all spans sharing the same trace_id), shaped for "
        + "the dashboard trace list. The detail spans are fetched separately via GET /api/traces/{traceId}.")
public class TraceSummary {

    @Schema(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)", example = "0102030405060708090a0b0c0d0e0f10")
    private String traceId;

    @Schema(description = "Name of the root span (the span with no parent in this trace). "
            + "Null when no root span is present, e.g. when only child spans have been ingested.", example = "Bash")
    private String rootSpanName;

    @Schema(description = "Session identifier from the root span's attributes (session.id). "
            + "Null when the root span does not carry a session.id attribute.", example = "05f2af1b-432f-4b18-9263-cfcff14b2566")
    private String sessionId;

    @Schema(description = "Hex-encoded span ID of the root span (the span with no parent). "
            + "Null when no root span is present.", example = "0102030405060708")
    private String rootSpanId;

    @Schema(description = "Number of spans in this trace", example = "12")
    private long spanCount;

    @Schema(description = "Earliest start_timestamp across all spans in the trace", example = "2026-05-21T17:30:00Z")
    private Instant startTimestamp;

    @Schema(description = "Latest end_timestamp across all spans in the trace", example = "2026-05-21T17:30:00.250Z")
    private Instant endTimestamp;

    @Schema(description = "Wall-clock duration of the trace in nanoseconds "
            + "(endTimestamp − startTimestamp)", example = "250000000")
    private Long durationNanos;

    @Schema(description = "Number of spans in the trace whose statusCode is \"error\"", example = "0")
    private long errorCount;

    @Schema(description = "Total token usage summed across all spans in the trace "
            + "(input + output + cache-read + cache-creation, first-present key per category). "
            + "0 when no span carries token attributes.", example = "5400000")
    private long totalTokens;

    @Schema(description = "The user prompt that initiated this trace, whitespace-collapsed and truncated to 200 "
            + "characters. Taken from the earliest user_prompt log record correlated to this trace by trace_id, "
            + "mirroring SessionSummary.firstUserPrompt. Null in two cases: the trace is not rooted in a "
            + "conversational turn (tool / model / mcp / compaction-rooted traces carry no user_prompt log of "
            + "their own), or prompt-body capture was disabled when the trace was recorded (the user_prompt log "
            + "exists but carries no prompt text).",
            example = "Add a firstUserPrompt field to the Traces API", nullable = true)
    private String firstUserPrompt;
}
