/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "CostBreakdown", description = "Full response for the Cost page (GET /api/cost/breakdown). "
        + "Measured exclusively from api_request log records -- the exact per-call cost_usd figure, never "
        + "the claude_code.cost.usage cumulative counter used elsewhere on the dashboard (Tokens, "
        + "Sessions). The two pipelines do not reconcile (see AGENTS.md); this endpoint reads ~2-3% below "
        + "the counter-derived totals shown on those other pages, and that gap is not a bug in either "
        + "one. Chosen because it is the only side where the dollars and the skill/subagent/model/effort "
        + "tags sit on the same row, which is what lets every category and every cell below sum exactly "
        + "to totalCostUsd.")
public record CostBreakdown(
        @Schema(description = "Total spend in the window", example = "408.16") double totalCostUsd,
        @Schema(description = "Total spend in the equal prior window", example = "331.90") double priorCostUsd,
        @Schema(description = "Percentage change vs. the prior window; 0 when priorCostUsd is 0", example = "22.98") double deltaPct,
        @Schema(description = "totalCostUsd divided by window hours", example = "1.21") double burnRatePerHour,
        @Schema(description = "burnRatePerHour x 720 (30 days)", example = "873.6") double projected30dUsd,
        @Schema(description = "Total priced requests in the window", example = "5814") long totalRequests,
        @Schema(description = "Summed input tokens across every priced request") long totalInputTokens,
        @Schema(description = "Summed output tokens across every priced request") long totalOutputTokens,
        @Schema(description = "Summed cache-creation tokens across every priced request") long totalCacheCreationTokens,
        @Schema(description = "Summed cache-read tokens across every priced request -- usually the "
                + "dominant figure behind total spend") long totalCacheReadTokens,
        @Schema(description = "The work-category partition (MAIN_LOOP, SUBAGENT, SKILL, AUXILIARY), "
                + "sorted by cost descending. costUsd across all four sums exactly to totalCostUsd.")
                List<CostCategoryShare> categories,
        @Schema(description = "Stacked spend-over-time trend, one point per bucket, oldest first")
                List<CostTrendPoint> trend,
        @Schema(description = "Cost drivers grid: one row per (model, effort) pair, sorted by cost "
                + "descending, with the token composition behind each cell's spend")
                List<CostModelEffortCell> modelEffort,
        @Schema(description = "Biggest line items: top sessions by spend, sorted descending")
                List<CostSessionShare> topSessions,
        @Schema(description = "Width of each trend bucket in seconds", example = "86400") long bucketSeconds) {
}
