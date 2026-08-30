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

import com.guavasoft.agentcompass.model.CostBreakdown;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.service.CostService;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/cost")
@Tag(name = "Cost",
        description = "Where spend went over the selected window: a work-category partition "
                + "(main loop / subagent / skill / auxiliary), cost drivers by model and effort, "
                + "and the biggest line-item sessions. Measured exclusively from api_request log "
                + "records -- see CostBreakdown's description for why this differs from the "
                + "counter-derived cost KPIs shown on Tokens and Sessions.")
public class CostController {

    private final CostService costService;

    @GetMapping("/breakdown")
    @Operation(
            summary = "Full cost breakdown for the Cost page",
            description = "Returns total spend, delta vs. the equal prior window, burn rate, "
                    + "30-day projection, the work-category partition, a stacked spend-over-time "
                    + "trend, a (model, effort) cost-drivers grid, and the top sessions by spend.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Cost breakdown for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CostBreakdown.class))))
    public CostBreakdown breakdown(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return costService.breakdownInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return costService.breakdown(minutes);
    }
}
