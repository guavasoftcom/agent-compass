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

import com.guavasoft.agentcompass.model.SessionApiRequest;
import com.guavasoft.agentcompass.model.SessionCacheEfficiency;
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
            summary = "Sessions active in the window with whole-session cost totals, "
                    + "sorted and paginated server-side",
            description = "Joins the configured cost-usage and active-time metrics by session.id. Both "
            + "are cumulative counters split into per-attribute-set streams; the aggregation SUMs the "
            + "reset-aware per-row increments precomputed at ingest, so multi-stream sessions are not "
            + "undercounted. The window selects WHICH sessions are listed — a session qualifies if it "
            + "emitted cost or active time inside it — but costUsd and activeTimeSeconds then cover the "
            + "session's ENTIRE lifetime, so a session that began earlier reports its full spend rather "
            + "than the part inside the range. Tokens, the start/end timestamps and the tool/denial/prompt "
            + "counts stay window-scoped. "
            + "Wall-clock duration is the span between the first and last in-window cost/active-time "
            + "emission carrying the session id. The grid sorts and paginates on the server: pass sort (a "
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
                    + "percentile_cont (linear interpolation). These costs ARE clipped to the window, unlike "
                    + "the whole-session costUsd on GET /api/sessions — the median here is a window figure and "
                    + "will not equal the median of the grid's Cost column.")
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

    @GetMapping("/cache-efficiency")
    @Operation(
            summary = "Sessions ranked by worst cache efficiency",
            description = "Sessions in the window ordered by ascending cacheRead / (input + "
                    + "cacheCreation + cacheRead) — the same ratio the Sessions grid's Cache eff. "
                    + "column renders and sorts on, so the two never disagree. Cache reads bill at a "
                    + "fraction of fresh input, which makes a low ratio the single biggest per-session "
                    + "cost lever. Sessions below the configured input-side token floor "
                    + "(tuning.cache-efficiency-minimum-input-tokens, default 100k) are excluded: a "
                    + "session that made two small calls can sit at 0% without anything being wrong "
                    + "and would crowd out the sessions where a poor ratio actually costs money. "
                    + "Token figures are window-scoped; costUsd is whole-session, matching the "
                    + "Sessions grid's Cost column.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Sessions ordered worst cache efficiency first",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SessionCacheEfficiency.class)))))
    public List<SessionCacheEfficiency> cacheEfficiency(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Parameter(description = "Maximum sessions to return", example = "8") @RequestParam(defaultValue = "8") int limit,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return metricService.worstCacheEfficiencySessionsInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp(), limit);
        }
        return metricService.worstCacheEfficiencySessions(minutes, limit);
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

    @GetMapping("/{sessionId}/requests")
    @Operation(
            summary = "Every LLM request issued during one session, with exact per-call tokens and cost",
            description = "The per-request drill-down behind the prompt timeline's per-turn figures. "
                    + "Each row is one api_request log: exact input / output / cache-creation / "
                    + "cache-read tokens, reported cost, duration, model, and the effort/speed "
                    + "settings in force. promptId names the turn that issued the request, so "
                    + "grouping by it reproduces exactly the per-turn rollups on GET "
                    + "/api/sessions/{id}/prompts wherever those report attribution=REQUEST. "
                    + "Not window-scoped — returns the session's whole request history, oldest "
                    + "first, capped at 500 rows. An empty list is a normal outcome for sessions "
                    + "recorded without event logging or by an older CLI; those sessions still "
                    + "have counter-derived cost on the other endpoints, so treat empty as "
                    + "'no per-request detail available', never as zero spend.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Requests ordered by timestamp ascending",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SessionApiRequest.class)))))
    public List<SessionApiRequest> requests(
            @Parameter(description = "Session identifier (the session.id attribute value)",
                    example = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b") @PathVariable String sessionId) {
        return logService.requestsForSession(sessionId);
    }
}
