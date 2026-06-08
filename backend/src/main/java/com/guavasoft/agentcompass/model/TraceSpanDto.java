package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TraceSpanDto",
        description = "A single span from a trace waterfall, shaped for the exemplar drawer. "
                + "offsetMs and durMs are relative to the trace's earliest span start.")
public record TraceSpanDto(
        @Schema(description = "Span name as emitted by the agent", example = "Bash")
        String name,

        @Schema(description = "Derived span kind for the waterfall colouring",
                example = "tool",
                allowableValues = {"root", "genai", "tool", "http", "db"})
        String kind,

        @Schema(description = "Start offset in milliseconds relative to the trace's earliest span start",
                example = "0")
        long offsetMs,

        @Schema(description = "Span duration in milliseconds (durationNanos / 1_000_000)",
                example = "1942")
        long durMs,

        @Schema(description = "Nesting depth in the span tree; root spans are depth 0",
                example = "0")
        int depth,

        @Schema(description = "True when this span's durMs is >= 60% of the root span's durMs (slow-span highlight)",
                example = "false")
        boolean slow) {
}
