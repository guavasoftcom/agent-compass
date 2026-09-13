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
package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.model.TraceAnalysis;
import com.guavasoft.agentcompass.model.TraceAnalysisPhase;
import com.guavasoft.agentcompass.model.TraceAnalysisStreamEvent;
import com.guavasoft.agentcompass.ollama.OllamaUnavailableException;
import com.guavasoft.agentcompass.service.TraceAnalysisProgressListener;
import com.guavasoft.agentcompass.service.TraceAnalysisService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Drives {@link TraceAnalysisSseStreamer#runAnalysis} with a recording sink, so the event sequence a
 * client actually parses is pinned without standing up a servlet response.
 */
@ExtendWith(MockitoExtension.class)
class TraceAnalysisSseStreamerTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final TraceAnalysisProgressListener.PhaseScope SINGLE =
            TraceAnalysisProgressListener.PhaseScope.single();

    @Mock
    TraceAnalysisService traceAnalysisService;

    private static TraceAnalysis storedAnalysis() {
        return new TraceAnalysis(
                TRACE_ID, "llama3.1", "This trace reads one file twice.", 9_000L,
                Instant.parse("2026-08-30T10:05:00Z"), Instant.parse("2026-08-30T10:04:55Z"), false, null,
                false, 0, 1, 12, null);
    }

    @Test
    void aSuccessfulRunAnnouncesEveryPhaseUpFrontThenNarratesItAndEndsWithTheStoredAnalysis() {
        TraceAnalysis stored = storedAnalysis();
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any())).thenAnswer(invocation -> {
            TraceAnalysisProgressListener listener = invocation.getArgument(1);
            listener.phaseStarted(TraceAnalysisPhase.READING_TRACE, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, SINGLE);
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING", "This trace ", 11);
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING", "reads one file twice.", 32);
            listener.phaseStarted(TraceAnalysisPhase.SAVING, SINGLE);
            return Optional.of(stored);
        });
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        // The whole checklist is announced before any work, so the dialog can render every step
        // greyed out rather than growing the list one line at a time.
        assertThat(sink.eventNames()).startsWith(TraceAnalysisSseStreamer.STARTED_EVENT);
        TraceAnalysisStreamEvent.Started started = (TraceAnalysisStreamEvent.Started) sink.payloads.get(0);
        assertThat(started.phases()).extracting(TraceAnalysisStreamEvent.PhaseDescriptor::phase)
                .containsExactly("READING_TRACE", "BUILDING_PROMPT", "DRAFTING", "APPLYING", "MERGING", "SAVING");
        assertThat(started.phases()).extracting(TraceAnalysisStreamEvent.PhaseDescriptor::label)
                .allSatisfy(label -> assertThat(label).isNotBlank());

        assertThat(sink.eventNames()).containsExactly(
                TraceAnalysisSseStreamer.STARTED_EVENT,
                TraceAnalysisSseStreamer.PHASE_EVENT,
                TraceAnalysisSseStreamer.PHASE_EVENT,
                TraceAnalysisSseStreamer.DELTA_EVENT,
                TraceAnalysisSseStreamer.PHASE_EVENT,
                TraceAnalysisSseStreamer.DONE_EVENT);
        assertThat(sink.payloads).last().isEqualTo(stored);
        assertThat(sink.isClosed).isTrue();
    }

    /**
     * Both fragments above landed in one delta, and it was flushed by the phase change rather than
     * by either threshold. That ordering is the point: text the model wrote during DRAFTING must not
     * arrive after the event saying DRAFTING is over, or the dialog attributes it to the next step.
     */
    @Test
    void answerTextIsCoalescedIntoOneDeltaAndFlushedBeforeTheNextPhase() {
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any())).thenAnswer(invocation -> {
            TraceAnalysisProgressListener listener = invocation.getArgument(1);
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, SINGLE);
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING", "This trace ", 11);
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING", "reads one file twice.", 32);
            listener.phaseStarted(TraceAnalysisPhase.SAVING, SINGLE);
            return Optional.of(new TraceAnalysis(
                    TRACE_ID, "llama3.1", "x", 1L, Instant.EPOCH, Instant.EPOCH, false, null, false, 0, 1, 1, null));
        });
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        List<TraceAnalysisStreamEvent.Delta> deltas = sink.payloadsOfType(TraceAnalysisStreamEvent.Delta.class);
        assertThat(deltas).singleElement().satisfies(delta -> {
            assertThat(delta.text()).isEqualTo("This trace reads one file twice.");
            assertThat(delta.characters()).isEqualTo(32);
            assertThat(delta.phase()).isEqualTo("DRAFTING");
            assertThat(delta.key()).isEqualTo("DRAFTING");
        });
        assertThat(sink.eventNames().indexOf(TraceAnalysisSseStreamer.DELTA_EVENT))
                .as("the delta is flushed before the phase event that follows it")
                .isLessThan(sink.eventNames().lastIndexOf(TraceAnalysisSseStreamer.PHASE_EVENT));
    }

    /**
     * A partitioned trace repeats DRAFTING once per window, and each occurrence has to carry a key
     * distinct from the others or a listener counting characters (or a dialog rendering a checklist)
     * cannot tell one window's draft from the next.
     */
    @Test
    void repeatedDraftingPhasesCarryDistinctKeys() {
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any())).thenAnswer(invocation -> {
            TraceAnalysisProgressListener listener = invocation.getArgument(1);
            listener.phaseStarted(TraceAnalysisPhase.READING_TRACE, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.BUILDING_PROMPT, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, new TraceAnalysisProgressListener.PhaseScope(
                    1, 2, "calls 1-40"));
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING#1", "first window", 13);
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, new TraceAnalysisProgressListener.PhaseScope(
                    2, 2, "calls 41-78"));
            listener.answerAdvanced(TraceAnalysisPhase.DRAFTING, "DRAFTING#2", "second window", 27);
            listener.phaseStarted(TraceAnalysisPhase.MERGING, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.APPLYING, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.SAVING, SINGLE);
            return Optional.of(storedAnalysis());
        });
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        List<TraceAnalysisStreamEvent.PhaseStarted> phaseEvents =
                sink.payloadsOfType(TraceAnalysisStreamEvent.PhaseStarted.class);
        List<String> draftingKeys = phaseEvents.stream()
                .filter(event -> "DRAFTING".equals(event.phase()))
                .map(TraceAnalysisStreamEvent.PhaseStarted::key)
                .toList();
        assertThat(draftingKeys).containsExactly("DRAFTING#1", "DRAFTING#2").doesNotHaveDuplicates();

        List<TraceAnalysisStreamEvent.Delta> deltas = sink.payloadsOfType(TraceAnalysisStreamEvent.Delta.class);
        assertThat(deltas).extracting(TraceAnalysisStreamEvent.Delta::key)
                .containsExactly("DRAFTING#1", "DRAFTING#2");
    }

    /**
     * The optimistic `started` list is one entry per TraceAnalysisPhase; the real plan (sent once
     * window count is known) may carry more DRAFTING entries than that, and every phase event this
     * run emits has to be describable by one of the plan's entries.
     */
    @Test
    void thePlanDescribesEveryPhaseEventTheRunEmits() {
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any())).thenAnswer(invocation -> {
            TraceAnalysisProgressListener listener = invocation.getArgument(1);
            listener.planned(List.of(
                    new TraceAnalysisProgressListener.PlannedPhase(
                            TraceAnalysisPhase.READING_TRACE, "READING_TRACE", SINGLE),
                    new TraceAnalysisProgressListener.PlannedPhase(
                            TraceAnalysisPhase.BUILDING_PROMPT, "BUILDING_PROMPT", SINGLE),
                    new TraceAnalysisProgressListener.PlannedPhase(
                            TraceAnalysisPhase.DRAFTING, "DRAFTING#1",
                            new TraceAnalysisProgressListener.PhaseScope(1, 2, "calls 1-40")),
                    new TraceAnalysisProgressListener.PlannedPhase(
                            TraceAnalysisPhase.DRAFTING, "DRAFTING#2",
                            new TraceAnalysisProgressListener.PhaseScope(2, 2, "calls 41-78")),
                    new TraceAnalysisProgressListener.PlannedPhase(TraceAnalysisPhase.MERGING, "MERGING", SINGLE),
                    new TraceAnalysisProgressListener.PlannedPhase(TraceAnalysisPhase.APPLYING, "APPLYING", SINGLE),
                    new TraceAnalysisProgressListener.PlannedPhase(TraceAnalysisPhase.SAVING, "SAVING", SINGLE)));
            listener.phaseStarted(TraceAnalysisPhase.READING_TRACE, SINGLE);
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, new TraceAnalysisProgressListener.PhaseScope(
                    1, 2, "calls 1-40"));
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING, new TraceAnalysisProgressListener.PhaseScope(
                    2, 2, "calls 41-78"));
            listener.phaseStarted(TraceAnalysisPhase.SAVING, SINGLE);
            return Optional.of(storedAnalysis());
        });
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        TraceAnalysisStreamEvent.Planned plan =
                sink.payloadsOfType(TraceAnalysisStreamEvent.Planned.class).get(0);
        List<String> plannedKeys = plan.phases().stream().map(TraceAnalysisStreamEvent.PhaseDescriptor::key).toList();
        List<String> emittedKeys = sink.payloadsOfType(TraceAnalysisStreamEvent.PhaseStarted.class).stream()
                .map(TraceAnalysisStreamEvent.PhaseStarted::key)
                .toList();
        assertThat(plannedKeys).containsAll(emittedKeys);
        assertThat(plan.phases()).hasSize(7);
    }

    /**
     * Ollama being unreachable is a 503 on the plain POST, but by the time this stream is running the
     * response is committed at 200 — so the message has to travel as an event. Losing it would leave
     * the dialog with a bare connection fault instead of the sentence written to be shown to the user.
     */
    @Test
    void anUnreachableOllamaEndsTheStreamWithItsUserFacingMessageRatherThanAnErroredClose() {
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any()))
                .thenThrow(new OllamaUnavailableException("Could not reach Ollama at http://localhost:11434 — is it running?"));
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        assertThat(sink.eventNames()).endsWith(TraceAnalysisSseStreamer.FAILED_EVENT);
        assertThat(sink.payloadsOfType(TraceAnalysisStreamEvent.Failed.class))
                .singleElement()
                .extracting(TraceAnalysisStreamEvent.Failed::message)
                .isEqualTo("Could not reach Ollama at http://localhost:11434 — is it running?");
        assertThat(sink.isClosed).isTrue();
    }

    @Test
    void anUnknownTraceFailsWithAnExplanationSinceThereIsNoStatusCodeLeftToReturn() {
        when(traceAnalysisService.regenerate(eq(TRACE_ID), any())).thenReturn(Optional.empty());
        RecordingEventSink sink = new RecordingEventSink();

        streamer().runAnalysis(TRACE_ID, sink);

        assertThat(sink.eventNames()).doesNotContain(TraceAnalysisSseStreamer.DONE_EVENT);
        assertThat(sink.payloadsOfType(TraceAnalysisStreamEvent.Failed.class))
                .singleElement()
                .extracting(TraceAnalysisStreamEvent.Failed::message)
                .asString()
                .contains("nothing to analyze");
    }

    private TraceAnalysisSseStreamer streamer() {
        return new TraceAnalysisSseStreamer(traceAnalysisService, new OllamaProperties());
    }

    private static final class RecordingEventSink implements TraceAnalysisSseStreamer.EventSink {

        private final List<String> names = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private boolean isClosed;

        @Override
        public void send(String eventName, Object payload) {
            names.add(eventName);
            payloads.add(payload);
        }

        @Override
        public void close() {
            isClosed = true;
        }

        private List<String> eventNames() {
            return names;
        }

        private <T> List<T> payloadsOfType(Class<T> payloadType) {
            return payloads.stream().filter(payloadType::isInstance).map(payloadType::cast).toList();
        }
    }
}
