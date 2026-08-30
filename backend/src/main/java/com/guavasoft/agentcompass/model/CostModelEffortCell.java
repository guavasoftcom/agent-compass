package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CostModelEffortCell", description = "One (model, effort) cell of the Cost page's "
        + "drivers grid, plus the token composition behind its spend.")
public record CostModelEffortCell(
        @Schema(description = "Model identifier", example = "claude-sonnet-5") String model,
        @Schema(description = "Reasoning effort the calls in this cell ran at. Null means NOT RECORDED "
                + "(~7% of api_request rows carry no effort attribute) -- never render null as a default "
                + "level.", example = "high") String effort,
        @Schema(description = "Cost in USD for this cell", example = "187.29") double costUsd,
        @Schema(description = "api_request rows in this cell", example = "2843") long requests,
        @Schema(description = "Summed input tokens") long inputTokens,
        @Schema(description = "Summed output tokens") long outputTokens,
        @Schema(description = "Summed cache-creation tokens") long cacheCreationTokens,
        @Schema(description = "Summed cache-read tokens -- usually the dominant figure behind spend") long cacheReadTokens) {
}
