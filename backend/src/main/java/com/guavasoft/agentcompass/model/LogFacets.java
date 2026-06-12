package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response DTO for {@code GET /api/logs/facets}. Each dimension is counted with
 * all other active filters applied but its own excluded (standard faceted
 * search). {@code severity} is always zero-filled to four values in
 * ERROR→WARN→INFO→DEBUG order.
 *
 * <p>The {@code scope} dimension is intentionally absent: Claude Code emits
 * exactly one instrumentation scope name for all rows, so the dimension carries
 * no signal and clutters the filter rail.
 */
@Schema(description = "Per-dimension facet counts for the Logs filter rail")
public record LogFacets(
        @Schema(description = "Severity counts — always four entries in ERROR→WARN→INFO→DEBUG order, zero-filled")
        List<FacetValue> severity,

        @Schema(description = "Event-name (attributes['event.name']) counts, descending by count, capped at 50")
        List<FacetValue> event,

        @Schema(description = "Tool name (attributes['tool_name']) counts, descending by count, capped at 50")
        List<FacetValue> tool) {
}
