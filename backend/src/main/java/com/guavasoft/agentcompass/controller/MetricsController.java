package com.guavasoft.agentcompass.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.EventRow;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.model.TokenDistribution;
import com.guavasoft.agentcompass.service.MetricQueryService;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/metrics")
@Tag(name = "Metrics", description = "OTLP metric data points and attribute autocomplete for the Metrics DataGrid")
public class MetricsController {

    private final MetricQueryService metricQueryService;
    private final MetricSeriesService metricSeriesService;

    @GetMapping("/series")
    @Operation(
            summary = "Per-metric series for the Metrics page: trend, headline stats, and attribute splits",
            description = "Returns one object per fixed claude_code.* counter over the window: a windowed "
                    + "trend, headline sum / rate / peak, signed delta vs. the previous equal window, and "
                    + "any attribute splits (e.g. token usage by model or type). All display values are "
                    + "pre-formatted strings. Both from and to are required ISO-8601 instants.")
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
            @RequestParam Instant to) {
        return metricSeriesService.metricSeries(from, to);
    }

    @GetMapping("")
    @Operation(
            summary = "Persisted OTLP metric data points, narrowed by active attribute filters",
            description = "Drives the Metrics DataGrid. Returns every metric_points row that contains "
                    + "*every* filter parameter (AND) in its attributes jsonb, in reverse chronological "
                    + "order. When no filter is supplied, returns all rows. X-Total-Count mirrors the "
                    + "returned list size, so it reflects the filtered count when filters are active.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "OTLP metric data points matching every filter, newest first. The "
                    + "X-Total-Count header carries the size of the returned (filtered) list.",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = EventRow.class)))))
    public ResponseEntity<List<EventRow>> metrics(
            @Parameter(description = "Active key=value filters; rows must contain all of them", example = "method=GET")
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        List<EventRow> items = metricQueryService.recentEvents(
                filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Total-Count", String.valueOf(items.size()));
        return ResponseEntity.ok().headers(headers).body(items);
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
        return metricQueryService.aggregateMetricCatalog(from, to);
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
        return metricQueryService.aggregateCostSummary(from, to);
    }

    @GetMapping("/distribution")
    @Operation(
            summary = "Token-band heatmap data for the Metrics distribution panel",
            description = "Returns the Y-axis band labels and up to 8 sampled exemplar requests "
                    + "placed on a col × row grid. Each exemplar carries its token count, "
                    + "correlated span duration, model, status, and trace-id display string. "
                    + "Both from and to are required ISO-8601 instants.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Token distribution heatmap for the requested window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TokenDistribution.class))))
    public TokenDistribution metricDistribution(
            @Parameter(description = "Inclusive window start (ISO-8601)", example = "2026-05-01T00:00:00Z")
            @RequestParam Instant from,
            @Parameter(description = "Inclusive window end (ISO-8601)", example = "2026-05-31T23:59:59Z")
            @RequestParam Instant to) {
        return metricQueryService.aggregateTokenDistribution(from, to);
    }

    @GetMapping("/attributes")
    @Operation(
            summary = "Distinct attribute key=value pairs across metric_points, narrowed by active filters",
            description = "Powers the autocomplete in the Metrics filter. Returns every distinct "
                    + "\"key=value\" string found in the attributes jsonb column across rows that contain "
                    + "*every* filter parameter (AND). When no filter is supplied, returns the full set. "
                    + "Primitive values are rendered without JSON quoting (e.g. method=GET).")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Sorted distinct key=value pairs from rows matching every filter",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))))
    public List<String> metricAttributes(
            @Parameter(description = "Active key=value filters; rows must contain all of them", example = "method=GET")
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        return metricQueryService.availableAttributePairs(
                filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
    }
}
