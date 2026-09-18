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

/**
 * Stateless parsing of the {@code <task-notification>} envelope the harness delivers when a
 * background subagent finishes, landing on a {@code user_prompt} log like any other. Extracted
 * from {@link TraceAnalysisPromptBuilder} — which owns detection and rendering of the envelope
 * into that trace's own prompt — so {@code LogService} can reuse the tool-use-id extraction
 * without depending on {@code TuningProperties}, the same "stays free of any configuration bean"
 * shape {@link JsonAttributeReaders} follows for its own callers.
 */
final class TaskNotificationEnvelope {

    static final String OPENING_TAG = "<task-notification>";

    // The field read back out of the envelope by toolUseId.
    private static final String TOOL_USE_ID_TAG = "tool-use-id";

    private TaskNotificationEnvelope() {
    }

    /**
     * Whether {@code promptText} is a {@code <task-notification>} envelope rather than text a
     * person typed. The harness delivers one when a background subagent finishes, and it lands on
     * a {@code user_prompt} log like any other — 101 of 773 (13.1%) over 30 days, measured against
     * this application's own database. Feeding one to the prompt-quality half of the trace-analysis
     * review asks the model to critique the phrasing of a machine-generated status message, which is
     * exactly what produced a fabricated "the prompt said 'I'm not sure if this is the right file'"
     * finding on a trace whose prompt was an envelope. Treating it as "no prompt" instead degrades
     * to the execution-quality half, which is the honest read of such a trace.
     *
     * <p>Mirrors {@code frontend/src/lib/promptSummary.ts}, deliberately including its
     * <b>starts-with</b> test rather than a contains: a real envelope always opens with the tag,
     * while a human prompt that merely quotes one further in is genuine text worth judging. Keep
     * the two in step — another non-authored prompt shape needs adding in both places.
     */
    static boolean isMachineAuthored(String promptText) {
        return promptText.stripLeading().startsWith(OPENING_TAG);
    }

    // Plain substring extraction rather than a regex or an XML parse: the envelope is written by the
    // harness to a fixed shape, and a malformed one has to degrade to "no continuation section" (or,
    // for a caller resolving the dispatching trace, "no dispatching call") rather than throw on a
    // dialog or page the reader is waiting on.
    static String tagValue(String text, String tagName) {
        String openingTag = "<" + tagName + ">";
        String closingTag = "</" + tagName + ">";
        int valueStart = text.indexOf(openingTag);
        if (valueStart < 0) {
            return null;
        }
        valueStart += openingTag.length();
        int valueEnd = text.indexOf(closingTag, valueStart);
        if (valueEnd < 0) {
            return null;
        }
        String value = text.substring(valueStart, valueEnd).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * The {@code tool_use_id} a {@code <task-notification>} envelope quotes back, naming the call
     * that launched the background task whose completion produced this prompt — or null when
     * {@code promptText} is not such an envelope, or carries no id.
     */
    static String toolUseId(String promptText) {
        return isMachineAuthored(promptText) ? tagValue(promptText, TOOL_USE_ID_TAG) : null;
    }
}
