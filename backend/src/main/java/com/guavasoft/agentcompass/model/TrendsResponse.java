package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Schema(name = "TrendsResponse", description = "Before/after diff comparing the selected window (current) "
        + "against the immediately preceding period of equal length (previous), across 11 metrics in 4 "
        + "groups (Cost, Token efficiency, Reliability, Activity). Cost/token metrics read exclusively "
        + "from the metric_points cumulative-counter pipeline (SUM(value_delta)); reliability/activity "
        + "metrics read exclusively from log_records -- the two pipelines are never mixed within one "
        + "comparison. See backend/CLAUDE.md's two-pipelines note.")
public record TrendsResponse(
        @Schema(description = "The selected window") Window current,
        @Schema(description = "The immediately preceding period of equal duration: "
                + "windowDuration = Duration.between(current.start, current.end), "
                + "previous.start = current.start - windowDuration, previous.end = current.start.") Window previous,
        @Schema(description = "One entry per tracked metric, keyed by its id: total_cost, cost_per_session, "
                + "blended_rate_per_1m, cache_read_ratio_pct, tokens_total, tokens_per_session, tool_errors, "
                + "error_rate_pct, session_failures, sessions, avg_duration_min.")
        Map<String, MetricTrend> metrics) {

    @Schema(name = "TrendsResponse.Window", description = "An inclusive time window")
    public record Window(
            @Schema(description = "Inclusive window start (ISO-8601)", example = "2026-08-22T00:00:00Z") Instant start,
            @Schema(description = "Inclusive window end (ISO-8601)", example = "2026-08-29T00:00:00Z") Instant end) {
    }

    @Schema(name = "TrendsResponse.MetricTrend", description = "Before/after figures for one metric, plus a "
            + "7-point sparkline for each side.")
    public record MetricTrend(
            @Schema(description = "Metric value over the previous (prior) period", example = "128.40") double before,
            @Schema(description = "Metric value over the current period", example = "154.10") double after,
            @Schema(description = "7-point sparkline for the previous period, oldest first")
            @ArraySchema(schema = @Schema(example = "12.0")) List<Double> beforeSeries,
            @Schema(description = "7-point sparkline for the current period, oldest first")
            @ArraySchema(schema = @Schema(example = "14.0")) List<Double> afterSeries,
            @Schema(description = "Which direction of change reads as an improvement for this metric: "
                    + "\"down\" (e.g. total_cost, tool_errors) or \"up\" (e.g. cache_read_ratio_pct). "
                    + "\"up\" is used as a neutral placeholder for metrics with no strong direction "
                    + "(e.g. sessions), which the frontend typically renders flat when the change is small.",
                    example = "down") String directionIsGoodWhen) {
    }
}
