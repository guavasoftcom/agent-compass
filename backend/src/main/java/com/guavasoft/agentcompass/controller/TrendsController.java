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
package com.guavasoft.agentcompass.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.model.TrendsResponse;
import com.guavasoft.agentcompass.service.TrendService;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/trends")
@Tag(name = "Trends",
        description = "Before/after diffs for the Trend Report page: the selected window compared against "
                + "the immediately preceding period of equal length, split into four independently-fetchable "
                + "sections (Cost, Token efficiency, Reliability, Activity) so the frontend is not blocked by "
                + "the slowest section's query.")
public class TrendsController {

    private static final String DEFAULT_MINUTES = "1440";
    private static final String MINUTES_EXAMPLE = "1440";
    private static final String MINUTES_DESCRIPTION = "Window size in minutes";

    private final TrendService trendService;

    @GetMapping("/cost")
    @Operation(
            summary = "Cost section: current vs. previous period",
            description = "Compares the selected window against the immediately preceding period of equal "
                    + "length for the Cost section: total_cost, cost_per_session, blended_rate_per_1m. Read "
                    + "from the metric_points cumulative-counter pipeline. Each metric carries a before/after "
                    + "scalar plus a 7-point sparkline per side.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Cost trend section for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TrendsResponse.class))))
    public TrendsResponse costTrends(
            @Parameter(description = MINUTES_DESCRIPTION, example = MINUTES_EXAMPLE)
            @RequestParam(defaultValue = DEFAULT_MINUTES) int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return trendService.costTrendsInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return trendService.costTrends(minutes);
    }

    @GetMapping("/token-efficiency")
    @Operation(
            summary = "Token efficiency section: current vs. previous period",
            description = "Compares the selected window against the immediately preceding period of equal "
                    + "length for the Token efficiency section: cache_read_ratio_pct, tokens_total, "
                    + "tokens_per_session. Read from the metric_points cumulative-counter pipeline. Each "
                    + "metric carries a before/after scalar plus a 7-point sparkline per side.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Token efficiency trend section for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TrendsResponse.class))))
    public TrendsResponse tokenEfficiencyTrends(
            @Parameter(description = MINUTES_DESCRIPTION, example = MINUTES_EXAMPLE)
            @RequestParam(defaultValue = DEFAULT_MINUTES) int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return trendService.tokenEfficiencyTrendsInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return trendService.tokenEfficiencyTrends(minutes);
    }

    @GetMapping("/reliability")
    @Operation(
            summary = "Reliability section: current vs. previous period",
            description = "Compares the selected window against the immediately preceding period of equal "
                    + "length for the Reliability section: tool_errors, error_rate_pct, session_failures. "
                    + "Read from log_records tool_result events. Each metric carries a before/after scalar "
                    + "plus a 7-point sparkline per side.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Reliability trend section for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TrendsResponse.class))))
    public TrendsResponse reliabilityTrends(
            @Parameter(description = MINUTES_DESCRIPTION, example = MINUTES_EXAMPLE)
            @RequestParam(defaultValue = DEFAULT_MINUTES) int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return trendService.reliabilityTrendsInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return trendService.reliabilityTrends(minutes);
    }

    @GetMapping("/activity")
    @Operation(
            summary = "Activity section: current vs. previous period",
            description = "Compares the selected window against the immediately preceding period of equal "
                    + "length for the Activity section: sessions, avg_duration_min. Read from the "
                    + "metric_points cumulative-counter pipeline. Each metric carries a before/after scalar "
                    + "plus a 7-point sparkline per side.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Activity trend section for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TrendsResponse.class))))
    public TrendsResponse activityTrends(
            @Parameter(description = MINUTES_DESCRIPTION, example = MINUTES_EXAMPLE)
            @RequestParam(defaultValue = DEFAULT_MINUTES) int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return trendService.activityTrendsInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return trendService.activityTrends(minutes);
    }
}
