package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CostIdentifierShare", description = "One named identifier's share of its parent "
        + "CostCategoryShare's drilldown (a skill or subagent identifier). share is percent of the "
        + "category's identifiedCostUsd, not of the page total.")
public record CostIdentifierShare(
        @Schema(description = "Skill or subagent identifier", example = "Explore") String identifier,
        @Schema(description = "Cost in USD attributed to this identifier in the window", example = "22.04") double costUsd,
        @Schema(description = "Percentage share of the parent category's identifiedCostUsd (0-100)", example = "27") int share) {
}
