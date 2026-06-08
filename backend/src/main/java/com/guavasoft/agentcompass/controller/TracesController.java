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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.LogQueryService;
import com.guavasoft.agentcompass.service.TraceQueryService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/traces")
@Tag(name = "Traces", description = "OTLP trace summaries, per-trace spans, and correlated log records")
public class TracesController {

    private final TraceQueryService traceQueryService;
    private final LogQueryService logQueryService;

    @GetMapping("")
    @Operation(
            summary = "Recent OTLP traces, one row per distinct trace_id, newest first",
            description = "Drives the Traces page. Each row aggregates all spans sharing a trace_id. "
                    + "Server-controlled page size; total distinct trace count is returned via the "
                    + "X-Total-Count response header. Fetch the spans of a specific trace via "
                    + "GET /api/traces/{traceId}.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per trace, newest first by trace start timestamp. X-Total-Count "
                    + "carries the total number of distinct traces in the spans table.",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = TraceSummary.class)))))
    public ResponseEntity<List<TraceSummary>> traces(
            @Parameter(description = "Optional lookback window in minutes; omit for all traces", example = "1440")
            @RequestParam(required = false) Integer minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        List<TraceSummary> items;
        long totalCount;
        if (timeWindow.startTimestamp() != null && timeWindow.endTimestamp() != null) {
            items = traceQueryService.recentTracesInRange(timeWindow.startTimestamp(), timeWindow.endTimestamp());
            totalCount = traceQueryService.countTracesInRange(timeWindow.startTimestamp(), timeWindow.endTimestamp());
        } else {
            items = traceQueryService.recentTraces(minutes);
            totalCount = traceQueryService.countTraces(minutes);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Total-Count", String.valueOf(totalCount));
        return ResponseEntity.ok().headers(headers).body(items);
    }

    @GetMapping("/{traceId}")
    @Operation(
            summary = "All spans belonging to a single trace, ordered by start_timestamp ascending",
            description = "Returns every persisted span whose trace_id matches the given hex-encoded ID. "
                    + "Empty list when no spans exist for that trace.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Spans of the requested trace, oldest → newest",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = Span.class)))))
    public List<Span> traceSpans(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)", example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return traceQueryService.spansForTrace(traceId);
    }

    @GetMapping("/{traceId}/logs")
    @Operation(
            summary = "All log records correlated to a single trace by trace_id, oldest first",
            description = "Returns every log_records row whose trace_id column matches the given "
                    + "hex-encoded ID, ordered by timestamp ascending. Empty list when no log records "
                    + "carry that trace_id.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Log records for the requested trace, oldest → newest",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = LogRecord.class)))))
    public List<LogRecord> traceLogRecords(
            @Parameter(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)", example = "0102030405060708090a0b0c0d0e0f10")
            @PathVariable String traceId) {
        return logQueryService.logsForTrace(traceId);
    }
}
