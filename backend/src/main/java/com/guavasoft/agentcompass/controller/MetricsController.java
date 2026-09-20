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
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.MetricAttributes;
import com.guavasoft.agentcompass.model.MetricDistribution;
import com.guavasoft.agentcompass.model.MetricPage;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricSeriesAggregation;
import com.guavasoft.agentcompass.model.MetricSeriesFilter;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.service.MetricService;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "OTLP metric data points, per-metric series, attribute facets and "
        + "per-request distributions for the Metrics page")
public class MetricsController {

    private static final String FILTER_PARAMETER = "filter";

    private final MetricService metricService;
    private final MetricSeriesService metricSeriesService;

    // The filter parameter is read off the request rather than bound as a List<String>: Spring splits a
    // SINGLE value of a collection-typed @RequestParam on commas, which would cut a value such as
    // "model:a,b" into two malformed pairs. It is documented via the operation's parameters instead.
    @GetMapping("/series")
    @Operation(
            summary = "Per-metric series for the Metrics page: trend, headline stats, and attribute splits",
            description = "Returns one object per fixed claude_code.* counter over the window: a windowed "
                    + "trend, headline sum / rate / peak, signed delta vs. the previous equal window, "
                    + "any attribute splits (e.g. token usage by model or type), and the distinct-stream "
                    + "cardinality (a number) with its server-computed health (ok / warn / bad). Display "
                    + "values are pre-formatted strings. Both from and to are required ISO-8601 instants. "
                    + "Optional attribute filters (repeated filter=key:value, ANDed, each split on its "
                    + "FIRST colon so a value may contain colons, plus the filterMetricId they apply to) "
                    + "narrow ONE metric's rows; every other metric in the response is unaffected. Values "
                    + "compare as text, so a numeric or boolean attribute matches its text form. Under an "
                    + "active filter the filtered metric's cardinality counts only label-sets with non-zero "
                    + "activity in the window (streams that merely re-emitted an unchanged value are "
                    + "excluded), so it can be lower than that metric's unfiltered cardinality. filter "
                    + "without filterMetricId, filterMetricId without filter, a malformed pair (no colon, "
                    + "or an empty key) or a filterMetricId matching no metric is a 400. "
                    + "An optional aggregation (aggMetricId + agg, both or neither) changes ONLY the trend "
                    + "array of that one metric to the per-bucket avg, p95 or count of its individual "
                    + "non-zero increments (agg=sum is the default and a no-op); header stats, splits and "
                    + "cardinality stay sum-based. Only one of the two, an unknown aggMetricId or an "
                    + "unknown agg is a 400.",
            parameters = @Parameter(
                    name = FILTER_PARAMETER,
                    in = ParameterIn.QUERY,
                    description = "Attribute filter written key:value, repeatable; all must match (AND). Split on "
                            + "the first colon only. Requires filterMetricId.",
                    array = @ArraySchema(schema = @Schema(type = "string", example = "model:claude-sonnet-4"))))
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Metric series for the requested window, one per metric",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = MetricSeries.class)))))
    public List<MetricSeries> metricSeries(
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to,
            @Parameter(description = "Restrict to one repository's telemetry (vcs.repository.url.full); omit for all repositories")
            @RequestParam(required = false) String repositoryUrl,
            @Parameter(description = "Id of the one metric the attribute filters apply to (a series id such as token); "
                    + "must be supplied together with filter", example = "token")
            @RequestParam(required = false) String filterMetricId,
            @Parameter(description = "Id of the one metric whose trend agg applies to (a series id such as token); "
                    + "must be supplied together with agg", example = "token")
            @RequestParam(required = false) String aggMetricId,
            @Parameter(description = "How that metric's trend buckets are aggregated, case-insensitive: sum "
                    + "(default, per-bucket total), avg (mean increment), p95 (95th-percentile increment) or "
                    + "count (number of increments). avg/p95/count consider only non-zero increments, so "
                    + "the exporter's per-minute zero-delta re-emissions do not distort them. Affects the "
                    + "trend array only.", example = "avg",
                    schema = @Schema(allowableValues = {"sum", "avg", "p95", "count"}))
            @RequestParam(required = false) String agg,
            @Parameter(hidden = true) HttpServletRequest request) {
        String[] filterValues = request.getParameterValues(FILTER_PARAMETER);
        return metricSeriesService.metricSeries(
                from, to, repositoryUrl,
                MetricSeriesFilter.of(filterMetricId, filterValues == null ? List.of() : List.of(filterValues)),
                MetricSeriesAggregation.of(aggMetricId, agg));
    }

