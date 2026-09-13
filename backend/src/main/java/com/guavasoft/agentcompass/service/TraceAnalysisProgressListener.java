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
package com.guavasoft.agentcompass.service;

import com.guavasoft.agentcompass.model.TraceAnalysisPhase;

import java.util.List;

/**
 * How {@link TraceAnalysisService#regenerate(String, TraceAnalysisProgressListener)} reports where it
 * has got to. Deliberately a callback rather than a return value: the interesting part of a run is
 * what it is doing while it has not returned yet.
 *
 * <p>Both methods run on the thread doing the work, inline with it, so an implementation that blocks
 * slows the analysis down. The SSE adapter that backs the dialog coalesces
 * {@link #answerAdvanced} rather than writing a frame per fragment for exactly this reason.
 *
 * <p><b>{@link #phaseStarted} and {@link #answerAdvanced} both carry a {@code key}</b> because
 * windowing (see {@code TraceAnalysisPromptBuilder#partitionToBudget}) repeats {@link
 * TraceAnalysisPhase#DRAFTING} once per window: without a way to tell one occurrence from the next,
 * window 2's draft would look like more of window 1's to a listener counting characters, and the
 * reported total would run backwards the moment a new window's draft restarted from zero. The key is
 * {@code phase.name()} for a phase reached once, and {@code phase.name() + "#" + stepNumber} for a
 * repeated one.
 */
public interface TraceAnalysisProgressListener {

    /**
     * One phase's position within the run — always {@link #single()} for a phase reached exactly
     * once, and a real position for a phase repeated across windows.
     *
     * @param detail backend-authored free text describing this occurrence, e.g. {@code "calls 78-140"}
     *     for a {@link TraceAnalysisPhase#DRAFTING} window — null when there is nothing beyond the
     *     phase's own label worth saying.
     */
    record PhaseScope(int stepNumber, int stepCount, String detail) {

        private static final PhaseScope SINGLE = new PhaseScope(1, 1, null);

        public static PhaseScope single() {
            return SINGLE;
        }
    }

    /** One entry of the plan reported by {@link #planned}. */
    record PlannedPhase(TraceAnalysisPhase phase, String key, PhaseScope scope) {
    }

    /**
     * Discards everything — what the plain, non-streaming
     * {@link TraceAnalysisService#regenerate(String)} passes so that both entry points run the exact
     * same code and the streaming one cannot drift into being the only one that works.
     */
    TraceAnalysisProgressListener NO_OP = new TraceAnalysisProgressListener() {

        @Override
        public void phaseStarted(TraceAnalysisPhase phase, PhaseScope scope) {
            // Intentionally empty.
        }

        @Override
        public void answerAdvanced(TraceAnalysisPhase phase, String key, String appendedText, int totalCharacters) {
            // Intentionally empty.
        }
    };

    /**
     * Called once per phase occurrence, on entering it. Phases are reported in run order; a
     * partitioned trace reports {@link TraceAnalysisPhase#DRAFTING} once per window, each with its
     * own {@link PhaseScope}.
     */
    void phaseStarted(TraceAnalysisPhase phase, PhaseScope scope);

    /**
     * The full plan for this run, known once {@code TraceAnalysisService#preparePrompt} has decided
     * how many windows the timeline needed — sent once, before the first {@link #phaseStarted} call,
     * so a listener that wants to show the whole checklist up front (rather than growing it a step at
     * a time) can. No-op by default so {@link #NO_OP} needs no change here.
     */
    default void planned(List<PlannedPhase> plan) {
        // Intentionally empty by default.
    }

    /**
     * Called as the model writes, during a {@link TraceAnalysisPhase#DRAFTING} occurrence only.
     *
     * @param phase always {@link TraceAnalysisPhase#DRAFTING} today; carried so a listener need not
     *     remember which phase is currently streaming
     * @param key identifies which occurrence of {@code phase} this fragment belongs to — see this
     *     interface's own javadoc
     * @param appendedText the text the model just produced. <b>Empty when
     *     {@code ollama.structured-output} is on</b> — the answer is then a JSON document being
     *     built up rather than the markdown a reader would want shown, so only the count is
     *     reported and the client shows progress rather than a live draft.
     * @param totalCharacters characters of answer produced so far across the WHOLE run (every window
     *     plus the apply-this call), including this fragment — monotonically increasing for the
     *     run's lifetime, never reset per window.
     */
    void answerAdvanced(TraceAnalysisPhase phase, String key, String appendedText, int totalCharacters);
}
