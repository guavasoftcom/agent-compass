package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for cursor-paged {@code GET /api/traces} (Stream and live-tail modes).
 * Matches the {@code TraceCursorPage} TypeScript interface in {@code tracesApi.ts}.
 */
@Schema(description = "One page of cursor-paged trace summaries")
public record TraceCursorPage(
        @Schema(description = "Trace summaries for this page, in requested sort order")
        List<TraceSummary> items,

        @Schema(description = "Cursor for the next page (in sort direction), null when exhausted",
                nullable = true)
        TraceCursor nextCursor,

        @Schema(description = "True when the page filled to the requested limit and more rows exist")
        boolean hasMore,

        @Schema(description = "Total matching traces for the full window. Only computed on the initial "
                + "page (no before/after cursor); continuation pages return 0.",
                example = "540")
        long totalCount) {
}
