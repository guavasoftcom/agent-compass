package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Consecutive same-tool same-scope run-length stats over a window. "
        + "Computed by detecting islands of identical (tool, scope) rows within each session "
        + "ordered by timestamp, taking the longest run per (session, tool, scope), then rolling "
        + "those longest-per-session values up into a median and a max. Sessions whose longest run "
        + "was only one call are excluded from the rollup — they are not repeats. Runs where the "
        + "scope couldn't be determined are also excluded — a shared 'unknown' scope isn't evidence "
        + "the calls repeated the same action.")
public record ToolRepeatStat(
        @Schema(description = "Tool name (matches the tool_name attribute on tool_result events)", example = "Edit") String tool,

        @Schema(description = "Scope key the runs are computed within: file_path for Edit / Write / "
                + "Read / MultiEdit; for Bash, the command with any leading 'cd <path> &&' chain "
                + "stripped, then its first two whitespace-delimited tokens (program plus "
                + "subcommand/flag). Rows for every other tool, and rows where this couldn't be "
                + "resolved, are dropped rather than reported under a shared '(no scope)' bucket.",
                example = "/repo/src/foo.ts") String scope,

        @Schema(description = "Median (P50) of the longest-run-per-session values for this "
                + "(tool, scope). Rounded to the nearest integer.", example = "3") long medianRunLength,

        @Schema(description = "Maximum longest-run observed for this (tool, scope) across any "
                + "single session in the window.", example = "8") long maxRunLength,

        @Schema(description = "Number of distinct sessions that contributed a longest-run-≥2 for "
                + "this (tool, scope).", example = "4") long sessions) {
}
