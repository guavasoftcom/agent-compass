package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CostCategoryShare", description = "One slice of the Cost page's work-category money "
        + "map. Every api_request row in the window belongs to exactly one category (SUBAGENT beats "
        + "SKILL beats MAIN_LOOP beats AUXILIARY), so costUsd across all categories sums to "
        + "CostBreakdown.totalCostUsd exactly.")
public record CostCategoryShare(
        @Schema(description = "MAIN_LOOP, SUBAGENT, SKILL, or AUXILIARY", example = "SUBAGENT") String category,
        @Schema(description = "Cost in USD attributed to this category in the window", example = "82.43") double costUsd,
        @Schema(description = "api_request rows in this category", example = "1309") long requests,
        @Schema(description = "Percentage share of the page total (0-100)", example = "20") int share,
        @Schema(description = "Per-identifier drilldown for SUBAGENT (by subagent type, resolved via span "
                + "correlation) and SKILL (by skill.name) categories; empty for MAIN_LOOP and AUXILIARY, "
                + "which have no meaningful identifier split.") List<CostIdentifierShare> drilldown,
        @Schema(description = "Sum of drilldown costUsd, present only when drilldown is non-empty. Can be "
                + "LESS than costUsd -- e.g. a subagent dispatch with no matching execution span, or one "
                + "nested inside another subagent's own dispatch, contributes to costUsd via the category's "
                + "own query but has no resolvable identifier here. Never force these to match by dropping "
                + "unidentified cost.", example = "80.72") Double identifiedCostUsd) {
}
