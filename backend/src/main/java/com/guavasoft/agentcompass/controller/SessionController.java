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

import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionPrompt;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.MetricService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/sessions")
@Tag(name = "Sessions", description = "Per-session cost, token usage, and window-level KPIs")
public class SessionController {

    private final MetricService metricService;
    private final LogService logService;

    @GetMapping("")
    @Operation(
            summary = "Per-session cost and active-time totals over the window, sorted and paginated server-side",
            description = "Joins the configured cost-usage and active-time metrics by session.id. Both "
            + "are emitted by Claude Code as cumulative gauges split by (model, query_source) — the "
            + "aggregation takes MAX per (session, model, query_source) stream then SUMs the per-"
            + "stream maxima for the session, so multi-stream sessions are not undercounted. "
            + "Wall-clock duration is the span between the first and last cost/active-time emission "
            + "carrying the session id. The grid sorts and paginates on the server: pass sort (a "
            + "column field), direction (asc/desc), page (zero-based) and size; the total session "
            + "count for the window is returned via the X-Total-Count header. Window-level stat-card "
            + "KPIs come from GET /api/sessions/summary.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One page of sessions in the requested sort order. X-Total-Count carries the "
                    + "total number of sessions in the window.",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SessionSummary.class)))))
    public ResponseEntity<List<SessionSummary>> sessions(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Parameter(description = "Column field to sort by", example = "costUsd") @RequestParam(required = false) String sort,
            @Parameter(description = "Sort direction, asc or desc", example = "desc") @RequestParam(required = false) String direction,
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "25") @RequestParam(defaultValue = "25") int size,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        SessionSummaryPage result;
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            result = metricService.sessionsSummaryInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp(), sort, direction, page, size);
        } else {
            result = metricService.sessionsSummary(minutes, sort, direction, page, size);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Total-Count", String.valueOf(result.totalCount()));
        return ResponseEntity.ok().headers(headers).body(result.items());
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Window-level session KPIs for the Sessions page stat cards",
            description = "Total session count plus median and P95 per-session cost and median "
                    + "$/active-minute, computed over every session in the window rather than the visible "
                    + "page so the cards stay stable as the user pages or re-sorts the grid. Percentiles use "
                    + "percentile_cont (linear interpolation).")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Window-level session KPIs",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = SessionKpis.class))))
    public SessionKpis sessionsSummary(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return metricService.sessionsKpisInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return metricService.sessionsKpis(minutes);
    }

    @GetMapping("/token-usage")
    @Operation(
            summary = "Token totals split by type plus a bucketed time series for the window",
            description = "Sums the configured token-usage metric (default claude_code.token.usage) "
                    + "grouped by the type attribute (input / output / cacheCreation / cacheRead) over the "
                    + "window. Returns both the window-wide totals (used for the KPI tiles and the "
                    + "cacheRead ratio) and the per-bucket breakdown for the trend chart. Bucket width is "
                    + "chosen by the server; the response carries it so the client can render axes "
                    + "consistently.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Window totals + bucketed series for the trend chart",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = TokenUsageSummary.class))))
    public TokenUsageSummary tokenUsage(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return metricService.aggregateTokenUsageInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return metricService.aggregateTokenUsage(minutes);
    }

    @GetMapping("/{sessionId}/prompts")
    @Operation(
            summary = "Full user-prompt timeline for one session",
            description = "Every user_prompt log record for the given session, oldest first, with the full "
                    + "untruncated prompt text. Not window-scoped — returns the session's entire prompt "
                    + "history regardless of the dashboard's current time window. Powers the Sessions grid's "
                    + "expandable row.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Prompt timeline ordered by timestamp ascending",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SessionPrompt.class)))))
    public List<SessionPrompt> prompts(
            @Parameter(description = "Session identifier (the session.id attribute value)",
                    example = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b") @PathVariable String sessionId) {
        return logService.promptsForSession(sessionId);
    }
}
