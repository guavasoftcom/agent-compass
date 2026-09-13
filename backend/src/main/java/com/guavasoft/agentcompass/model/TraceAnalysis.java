/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "TraceAnalysis", description = "A stored local-Ollama analysis of one trace. Latest-only: "
        + "regenerating overwrites whatever was here before, there is no history.")
public record TraceAnalysis(
        @Schema(description = "Hex-encoded OTLP trace ID this analysis is for",
                example = "0102030405060708090a0b0c0d0e0f10") String traceId,

        @Schema(description = "Ollama model that produced this analysis", example = "llama3.1") String model,

        @Schema(description = "Freeform analysis text. Render as plain text, never parsed markdown/HTML — "
                + "it is model output derived from trace content this application did not author.")
        String analysis,

        @Schema(description = "How long the Ollama call took, in milliseconds", example = "18420")
        long generationDurationMs,

        @Schema(description = "When this analysis was generated", example = "2026-08-23T11:48:19Z")
        Instant generatedAt,

        @Schema(description = "MAX(end_timestamp) across the trace's spans as of generation -- "
                + "the trace activity this analysis actually covers.", example = "2026-08-23T11:47:58Z")
        Instant analyzedThroughTimestamp,

        @Schema(description = "True when the trace has picked up spans since this analysis was "
                + "generated (a still-running trace, or a late background/subagent completion) -- "
                + "the analysis may no longer reflect everything that happened.", example = "false")
        boolean outdated,

        @Schema(description = "The human-written request this analysis judged, verbatim -- the "
                + "'before' half of the Better wording advice, so a reader can compare the "
                + "suggestion against what was actually typed. Null when the trace carries no "
                + "wording anyone authored (a slash command, a background-task notification, a "
                + "sub-agent run), which is also when the review skips request quality entirely.",
                nullable = true, example = "fix the token thing, it's wrong somewhere in the backend")
        String userPrompt,

        @Schema(description = "Legacy -- no analysis generated after V28 sets this true. Historically: "
                + "true when the call timeline had to be elided to fit the configured Ollama prompt "
                + "budget (ollama.max-prompt-chars). An oversized timeline is now partitioned into "
                + "consecutive review windows instead -- see reviewPassCount.", example = "false")
        boolean timelineTruncated,

        @Schema(description = "Legacy -- no analysis generated after V28 sets this above 0. "
                + "Historically: how many condensed timeline lines were dropped from the middle to "
                + "fit the budget.", example = "0")
        int omittedLineCount,

        @Schema(description = "How many review windows (model calls that wrote findings) this "
                + "analysis was generated from -- see partitionToBudget. 1 for an ordinary trace and "
                + "for any analysis stored before this field existed.", example = "1")
        int reviewPassCount,

        @Schema(description = "The trace's total call count, unaffected by how many review windows "
                + "it took. 0 for an analysis stored before this field existed.", example = "42")
        int timelineCallCount,

        @Schema(description = "A plain-text 'what happened' recap -- request, work, outcome -- "
                + "composed entirely in code from the same facts the review's overview is built "
                + "from, and never sent to the model: it is not a finding and carries no judgment. "
                + "Render as plain text, same as `analysis`. Null for an analysis stored before this "
                + "field existed.", nullable = true,
                example = "Request: \"fix the flaky token test\"\nWork: 4 tool calls and 2 model "
                        + "calls over 18.2s, touching TokenServiceTest.java, costing $0.0123\n"
                        + "Outcome: no errors; ended: \"Fixed -- the assertion compared cents to "
                        + "dollars.\"")
        String summary) {

    /**
     * A copy of this analysis with {@code outdated} replaced. {@code outdated} has no source field on
     * {@code TraceAnalysisEntity} -- {@code TraceAnalysisMapper} leaves it at its default and
     * {@code TraceAnalysisService} fills it in afterwards from a fresh staleness check, the same
     * "mapper builds the DTO, the service completes it" split {@code SpanMapper} uses for
     * {@code costUsd}/{@code effort}.
     */
    public TraceAnalysis withOutdated(boolean outdated) {
        return new TraceAnalysis(traceId, model, analysis, generationDurationMs, generatedAt,
                analyzedThroughTimestamp, outdated, userPrompt, timelineTruncated, omittedLineCount,
                reviewPassCount, timelineCallCount, summary);
    }
}
