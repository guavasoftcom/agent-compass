package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CatalogMetric", description = "One row in the metric catalog: name, cardinality, health signal, "
        + "and a mini sparkline covering the requested window")
public record CatalogMetric(
        @Schema(description = "OTLP metric name", example = "claude_code.token.usage") String name,
        @Schema(description = "UCUM metric unit as emitted by the instrumentation", example = "tokens") String unit,
        @Schema(description = "Instrument type; always 'counter' for Claude Code metrics", example = "counter") String type,
        @Schema(description = "Formatted distinct-attributes cardinality for the window", example = "1.2K") String cardinality,
        @Schema(description = "Health bucket: ok (<1000 distinct series), warn (1000–5000), bad (>5000)",
                example = "ok", allowableValues = {"ok", "warn", "bad"}) String health,
        @Schema(description = "8-bucket sparkline: COUNT(*) per even time slice across the window") List<Long> spark) {
}
