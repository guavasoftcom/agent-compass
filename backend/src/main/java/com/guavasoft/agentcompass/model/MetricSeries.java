package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(name = "MetricSeries",
        description = "One claude_code.* metric over the selected window: headline stats, a per-bucket "
                + "trend, and any attribute splits. Powers the Metrics page master-detail.")
public record MetricSeries(
        @Schema(description = "Stable short id used as the React key", example = "token") String id,
        @Schema(description = "Fully-qualified OTLP metric name", example = "claude_code.token.usage") String name,
        @Schema(description = "OTLP instrument type", example = "counter") String type,
        @Schema(description = "Unit label", example = "tokens") String unit,
        @Schema(description = "Pre-formatted headline total for the window", example = "13.0M") String sum,
        @Schema(description = "Caption for the headline stat", example = "Sum (24h)") String sumLabel,
        @Schema(description = "Pre-formatted per-hour rate", example = "542K") String rate,
        @Schema(description = "Suffix for the rate", example = "/h") String rateUnit,
        @Schema(description = "Pre-formatted peak bucket value", example = "820K") String peak,
        @Schema(description = "Signed change vs. the previous equal window", example = "+18.3%") String delta,
        @Schema(description = "Direction of the change", example = "up") String dir,
        @Schema(description = "One-line plain-text description") String description,
        @Schema(description = "Per-bucket trend values for the window (raw numbers, newest last)")
        List<Double> trend,
        @Schema(description = "Attribute breakdowns keyed by split name (Model, Type, …); empty when none")
        Map<String, List<MetricSplitRow>> splits) {
}
