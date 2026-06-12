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
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.LogCursorPage;
import com.guavasoft.agentcompass.model.LogFacets;
import com.guavasoft.agentcompass.model.LogFilterParams;
import com.guavasoft.agentcompass.model.LogHistogram;
import com.guavasoft.agentcompass.model.LogPage;
import com.guavasoft.agentcompass.model.LogPaginationParams;
import com.guavasoft.agentcompass.model.LogQueryCriteria;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.service.LogService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/logs")
@Tag(name = "Logs", description = "OTLP log records and attribute autocomplete for the Logs DataGrid")
public class LogsController {

    private final LogService logService;

    @GetMapping("/histogram")
    @Operation(
            summary = "Severity histogram over the selected window",
            description = "Server-buckets the window by severity using a 'nice' bucket-width ladder "
                    + "(1m,2m,5m,10m,15m,30m,1h,2h,3h,6h,12h,1d,2d) so bars remain readable at any "
                    + "zoom. Severity filter is excluded from the WHERE so all four series are always "
                    + "populated; legend muting is client-side. Empty buckets are zero-filled. "
                    + "Accepts all shared filter params except 'severity'.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Zero-filled histogram covering the full window",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = LogHistogram.class))))
    public LogHistogram histogram(
            @Parameter(description = "Target bar count — service picks the nearest 'nice' bucket width",
                    example = "50")
            @RequestParam(required = false, defaultValue = "50") int buckets,
            @ModelAttribute LogFilterParams logFilterParams,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        LogQueryCriteria criteria = LogQueryCriteria.of(
                timeWindowParams.startTimestamp(),
                timeWindowParams.endTimestamp(),
                logFilterParams.getFilter(),
                List.of(),
                logFilterParams.getEvent(),
                logFilterParams.getTool(),
                logFilterParams.getQuery());
        return logService.histogram(criteria, buckets);
    }

    @GetMapping("/facets")
    @Operation(
            summary = "Per-dimension facet counts for the Logs filter rail",
            description = "Returns per-value counts for severity, event, and tool. Each "
                    + "dimension is counted with all other active filters applied but its own "
                    + "excluded (standard faceted search). severity is always zero-filled to "
                    + "four values in ERROR→WARN→INFO→DEBUG order; others return observed values "
                    + "descending by count, capped at 50. The scope dimension is omitted because "
                    + "Claude Code emits exactly one scope name for all rows.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Facet counts for severity, event, and tool dimensions",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = LogFacets.class))))
    public LogFacets facets(
            @ModelAttribute LogFilterParams logFilterParams,
            @RequestParam(required = false) List<String> severity,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        LogQueryCriteria criteria = LogQueryCriteria.of(
                timeWindowParams.startTimestamp(),
                timeWindowParams.endTimestamp(),
                logFilterParams.getFilter(),
                severity,
                logFilterParams.getEvent(),
                logFilterParams.getTool(),
                logFilterParams.getQuery());
        return logService.facets(criteria);
    }

    @GetMapping("")
    @Operation(
            summary = "Log records — cursor-paged (Stream/live-tail) or offset-paged (Table)",
            description = "When 'page' is present, returns an offset-paged LogPage "
                    + "({items, totalCount}). Otherwise, performs cursor keyset paging on "
                    + "(timestamp, id) newest-first: 'before=<ts>,<id>' scrolls back; "
                    + "'after=<ts>,<id>' polls for live tail (returns [] when nothing new). "
                    + "A request carries either cursor or offset params, not both. "
                    + "The initial page's totalCount, the histogram bucket sum, and the facet "
                    + "severity totals all agree for the same filters; before/after pages skip "
                    + "the count and return totalCount=0 (clients keep their own running total).")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Cursor page (LogCursorPage) when no 'page' param present",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LogCursorPage.class))),
            @ApiResponse(
                    responseCode = "200",
                    description = "Offset page (LogPage) when 'page' param present",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LogPage.class)))
    })
    public ResponseEntity<Object> logs(
            @ModelAttribute LogFilterParams logFilterParams,
            @RequestParam(required = false) List<String> severity,
            @ModelAttribute LogPaginationParams logPaginationParams,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {

        LogQueryCriteria criteria = LogQueryCriteria.of(
                timeWindowParams.startTimestamp(),
                timeWindowParams.endTimestamp(),
                logFilterParams.getFilter(),
                severity,
                logFilterParams.getEvent(),
                logFilterParams.getTool(),
                logFilterParams.getQuery());

        if (logPaginationParams.isOffsetMode()) {
            LogPage logPage = logService.offsetPage(criteria, logPaginationParams.getPage(), logPaginationParams.getSize());
            return ResponseEntity.ok(logPage);
        }

        LogCursorPage cursorPage = logService.cursorPage(
                criteria, logPaginationParams.getBefore(), logPaginationParams.getAfter(), logPaginationParams.getLimit());
        return ResponseEntity.ok(cursorPage);
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
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        return logService.availableAttributePairs(
                filter == null ? List.of() : filter, timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
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
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        return logService.availableAttributeKeys(
                filter == null ? List.of() : filter, timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
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
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        return logService.availableAttributeValues(
                key, filter == null ? List.of() : filter, timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
    }
}
