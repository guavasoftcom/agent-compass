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

/**
 * The steps one trace-analysis run passes through, in order, as reported by
 * {@code POST /api/traces/{traceId}/analysis/stream}.
 *
 * <p><b>Every constant here is a real step, and the list is deliberately short.</b> The dialog this
 * feeds previously advanced a four-item checklist on a fixed three-second client-side timer, which
 * meant the labels described nothing: all four drained in nine seconds and the last one then absorbed
 * however long the model actually took. The fix is not more labels — it is that these four are the
 * ones the backend can honestly say it has reached, and that {@link #DRAFTING}, the only slow one
 * (seconds to minutes of local inference against everything before it costing milliseconds), reports
 * its progress as the answer text itself rather than as invented sub-steps.
 *
 * <p>{@code label} travels to the client with the phase, so the dialog renders whatever the backend
 * names rather than keeping its own copy of these strings: adding, renaming, or reordering a phase
 * here needs no frontend change.
 */
@Schema(name = "TraceAnalysisPhase", description = "One step of a streaming trace-analysis run")
public enum TraceAnalysisPhase {

    /** Trace summary, spans, logs, the preceding assistant turn, and any dispatching tool call. */
    READING_TRACE("Reading trace spans & logs"),

    /**
     * Projecting all of that into the review prompt — the call timeline, the verified observations
     * (revisits, failed calls, shell antipatterns, cost drivers, cache reuse, context size) and the
     * closed list of rule targets.
     */
    BUILDING_PROMPT("Reviewing tool calls, errors & prompt quality"),

    /**
     * The first Ollama call, which writes the findings. Slow, and the one that streams the bulk of
     * the answer.
     */
    DRAFTING("Drafting review"),

    /**
     * The second Ollama call, which distils those findings into the three "Apply this" lines — see
     * {@code TraceAnalysisService#generateAndSave} for why that is its own call. Genuinely slow
     * enough to deserve naming (it is local inference, not a render step), but much shorter than
     * {@link #DRAFTING}: its prompt carries the review text alone, with no timeline or overview.
     */
    APPLYING("Writing what to apply"),

    /**
     * Only reached when the trace's timeline was partitioned into more than one window (see {@code
     * TraceAnalysisPromptBuilder#partitionToBudget}) — joining each window's own {@link #DRAFTING}
     * findings into the single result the rest of the pipeline renders. Pure code, no model call, but
     * genuinely a distinct step: without it, a run that drafted three windows would otherwise jump
     * straight from the last {@link #DRAFTING} to {@link #APPLYING} with no acknowledgement that
     * anything happened in between.
     */
    MERGING("Combining the passes"),

    /** Rendering the stored markdown and upserting the {@code trace_analyses} row. */
    SAVING("Saving the review");

    private final String label;

    TraceAnalysisPhase(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
