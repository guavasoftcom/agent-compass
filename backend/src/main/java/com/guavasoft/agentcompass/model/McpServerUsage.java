package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpServerUsage",
        description = "Per-(server, tool) MCP invocation stats over the requested window, read entirely off "
                + "log_records (tool_result / tool_decision) — never spans, whose duration includes time "
                + "blocked on user approval and would conflate server slowness with approval latency. Server "
                + "and tool identity come from the tool_parameters JSON attribute, not tool_name, which is a "
                + "single constant ('mcp_tool') shared by every server on this signal.")
public record McpServerUsage(
        @Schema(description = "MCP server name, from tool_parameters.mcp_server_name; 'unknown' when absent or blank",
                example = "playwright") String server,

        @Schema(description = "Server-side tool name, from tool_parameters.mcp_tool_name; 'unknown' when absent or blank",
                example = "browser_evaluate") String tool,

        @Schema(description = "Total tool_result events observed for this (server, tool) pair", example = "460") long calls,

        @Schema(description = "Subset of calls whose success attribute was false", example = "54") long failures,

        @Schema(description = "failures / calls, in [0.0, 1.0]", example = "0.1174") double failureRate,

        @Schema(description = "Mean execution duration in milliseconds (log duration_ms, not span duration)",
                example = "1820") long avgDurationMs,

        @Schema(description = "95th-percentile execution duration in milliseconds", example = "13100") long p95DurationMs,

        @Schema(description = "Summed tool_result_size_bytes across all calls", example = "6500000") long totalBytes,

        @Schema(description = "Estimated one-time context tokens (totalBytes / 4) — a ranking aid, not a "
                + "billed token count, same convention as ToolContextFootprint.estimatedTokens",
                example = "1625000") long estimatedTokens,

        @Schema(description = "P95 single-result size in bytes (percentile_cont, linear interpolation)",
                example = "180000") long p95Bytes) {
}
