package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single (value, count) entry inside a facet dimension for
 * {@code GET /api/logs/facets}.
 */
@Schema(description = "One facet value with its row count")
public record FacetValue(
        @Schema(description = "The facet dimension value", example = "ERROR")
        String value,

        @Schema(description = "Number of log records matching this value (with all other filters applied)", example = "61")
        long count) {
}
