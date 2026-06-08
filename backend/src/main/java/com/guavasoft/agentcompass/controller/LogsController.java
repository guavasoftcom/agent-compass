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

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.service.LogQueryService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/logs")
@Tag(name = "Logs", description = "OTLP log records and attribute autocomplete for the Logs DataGrid")
public class LogsController {

    private final LogQueryService logQueryService;

    @GetMapping("")
    @Operation(
            summary = "Persisted OTLP log records, optionally narrowed by attribute filters or a time window",
            description = "Drives the Logs DataGrid and the inline-log rendering on the Traces detail "
                    + "dialog. Returns every log_records row that contains *every* filter parameter (AND) "
                    + "in its attributes jsonb, optionally restricted to a [startTimestamp, endTimestamp] "
                    + "window, in reverse chronological order. The time-window pair is used for cross-signal "
                    + "linkage from a trace or span because this dataset's log records do not propagate OTLP "
                    + "trace_id / span_id. X-Total-Count mirrors the returned list size.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "OTLP log records matching every filter, newest first. The X-Total-Count "
                    + "header carries the size of the returned (filtered) list.",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = LogRecord.class)))))
    public ResponseEntity<List<LogRecord>> logs(
            @Parameter(description = "Active key=value filters; rows must contain all of them", example = "event.name=tool_result")
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        List<LogRecord> items = logQueryService.recentLogs(
                filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Total-Count", String.valueOf(items.size()));
        return ResponseEntity.ok().headers(headers).body(items);
    }

    @GetMapping("/attributes")
    @Operation(
            summary = "Distinct attribute key=value pairs across log_records, narrowed by filters or window",
            description = "Powers the autocomplete in the Logs filter. Returns every distinct "
                    + "\"key=value\" string found in the attributes jsonb column across rows that contain "
                    + "every filter parameter, optionally restricted to a [startTimestamp, endTimestamp] "
                    + "window so the autocomplete narrows when the Logs page is deep-linked from a trace.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Sorted distinct key=value pairs from rows matching every filter",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))))
    public List<String> logAttributes(
            @Parameter(description = "Active key=value filters; rows must contain all of them", example = "event.name=tool_result")
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        return logQueryService.availableAttributePairs(
                filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
    }

    @GetMapping("/attribute-keys")
    @Operation(
            summary = "Distinct attribute keys across log_records, narrowed by filters or window",
            description = "First stage of the Logs filter autocomplete. Returns every distinct "
                    + "attribute key found in the attributes jsonb column across rows that contain "
                    + "every filter parameter, optionally restricted to a [startTimestamp, endTimestamp] "
                    + "window. The reserved log body field is excluded.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Sorted distinct attribute keys",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))))
    public List<String> logAttributeKeys(
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        return logQueryService.availableAttributeKeys(
                filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
    }

    @GetMapping("/attribute-values")
    @Operation(
            summary = "Distinct values for a single attribute key across log_records",
            description = "Second stage of the Logs filter autocomplete: once the user has chosen a "
                    + "key, this lists the distinct values present for that key under the same filter and "
                    + "time window as the keys endpoint.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Sorted distinct values for the requested key",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = String.class)))))
    public List<String> logAttributeValues(
            @Parameter(description = "Attribute key whose values should be returned", example = "tool_name") @RequestParam String key,
            @RequestParam(required = false) List<String> filter,
            @Valid @ModelAttribute TimeWindowParams timeWindow) {
        return logQueryService.availableAttributeValues(
                key, filter == null ? List.of() : filter, timeWindow.startTimestamp(), timeWindow.endTimestamp());
    }
}
