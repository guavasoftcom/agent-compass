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

/**
 * The payloads {@code POST /api/traces/{traceId}/analysis/stream} sends, one nested record per SSE
 * event name. A namespace rather than a type: nothing implements this interface, it just keeps the
 * whole wire contract legible in one file, since springdoc cannot describe an SSE event stream and
 * the frontend parser is written against exactly these shapes.
 *
 * <p>The event sequence is always {@code started}, then {@code phase} per step, then any number of
 * {@code delta}s while the model writes, and finally exactly one of {@code done} (carrying a
 * {@link TraceAnalysis}) or {@code failed}.
 *
 * <p><b>A failed run ends with a {@code failed} event and a normal stream close, not with
 * {@code SseEmitter.completeWithError}.</b> By the time the model call is running the response is
 * long committed at 200, so there is no status code left to fail with; an errored close reaches the
 * browser as a bare connection fault with no message, which would throw away exactly the text this
 * feature works hardest to produce — {@code OllamaUnavailableException}'s messages are written to be
 * read by the user (see {@code ApiExceptionHandler}).
 */
public interface TraceAnalysisStreamEvent {

    /**
     * Event {@code started}: the OPTIMISTIC plan for the run, sent before any work — greyed out —
     * rather than growing it a line at a time. It lists one entry per {@link TraceAnalysisPhase},
     * before it is known whether the timeline will need more than one {@code DRAFTING} window; the
     * real, expanded plan follows as {@link Planned} once that is known.
     */
    @Schema(name = "TraceAnalysisStreamStarted")
    record Started(List<PhaseDescriptor> phases) implements TraceAnalysisStreamEvent {
    }

    /**
     * Event {@code plan}: the REAL plan, sent once window count is known (right after the prompt is
     * prepared, before any model call) — replaces the optimistic {@link Started} list once a
     * partitioned trace means {@code DRAFTING} repeats.
     */
    @Schema(name = "TraceAnalysisStreamPlanned")
    record Planned(List<PhaseDescriptor> phases) implements TraceAnalysisStreamEvent {
    }

    /**
     * One occurrence of a step. {@code label} travels with the id so the dialog renders the
     * backend's wording; adding or renaming a {@link TraceAnalysisPhase} needs no frontend change.
     *
     * @param key identifies this occurrence — {@code phase} alone for a phase reached once,
     *     {@code phase + "#" + stepNumber} for a repeated one (see
     *     {@code TraceAnalysisProgressListener}'s own javadoc)
     * @param stepNumber this occurrence's 1-based position among {@code stepCount} occurrences of
     *     {@code phase} in this run
     * @param detail backend-authored free text about this occurrence, e.g. {@code "calls 78-140"} —
     *     null when there is nothing beyond the phase's own label worth saying
     */
    @Schema(name = "TraceAnalysisPhaseDescriptor")
    record PhaseDescriptor(String phase, String label, String key, int stepNumber, int stepCount, String detail) {
    }

    /** Event {@code phase}: the run has reached this occurrence. Names a {@link TraceAnalysisPhase}. */
    @Schema(name = "TraceAnalysisStreamPhaseStarted")
    record PhaseStarted(String phase, String key, int stepNumber, int stepCount, String detail)
            implements TraceAnalysisStreamEvent {
    }

    /**
     * Event {@code delta}: the model wrote more of the answer.
     *
     * @param phase always {@code DRAFTING} today
     * @param key which occurrence of {@code phase} this delta belongs to — see
     *     {@code TraceAnalysisProgressListener}'s own javadoc for why a partitioned trace needs this
     * @param text the newly written text, coalesced from however many fragments arrived since the
     *     last delta. <b>Empty string when {@code ollama.structured-output} is on</b> — the answer is
     *     then a JSON document that only becomes a readable review once it has fully parsed. The
     *     client needs no flag to tell the two apart: it renders a live draft when deltas carry text
     *     and falls back to reporting {@code characters} when they do not.
     * @param characters total characters of answer produced so far across the WHOLE run — every
     *     window plus the apply-this call, monotonically increasing for the run's lifetime
     */
    @Schema(name = "TraceAnalysisStreamDelta")
    record Delta(String phase, String key, String text, int characters) implements TraceAnalysisStreamEvent {
    }

    /** Event {@code failed}: the run ended without storing anything. Message is user-facing text. */
    @Schema(name = "TraceAnalysisStreamFailed")
    record Failed(String message) implements TraceAnalysisStreamEvent {
    }
}
