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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceFacets;
import com.guavasoft.agentcompass.model.TraceFilterParams;
import com.guavasoft.agentcompass.model.TraceHistogram;
import com.guavasoft.agentcompass.model.TracePage;
import com.guavasoft.agentcompass.model.TracePaginationParams;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceExplorerService;
import com.guavasoft.agentcompass.service.TraceService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/traces")
@Tag(name = "Traces",
        description = "OTLP trace explorer: histogram, facets, cursor/offset paged list, per-trace spans, and correlated logs")
public class TracesController {

    private static final int MINIMUM_HISTOGRAM_BUCKETS = 1;
    private static final int MAXIMUM_HISTOGRAM_BUCKETS = 500;

    private final TraceService traceService;
    private final TraceExplorerService traceExplorerService;
    private final LogService logService;

    @GetMapping("/histogram")
    @Operation(
            summary = "Trace throughput histogram with per-bucket p95 latency",
            description = "Server-buckets the window using a 'nice' ladder (1m,2m,5m,10m,15m,30m,1h,2h,3h,6h) "
                    + "so bars remain readable at any zoom. Buckets traces by start timestamp. "
                    + "All active filters applied. Empty buckets zero-filled.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Zero-filled histogram covering the full window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TraceHistogram.class))))
    public TraceHistogram histogram(
            @Parameter(description = "Target bar count — service picks the nearest 'nice' bucket width",
                    example = "48")
            @RequestParam(required = false, defaultValue = "48")
            @Min(MINIMUM_HISTOGRAM_BUCKETS) @Max(MAXIMUM_HISTOGRAM_BUCKETS) int buckets,
            @Valid @ModelAttribute TraceFilterParams traceFilterParams) {
        TraceQueryCriteria criteria = buildCriteria(traceFilterParams);
        return traceExplorerService.histogram(criteria, buckets);
    }

    @GetMapping("/facets")
    @Operation(
            summary = "Per-dimension facet counts for the Traces filter rail",
            description = "Returns per-value counts for status, operation, service, duration, and session. "
                    + "Each dimension is counted with all other active filters applied but its own excluded "
                    + "(standard faceted search). status always returns ok and error (zero-filled); "
                    + "duration always returns d0–d3 (zero-filled); operation/service capped at 50; "
                    + "session capped at 8.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Facet counts for all trace dimensions",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TraceFacets.class))))
    public TraceFacets facets(@Valid @ModelAttribute TraceFilterParams traceFilterParams) {
        TraceQueryCriteria criteria = buildCriteria(traceFilterParams);
        return traceExplorerService.facets(criteria);
    }

    @GetMapping(value = "", params = "page")
    @Operation(
            summary = "Trace list — offset-paged (Table mode)",
            description = "Returns a TracePage ({items, totalCount}) for the requested 0-based page. "
                    + "Sort is one of: new (start desc), old (start asc), slow (duration desc), "
                    + "fast (duration asc), spans (spanCount desc), err (errorCount desc then start desc), "
                    + "tokens (totalTokens desc).")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Offset-paged trace list",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TracePage.class))))
    public TracePage tracesOffsetPage(
            @Valid @ModelAttribute TraceFilterParams traceFilterParams,
            @ModelAttribute TracePaginationParams tracePaginationParams) {
        TraceQueryCriteria criteria = buildCriteria(traceFilterParams);
        return traceExplorerService.offsetPage(
                criteria,
                tracePaginationParams.getSort(),
                tracePaginationParams.resolvedPage(),
                tracePaginationParams.getSize());
    }

    @GetMapping(value = "")
    @Operation(
            summary = "Trace list — cursor-paged (Stream and live-tail modes)",
            description = "Keyset paging on (startTimestamp, traceId). "
                    + "'before=ts,traceId' scrolls back (rows after this row in sort order); "
                    + "'after=ts,traceId' polls for live tail (returns [] when nothing new). "
                    + "Default sort is 'new' (start desc). limit defaults to 60. "
                    + "Sort values: new, old, slow, fast, spans, err, tokens (totalTokens desc). "
                    + "Initial page (no cursor) includes totalCount; continuation pages return totalCount=0.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Cursor-paged trace list",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TraceCursorPage.class))))
    public ResponseEntity<TraceCursorPage> tracesCursorPage(
            @Valid @ModelAttribute TraceFilterParams traceFilterParams,
            @ModelAttribute TracePaginationParams tracePaginationParams) {
        TraceQueryCriteria criteria = buildCriteria(traceFilterParams);
        TraceCursorPage cursorPage = traceExplorerService.cursorPage(
                criteria,
                tracePaginationParams.getSort(),
                tracePaginationParams.getBefore(),
                tracePaginationParams.getAfter(),
                tracePaginationParams.getLimit());
        return ResponseEntity.ok(cursorPage);
    }

    @GetMapping("/{traceId}/summary")
    @Operation(
            summary = "Aggregate summary of a single trace (the trace detail header row)",
            description = "Returns the same TraceSummary shape the list endpoints return — span/error counts, "
                    + "duration, root span, session, token total, and firstUserPrompt — for one trace. "
                    + "Not window-scoped, so a permalinked trace always resolves. "
                    + "404 when no spans carry the given trace id.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Summary of the requested trace",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TraceSummary.class))),
            @ApiResponse(responseCode = "404", description = "No spans exist for that trace id", content = @Content())})
    public ResponseEntity<TraceSummary> traceSummary(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return ResponseEntity.of(traceExplorerService.traceSummary(traceId));
    }

    @GetMapping("/{traceId}")
    @Operation(
            summary = "All spans belonging to a single trace, ordered by start_timestamp ascending",
            description = "Returns every persisted span whose trace_id matches the given hex-encoded ID. "
                    + "Empty list when no spans exist for that trace.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Spans of the requested trace, oldest to newest",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Span.class)))))
    public List<Span> traceSpans(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return traceService.spansForTrace(traceId);
    }

    @GetMapping("/{traceId}/logs")
    @Operation(
            summary = "All log records correlated to a single trace by trace_id, oldest first",
            description = "Returns every log_records row whose trace_id column matches the given "
                    + "hex-encoded ID, ordered by timestamp ascending. Empty list when no log records "
                    + "carry that trace_id.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Log records for the requested trace, oldest to newest",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = LogRecord.class)))))
    public List<LogRecord> traceLogRecords(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return logService.logsForTrace(traceId);
    }

    private static TraceQueryCriteria buildCriteria(TraceFilterParams filterParams) {
        return TraceQueryCriteria.of(
                filterParams.getStartTimestamp(),
                filterParams.getEndTimestamp(),
                filterParams.getStatus(),
                filterParams.getOperation(),
                filterParams.getService(),
                filterParams.getDuration(),
                filterParams.getSession(),
                filterParams.getQ());
    }
}
