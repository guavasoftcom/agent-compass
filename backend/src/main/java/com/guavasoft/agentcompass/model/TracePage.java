package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for offset-paged {@code GET /api/traces} (Table mode).
 * Matches the {@code TracesListResult} TypeScript interface in {@code tracesApi.ts}.
 */
@Schema(description = "One page of offset-paged trace summaries")
public record TracePage(
        @Schema(description = "Trace summaries for this page, in requested sort order")
        List<TraceSummary> items,

        @Schema(description = "Total matching traces across all pages", example = "540")
        long totalCount) {
}
