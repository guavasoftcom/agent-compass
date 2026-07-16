package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Four-way claude_code.token.usage split by type (reset-aware SUM(value_delta) per kind). "
        + "Every field is non-null; a kind with no points in scope reads 0.")
public record SessionTokenBreakdown(
        @Schema(description = "Sum of value_delta for type='input'", example = "61200") long input,

        @Schema(description = "Sum of value_delta for type='output'", example = "148500") long output,

        @Schema(description = "Sum of value_delta for type='cacheCreation'", example = "402300") long cacheCreation,

        @Schema(description = "Sum of value_delta for type='cacheRead'", example = "4788000") long cacheRead) {

    /** Sum of the four kinds -- the single source of truth for any "total tokens" figure. */
    public long total() {
        return input + output + cacheCreation + cacheRead;
    }
}
