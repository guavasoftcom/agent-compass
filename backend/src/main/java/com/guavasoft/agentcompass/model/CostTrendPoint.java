package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(name = "CostTrendPoint", description = "One bucket of the Cost page's stacked spend-over-time "
        + "chart. costByCategory keys are the same four category names as CostCategoryShare.category; a "
        + "category with zero spend in this bucket is omitted rather than sent as 0.")
public record CostTrendPoint(
        @Schema(description = "Inclusive start of this bucket") Instant timestamp,
        @Schema(description = "Cost in USD per category for this bucket",
                example = "{\"MAIN_LOOP\": 12.40, \"SUBAGENT\": 3.10}") Map<String, Double> costByCategory) {
}
