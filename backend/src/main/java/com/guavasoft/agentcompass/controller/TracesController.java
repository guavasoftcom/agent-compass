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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceAnalysis;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceFacets;
import com.guavasoft.agentcompass.model.TraceFilterParams;
import com.guavasoft.agentcompass.model.TraceHistogram;
import com.guavasoft.agentcompass.model.TracePage;
import com.guavasoft.agentcompass.model.TracePaginationParams;
import com.guavasoft.agentcompass.model.TraceCostBreakdown;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceAnalysisService;
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
    private final TraceAnalysisService traceAnalysisService;
    private final TraceAnalysisSseStreamer traceAnalysisSseStreamer;

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
                    + "tokens (totalTokens desc), cost (totalCostUsd desc).")
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
                    + "Sort values: new, old, slow, fast, spans, err, tokens (totalTokens desc), cost (totalCostUsd desc). "
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

    @GetMapping("/{traceId}/cost-breakdown")
    @Operation(
            summary = "Per-subagent cost breakdown for a single trace",
            description = "Splits a trace's measured spend by who spent it: the main loop, one entry per "
                    + "Agent-tool dispatch (in the order each was dispatched), and auxiliary harness work "
                    + "(session-title generation, compaction, web fetch). Deterministic and code-computed -- "
                    + "unlike GET /{traceId}/analysis this needs no Ollama analysis to have ever been "
                    + "generated for the trace. measuredCostUsd is not guaranteed to equal "
                    + "TraceSummary.totalCostUsd; see TraceCostBreakdown's own description. Returns an "
                    + "all-zero breakdown with no dispatches, never 404, when the trace has no spans.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Cost breakdown for the requested trace",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TraceCostBreakdown.class))))
    public TraceCostBreakdown traceCostBreakdown(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return traceService.costBreakdownForTrace(traceId);
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

    @GetMapping("/{traceId}/analysis")
    @Operation(
            summary = "The stored local-Ollama analysis of a single trace, if one has been generated",
            description = "Latest-only: a trace has at most one stored analysis, overwritten by the most "
                    + "recent regenerate. 404 when none has been generated yet.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The stored analysis",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TraceAnalysis.class))),
            @ApiResponse(responseCode = "404", description = "No analysis has been generated for this trace",
                    content = @Content())})
    public ResponseEntity<TraceAnalysis> traceAnalysis(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return ResponseEntity.of(traceAnalysisService.getStored(traceId));
    }

    @PostMapping("/{traceId}/analysis")
    @Operation(
            summary = "Generate (or regenerate) a local-Ollama analysis of a single trace, blocking until done",
            description = "Overwrites whatever analysis was previously stored for this trace. Prefer "
                    + "POST /{traceId}/analysis/stream to narrate progress instead of blocking for the "
                    + "full Ollama call. 503 when Ollama cannot be reached, errors, or returns no usable "
                    + "output; 404 when no spans exist for this trace.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "The freshly generated analysis",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = TraceAnalysis.class))),
            @ApiResponse(responseCode = "404", description = "No spans exist for that trace id", content = @Content()),
            @ApiResponse(responseCode = "503", description = "Ollama is unavailable", content = @Content())})
    public ResponseEntity<TraceAnalysis> regenerateTraceAnalysis(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return ResponseEntity.of(traceAnalysisService.regenerate(traceId));
    }

    @PostMapping(value = "/{traceId}/analysis/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Generate (or regenerate) a local-Ollama analysis, narrating progress over Server-Sent Events",
            description = "Runs the same analysis as POST /{traceId}/analysis, but returns immediately with "
                    + "an open SSE stream carrying 'started', 'plan', 'phase', 'delta', 'done'/'failed' events "
                    + "as the run progresses, so the caller can show the draft being written instead of "
                    + "blocking on the full Ollama call.")
    @ApiResponse(responseCode = "200", description = "SSE stream of analysis progress events")
    public SseEmitter streamTraceAnalysis(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)",
                    example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return traceAnalysisSseStreamer.stream(traceId);
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