    @GetMapping("")
    @Operation(
            summary = "Persisted OTLP metric data points, narrowed by active attribute filters — offset-paged",
            description = "Drives the Metrics DataGrid. Returns one page of metric_points rows that contain "
                    + "*every* filter parameter (AND) in their attributes jsonb, in reverse chronological "
                    + "order (timestamp DESC, id DESC). page is 0-based; size is clamped between 1 and 500 "
                    + "(default 25). totalCount is the full filtered row count across all pages, independent "
                    + "of page/size.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One page of OTLP metric data points matching every filter, newest first, plus "
                    + "the total matching row count",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MetricPage.class))))
    public MetricPage metrics(
            @Parameter(description = "Active key=value filters; rows must contain all of them", example = "method=GET")
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams,
            @Parameter(description = "0-based page number", example = "0")
            @RequestParam(required = false, defaultValue = "0") int page,
            @Parameter(description = "Page size (clamped between 1 and 500)", example = "25")
            @RequestParam(required = false, defaultValue = "25") int size) {
        return metricService.recentEvents(
                filter == null ? List.of() : filter,
                timeWindowParams.startTimestamp(),
                timeWindowParams.endTimestamp(),
                page,
                size);
    }

    @GetMapping("/catalog")
    @Operation(
            summary = "Metric catalog: one row per distinct metric name with cardinality and sparkline",
            description = "Returns every distinct metric name observed in the window with its unit, "
                    + "cardinality label (distinct attribute combinations), health bucket "
                    + "(ok / warn / bad), and an 8-bucket sparkline showing ingestion volume "
                    + "across the window. Both from and to are required ISO-8601 instants.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Metric catalog rows for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = CatalogMetric.class)))))
    public List<CatalogMetric> metricCatalog(
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to) {
        return metricService.aggregateMetricCatalog(from, to);
    }

    @GetMapping("/cost")
    @Operation(
            summary = "Aggregated cost summary for the Metrics cost panel",
            description = "Returns total spend, delta vs. the equal prior window, burn rate, "
                    + "30-day projection, cost per 1 000 tokens, a 14-bucket trend, and a "
                    + "per-model cost breakdown. All currency values are pre-formatted strings. "
                    + "Both from and to are required ISO-8601 instants.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Cost summary for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = CostSummary.class))))
    public CostSummary metricCost(
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to) {
        return metricService.aggregateCostSummary(from, to);
    }

    @GetMapping("/distribution")
    @Operation(
            summary = "Per-request points of the token or cost metric for the Metrics distribution scatter plot",
            description = "Returns one point per api_request log record in the window: its timestamp, its "
                    + "value (input + output + cache-creation + cache-read tokens for the token metric, "
                    + "cost_usd for the cost metric) and a traceId. Points are ascending by timestamp and "
                    + "capped to the NEWEST 2,000 requests in the window. traceId is non-null only for a "
                    + "handful (at most 5) of server-chosen exemplars, each a real trace id: the maximum, "
                    + "the requests nearest the p50 / p95 / p99 values of the returned set, and the "
                    + "highest-value failed request; every other point carries null. The client computes "
                    + "its own percentiles. Sourced from the exact per-call api_request logs, so it "
                    + "deliberately does NOT reconcile with the counter-based /series sum. "
                    + "Both from and to are required ISO-8601 instants; metric must be the full token-usage "
                    + "or cost-usage metric name (any other or unknown name is a 400).")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Per-request points for the requested window and metric",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MetricDistribution.class))))
    public MetricDistribution metricDistribution(
            @Parameter(description = "Full metric name to plot: the token-usage or cost-usage metric",
                    example = "claude_code.token.usage")
            @RequestParam String metric,
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to,
            @Parameter(description = "Restrict to one repository's telemetry (vcs.repository.url.full); omit for all repositories")
            @RequestParam(required = false) String repositoryUrl) {
        return metricService.aggregateMetricDistribution(from, to, repositoryUrl, metric);
    }

    @GetMapping("/attributes")
    @Operation(
            summary = "Filterable attribute keys and their values for one metric, to populate the Metrics filter picker",
            description = "Returns, for the named metric, every attribute key its data points carry in the "
                    + "window with the key's distinct values and, per value, how many distinct active "
                    + "label-sets (streams) carry it. Keys are alphabetical; values are most common first "
                    + "(ties alphabetical). Keys with more than 25 distinct values (session ids and other "
                    + "unbounded attributes) are omitted. A metric name that was never seen, or that has no "
                    + "qualifying attribute, is NOT an error: it yields {\"attributes\": []}. Both from and "
                    + "to are required ISO-8601 instants.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Filterable attributes for the requested metric and window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = MetricAttributes.class))))
    public MetricAttributes metricAttributes(
            @Parameter(description = "Full metric name to list attributes for", example = "claude_code.token.usage")
            @RequestParam String metric,
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to,
            @Parameter(description = "Restrict to one repository's telemetry (vcs.repository.url.full); omit for all repositories")
            @RequestParam(required = false) String repositoryUrl) {
        return metricSeriesService.metricAttributes(from, to, repositoryUrl, metric);
    }
}
