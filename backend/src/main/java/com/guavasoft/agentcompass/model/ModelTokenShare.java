package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ModelTokenShare", description = "Per-model token breakdown for the Token Usage page 'Token sum by model' card")
public record ModelTokenShare(
        @Schema(description = "Model identifier as stored in the attributes column",
                example = "claude-opus-4") String model,
        @Schema(description = "Formatted total token count for this model in the window",
                example = "7.8M") String tokens,
        @Schema(description = "Percentage share of total window tokens (0–100)",
                example = "64") int share,
        @Schema(description = "Chart palette index for consistent colour assignment, 0 = highest token count",
                example = "0") int colorIndex) {
}
