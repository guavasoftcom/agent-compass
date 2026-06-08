package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TokenDistribution", description = "Token-band heatmap data for the Metrics distribution panel. "
        + "bands is the ordered list of Y-axis labels (top → bottom). exemplars are sampled "
        + "high-token requests placed on the col × row grid.")
public record TokenDistribution(
        @Schema(description = "Y-axis band labels ordered top-to-bottom",
                example = "[\"256K\",\"128K\",\"64K\",\"32K\",\"16K\",\"8K\",\"4K\",\"0\"]") List<String> bands,
        @Schema(description = "Sampled exemplar requests, up to 8") List<ExemplarPoint> exemplars) {
}
