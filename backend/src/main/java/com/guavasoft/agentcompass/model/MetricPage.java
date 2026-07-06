package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for offset-paged {@code GET /api/metrics} (Metrics DataGrid).
 * Mirrors {@link LogPage}, which follows the same {@code {items, totalCount}}
 * envelope for the Logs Table view.
 */
@Schema(description = "One page of offset-paged metric data points")
public record MetricPage(
        @Schema(description = "Metric data points for this page")
        List<EventRow> items,

        @Schema(description = "Total matching records across all pages", example = "3443")
        long totalCount) {
}
