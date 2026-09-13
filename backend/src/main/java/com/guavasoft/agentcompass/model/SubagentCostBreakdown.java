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

@Schema(name = "SubagentCostBreakdown",
        description = "What one Agent-tool dispatch (a sub-agent run) cost, resolved by joining its "
                + "llm_request spans back to their api_request logs on request_id -- see "
                + "SubagentCostAttributor. Ancestry (not query_source, which Claude Code collapses "
                + "every project-local agent to \"custom\" under) is what keeps two dispatches of the "
                + "same agent type from being merged into one bucket, so dispatchCallNumber is the only "
                + "thing that tells them apart.")
public record SubagentCostBreakdown(

        @Schema(description = "The sub-agent type this dispatch ran as, read from the query_source of its "
                + "own requests (agent:builtin:<type> / agent:custom). \"subagent\" when no request carried "
                + "one yet.", example = "Explore")
        String subagentLabel,

        @Schema(description = "The 1-based call number (in the same numbering the trace detail waterfall "
                + "and the trace-analysis timeline both use -- see TraceCallNumbering) of the Agent tool "
                + "call that dispatched this sub-agent run.", example = "12")
        int dispatchCallNumber,

        @Schema(description = "USD spend measured across this dispatch's own model calls, summed from the "
                + "api_request logs joined to its llm_request spans by request_id. Not guaranteed to sum to "
                + "TraceCostBreakdown.measuredCostUsd's own total in any particular way beyond addition -- "
                + "see the class-level note on TraceCostBreakdown.", example = "0.42")
        double costUsd,

        @Schema(description = "Model calls made inside this dispatch.", example = "6")
        int modelCallCount,

        @Schema(description = "Tool calls made inside this dispatch.", example = "3")
        int toolCallCount) {
}
