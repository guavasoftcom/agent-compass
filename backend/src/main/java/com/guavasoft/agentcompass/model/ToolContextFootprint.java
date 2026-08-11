package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ToolContextFootprint",
        description = "How much context window one tool's results consumed over the window. "
                + "estimatedTokens is bytes/4 — an ESTIMATE of one-time injection size, not a "
                + "billed token count, and deliberately not comparable to the exact figures on "
                + "/api/sessions/token-usage. It also understates true cost: a tool result is "
                + "re-sent with every later request in the same session, so a large result early "
                + "in a long session is paid for many times over. Useful for ranking tools "
                + "against each other, which is what it is for.")
public record ToolContextFootprint(
        @Schema(description = "Tool name as reported by the agent; 'unknown' when the attribute is absent",
                example = "Bash") String tool,

        @Schema(description = "Calls that reported a result size, including failed ones — error "
                + "output fills context too", example = "412") long calls,

        @Schema(description = "Summed tool_result_size_bytes across those calls", example = "18432000") long totalBytes,

        @Schema(description = "Estimated one-time context tokens (totalBytes / 4)", example = "4608000") long estimatedTokens,

        @Schema(description = "P95 single-result size in bytes (percentile_cont, linear interpolation). "
                + "A tool with a modest total but a high P95 has an occasional blowout rather than a "
                + "steady drip.", example = "96000") long p95Bytes) {
}
