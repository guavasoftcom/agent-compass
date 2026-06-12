package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for offset-paged {@code GET /api/logs} (Table mode). Matches
 * the {@code LogsListResult} TypeScript interface in {@code logsApi.ts}.
 */
@Schema(description = "One page of offset-paged log records")
public record LogPage(
        @Schema(description = "Log records for this page")
        List<LogRecord> items,

        @Schema(description = "Total matching records across all pages", example = "3443")
        long totalCount) {
}
