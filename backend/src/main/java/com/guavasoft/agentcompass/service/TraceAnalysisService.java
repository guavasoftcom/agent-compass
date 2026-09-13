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

import com.samskivert.mustache.Mustache;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.TraceAnalysisEntity;
import com.guavasoft.agentcompass.mapper.TraceAnalysisMapper;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceAnalysis;
import com.guavasoft.agentcompass.model.TraceAnalysisPhase;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.ollama.OllamaClient;
import com.guavasoft.agentcompass.ollama.OllamaUnavailableException;
import com.guavasoft.agentcompass.repository.TraceAnalysisRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * On-demand "Analyze trace via local Ollama" — gathers a trace's spans and logs, asks a local
 * Ollama model to judge the causal link between the user's prompt wording and the tool-call shape
 * that followed, and persists the result (latest-only; a regenerate overwrites it).
 *
 * <p><b>⚠ Deliberately carries NO class-level {@code @Transactional(readOnly = true)}</b>, unlike
 * every sibling service in this package. The Ollama call in {@link #regenerate(String)} can take up
 * to {@code ollama.read-timeout} (120s by default) to return, and copying the sibling convention
 * here would hold a pooled Postgres connection open, idle, for the whole call — and then fail
 * outright when the subsequent {@code save()} ran against a read-only transaction. Instead: the two
 * gather calls ({@link TraceService#spansForTrace}, {@link LogService#logsForTrace}) use their own
 * read-only transactions, {@link OllamaClient#generate(String)} runs with NO transaction open at
 * all, and only the final upsert is wrapped in a short {@link Transactional}. Do not "fix" this back
 * to match the neighbouring services.
 */
@Slf4j
@Service
public class TraceAnalysisService {

    // Duplicate-answer collapsing -- see collapseDuplicatedBlock. The anchor line has to be long
    // enough that it identifies a section rather than matching "- " or a bare "**"; the block has
    // to be substantial enough that a short intentional repetition is never mistaken for a loop.
    private static final int MINIMUM_ANCHOR_LINE_LENGTH = 8;
    private static final int MINIMUM_DUPLICATED_BLOCK_LENGTH = 200;
    private static final int MAX_DUPLICATE_COLLAPSE_PASSES = 3;

    // The three Apply-this prefixes, and the literal the contract uses for "no advice here" -- see
    // normalizeApplyThisLines. Kept in step with TraceAnalysisAnswer's own copies (the structured
    // path) and with AnalyzeTraceDialogView's parseApplyThis (the reader's side): one contract,
    // three implementations, because each sees the answer at a different stage.
    private static final List<String> APPLY_THIS_PREFIXES =
            List.of("Instruction rule:", "Tool swap:", "Better wording:");
    private static final String NONE = "None";
    private static final String TRAILING_PUNCTUATION = ".!,;:";

    // Written by this service rather than asked of the model -- see joinProseAnswer. Must match
    // TraceAnalysisAnswer's own copy and AnalyzeTraceDialogView's parseApplyThis heading regex.
    private static final String APPLY_THIS_HEADING = "**Apply this**";

    // The machine-read verdict line the prose findings contract ends with -- see splitRequestKind.
    private static final String REQUEST_KIND_PREFIX = "Request kind:";

    // What the second call would have returned for a review with no faults -- see hasFaults. Written
    // here instead of being generated, since "None" three times is the only answer that document can
    // legally have when there is nothing to act on.
    private static final String NO_APPLY_THIS_LINES =
            "Instruction rule: None\nTool swap: None\nBetter wording: None";
    private static final String NO_APPLY_THIS_JSON = """
            {"instructionRuleTarget": "None", "instructionRule": "None",
             "toolSwap": "None", "betterWording": "None"}""";

    // How the prose findings contract renders a fault, and the heading it renders them under -- see
    // hasFaults for why the prose side is parsed conservatively rather than trusted.
    private static final String WENT_WRONG_HEADING = "**What went wrong**";
    private static final String FAULT_BULLET_PREFIX = "- **";
    private static final String SECTION_HEADING_PREFIX = "**";

    private final TraceService traceService;
    private final LogService logService;
    private final TraceExplorerService traceExplorerService;
    private final OllamaClient ollamaClient;
    private final OllamaSettingsService ollamaSettingsService;
    private final TraceAnalysisRepository traceAnalysisRepository;
    private final TraceAnalysisMapper traceAnalysisMapper;
    private final TraceAnalysisPromptBuilder promptBuilder;
    private final OllamaProperties ollamaProperties;
    private final ObjectMapper objectMapper;

    public TraceAnalysisService(
            TraceService traceService,
            LogService logService,
            TraceExplorerService traceExplorerService,
            OllamaClient ollamaClient,
            OllamaSettingsService ollamaSettingsService,
            OllamaProperties ollamaProperties,
            TuningProperties tuningProperties,
            SubagentCostAttributor subagentCostAttributor,
            TraceAnalysisRepository traceAnalysisRepository,
            TraceAnalysisMapper traceAnalysisMapper,
            Mustache.Compiler mustacheCompiler,
            ObjectMapper objectMapper,
            @Value("classpath:templates/trace-analysis-prompt.mustache") Resource templateResource,
            @Value("classpath:templates/trace-analysis-apply-this.mustache") Resource applyThisTemplateResource)
            throws IOException {
        this.ollamaProperties = ollamaProperties;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
        this.logService = logService;
        this.traceExplorerService = traceExplorerService;
        this.ollamaClient = ollamaClient;
        this.ollamaSettingsService = ollamaSettingsService;
        this.traceAnalysisRepository = traceAnalysisRepository;
        this.traceAnalysisMapper = traceAnalysisMapper;
        this.promptBuilder = new TraceAnalysisPromptBuilder(
                mustacheCompiler.compile(templateResource.getContentAsString(StandardCharsets.UTF_8)),
                mustacheCompiler.compile(applyThisTemplateResource.getContentAsString(StandardCharsets.UTF_8)),
                tuningProperties,
                ollamaProperties,
                subagentCostAttributor);
    }

    /**
     * Plain lookup — no Ollama call. Backs {@code GET /api/traces/{traceId}/analysis}. Also
     * checks the trace's CURRENT latest span activity against the snapshot the stored analysis
     * was generated against, so a reader coming back to a trace that kept running (or later
     * picked up a background/subagent completion) sees {@link TraceAnalysis#outdated()} rather
     * than a silently stale read. This is a single cheap {@code MAX(end_timestamp)} probe
     * ({@link TraceService#latestSpanEndTimestamp}), not the full span/log gather —
     * {@code getStored} must stay cheap since it fires on every dialog open.
     */
    @Transactional(readOnly = true)
    public Optional<TraceAnalysis> getStored(String traceId) {
        return traceAnalysisRepository.findById(traceId)
                .map(entity -> toTraceAnalysis(entity, traceService.latestSpanEndTimestamp(traceId)));
    }

    // currentLatestSpanEndTimestamp is empty only if the trace's spans vanished between the
    // stored-analysis read and this probe (e.g. a concurrent purge) -- treated as "can't tell,
    // don't alarm the user" rather than outdated.
    private TraceAnalysis toTraceAnalysis(
            TraceAnalysisEntity entity, Optional<Instant> currentLatestSpanEndTimestamp) {
        boolean outdated = currentLatestSpanEndTimestamp
                .map(current -> current.isAfter(entity.getLastSpanEndTimestamp()))
                .orElse(false);
        return traceAnalysisMapper.toTraceAnalysis(entity).withOutdated(outdated);
    }

    /**
     * Regenerates the stored analysis for a trace. Empty when the trace itself does not exist (no
     * wasted LLM call on a bad id); an {@link OllamaUnavailableException} from the model call
     * propagates uncaught, so nothing is written for a failed run.
     */
    public Optional<TraceAnalysis> regenerate(String traceId) {
        return regenerate(traceId, TraceAnalysisProgressListener.NO_OP);
    }

    /**
     * As {@link #regenerate(String)}, but reports each phase to {@code listener} and streams the
     * model's answer to it as it is written. Backs
     * {@code POST /api/traces/{traceId}/analysis/stream}.
     *
     * <p>Both entry points run this same method rather than the streaming one being a parallel
     * implementation, so the plain POST cannot quietly diverge from the one the dialog uses.
     *
     * <p>Gated on {@link OllamaSettingsService#effectiveSettings()}'s {@code enabled} flag before
     * anything else runs — the Settings page's Ollama tab offers an Enabled/Disabled toggle, and
     * that switch has to hold here rather than only in the frontend, or a client that skipped the
     * UI (or a stale tab) could still trigger a call to a deliberately-disabled Ollama. Reuses
     * {@link OllamaUnavailableException} rather than a new exception type, since it already maps to
     * a 503 whose message is shown to the user verbatim.
     */
    public Optional<TraceAnalysis> regenerate(String traceId, TraceAnalysisProgressListener listener) {
        if (!ollamaSettingsService.effectiveSettings().enabled()) {
            throw new OllamaUnavailableException(
                    "Ollama is disabled in Settings — turn it back on to analyze traces.");
        }
        listener.phaseStarted(TraceAnalysisPhase.READING_TRACE, TraceAnalysisProgressListener.PhaseScope.single());
        Optional<PreparedPrompt> prepared = preparePrompt(traceId);
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        listener.planned(planFor(prepared.get()));
        return Optional.of(generateAndSave(traceId, prepared.get(), listener));
    }

    /**
     * The real, expanded run plan — sent as the {@code plan} SSE event once window count is known,
     * replacing the optimistic one-entry-per-phase list {@code Started} carries. {@code
     * TraceAnalysisPhase#DRAFTING} repeats once per window; every other phase occurs once.
     */
    private static List<TraceAnalysisProgressListener.PlannedPhase> planFor(PreparedPrompt prepared) {
        List<TraceAnalysisProgressListener.PlannedPhase> plan = new ArrayList<>();
        plan.add(singlePlannedPhase(TraceAnalysisPhase.READING_TRACE));
        plan.add(singlePlannedPhase(TraceAnalysisPhase.BUILDING_PROMPT));
        int windowCount = prepared.windows().size();
        for (int index = 0; index < windowCount; index++) {
            TraceAnalysisPromptBuilder.PromptWindow window = prepared.windows().get(index);
            String detail = windowCount == 1 ? null : "calls " + window.firstCallNumber() + "-" + window.lastCallNumber();
            plan.add(new TraceAnalysisProgressListener.PlannedPhase(
                    TraceAnalysisPhase.DRAFTING, draftingKey(index, windowCount),
                    new TraceAnalysisProgressListener.PhaseScope(index + 1, windowCount, detail)));
        }
        if (windowCount > 1) {
            plan.add(singlePlannedPhase(TraceAnalysisPhase.MERGING));
        }
        plan.add(singlePlannedPhase(TraceAnalysisPhase.APPLYING));
        plan.add(singlePlannedPhase(TraceAnalysisPhase.SAVING));
        return plan;
    }

    private static TraceAnalysisProgressListener.PlannedPhase singlePlannedPhase(TraceAnalysisPhase phase) {
        return new TraceAnalysisProgressListener.PlannedPhase(
                phase, phase.name(), TraceAnalysisProgressListener.PhaseScope.single());
    }

    private static String draftingKey(int windowIndex, int windowCount) {
        return windowCount <= 1
                ? TraceAnalysisPhase.DRAFTING.name()
                : TraceAnalysisPhase.DRAFTING.name() + "#" + (windowIndex + 1);
    }

    /**
     * Everything {@link #regenerate} needs before it can call Ollama, and nothing that calls it.
     *
     * @param windows the trace's prompt, as one or more consecutive, non-overlapping review windows
     *     — see {@link TraceAnalysisPromptBuilder#partitionToBudget}. Never empty; a trace whose
     *     timeline fits whole is exactly one window.
     * @param timelineCallCount the trace's total call count, unaffected by windowing — stored
     *     alongside the analysis so the dialog can report how much of the trace this review covers.
     * @param logRecords kept alongside the rendered text because the answer schema's rule-target
     *     enum is resolved from the same logs the prompt's target list was built from — two lists
     *     built from one read, so they cannot disagree
     * @param userPrompt the request the review will judge, or null when this trace carries no
     *     wording anyone authored. Read off the builder rather than the logs directly so it is the
     *     same text the prompt's "User prompt (full text)" section shows the model — the stored
     *     "before" can then never be a request the review was not actually given
     * @param wordingAlreadySettled read off {@link TraceAnalysisPromptBuilder.PromptResult} and
     *     passed to {@link TraceAnalysisAnswer#applyThisJsonSchema} on the structured path, so a
     *     trace whose request already named its target correctly cannot come back with a
     *     {@code betterWording} that drops it — see that method's javadoc.
     * @param hasPositiveObservations read off the same {@link TraceAnalysisPromptBuilder.PromptResult}
     *     field and passed to {@link TraceAnalysisAnswer#findingsJsonSchema(boolean, boolean)} on the
     *     structured path, so a trace that verified nothing worth praising cannot come back with a
     *     "What went well" section at all — the fabrication trace
     *     {@code adae1753270dd3088520435ae7f8af94} produced. See that method's javadoc.
     * @param applyThisInputs everything the second model call needs, computed during the first
     *     prompt's build so both prompts derive from one read of the trace — see
     *     {@link TraceAnalysisPromptBuilder.ApplyThisInputs}
     * @param summary the code-composed "what happened" recap — see
     *     {@link TraceAnalysisPromptBuilder#buildTraceSummary}. Never sent to Ollama; stored
     *     alongside the model's answer purely so the dialog can show it.
     * @param failedToolCalls every tool call this trace's spans/logs show failed — see
     *     {@link TraceAnalysisPromptBuilder.PromptResult#failedToolCalls}. Checked against the
     *     model's own findings by {@link #ensureFailedToolCallsReported}.
     */
    record PreparedPrompt(
            List<TraceAnalysisPromptBuilder.PromptWindow> windows,
            int timelineCallCount,
            List<LogRecord> logRecords,
            Instant lastSpanEndTimestamp,
            String userPrompt,
            boolean wordingAlreadySettled,
            boolean hasPositiveObservations,
            TraceAnalysisPromptBuilder.ApplyThisInputs applyThisInputs,
            String summary,
            List<TraceAnalysisPromptBuilder.UnrecoveredFailure> failedToolCalls) {
    }

    /**
     * Gathers a trace and renders its prompt, stopping short of the model call. Empty when the
     * trace does not exist.
     *
     * <p>Package-visible as its own step so {@code TraceAnalysisRegressionHarness} can read the
     * prompt a run was actually given — the timeline in it is the only correct oracle for "does
     * this answer cite a call that exists, and call it what it is". Reconstructing that numbering
     * in the harness instead would be a second implementation of it, free to drift from the one
     * the model was shown.
     */
    Optional<PreparedPrompt> preparePrompt(String traceId) {
        Optional<TraceSummary> traceSummary = traceExplorerService.traceSummary(traceId);
        if (traceSummary.isEmpty()) {
            return Optional.empty();
        }

        List<Span> spans = traceService.spansForTrace(traceId);
        List<LogRecord> logRecords = logService.logsForTrace(traceId);
        // The turn this one is answering. A follow-up request read on its own ("yes implement those
        // two") looks like under-specified wording; read against the message that ended "Want me to
        // implement those two?" it is exact. Null is a normal outcome -- the trace may open a
        // session, or predate assistant-response logging -- and simply drops that section.
        LogRecord precedingAssistantResponse = logService
                .lastAssistantResponseBeforeTrace(
                        traceSummary.get().getSessionId(), traceSummary.get().getStartTimestamp(), traceId)
                .orElse(null);
        // A <task-notification> trace is a continuation: the work it reports on was dispatched in an
        // earlier trace, so the call that started it is not among this trace's own logs and has to
        // be fetched by the id the envelope quotes back. Null whenever the trace was not woken by
        // one, which is the common case.
        LogRecord dispatchingToolCall = logService
                .dispatchingToolCall(
                        traceSummary.get().getSessionId(),
                        traceSummary.get().getStartTimestamp(),
                        promptBuilder.backgroundTaskToolUseId(logRecords))
                .orElse(null);
        TraceAnalysisPromptBuilder.PromptResult prompt = promptBuilder.build(
                traceSummary.get(), spans, logRecords, precedingAssistantResponse, dispatchingToolCall);
        // Non-null: traceSummary is only present when the trace has at least one span, so its
        // aggregate MAX(end_timestamp) is never null. This is the snapshot regenerate() analyzes
        // "through" — getStored() later compares the CURRENT latest span timestamp against it.
        Instant lastSpanEndTimestamp = traceSummary.get().getEndTimestamp();
        return Optional.of(new PreparedPrompt(
                prompt.windows(),
                prompt.timelineCallCount(),
                logRecords,
                lastSpanEndTimestamp,
                promptBuilder.judgedPromptText(logRecords),
                prompt.wordingAlreadySettled(),
                prompt.hasPositiveObservations(),
                prompt.applyThisInputs(),
                prompt.summary(),
                prompt.failedToolCalls()));
    }

    /**
     * Runs the review as <b>two</b> model calls and stores the joined result.
     *
     * <p>The first writes the findings; the second is handed only those findings and distils them
     * into the three "Apply this" lines. Splitting them is a response to where this feature measurably
     * fails: every wording/rule defect found so far (fabricated advice on a well-aimed request, the
     * template's own placeholder echoed onto the line, a dropped rule target, advice that handed back
     * what the trace had just discovered) landed in that three-line block, while the findings above it
     * read correctly — and that block had grown to ~3.8k characters of rules competing for a small
     * model's attention against the whole trace. The second prompt carries no timeline, no overview
     * and no observations, so the model writing those lines is looking at the review and nothing else.
     *
     * <p><b>Both calls are timed as one run.</b> {@code generationDurationMs} is what the dialog shows
     * a reader as "how long this took", and they waited through both.
     */
    /**
     * The second call's prompt for an already-prepared trace — what {@code generateAndSave} sends
     * after the findings come back.
     *
     * <p>Package-visible for {@code TraceAnalysisRegressionHarness} alone, and for the same reason
     * {@link #preparePrompt} is: the scorer measures an answer against the prompts the model was
     * actually shown, and since the split there are two of them. The rule targets and the wording
     * rules live only in this one, so scoring against the findings prompt alone would report every
     * correct rule target as off-list.
     */
    String applyThisPromptFor(PreparedPrompt prepared, String findings) {
        return promptBuilder.buildApplyThisPrompt(findings, prepared.applyThisInputs());
    }

    /**
     * Runs the review across every window in {@code prepared.windows()} (one full {@code DRAFTING}
     * model call per window, see {@code TraceAnalysisPromptBuilder#partitionToBudget}), merges what
     * they come back with in code ({@link TraceAnalysisFindingsMerge}), and then runs the unchanged
     * apply-this call against the merged result exactly as it would run against a single window's
     * own findings — that call needs no windowing awareness of its own, since it is handed one
     * findings document either way.
     *
     * <p>The whole run — every window's draft plus the apply-this call — is timed as one span and
     * shares one running answer-character counter ({@code answerCharacters}), which is threaded
     * through every {@link #generate} call rather than allocated fresh per call: it must only ever
     * increase for the run's lifetime, the same guarantee a single-window trace already gave for
     * free before windowing existed.
     */
    private TraceAnalysis generateAndSave(String traceId, PreparedPrompt prepared, TraceAnalysisProgressListener listener) {
        listener.phaseStarted(TraceAnalysisPhase.BUILDING_PROMPT, TraceAnalysisProgressListener.PhaseScope.single());
        // Resolved fresh on every call, not cached on this service, so a Settings-page edit takes
        // effect on the very next "Regenerate" without a restart.
        EffectiveOllamaSettings ollamaSettings = ollamaSettingsService.effectiveSettings();
        boolean structured = ollamaProperties.isStructuredOutput();
        int windowCount = prepared.windows().size();
        boolean partitioned = windowCount > 1;

        long startedAtMillis = System.currentTimeMillis();
        int[] answerCharacters = {0};

        List<TraceAnalysisAnswer.Findings> structuredFindingsPerWindow = new ArrayList<>();
        List<String> proseFindingsPerWindow = new ArrayList<>();
        for (int index = 0; index < windowCount; index++) {
            TraceAnalysisPromptBuilder.PromptWindow window = prepared.windows().get(index);
            String detail = partitioned ? "calls " + window.firstCallNumber() + "-" + window.lastCallNumber() : null;
            listener.phaseStarted(TraceAnalysisPhase.DRAFTING,
                    new TraceAnalysisProgressListener.PhaseScope(index + 1, windowCount, detail));
            String key = draftingKey(index, windowCount);
            // Null schema on the prose path is what tells OllamaClient not to send a `format` at all.
            String rawFindings = generate(
                    window.text(),
                    structured
                            ? TraceAnalysisAnswer.findingsJsonSchema(
                                    prepared.hasPositiveObservations(), window.judgesRequest())
                            : null,
                    ollamaSettings, listener, TraceAnalysisPhase.DRAFTING, key, answerCharacters);
            if (structured) {
                // Runs PER WINDOW, against that window's own text, before merge -- checking against
                // the concatenation of every window would license a sameness claim from markers the
                // model never actually read in the window it was writing from. Sameness runs first so
                // a fault it already removed is not logged a second time by the citation check.
                TraceAnalysisAnswer.Findings windowFindings =
                        dropUnsupportedSamenessFaults(traceId, parseFindings(rawFindings), window.text());
                structuredFindingsPerWindow.add(dropMiscitedCallFaults(traceId, windowFindings, window.text()));
            } else {
                proseFindingsPerWindow.add(rawFindings);
            }
        }

        if (partitioned) {
            listener.phaseStarted(TraceAnalysisPhase.MERGING, TraceAnalysisProgressListener.PhaseScope.single());
        }
        TraceAnalysisAnswer.Findings parsedFindings = null;
        String findings;
        String requestKind;
        if (structured) {
            TraceAnalysisAnswer.Findings merged = TraceAnalysisFindingsMerge.merge(structuredFindingsPerWindow);
            // Runs ONCE, on the merged result -- more important under windowing than it ever was for
            // a single window, since a failure can now lose its own window's slot competition with
            // no other window able to rescue it.
            parsedFindings = ensureFailedToolCallsReported(traceId, merged, prepared.failedToolCalls());
            findings = parsedFindings.toMarkdown();
            requestKind = parsedFindings.requestKind();
        } else {
            RequestKindSplit mergedProse = mergeProseFindings(proseFindingsPerWindow);
            findings = mergedProse.findings();
            requestKind = mergedProse.requestKind();
        }

        // The first call(s) read the request and said what kind it was; this is where that verdict is
        // enforced rather than left for the second call to reach again on its own. It did reach it
        // again, and wrongly: on dfe4ea1da356f008ae46b2790736e223 the findings correctly identified
        // an open question and the wording line still told the reader to stop asking open questions.
        boolean wordingAlreadySettled = prepared.wordingAlreadySettled() || requestWasQuestion(requestKind);

        listener.phaseStarted(TraceAnalysisPhase.APPLYING, TraceAnalysisProgressListener.PhaseScope.single());
        // A review with no faults has nothing to apply: all three lines are None by definition, so
        // the second call would spend a whole inference pass being told so. Worth skipping rather
        // than optimising away as trivial -- the median trace here is 9 calls, clean traces are the
        // common case, and this is the population where a second pass is least justified.
        String applyThis;
        if (hasFaults(structured, parsedFindings, findings)) {
            Map<String, Object> applyThisSchema = structured
                    // Read off the prompt's own resolved list, never recomputed: the permissions
                    // target is gated on an observation only the builder's own pass knows about, so
                    // a second computation here could offer the model an enum the prompt never named.
                    ? TraceAnalysisAnswer.applyThisJsonSchema(
                            prepared.applyThisInputs().ruleTargets(), wordingAlreadySettled)
                    : null;
            applyThis = generate(
                    promptBuilder.buildApplyThisPrompt(
                            findings, prepared.applyThisInputs().withWordingSettled(wordingAlreadySettled)),
                    applyThisSchema,
                    ollamaSettings,
                    listener, TraceAnalysisPhase.APPLYING, TraceAnalysisPhase.APPLYING.name(), answerCharacters);
        } else {
            applyThis = structured ? NO_APPLY_THIS_JSON : NO_APPLY_THIS_LINES;
        }
        long generationDurationMs = System.currentTimeMillis() - startedAtMillis;

        listener.phaseStarted(TraceAnalysisPhase.SAVING, TraceAnalysisProgressListener.PhaseScope.single());
        return save(
                traceId,
                ollamaSettings.model(),
                structured
                        ? renderStructuredAnswer(
                                parsedFindings, applyThis, prepared.applyThisInputs().settledToolSwap())
                        : joinProseAnswer(findings, applyThis),
                generationDurationMs,
                prepared);
    }

    /**
     * The prose path's merge: lossier than the structured path's {@link TraceAnalysisFindingsMerge},
     * since prose has no per-fault citation set to dedup on. Splits each window's {@code Request
     * kind:} line off first (the first window's kind wins — it is the only one whose request-quality
     * section rendered against the real prompt text), concatenates the remaining findings text, and
     * runs the existing byte-identical-block collapse over the result — the same collapse a single
     * window's own doubled answer already goes through.
     */
    private static RequestKindSplit mergeProseFindings(List<String> perWindowRawFindings) {
        if (perWindowRawFindings.size() == 1) {
            return splitRequestKind(perWindowRawFindings.get(0));
        }
        StringBuilder concatenated = new StringBuilder();
        String requestKind = null;
        for (String rawFindings : perWindowRawFindings) {
            RequestKindSplit split = splitRequestKind(rawFindings);
            if (requestKind == null) {
                requestKind = split.requestKind();
            }
            if (!concatenated.isEmpty()) {
                concatenated.append("\n\n");
            }
            concatenated.append(split.findings());
        }
        return new RequestKindSplit(collapseDuplicatedBlock(concatenated.toString()), requestKind);
    }

    /**
     * Whether the first call classified the request as a <b>question</b> — one asking for gaps,
     * options or an opinion rather than naming work to do.
     *
     * <p>When it did, wording advice is suppressed for the same reason a directed start suppresses
     * it: there is no wording fault to report. A reader who asks "is anything missing in X" does not
     * know the answer — that is why they asked — so advice built from what the trace then discovered
     * tells them to have already known it, and advice telling them to ask something narrower next
     * time is telling them not to have asked the question. Neither is usable.
     *
     * <p><b>The judgment is the model's and the enforcement is this method's, deliberately.</b>
     * Deciding whether prose is a question or an instruction is a language task, and a keyword list
     * would encode one person's phrasing (measured: a bare "?" matches 23.6% of this database's
     * human prompts, while any tight phrase list matches under 3%). But a judgment the model makes
     * and is then free to re-litigate is not a judgment — on
     * {@code dfe4ea1da356f008ae46b2790736e223} the review identified the open question and wrote the
     * wording advice anyway. So the model classifies once, in the call that can see the request, and
     * the second call is handed a schema in which the wrong answer is not a legal token.
     */
    /**
     * Whether the review found anything to act on — the gate on making the second model call at all.
     *
     * <p><b>Both paths fail towards making the call.</b> Skipping it wrongly costs the reader every
     * piece of advice this feature exists to produce, while making it wrongly costs one short
     * inference on a clean trace, so the asymmetry decides every judgment call here. The structured
     * path is exact (an empty {@code wentWrong} list is unambiguous); the prose path parses, and
     * therefore only skips when it can positively identify the "What went wrong" section AND find no
     * fault bullet in it. A findings answer whose shape it does not recognise gets the call.
     *
     * <p>Positives deliberately do not count: a {@code Went well:} bullet feeds none of the three
     * lines — "keep doing X" is not a standing order — so a trace with only positives has nothing to
     * apply either.
     */
    private static boolean hasFaults(
            boolean structured, TraceAnalysisAnswer.Findings parsedFindings, String proseFindings) {
        if (structured) {
            return parsedFindings.wentWrong() != null && !parsedFindings.wentWrong().isEmpty();
        }
        int sectionStart = proseFindings.indexOf(WENT_WRONG_HEADING);
        if (sectionStart < 0) {
            return true;
        }
        String section = proseFindings.substring(sectionStart + WENT_WRONG_HEADING.length());
        for (String line : section.lines().map(String::strip).toList()) {
            if (line.startsWith(FAULT_BULLET_PREFIX)) {
                return true;
            }
            // A later section heading ends the fault list -- anything past it is not a fault.
            if (line.startsWith(SECTION_HEADING_PREFIX) && !line.isBlank()) {
                return false;
            }
        }
        return false;
    }

    private static boolean requestWasQuestion(String requestKind) {
        return requestKind != null
                && TraceAnalysisAnswer.QUESTION_REQUEST_KIND.equalsIgnoreCase(requestKind.strip());
    }

    /** A prose findings answer split from the machine-read {@code Request kind:} line it ends with. */
    record RequestKindSplit(String findings, String requestKind) {
    }

    /**
     * Peels the {@code Request kind: <word>} line off a prose findings answer.
     *
     * <p>That line is the prose path's equivalent of the structured path's {@code requestKind} field
     * — a one-word verdict the program reads and the reader never sees, so it is stripped here rather
     * than stored. Scanned from the end because the contract asks for it last, and tolerant of its
     * absence: a model that omits it yields a null kind, which simply leaves the wording question
     * where it was rather than failing a review over one missing line.
     */
    static RequestKindSplit splitRequestKind(String findingsText) {
        List<String> lines = new ArrayList<>(findingsText.strip().lines().toList());
        for (int index = lines.size() - 1; index >= 0; index--) {
            String line = lines.get(index).strip();
            if (!line.regionMatches(true, 0, REQUEST_KIND_PREFIX, 0, REQUEST_KIND_PREFIX.length())) {
                continue;
            }
            lines.remove(index);
            return new RequestKindSplit(
                    String.join("\n", lines).strip(),
                    line.substring(REQUEST_KIND_PREFIX.length()).strip());
        }
        return new RequestKindSplit(findingsText.strip(), null);
    }

    private TraceAnalysisAnswer.Findings parseFindings(String findingsJson) {
        try {
            return objectMapper.readValue(findingsJson, TraceAnalysisAnswer.Findings.class);
        } catch (JacksonException exception) {
            throw structuredAnswerFailure(exception);
        }
    }

    // One model call, with the empty-answer check both of them need. An empty answer from either
    // call is the same user-facing failure -- there is no half-review worth storing.
    private String generate(
            String prompt,
            Map<String, Object> answerSchema,
            EffectiveOllamaSettings ollamaSettings,
            TraceAnalysisProgressListener listener,
            TraceAnalysisPhase phase,
            String key,
            int[] runningAnswerCharacters) {
        String generated = ollamaClient.generate(
                prompt,
                ollamaSettings.baseUrl(),
                ollamaSettings.model(),
                answerSchema,
                answerProgressReporter(listener, answerSchema != null, phase, key, runningAnswerCharacters));
        if (StringUtils.isBlank(generated)) {
            throw new OllamaUnavailableException(
                    "Ollama returned an empty analysis for this trace — try regenerating again.");
        }
        return generated;
    }

    /**
     * Joins the two prose answers into the one markdown document the frontend parses.
     *
     * <p>The heading is written here rather than asked for, which is the point of the split: the
     * second call is told to emit three lines and nothing else, so the one piece of structure the
     * dialog's {@code parseApplyThis} splits on cannot go missing or come back misspelled.
     * {@link #normalizeApplyThisLines} still runs over the joined text, since the leaked-placeholder
     * repair it performs is about the lines' content rather than their position.
     */
    private static String joinProseAnswer(String findings, String applyThis) {
        return normalizeApplyThisLines(
                collapseDuplicatedBlock(findings.strip()) + "\n\n" + APPLY_THIS_HEADING + "\n\n" + applyThis.strip());
    }

    /**
     * Adapts {@link OllamaClient}'s per-fragment callback to
     * {@link TraceAnalysisProgressListener#answerAdvanced}, which also wants a running total.
     *
     * <p>{@code answerIsJson} is what decides whether the fragment itself travels. On the structured
     * path the answer being assembled is a JSON document, and streaming that to the dialog would show
     * a reader raw schema keys and escaped newlines rather than the review — the rendered markdown
     * only exists once the whole document has parsed. So the text is withheld and only the count is
     * reported, which still tells the reader the model is writing without showing them plumbing.
     *
     * <p>{@code runningAnswerCharacters} is owned by {@code generateAndSave} and threaded through
     * every call across the whole run (every window plus the apply-this call) rather than allocated
     * fresh here, so the count it reports can only ever increase for the run's lifetime — it must
     * never reset when a new window's draft starts from an empty answer.
     */
    private static Consumer<String> answerProgressReporter(
            TraceAnalysisProgressListener listener, boolean answerIsJson, TraceAnalysisPhase phase, String key,
            int[] runningAnswerCharacters) {
        return fragment -> {
            runningAnswerCharacters[0] += fragment.length();
            listener.answerAdvanced(phase, key, answerIsJson ? "" : fragment, runningAnswerCharacters[0]);
        };
    }

    /**
     * Turns the JSON answer back into the markdown that is stored and rendered — the same shape the
     * prose path produces, so nothing downstream can tell which path wrote a row.
     *
     * <p><b>A parse failure is raised, not repaired.</b> The likeliest cause is an Ollama old enough
     * to ignore an unknown {@code format} field and answer in prose instead — the same silent-ignore
     * shape {@code num_ctx} documents — and the honest outcome there is a visible failure telling
     * the operator to upgrade or turn the flag off. Falling back to storing the raw text would hide
     * a misconfiguration behind an answer that looks fine, and nothing would ever surface it.
     */
    /**
     * Removes any fault whose own text claims two calls were the same call when nothing in the
     * rendered timeline links them — see {@link TimelineCitations#unsupportedSamenessViolations}.
     *
     * <p><b>Dropped, not annotated.</b> That is this feature's clearest result, recorded at length in
     * backend/CLAUDE.md: every fence that removed something from what the model could see or emit has
     * held, and every fence that left the wrong answer visible and argued against it in prose has
     * eventually lost. A fabricated bullet marked as doubtful is still a bullet the reader has to
     * adjudicate, and it is still available to the second call as the fault the {@code Apply this}
     * lines get built from.
     *
     * <p><b>It runs before the findings are rendered</b>, so a dropped fault reaches neither the
     * stored review nor the second model call — on trace {@code 9ab1feeebdd15a449bbc4c9983dcb79d} the
     * fabricated "Calls 2 and 5 are identical" bullet is what the {@code Instruction rule} was then
     * hung on. Dropping every fault is a legal outcome: {@code hasFaults} then skips the second call
     * and the answer degrades to the empty-review sentence, which is the honest result for a review
     * whose only faults were invented. The settled {@code Tool swap} still survives that path, since
     * it is evidence this application verified rather than anything the model wrote.
     */
    private static TraceAnalysisAnswer.Findings dropUnsupportedSamenessFaults(
            String traceId, TraceAnalysisAnswer.Findings findings, String prompt) {
        List<TraceAnalysisAnswer.Finding> faults = findings.wentWrong();
        if (faults == null || faults.isEmpty()) {
            return findings;
        }
        TimelineCitations.TimelineSameness sameness = TimelineCitations.samenessIn(prompt);
        List<TraceAnalysisAnswer.Finding> keptFaults = new ArrayList<>();
        for (TraceAnalysisAnswer.Finding fault : faults) {
            List<String> violations = TimelineCitations.unsupportedSamenessViolations(findingText(fault), sameness);
            if (violations.isEmpty()) {
                keptFaults.add(fault);
            } else {
                // Logged because the drop is otherwise invisible: a stored review reads identically
                // whether the model wrote one fault or wrote two and had one removed here, so
                // without this there is no way to tell how often the check fires -- or to notice it
                // firing on a finding it should not have. INFO rather than DEBUG: it changes what
                // the reader is shown, which is not a thing to have to switch on after the fact.
                log.info("Trace {}: dropped fault \"{}\" — {}", traceId, fault.label(), violations);
            }
        }
        if (keptFaults.size() == faults.size()) {
            return findings;
        }
        return new TraceAnalysisAnswer.Findings(findings.wentWell(), keptFaults, findings.requestKind());
    }

    /**
     * Removes any fault whose citation names the wrong kind of call or the wrong file — see
     * {@link TimelineCitations#miscitedKindViolations} and {@link TimelineCitations#miscitedFileViolations}.
     *
     * <p>Trace {@code 299f2704e7161e2271a5c3749cdf3551} is why this exists: its stored review claimed
     * "Used `grep -n` for file searches at calls 22 and 155, which could be more efficiently handled
     * by the dedicated `Read` tool" — call 22 is a model call, not the {@code Read} the sentence's own
     * follow-up clause names it as. Same doctrine as {@link #dropUnsupportedSamenessFaults}: dropped,
     * not annotated, run before the findings are rendered, and safe if it drops every fault —
     * {@code hasFaults} then skips the second model call and the review degrades to the empty-review
     * sentence.
     *
     * <p><b>Known gap, deliberately not addressed here</b>: the same stored review separately cited a
     * redundant-read finding at "calls 93 and 101, suggesting inefficiency in file handling" when 93
     * is a Bash grep and the real duplicate Read pair is 95/101 — see
     * {@link TimelineCitations}'s class javadoc for why this exact sentence evades both checks below.
     *
     * <p><b>Absent-call citations are deliberately not faulted here</b> — {@code faultAbsentCalls} is
     * always {@code false} on this path, unlike {@code TraceAnalysisAnswerScorer}'s use of the same
     * check against a whole review. A partitioned trace's later windows carry a carry-over block
     * naming calls from before the window ({@code TraceAnalysisPromptBuilder}'s {@code countsLine} /
     * {@code failureLines} / {@code fileLines}), rendered in a shape {@link TimelineCitations#TIMELINE_LINE}
     * does not match, so a legitimate citation of one of those calls would otherwise be dropped as if
     * it did not exist.
     */
    private static TraceAnalysisAnswer.Findings dropMiscitedCallFaults(
            String traceId, TraceAnalysisAnswer.Findings findings, String prompt) {
        List<TraceAnalysisAnswer.Finding> faults = findings.wentWrong();
        if (faults == null || faults.isEmpty()) {
            return findings;
        }
        Map<Integer, String> callKinds = TimelineCitations.callKindsIn(prompt);
        Map<Integer, String> callTargets = TimelineCitations.callTargetsIn(prompt);
        List<TraceAnalysisAnswer.Finding> keptFaults = new ArrayList<>();
        for (TraceAnalysisAnswer.Finding fault : faults) {
            String text = findingText(fault);
            List<String> violations = new ArrayList<>();
            violations.addAll(TimelineCitations.miscitedKindViolations(text, callKinds, false));
            violations.addAll(TimelineCitations.miscitedFileViolations(text, callTargets));
            if (violations.isEmpty()) {
                keptFaults.add(fault);
            } else {
                log.info("Trace {}: dropped fault \"{}\" — {}", traceId, fault.label(), violations);
            }
        }
        if (keptFaults.size() == faults.size()) {
            return findings;
        }
        return new TraceAnalysisAnswer.Findings(findings.wentWell(), keptFaults, findings.requestKind());
    }

    /**
     * Guarantees a tool call this trace's own spans/logs show failed does not silently drop out of
     * "What went wrong" for having lost a competition against other candidate findings.
     *
     * <p><b>The gap this closes is documented, not hypothetical.</b> {@code buildObservations} hands
     * the model a "Tool calls that failed" line with the call number and the failure text already on
     * it, and the answer contract's section A asks for "Recovery after a failure — for each FAILED or
     * REJECTED call". Neither is enforced: the answer caps at five bullets, worst first, and on trace
     * {@code d5341fa1bd5301141829f510a2870505} — one real failure (a Bash loop that died) alongside
     * four other candidate findings (redundant reads, `sed` overuse, context bloat, repetitive model
     * calls) — the failure lost the competition on two consecutive regenerations. This is the same
     * move {@link TraceAnalysisAnswer#of(TraceAnalysisAnswer.Findings, TraceAnalysisAnswer.ApplyThis,
     * String)} already makes for {@code Tool swap}: decide it in code and hand the model a fact it
     * cannot omit, rather than trust a prompt instruction competing for the model's attention against
     * everything else in the trace.
     *
     * <p><b>Citation, not substring.</b> Each failed call's bare number is checked against every
     * number the model's own {@code wentWrong} findings actually cite, parsed with the identical
     * {@link TimelineCitations#CITATION_PHRASE}/{@link TimelineCitations#CALL_NUMBER} pair
     * {@code unsupportedSamenessViolations} uses — not a bare {@code contains(String.valueOf(n))},
     * which a call number embedded in an unrelated figure (a token count, a duration in ms) could
     * false-positive on.
     *
     * <p><b>Prepended, not appended, and deliberately not judged.</b> A citation-less failure becomes
     * a new fault at the FRONT of {@code wentWrong}, so it survives {@link TraceAnalysisAnswer}'s
     * five-bullet cap even when the model already spent all five slots on weaker findings — the exact
     * failure mode this method exists to close. Its wording states only what is already known: the
     * call failed, with what error, and that the review did not address it. It does not claim the
     * trace recovered badly, because judging that needs to read what happened after the failure, which
     * this method has no evidence for — the same restraint {@code buildObservations} applies to a
     * {@code Not a finding:} environmental failure, just for the opposite reason (there, the verdict
     * is known and prose must not re-litigate it; here, the verdict is unknown and code must not
     * invent one).
     *
     * <p><b>Structured-output path only</b>, like the settled {@code Tool swap} — the prose path
     * produces free-form markdown with no {@code Finding} list to prepend to; hardening it would mean
     * re-parsing the model's own bullet list, which is exactly the kind of second implementation
     * {@code TraceCallNumbering}'s own javadoc warns is free to drift.
     */
    private static TraceAnalysisAnswer.Findings ensureFailedToolCallsReported(
            String traceId,
            TraceAnalysisAnswer.Findings findings,
            List<TraceAnalysisPromptBuilder.UnrecoveredFailure> failedToolCalls) {
        if (failedToolCalls.isEmpty()) {
            return findings;
        }
        Set<Integer> citedCallNumbers = citedCallNumbers(findings.wentWrong());
        List<TraceAnalysisAnswer.Finding> unreported = new ArrayList<>();
        for (TraceAnalysisPromptBuilder.UnrecoveredFailure failure : failedToolCalls) {
            if (!citedCallNumbers.contains(failure.callNumber())) {
                unreported.add(unaddressedFailureFinding(failure));
            }
        }
        if (unreported.isEmpty()) {
            return findings;
        }
        for (TraceAnalysisAnswer.Finding finding : unreported) {
            // INFO, not DEBUG: this changes what the reader is shown, the same reason
            // dropUnsupportedSamenessFaults logs its own drops at INFO rather than staying silent.
            log.info("Trace {}: review omitted its own failed call — injecting \"{}\"", traceId, finding.detail());
        }
        List<TraceAnalysisAnswer.Finding> wentWrong = new ArrayList<>(unreported);
        if (findings.wentWrong() != null) {
            wentWrong.addAll(findings.wentWrong());
        }
        return new TraceAnalysisAnswer.Findings(findings.wentWell(), wentWrong, findings.requestKind());
    }

    private static TraceAnalysisAnswer.Finding unaddressedFailureFinding(
            TraceAnalysisPromptBuilder.UnrecoveredFailure failure) {
        String detail = "Call " + failure.callReference() + " (" + failure.toolName() + ") failed"
                + (failure.message() == null ? "" : ": " + failure.message())
                + ", and the review above did not address it.";
        String fix = "Check whether the calls after " + failure.callReference()
                + " addressed this specific failure, or continued as though it had succeeded.";
        return new TraceAnalysisAnswer.Finding("Unaddressed failure", detail, fix);
    }

    /** Every call number cited anywhere across a set of findings — see {@code TimelineCitations}. */
    private static Set<Integer> citedCallNumbers(List<TraceAnalysisAnswer.Finding> faults) {
        if (faults == null || faults.isEmpty()) {
            return Set.of();
        }
        Set<Integer> citedCallNumbers = new HashSet<>();
        for (TraceAnalysisAnswer.Finding fault : faults) {
            citedCallNumbers.addAll(TimelineCitations.citedCallNumbers(findingText(fault)));
        }
        return citedCallNumbers;
    }

    /**
     * A fault's three fields as one string to check. Joined rather than checked field by field
     * because the citation and the claim about it routinely straddle them — the label names the
     * pattern ("Redundant work") while the detail carries "Calls 2 and 5 are identical".
     *
     * <p>Package-visible so {@link TraceAnalysisFindingsMerge} reads a finding's citations through
     * the identical implementation, rather than a second copy of the field-joining rule.
     */
    static String findingText(TraceAnalysisAnswer.Finding fault) {
        StringBuilder text = new StringBuilder();
        for (String part : List.of(
                fault.label() == null ? "" : fault.label(),
                fault.detail() == null ? "" : fault.detail(),
                fault.fix() == null ? "" : fault.fix())) {
            if (!part.isBlank()) {
                text.append(text.isEmpty() ? "" : " ").append(part);
            }
        }
        return text.toString();
    }

    private String renderStructuredAnswer(
            TraceAnalysisAnswer.Findings findings, String applyThisJson, String settledToolSwap) {
        try {
            return TraceAnalysisAnswer.of(
                    findings,
                    objectMapper.readValue(applyThisJson, TraceAnalysisAnswer.ApplyThis.class),
                    settledToolSwap)
                    .toMarkdown();
        } catch (JacksonException exception) {
            throw structuredAnswerFailure(exception);
        }
    }

    private static OllamaUnavailableException structuredAnswerFailure(JacksonException exception) {
        return new OllamaUnavailableException(
                "Ollama did not return the structured answer this build asked for. If the server is older "
                        + "than the `format` parameter it may have ignored the schema — upgrade Ollama, or set "
                        + "ollama.structured-output=false.",
                exception);
    }

    /**
     * Drops a byte-identical second copy of the answer's own body, which the small local models
     * this feature targets emit often enough to be worth handling: a real observed answer wrote its
     * whole two-section review, then wrote the same two sections again word for word, then a closing
     * paragraph — doubling the length of the thing the reader has to read while adding nothing. The
     * prompt now says to write each heading once and stop, which is the actual fix; this is the net
     * under it, because no instruction makes a 7B model reliably stop.
     *
     * <p>Deliberately an <b>exact</b> match, anchored on the answer's own first substantial line and
     * gated on a {@link #MINIMUM_DUPLICATED_BLOCK_LENGTH}-character block: it removes only text that
     * is already present verbatim immediately before it, so it can never delete something the reader
     * has not already been shown. Anything looser — near-duplicate detection, heading counting,
     * truncating at a repeated heading — risks eating a real finding, which is a far worse failure
     * than leaving a duplicate in place. Repeats the pass a few times so a tripled answer collapses
     * fully rather than to a pair.
     */
    /**
     * Rewrites an "Apply this" line whose value merely restates the instruction and then answers
     * {@code None} down to the bare {@code None} it meant.
     *
     * <p>Observed on trace {@code dfe4ea1da356f008ae46b2790736e223}, whose stored answer read
     * <i>"Better wording: how to word a request like this one next time, in one or two sentences:
     * None"</i> — the model copied the opening of the template's own backticked placeholder for
     * that line and then wrote its real answer after it. That is not a wrong judgment, it is
     * instruction text leaking into the slot, and the reader pays for it twice: the frontend's
     * "drop this card when the text is None" test is an exact match, so a leaked prefix turns a
     * correct {@code None} into a card full of the prompt's own words rendered as though it were
     * advice about their request.
     *
     * <p><b>The rule is deliberately narrow: the value's last word is {@code None}.</b> Real advice
     * never ends that way — it ends in a suggestion — and a correct answer is already exactly
     * {@code None}, so it passes through unchanged. Anything looser (stripping known placeholder
     * fragments, matching the value against the template's text) would couple this to prompt
     * wording that is edited often, and would risk truncating a real suggestion, which is the worse
     * failure — the same trade {@link #collapseDuplicatedBlock} makes when it insists on a
     * byte-identical repeat.
     *
     * <p>The template-side half of the fix is to stop offering a copyable placeholder at all; this
     * is the net under it, because no instruction makes a small model reliably resist echoing one.
     * Runs on the prose path only — the structured path's {@code betterWording} is a schema field
     * whose value never travels beside its own instructions.
     */
    static String normalizeApplyThisLines(String analysisText) {
        return analysisText.lines()
                .map(TraceAnalysisService::normalizeApplyThisLine)
                .collect(Collectors.joining("\n"));
    }

    private static String normalizeApplyThisLine(String line) {
        String strippedLine = line.strip();
        for (String prefix : APPLY_THIS_PREFIXES) {
            if (!strippedLine.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            String value = strippedLine.substring(prefix.length()).strip();
            if (!NONE.equalsIgnoreCase(value) && endsWithNone(value)) {
                return prefix + " " + NONE;
            }
            return line;
        }
        return line;
    }

    // Whether the value's final word is "None" -- the shape a leaked placeholder leaves behind once
    // the model gets round to answering. Trailing punctuation is tolerated because the model
    // sometimes closes the sentence it echoed; a "None" that is part of a longer final word
    // ("Nonexistent") is not a match, since the split is on whitespace.
    private static boolean endsWithNone(String value) {
        String[] words = value.split("\\s+");
        String lastWord = StringUtils.stripEnd(words[words.length - 1], TRAILING_PUNCTUATION);
        return NONE.equalsIgnoreCase(lastWord);
    }

    static String collapseDuplicatedBlock(String analysisText) {
        String collapsedText = analysisText;
        for (int pass = 0; pass < MAX_DUPLICATE_COLLAPSE_PASSES; pass++) {
            String withoutOneCopy = collapseOneDuplicatedBlock(collapsedText);
            if (withoutOneCopy.equals(collapsedText)) {
                return collapsedText;
            }
            collapsedText = withoutOneCopy;
        }
        return collapsedText;
    }

    private static String collapseOneDuplicatedBlock(String analysisText) {
        String anchorLine = analysisText.lines()
                .map(String::strip)
                .filter(line -> line.length() >= MINIMUM_ANCHOR_LINE_LENGTH)
                .findFirst()
                .orElse(null);
        if (anchorLine == null) {
            return analysisText;
        }
        int blockStart = analysisText.indexOf(anchorLine);
        int repeatIndex = analysisText.indexOf(anchorLine, blockStart + anchorLine.length());
        while (repeatIndex > blockStart) {
            String block = analysisText.substring(blockStart, repeatIndex).strip();
            if (block.length() >= MINIMUM_DUPLICATED_BLOCK_LENGTH && analysisText.startsWith(block, repeatIndex)) {
                // Cut from the end of the FIRST copy, not from where the second one starts, so the
                // blank line that separated the two copies goes with the copy rather than piling up
                // against whatever followed it.
                return (analysisText.substring(0, blockStart + block.length())
                        + analysisText.substring(repeatIndex + block.length())).strip();
            }
            repeatIndex = analysisText.indexOf(anchorLine, repeatIndex + anchorLine.length());
        }
        return analysisText;
    }

    // Only the write is transactional -- see the class javadoc for why the Ollama call above must
    // run with no transaction open. Takes the whole PreparedPrompt rather than the two fields it
    // reads off it, so a third piece of what-was-analyzed provenance needs no new parameter.
    @Transactional
    TraceAnalysis save(
            String traceId,
            String model,
            String analysisText,
            long generationDurationMs,
            PreparedPrompt prepared) {
        TraceAnalysisEntity entity = new TraceAnalysisEntity();
        entity.setTraceId(traceId);
        entity.setModel(model);
        entity.setAnalysisText(analysisText);
        entity.setGenerationDurationMs(generationDurationMs);
        entity.setGeneratedAt(Instant.now());
        entity.setLastSpanEndTimestamp(prepared.lastSpanEndTimestamp());
        entity.setUserPrompt(prepared.userPrompt());
        // Always false/0 going forward -- the timeline is partitioned into windows now rather than
        // elided, so nothing this build writes is ever truncated in the old sense. A legacy row
        // keeps whatever it was generated with until its trace is regenerated.
        entity.setTimelineTruncated(false);
        entity.setOmittedLineCount(0);
        entity.setReviewPassCount(prepared.windows().size());
        entity.setTimelineCallCount(prepared.timelineCallCount());
        entity.setSummary(prepared.summary());
        TraceAnalysisEntity persisted = traceAnalysisRepository.save(entity);
        // Freshly generated against lastSpanEndTimestamp itself, so it's up to date by
        // definition -- no second latestSpanEndTimestamp probe needed here.
        return toTraceAnalysis(persisted, Optional.of(prepared.lastSpanEndTimestamp()));
    }
}
