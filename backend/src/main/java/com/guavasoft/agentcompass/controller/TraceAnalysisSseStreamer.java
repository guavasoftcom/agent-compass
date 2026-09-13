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

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.model.TraceAnalysis;
import com.guavasoft.agentcompass.model.TraceAnalysisPhase;
import com.guavasoft.agentcompass.model.TraceAnalysisStreamEvent;
import com.guavasoft.agentcompass.ollama.OllamaUnavailableException;
import com.guavasoft.agentcompass.service.TraceAnalysisProgressListener;
import com.guavasoft.agentcompass.service.TraceAnalysisService;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs one trace analysis on a background thread and narrates it over Server-Sent Events. The
 * transport half of {@code POST /api/traces/{traceId}/analysis/stream}; the analysis itself is
 * entirely {@link TraceAnalysisService}'s, reached through the same
 * {@link TraceAnalysisService#regenerate(String, TraceAnalysisProgressListener)} the plain POST uses.
 *
 * <p>Lives beside the controller rather than in {@code service/} deliberately: {@link SseEmitter},
 * event names and delta coalescing are HTTP shaping, which is the web layer's job — a service
 * returning an {@code SseEmitter} would be the leak. What the controller keeps is the thin-dispatch
 * shape the other endpoints have.
 *
 * <p>Two things are worth knowing about how this behaves at the edges:
 *
 * <ul>
 *   <li><b>A reader who closes the dialog does not cancel the run.</b> Once the client is gone every
 *       further write fails, so this stops narrating — but the analysis finishes and is saved, and
 *       reopening the dialog shows it. Cancelling instead would throw away a nearly-finished local
 *       inference run for a click that usually means "I'll check back later".
 *   <li><b>The emitter timeout is derived from {@code ollama.read-timeout}, not left at the
 *       container default.</b> An {@link SseEmitter} built with no timeout inherits the servlet
 *       container's async timeout — commonly 30 seconds, well inside a normal run — and would cut
 *       the stream mid-answer. Because the timer starts the instant the emitter is handed back —
 *       before the analysis executor has necessarily picked the task up — the budget also has to
 *       cover the worst case queuing wait behind {@link #MAXIMUM_CONCURRENT_ANALYSES} busy workers,
 *       not just one run's own duration; see {@link #MAXIMUM_QUEUED_ANALYSES}.
 *   <li><b>A queue full of already-waiting requests is rejected immediately, not queued
 *       unboundedly.</b> {@link #MAXIMUM_QUEUED_ANALYSES} bounds how many requests can wait behind
 *       the busy workers; past that, a new request gets an immediate {@code failed} event rather
 *       than an SSE connection that silently times out once its budget above runs out.
 * </ul>
 */
@Slf4j
@Component
public class TraceAnalysisSseStreamer {

    static final String STARTED_EVENT = "started";
    static final String PLAN_EVENT = "plan";
    static final String PHASE_EVENT = "phase";
    static final String DELTA_EVENT = "delta";
    static final String DONE_EVENT = "done";
    static final String FAILED_EVENT = "failed";

    /**
     * Coalescing thresholds for {@code delta}. A 7B model emits a token every few milliseconds, and
     * one SSE frame each would be thousands of writes and thousands of React renders for a page that
     * only needs to look alive. Flushing on whichever of these comes first keeps the draft visibly
     * moving (a few updates a second) at a small fraction of the frames.
     */
    static final int DELTA_FLUSH_CHARACTERS = 120;
    static final Duration DELTA_FLUSH_INTERVAL = Duration.ofMillis(200);

    /** Headroom over {@code ollama.read-timeout} for the gather, render and save around the call. */
    private static final Duration EMITTER_TIMEOUT_MARGIN = Duration.ofSeconds(30);

    /**
     * Concurrent analyses. Bounded on purpose: these are local inference runs, and letting a handful
     * of open tabs start one each would thrash the Ollama host rather than finish any of them sooner.
     */
    private static final int MAXIMUM_CONCURRENT_ANALYSES = 2;

    /**
     * Requests allowed to wait behind {@link #MAXIMUM_CONCURRENT_ANALYSES} busy workers before a new
     * one is refused outright. Bounded for the same reason the worker count is: an unbounded queue
     * lets a burst of clicks pile up behind an emitter timeout sized for one run, so a request queued
     * deep enough times out before its own analysis even starts while still finishing and saving in
     * the background — see {@link #rejectQueuedAnalysis}.
     */
    private static final int MAXIMUM_QUEUED_ANALYSES = 4;

    /**
     * Worst-case number of full {@link #MAXIMUM_CONCURRENT_ANALYSES}-wide batches a request at the
     * back of a full queue waits through before it is even picked up, rounded up. Sizes {@link
     * #emitterTimeoutMillis} so the emitter's timer — which starts the instant the request is
     * accepted, not when the worker starts it — has enough budget left to also cover its own run
     * once dequeued.
     */
    private static final int MAXIMUM_QUEUE_WAIT_ROUNDS =
            (MAXIMUM_QUEUED_ANALYSES + MAXIMUM_CONCURRENT_ANALYSES - 1) / MAXIMUM_CONCURRENT_ANALYSES;

    private final TraceAnalysisService traceAnalysisService;
    private final ExecutorService analysisExecutor;
    private final long emitterTimeoutMillis;

    public TraceAnalysisSseStreamer(TraceAnalysisService traceAnalysisService, OllamaProperties ollamaProperties) {
        this.traceAnalysisService = traceAnalysisService;
        this.emitterTimeoutMillis = ollamaProperties.getReadTimeout()
                .multipliedBy(1 + MAXIMUM_QUEUE_WAIT_ROUNDS)
                .plus(EMITTER_TIMEOUT_MARGIN)
                .toMillis();
        this.analysisExecutor = new ThreadPoolExecutor(
                MAXIMUM_CONCURRENT_ANALYSES,
                MAXIMUM_CONCURRENT_ANALYSES,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAXIMUM_QUEUED_ANALYSES),
                runnable -> {
                    Thread analysisThread = new Thread(runnable, "trace-analysis");
                    analysisThread.setDaemon(true);
                    return analysisThread;
                },
                this::rejectQueuedAnalysis);
    }

    /**
     * Runs when the queue is already at {@link #MAXIMUM_QUEUED_ANALYSES}. Answers the client
     * immediately with a {@code failed} event instead of leaving the request queued indefinitely
     * behind an emitter timeout that was never sized for an unbounded wait.
     */
    private void rejectQueuedAnalysis(Runnable runnable, ThreadPoolExecutor executor) {
        if (runnable instanceof QueuedAnalysis queuedAnalysis) {
            queuedAnalysis.sink.send(FAILED_EVENT, new TraceAnalysisStreamEvent.Failed(
                    "Too many analyses are already running or queued — try again in a moment."));
            queuedAnalysis.sink.close();
        }
    }

    @PreDestroy
    void shutDownAnalysisExecutor() {
        analysisExecutor.shutdownNow();
    }

    /** Starts the run and returns immediately with the emitter the events will arrive on. */
    public SseEmitter stream(String traceId) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        SseEventSink sink = new SseEventSink(emitter);
        analysisExecutor.execute(new QueuedAnalysis(traceId, sink));
        return emitter;
    }

    /**
     * Wraps one queued run so {@link #rejectQueuedAnalysis} can answer the client directly when the
     * queue is full, rather than the plain lambda a full queue's {@code RejectedExecutionHandler}
     * would have no way to read a trace id or sink back out of.
     */
    private final class QueuedAnalysis implements Runnable {

        private final String traceId;
        private final EventSink sink;

        private QueuedAnalysis(String traceId, EventSink sink) {
            this.traceId = traceId;
            this.sink = sink;
        }

        @Override
        public void run() {
            runAnalysis(traceId, sink);
        }
    }

    /**
     * Where a run's events are decided, separated from the emitter so a test can drive it with a
     * recording {@link EventSink} instead of a live servlet response — the event <i>sequence</i>
     * (and the coalescing that shapes it) is the part worth pinning, and it needs no HTTP to check.
     */
    void runAnalysis(String traceId, EventSink sink) {
        DeltaBuffer deltas = new DeltaBuffer(sink);
        try {
            sink.send(STARTED_EVENT, new TraceAnalysisStreamEvent.Started(describePhases()));
            Optional<TraceAnalysis> analysis = traceAnalysisService.regenerate(traceId, progressListener(sink, deltas));
            deltas.flush();
            if (analysis.isEmpty()) {
                // The trace-does-not-exist case the plain POST answers with a 404. There is no status
                // code left here, so it arrives as an ordinary failure with the reason spelled out.
                sink.send(FAILED_EVENT, new TraceAnalysisStreamEvent.Failed(
                        "No spans exist for trace " + traceId + " — there is nothing to analyze."));
            } else {
                sink.send(DONE_EVENT, analysis.get());
            }
        } catch (OllamaUnavailableException exception) {
            sink.send(FAILED_EVENT, new TraceAnalysisStreamEvent.Failed(exception.getMessage()));
        } catch (RuntimeException exception) {
            log.warn("Trace analysis failed for trace {}", traceId, exception);
            sink.send(FAILED_EVENT, new TraceAnalysisStreamEvent.Failed(
                    "The analysis run failed: " + exception.getMessage()));
        }
        sink.close();
    }

    // The optimistic, one-entry-per-phase list sent as `started`, before window count is known --
    // see TraceAnalysisStreamEvent.Started's own javadoc. Every phase is reported as its own single
    // occurrence here; the real, expanded list follows as `plan`.
    private static List<TraceAnalysisStreamEvent.PhaseDescriptor> describePhases() {
        return Arrays.stream(TraceAnalysisPhase.values())
                .map(phase -> new TraceAnalysisStreamEvent.PhaseDescriptor(
                        phase.name(), phase.getLabel(), phase.name(), 1, 1, null))
                .toList();
    }

    private static TraceAnalysisProgressListener progressListener(EventSink sink, DeltaBuffer deltas) {
        return new TraceAnalysisProgressListener() {

            @Override
            public void planned(List<PlannedPhase> plan) {
                sink.send(PLAN_EVENT, new TraceAnalysisStreamEvent.Planned(plan.stream()
                        .map(entry -> new TraceAnalysisStreamEvent.PhaseDescriptor(
                                entry.phase().name(), entry.phase().getLabel(), entry.key(),
                                entry.scope().stepNumber(), entry.scope().stepCount(), entry.scope().detail()))
                        .toList()));
            }

            @Override
            public void phaseStarted(TraceAnalysisPhase phase, PhaseScope scope) {
                // Whatever the previous occurrence produced belongs before the one that follows it.
                deltas.flush();
                sink.send(PHASE_EVENT, new TraceAnalysisStreamEvent.PhaseStarted(
                        phase.name(), keyOf(phase, scope), scope.stepNumber(), scope.stepCount(), scope.detail()));
            }

            @Override
            public void answerAdvanced(TraceAnalysisPhase phase, String key, String appendedText, int totalCharacters) {
                deltas.append(phase, key, appendedText, totalCharacters);
            }
        };
    }

    private static String keyOf(TraceAnalysisPhase phase, TraceAnalysisProgressListener.PhaseScope scope) {
        return scope.stepCount() <= 1 ? phase.name() : phase.name() + "#" + scope.stepNumber();
    }

    /** Where a run's events go. One implementation in production; a recorder in tests. */
    interface EventSink {

        void send(String eventName, Object payload);

        void close();
    }

    /**
     * Batches the model's output into {@code delta} events on the thresholds above.
     *
     * <p>Called only from the single thread running its analysis, so none of this needs to be
     * thread-safe.
     */
    private static final class DeltaBuffer {

        private final EventSink sink;
        private final StringBuilder pendingText = new StringBuilder();
        private int latestTotalCharacters;
        private long lastFlushedAtMillis = System.currentTimeMillis();
        private boolean hasPendingDelta;
        private TraceAnalysisPhase pendingPhase;
        private String pendingKey;

        private DeltaBuffer(EventSink sink) {
            this.sink = sink;
        }

        private void append(TraceAnalysisPhase phase, String key, String appendedText, int totalCharacters) {
            // A key change mid-buffer means a new DRAFTING window started -- flush whatever the
            // previous one had pending under its own key before buffering the new one's text.
            if (hasPendingDelta && !key.equals(pendingKey)) {
                flush();
            }
            pendingPhase = phase;
            pendingKey = key;
            pendingText.append(appendedText);
            latestTotalCharacters = totalCharacters;
            hasPendingDelta = true;
            boolean bufferIsFull = pendingText.length() >= DELTA_FLUSH_CHARACTERS;
            boolean intervalHasPassed =
                    System.currentTimeMillis() - lastFlushedAtMillis >= DELTA_FLUSH_INTERVAL.toMillis();
            if (bufferIsFull || intervalHasPassed) {
                flush();
            }
        }

        private void flush() {
            if (!hasPendingDelta) {
                return;
            }
            sink.send(DELTA_EVENT, new TraceAnalysisStreamEvent.Delta(
                    pendingPhase.name(), pendingKey, pendingText.toString(), latestTotalCharacters));
            pendingText.setLength(0);
            hasPendingDelta = false;
            lastFlushedAtMillis = System.currentTimeMillis();
        }
    }

    /**
     * Writes events to a live {@link SseEmitter}, and goes quiet for good once the reader is gone.
     *
     * <p>{@code clientIsGone} is {@code volatile} because the emitter's completion, timeout and
     * error callbacks fire on container threads while the analysis thread is the one writing.
     */
    private static final class SseEventSink implements EventSink {

        private final SseEmitter emitter;
        private volatile boolean clientIsGone;

        private SseEventSink(SseEmitter emitter) {
            this.emitter = emitter;
            emitter.onCompletion(() -> clientIsGone = true);
            emitter.onTimeout(() -> clientIsGone = true);
            emitter.onError(throwable -> clientIsGone = true);
        }

        @Override
        public void send(String eventName, Object payload) {
            if (clientIsGone) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException exception) {
                // The reader closed the dialog or navigated away. Their run keeps going and still
                // gets saved; there is just no longer anywhere to narrate it to.
                clientIsGone = true;
            }
        }

        @Override
        public void close() {
            if (!clientIsGone) {
                emitter.complete();
            }
        }
    }
}
