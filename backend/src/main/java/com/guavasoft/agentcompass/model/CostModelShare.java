package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CostModelShare", description = "Per-model cost breakdown for the cost summary panel")
public record CostModelShare(
        @Schema(description = "Model identifier as stored in the attributes column", example = "claude-opus-4") String model,
        @Schema(description = "Formatted USD spend for this model in the window", example = "$720") String usd,
        @Schema(description = "Percentage share of total window spend (0–100)", example = "56") int share,
        @Schema(description = "Chart palette index for consistent colour assignment", example = "1") int colorIndex) {
}
