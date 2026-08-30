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
        description = "Before/after diff for the Trend Report page: the selected window compared against "
                + "the immediately preceding period of equal length, across 11 metrics in 4 groups "
                + "(Cost, Token efficiency, Reliability, Activity).")
public class TrendsController {

    private final TrendService trendService;

    @GetMapping
    @Operation(
            summary = "Trend report: current vs. previous period across 11 metrics",
            description = "Compares the selected window against the immediately preceding period of equal "
                    + "length. Cost and token metrics are read from the metric_points cumulative-counter "
                    + "pipeline; reliability and activity metrics are read from log_records tool_result "
                    + "events. Each metric carries a before/after scalar plus a 7-point sparkline per side.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Trend report for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TrendsResponse.class))))
    public TrendsResponse trends(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return trendService.trendsInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return trendService.trends(minutes);
    }
}
