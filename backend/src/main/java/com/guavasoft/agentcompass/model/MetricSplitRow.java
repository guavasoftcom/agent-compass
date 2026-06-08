package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MetricSplitRow",
        description = "One attribute-value row within a metric's split breakdown on the Metrics page")
public record MetricSplitRow(
        @Schema(description = "Attribute value label, e.g. the model or token type",
                example = "claude-sonnet-4") String label,
        @Schema(description = "Pre-formatted total for this split value in the window",
                example = "7.8M") String value,
        @Schema(description = "Share of the metric total (0–100)", example = "60") int pct,
        @Schema(description = "Chart palette index, 0 = largest", example = "0") int colorIndex) {
}
