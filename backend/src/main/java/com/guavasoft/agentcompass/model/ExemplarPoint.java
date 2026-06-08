package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ExemplarPoint", description = "A sampled high-token request placed on the token-distribution heatmap. "
        + "col and row give the grid position; trace spans are lazily loaded via GET /api/traces/{traceId}.")
public record ExemplarPoint(
        @Schema(description = "Time-axis column index 0–23", example = "8") int col,
        @Schema(description = "Token-band row index 0–7 (0 = 256K, 7 = 0)", example = "4") int row,
        @Schema(description = "Formatted total token count", example = "13.2K") String tokens,
        @Schema(description = "Formatted span duration", example = "1.9s") String durationLabel,
        @Schema(description = "Shortened model name (claude- prefix stripped)", example = "sonnet-4") String model,
        @Schema(description = "ok or error based on the correlated span status_code",
                example = "ok", allowableValues = {"ok", "error"}) String status,
        @Schema(description = "Display form of trace_id: first 4 chars + middle dot + next 5 chars",
                example = "a13f·9c7e2") String traceId,
        @Schema(description = "Human-readable token band range", example = "8K – 16K") String bucket,
        @Schema(description = "Timestamp formatted HH:mm:ss", example = "14:03:51") String ts,
        @Schema(description = "True for the single highest-token exemplar in the result set") boolean worst,
        @Schema(description = "Waterfall spans for this exemplar's trace, ordered by offsetMs ascending") List<TraceSpanDto> spans,
        @Schema(description = "Key/value attribute pairs from the source metric row, e.g. [[model, claude-sonnet-4]]")
        List<List<String>> attributes) {
}
