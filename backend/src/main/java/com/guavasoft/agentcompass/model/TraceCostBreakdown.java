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

import java.util.List;

@Schema(name = "TraceCostBreakdown",
        description = "Deterministic, code-computed cost split for a single trace -- who spent what, not "
                + "an Ollama analysis output and available whether or not one has ever been generated for "
                + "the trace (like span_costs / span_efforts). measuredCostUsd sums only the requests this "
                + "attribution could join to a model-call span by request_id, so it is NOT guaranteed to "
                + "equal TraceSummary.totalCostUsd (which counts every trace-correlated request log) -- "
                + "never normalize the two to force agreement; see the two-pipelines note in "
                + "backend/CLAUDE.md.")
public record TraceCostBreakdown(

        @Schema(description = "Per-subagent-dispatch cost split, in trace order (the order each Agent call "
                + "was dispatched). Empty when the trace dispatched no sub-agent.")
        List<SubagentCostBreakdown> subagentCosts,

        @Schema(description = "Main-loop spend: model calls that ran neither inside a sub-agent dispatch "
                + "nor as auxiliary harness work (session-title generation, compaction, web fetch).",
                example = "1.20")
        double mainLoopCostUsd,

        @Schema(description = "Main-loop model call count.", example = "8")
        int mainLoopModelCallCount,

        @Schema(description = "Spend on auxiliary requests -- session-title generation, compaction, web "
                + "fetch -- kept separate so it never reads as the agent's own conversational work.",
                example = "0.01")
        double auxiliaryCostUsd,

        @Schema(description = "Auxiliary model call count.", example = "1")
        int auxiliaryModelCallCount,

        @Schema(description = "Total measured cost across every bucket above (main loop + every dispatch + "
                + "auxiliary). See the class description for why this is not guaranteed to equal "
                + "TraceSummary.totalCostUsd.", example = "1.63")
        double measuredCostUsd) {
}
