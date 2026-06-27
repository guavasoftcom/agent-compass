package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for {@code GET /api/traces/facets}.
 * Each dimension is counted with all other active filters applied but its own
 * excluded (standard faceted search).
 *
 * <p>{@code status} is always zero-filled to both values (ok, error);
 * {@code duration} is always all four buckets (d0–d3); others are observed values
 * descending by count with caps (operation/service: 50, session: 8).
 */
@Schema(description = "Per-dimension facet counts for the Traces filter rail")
public record TraceFacets(
        @Schema(description = "Status counts — always two entries (ok, error), zero-filled")
        List<FacetValue> status,

        @Schema(description = "Operation (rootSpanName) counts, descending by count, capped at 50")
        List<FacetValue> operation,

        @Schema(description = "Derived service key counts, descending by count, capped at 50")
        List<FacetValue> service,

        @Schema(description = "Duration bucket counts — always four entries (d0–d3), zero-filled")
        List<FacetValue> duration,

        @Schema(description = "Session id counts, descending by count, capped at 8")
        List<FacetValue> session) {
}
