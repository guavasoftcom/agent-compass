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

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.io.ClassPathResource;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.TraceAnalysisEntity;
import com.guavasoft.agentcompass.mapper.TraceAnalysisMapperImpl;
import com.guavasoft.agentcompass.model.EffectiveOllamaSettings;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceAnalysis;
import com.guavasoft.agentcompass.model.TraceAnalysisPhase;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.ollama.OllamaClient;
import com.guavasoft.agentcompass.ollama.OllamaUnavailableException;
import com.guavasoft.agentcompass.repository.TraceAnalysisRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceAnalysisServiceTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";

    // What the SECOND model call returns. The review is generated in two calls -- findings, then
    // the three "Apply this" lines -- so every regenerate test stubs both, in order.
    private static final String APPLY_THIS_ANSWER = """
            Instruction rule: CLAUDE.md — Default to Glob instead of `find` in Bash.
            Tool swap: use Glob instead of Bash find for locating files by name
            Better wording: None""";

    @Mock
    TraceService traceService;

    @Mock
    LogService logService;

    @Mock
    TraceExplorerService traceExplorerService;

    @Mock
    OllamaClient ollamaClient;

    @Mock
    OllamaSettingsService ollamaSettingsService;

    @Mock
    TraceAnalysisRepository traceAnalysisRepository;

    TraceAnalysisService traceAnalysisService;

    // Held as a field, and mutable, so a test can flip ollama.structured-output on the very
    // instance the service resolved it from -- the service reads the flag per call, not at
    // construction, for the same reason the Ollama base-url/model are resolved per call.
    OllamaProperties ollamaProperties = new OllamaProperties();

    @BeforeEach
    void setUp() throws Exception {
        Mustache.Compiler compiler = Mustache.compiler().escapeHTML(false).nullValue("");
        traceAnalysisService = new TraceAnalysisService(
                traceService,
                logService,
                traceExplorerService,
                ollamaClient,
                ollamaSettingsService,
                ollamaProperties,
                new TuningProperties(),
                new SubagentCostAttributor(new TuningProperties()),
                traceAnalysisRepository,
                new TraceAnalysisMapperImpl(),
                compiler,
                new ObjectMapper(),
                new ClassPathResource("templates/trace-analysis-prompt.mustache"),
                new ClassPathResource("templates/trace-analysis-apply-this.mustache"));
    }

    @Test
    void getStoredReadsTheRepositoryOnlyAndNeverCallsOllama() {
        TraceAnalysisEntity entity = entity("Looks fine.");
        when(traceAnalysisRepository.findById(TRACE_ID)).thenReturn(Optional.of(entity));
        when(traceService.latestSpanEndTimestamp(TRACE_ID))
                .thenReturn(Optional.of(entity.getLastSpanEndTimestamp()));

        Optional<TraceAnalysis> result = traceAnalysisService.getStored(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis()).isEqualTo("Looks fine.");
        verifyNoInteractions(ollamaClient);
    }

    @Test
    void getStoredReturnsEmptyWhenNothingIsStored() {
        when(traceAnalysisRepository.findById(TRACE_ID)).thenReturn(Optional.empty());

        assertThat(traceAnalysisService.getStored(TRACE_ID)).isEmpty();
    }

    @Test
    void getStoredFlagsTheAnalysisOutdatedWhenTheTraceHasNewerSpanActivity() {
        TraceAnalysisEntity entity = entity("Looks fine.");
        when(traceAnalysisRepository.findById(TRACE_ID)).thenReturn(Optional.of(entity));
        when(traceService.latestSpanEndTimestamp(TRACE_ID))
                .thenReturn(Optional.of(entity.getLastSpanEndTimestamp().plusSeconds(30)));

        Optional<TraceAnalysis> result = traceAnalysisService.getStored(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().outdated()).isTrue();
        assertThat(result.get().analyzedThroughTimestamp()).isEqualTo(entity.getLastSpanEndTimestamp());
    }

    @Test
    void getStoredDoesNotFlagTheAnalysisOutdatedWhenNoNewerSpanActivityExists() {
        TraceAnalysisEntity entity = entity("Looks fine.");
        when(traceAnalysisRepository.findById(TRACE_ID)).thenReturn(Optional.of(entity));
        when(traceService.latestSpanEndTimestamp(TRACE_ID))
                .thenReturn(Optional.of(entity.getLastSpanEndTimestamp()));

        Optional<TraceAnalysis> result = traceAnalysisService.getStored(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().outdated()).isFalse();
    }

    @Test
    void getStoredDoesNotFlagTheAnalysisOutdatedWhenTheTracesSpansHaveDisappeared() {
        TraceAnalysisEntity entity = entity("Looks fine.");
        when(traceAnalysisRepository.findById(TRACE_ID)).thenReturn(Optional.of(entity));
        when(traceService.latestSpanEndTimestamp(TRACE_ID)).thenReturn(Optional.empty());

        Optional<TraceAnalysis> result = traceAnalysisService.getStored(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().outdated())
                .as("can't tell whether it's outdated without current span activity -- don't alarm the user")
                .isFalse();
    }

    @Test
    void regenerateReturnsEmptyForAnUnknownTraceWithoutCallingOllama() {
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.empty());

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isEmpty();
        verifyNoInteractions(ollamaClient);
        verify(traceAnalysisRepository, never()).save(any());
    }

    @Test
    void regenerateRefusesWhenOllamaIsDisabledInSettingsWithoutTouchingTheTraceOrOllama() {
        OllamaProperties defaults = new OllamaProperties();
        when(ollamaSettingsService.effectiveSettings())
                .thenReturn(new EffectiveOllamaSettings(defaults.getBaseUrl(), defaults.getModel(), false, true));

        assertThatThrownBy(() -> traceAnalysisService.regenerate(TRACE_ID))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("disabled");

        verifyNoInteractions(traceExplorerService, ollamaClient);
        verify(traceAnalysisRepository, never()).save(any());
    }

    @Test
    void regenerateBuildsThePromptCallsOllamaAndUpsertsTheResult() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("This trace shows a targeted fix.", APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("the findings and the second call's three lines are joined under a heading this service writes")
                .isEqualTo("This trace shows a targeted fix.\n\n**Apply this**\n\n" + APPLY_THIS_ANSWER);
        assertThat(result.get().model()).isEqualTo(new OllamaProperties().getModel());
        assertThat(result.get().analyzedThroughTimestamp()).isEqualTo(traceSummary().getEndTimestamp());
        assertThat(result.get().outdated())
                .as("freshly generated against its own snapshot, so it can't be outdated")
                .isFalse();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).generate(
                promptCaptor.capture(),
                eq(new OllamaProperties().getBaseUrl()),
                eq(new OllamaProperties().getModel()),
                eq(null),
                any());
        assertThat(promptCaptor.getAllValues().get(0))
                .as("the findings call is shown the trace")
                .contains("Fix the bug in auth.js")
                .contains("## Call timeline");
        assertThat(promptCaptor.getAllValues().get(1))
                .as("the Apply-this call is shown the findings and no trace at all")
                .contains("This trace shows a targeted fix.")
                .doesNotContain("## Call timeline");

        ArgumentCaptor<TraceAnalysisEntity> entityCaptor = ArgumentCaptor.forClass(TraceAnalysisEntity.class);
        verify(traceAnalysisRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getTraceId()).isEqualTo(TRACE_ID);
        assertThat(entityCaptor.getValue().getAnalysisText())
                .isEqualTo("This trace shows a targeted fix.\n\n**Apply this**\n\n" + APPLY_THIS_ANSWER);
        assertThat(entityCaptor.getValue().getLastSpanEndTimestamp()).isEqualTo(traceSummary().getEndTimestamp());
        assertThat(entityCaptor.getValue().getUserPrompt())
                .as("the 'before' half of the Better wording card is the text that was actually typed")
                .isEqualTo("Fix the bug in auth.js");
    }

    /**
     * The Better wording card compares the model's suggestion against the request it judged, so the
     * stored "before" must be exactly the wording the review was shown — never a request the review
     * was told to ignore. A slash command is the case that proves it: {@code /ship} is a name
     * standing in for a skill definition, the prompt drops the request-quality half for it entirely,
     * and offering the reader a "you wrote /ship" comparison would invite advice the prompt has
     * already forbidden.
     */
    @Test
    void aSlashCommandTraceStoresNoOriginalRequestBecauseItsWordingIsNotJudged() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithSlashCommand("/ship", "ship"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("The commit and push both succeeded.");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        traceAnalysisService.regenerate(TRACE_ID);

        ArgumentCaptor<TraceAnalysisEntity> entityCaptor = ArgumentCaptor.forClass(TraceAnalysisEntity.class);
        verify(traceAnalysisRepository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getUserPrompt()).isNull();
    }

    /**
     * With {@code ollama.structured-output} on, the model is handed a schema instead of a
     * markdown contract and this application renders the stored text — so what lands in
     * {@code trace_analyses} is the same shape the prose path produces, which is what lets the flag
     * be flipped without migrating a single row.
     */
    @Test
    void structuredOutputSendsTheSchemaAndStoresMarkdownRenderedFromTheJsonAnswer() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [],
                 "wentWrong": [{"label": "Wrong instrument", "detail": "call 3 ran `find`",
                                "fix": "use Glob for filename lookups"}]}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Default to Glob instead of `find` in Bash.",
                 "toolSwap": "use Glob instead of Bash find for locating files by name",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis()).isEqualTo("""
                **What went wrong**

                - **Wrong instrument** — call 3 ran `find`. Fix: use Glob for filename lookups.

                **Apply this**

                Instruction rule: CLAUDE.md — Default to Glob instead of `find` in Bash.
                Tool swap: use Glob instead of Bash find for locating files by name
                Better wording: None""");

        ArgumentCaptor<Object> schemaCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ollamaClient, times(2))
                .generate(anyString(), anyString(), anyString(), schemaCaptor.capture(), any());
        assertThat(schemaCaptor.getAllValues().get(0))
                .as("each call is constrained to exactly the fields it is being asked for, and this "
                        + "fixture verified no positive, so wentWell is not among them")
                .isEqualTo(TraceAnalysisAnswer.findingsJsonSchema(false, true));
        assertThat(schemaCaptor.getAllValues().get(1))
                .as("the closed target list reaches the model as a grammar, not as a sentence to obey")
                .isEqualTo(TraceAnalysisAnswer.applyThisJsonSchema(List.of("CLAUDE.md"), false));
    }

    /**
     * A fault claiming two calls were the same call, when the timeline says one is a tool call and
     * the other a model call, never reaches the reader — see
     * {@link TimelineCitations#unsupportedSamenessViolations} for the trace that produced exactly
     * this sentence, and {@code dropUnsupportedSamenessFaults} for why it is dropped rather than
     * flagged.
     */
    @Test
    void aFaultClaimingTwoDifferentKindsOfCallAreIdenticalIsDroppedBeforeTheReaderSeesIt() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolAndModelCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [],
                 "wentWrong": [{"label": "Redundant work", "detail": "Calls 1 and 2 are identical",
                                "fix": "do not repeat a call"},
                               {"label": "Wrong instrument", "detail": "call 1 ran `find`",
                                "fix": "use Glob for filename lookups"}]}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Default to Glob instead of `find` in Bash.",
                 "toolSwap": "None",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("the fabricated bullet is gone entirely, not annotated")
                .doesNotContain("Redundant work")
                .doesNotContain("identical");
        assertThat(result.get().analysis())
                .as("the fault that cites nothing false is untouched")
                .contains("Wrong instrument");
    }

    /**
     * A fault citing the wrong kind of call is dropped before the reader sees it — the real shape
     * of trace {@code 299f2704e7161e2271a5c3749cdf3551}'s stored review, which claimed "Used
     * `grep -n` for file searches at calls 22 and 155, which could be more efficiently handled by
     * the dedicated `Read` tool" when call 22 is a model call, not a Read. Reproduced at small scale
     * with {@link #spansWithToolAndModelCall}, whose call 2 is the model call.
     */
    @Test
    void aFaultNamingTheWrongKindOfCallIsDroppedBeforeTheReaderSeesIt() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolAndModelCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [],
                 "wentWrong": [{"label": "Wrong tool",
                                "detail": "Duplicated work at calls 1 and 2, better handled by the Read tool",
                                "fix": "avoid re-reading with Read"},
                               {"label": "Wrong instrument", "detail": "call 1 ran `find`",
                                "fix": "use Glob for filename lookups"}]}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Default to Glob instead of `find` in Bash.",
                 "toolSwap": "None",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("call 2 is a model call, not the Read the fault's own follow-up clause names it as")
                .doesNotContain("Wrong tool")
                .doesNotContain("Duplicated work");
        assertThat(result.get().analysis())
                .as("the fault that cites nothing false is untouched")
                .contains("Wrong instrument");
    }

    /**
     * A fault citing the wrong FILE for a call is dropped too — the shape of trace
     * {@code df8c757de3bfbfe9c1da2b29f431a192}'s stored review, which cited a file two of its five
     * call numbers never touched.
     */
    @Test
    void aFaultNamingTheWrongFileForACitedCallIsDroppedBeforeTheReaderSeesIt() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithTwoReadsOnDifferentFiles());
        when(logService.logsForTrace(TRACE_ID))
                .thenReturn(logsWithUserPromptAndTwoFileReads("Why was this read twice?"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [],
                 "wentWrong": [{"label": "Redundant read",
                                "detail": "Read `AnalyzeTraceDialogView.tsx` twice (calls 1 and 2)",
                                "fix": "read it once"},
                               {"label": "Wrong instrument", "detail": "call 1 ran `find`",
                                "fix": "use Glob for filename lookups"}]}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Default to Glob instead of `find` in Bash.",
                 "toolSwap": "None",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("call 2 actually touched the sibling file AnalyzeTraceDialog.tsx, not the cited one")
                .doesNotContain("Redundant read")
                .doesNotContain("Read `AnalyzeTraceDialogView.tsx` twice");
        assertThat(result.get().analysis())
                .as("the fault that cites nothing false is untouched")
                .contains("Wrong instrument");
    }

    /**
     * A tool call this trace's own spans/logs show failed must not silently drop out of the review
     * for losing a five-bullet competition against other findings — see
     * {@code TraceAnalysisService#ensureFailedToolCallsReported}, and the real trace
     * ({@code d5341fa1bd5301141829f510a2870505}) whose sole failure went unmentioned across two
     * consecutive regenerations that motivated it.
     */
    @Test
    void aFailedToolCallTheModelOmittedIsInjectedAsAFinding() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithFailedToolCall());
        when(logService.logsForTrace(TRACE_ID))
                .thenReturn(logsWithUserPromptAndFailedToolResult("Run the migration script"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [], "wentWrong": []}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Some unrelated rule.",
                 "toolSwap": "None",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("the model reported no faults at all, but the trace's own failed call is not left out")
                .contains("**Unaddressed failure**")
                .contains("Call 1 (Bash) failed: Shell command failed");
    }

    /**
     * A failed call the model DID cite — by call number, anywhere in one of its own findings — is
     * left exactly as the model wrote it. Otherwise a real failure the model correctly folded into
     * a finding would end up reported twice.
     */
    @Test
    void aFailedToolCallTheModelAlreadyCitedIsNotDuplicated() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithFailedToolCall());
        when(logService.logsForTrace(TRACE_ID))
                .thenReturn(logsWithUserPromptAndFailedToolResult("Run the migration script"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [],
                 "wentWrong": [{"label": "Unhandled failure", "detail": "Call 1 (Bash) failed and was not retried",
                                "fix": "check the exit code before continuing"}]}""",
                """
                {"instructionRuleTarget": "CLAUDE.md",
                 "instructionRule": "Check exit codes before continuing.",
                 "toolSwap": "None",
                 "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("already reported by the model, so nothing is injected on top of it")
                .doesNotContain("Unaddressed failure")
                .contains("Unhandled failure");
    }

    /**
     * The failure this is really about is an Ollama too old to know the {@code format} field, which
     * ignores it and answers in prose. Storing that raw would hide the misconfiguration behind an
     * answer that looks fine.
     */
    @Test
    void structuredOutputFailsLoudlyRatherThanStoringAnAnswerThatIgnoredTheSchema() {
        ollamaProperties.setStructuredOutput(true);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(List.of());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(List.of());
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("**What went wrong**\n\n- **Something** — prose, not JSON.");

        assertThatThrownBy(() -> traceAnalysisService.regenerate(TRACE_ID))
                .isInstanceOf(OllamaUnavailableException.class)
                .hasMessageContaining("structured answer");
        verify(traceAnalysisRepository, never()).save(any());
    }

    @Test
    void regeneratePropagatesOllamaUnavailableWithoutPersistingAnything() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(List.of());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(List.of());
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new OllamaUnavailableException("Could not reach Ollama — is it running?"));

        assertThatThrownBy(() -> traceAnalysisService.regenerate(TRACE_ID))
                .isInstanceOf(OllamaUnavailableException.class);
        verify(traceAnalysisRepository, never()).save(any());
    }

    @Test
    void regenerateThrowsRatherThanPersistingWhenOllamaReturnsBlankOutput() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(List.of());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(List.of());
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn("   ");

        assertThatThrownBy(() -> traceAnalysisService.regenerate(TRACE_ID))
                .isInstanceOf(OllamaUnavailableException.class);
        verify(traceAnalysisRepository, never()).save(any());
    }

    @Test
    void regenerateDegradesGracefullyWhenTheTraceHasNoUserPromptRecord() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(List.of());
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("Proportionality looks fine.", APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).generate(promptCaptor.capture(), anyString(), anyString(), any(), any());
        String prompt = promptCaptor.getAllValues().get(0);
        assertThat(prompt).doesNotContain("## User prompt");
        assertThat(prompt).contains("no human-written request attached");
    }

    /**
     * The doubled-answer shape a real local model produced: the whole review, then the same review
     * again word for word, then a closing paragraph. Only the verbatim second copy goes; the closing
     * paragraph, which appears once, survives.
     */
    @Test
    void regenerateDropsAVerbatimSecondCopyOfTheAnswerBeforeStoringIt() {
        String doubledAnswer = REVIEW + "\n\n" + REVIEW + "\n\nOverall the trace was efficient.";
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(doubledAnswer, APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("collapsing runs on the findings half, which is the one a model doubles")
                .isEqualTo(REVIEW + "\n\nOverall the trace was efficient."
                        + "\n\n**Apply this**\n\n" + APPLY_THIS_ANSWER);
    }

    @Test
    void anAnswerRepeatedThreeTimesCollapsesAllTheWayToOneCopy() {
        assertThat(TraceAnalysisService.collapseDuplicatedBlock(REVIEW + "\n\n" + REVIEW + "\n\n" + REVIEW))
                .isEqualTo(REVIEW);
    }

    /**
     * The collapsing is an exact match on purpose — it may only remove text the reader has already
     * been shown verbatim. Two findings that merely look alike are two findings.
     */
    @Test
    void anAnswerThatOnlyNearlyRepeatsItselfIsLeftAlone() {
        String nearRepeat = REVIEW + "\n\n" + REVIEW.replace("call 3", "call 9");

        assertThat(TraceAnalysisService.collapseDuplicatedBlock(nearRepeat)).isEqualTo(nearRepeat);
    }

    /**
     * The {@code Request kind:} line is the prose path's equivalent of the structured
     * {@code requestKind} field: read by the program, never shown to the reader, so it must come off
     * the findings before they are stored or handed to the second call.
     */
    @Test
    void theRequestKindVerdictIsPeeledOffTheProseFindings() {
        TraceAnalysisService.RequestKindSplit split = TraceAnalysisService.splitRequestKind("""
                **What went wrong**

                - **Redundant reads** — the file was read twice. Fix: read it once.

                Request kind: question""");

        assertThat(split.requestKind()).isEqualTo("question");
        assertThat(split.findings())
                .isEqualTo("""
                        **What went wrong**

                        - **Redundant reads** — the file was read twice. Fix: read it once.""");
    }

    /** A model that forgets the line costs the review nothing but the verdict it would have carried. */
    @Test
    void findingsWithNoRequestKindLineAreLeftExactlyAsTheyAre() {
        String findings = "**What went wrong**\n\n- **X** — y. Fix: z.";

        TraceAnalysisService.RequestKindSplit split = TraceAnalysisService.splitRequestKind(findings);

        assertThat(split.requestKind()).isNull();
        assertThat(split.findings()).isEqualTo(findings);
    }

    /**
     * The whole point of making the first call commit: on trace
     * {@code dfe4ea1da356f008ae46b2790736e223} the review identified an open question and the second
     * call still wrote wording advice telling the reader to stop asking open questions. Now that
     * verdict reaches the second prompt as a settled fact.
     */
    @Test
    void aRequestTheFirstCallCalledAQuestionSettlesTheWordingForTheSecondCall() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID))
                .thenReturn(logsWithUserPrompt("is there anything missing in the payload doc?"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("**What went wrong**\n\n- **X** — y. Fix: z.\n\nRequest kind: question",
                        APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("the machine-read verdict line never reaches the reader")
                .doesNotContain("Request kind:");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).generate(promptCaptor.capture(), anyString(), anyString(), any(), any());
        assertThat(promptCaptor.getAllValues().get(1))
                .as("the second call is told the wording question is closed, not asked to re-decide it")
                .contains("For this trace that line is None")
                .contains("asked a question rather than giving an instruction");
    }

    @Test
    void aRequestTheFirstCallCalledAnInstructionLeavesTheWordingQuestionOpen() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("fix the token thing"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("**What went wrong**\n\n- **X** — y. Fix: z.\n\nRequest kind: instruction",
                        APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        traceAnalysisService.regenerate(TRACE_ID);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ollamaClient, times(2)).generate(promptCaptor.capture(), anyString(), anyString(), any(), any());
        assertThat(promptCaptor.getAllValues().get(1)).doesNotContain("For this trace that line is None");
    }

    /**
     * A clean trace has nothing to apply, so the second call would spend a whole local inference
     * pass returning three Nones. The median trace here is 9 calls, so this is the common case, not
     * an edge one.
     */
    @Test
    void aReviewWithNoFaultsSkipsTheSecondCallEntirely() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("**What went wrong**\n\nNothing in this trace is worth changing.");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID);

        verify(ollamaClient, times(1)).generate(anyString(), anyString(), anyString(), any(), any());
        assertThat(result).isPresent();
        assertThat(result.get().analysis())
                .as("and the reader still gets the Apply-this block the dialog parses, all None")
                .isEqualTo("""
                        **What went wrong**

                        Nothing in this trace is worth changing.

                        **Apply this**

                        Instruction rule: None
                        Tool swap: None
                        Better wording: None""");
    }

    /** The gate fails towards making the call: a fault bullet means there is something to distil. */
    @Test
    void aReviewWithFaultsStillMakesTheSecondCall() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("**What went wrong**\n\n- **X** — y. Fix: z.", APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        traceAnalysisService.regenerate(TRACE_ID);

        verify(ollamaClient, times(2)).generate(anyString(), anyString(), anyString(), any(), any());
    }

    /** An answer whose shape it cannot parse gets the call rather than losing the reader advice. */
    @Test
    void anUnrecognisedFindingsShapeStillMakesTheSecondCall() {
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(spansWithToolCall());
        when(logService.logsForTrace(TRACE_ID)).thenReturn(logsWithUserPrompt("Fix the bug in auth.js"));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("The agent did some things and some of them were not ideal.", APPLY_THIS_ANSWER);
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        traceAnalysisService.regenerate(TRACE_ID);

        verify(ollamaClient, times(2)).generate(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aShortAnswerIsNeverCollapsedHoweverMuchItRepeats() {
        String shortRepeat = "No changes needed.\n\nNo changes needed.";

        assertThat(TraceAnalysisService.collapseDuplicatedBlock(shortRepeat)).isEqualTo(shortRepeat);
    }

    /**
     * The exact line trace {@code dfe4ea1da356f008ae46b2790736e223} stored: the model copied the
     * opening of the template's own description of the line, then wrote its real answer after it.
     * The dialog drops this card only on an exact "None", so left alone the reader is shown the
     * prompt's own words rendered as advice about their request.
     */
    @Test
    void anApplyThisLineThatRestatesItsInstructionAndThenAnswersNoneIsReducedToNone() {
        String leakedAnswer = """
                **Apply this**

                Instruction rule: CLAUDE.md — Read a file once at the length you actually need.
                Tool swap: None
                Better wording: how to word a request like this one next time, in one or two sentences: None""";

        assertThat(TraceAnalysisService.normalizeApplyThisLines(leakedAnswer)).isEqualTo("""
                **Apply this**

                Instruction rule: CLAUDE.md — Read a file once at the length you actually need.
                Tool swap: None
                Better wording: None""");
    }

    /**
     * The narrowness is the safety: only a value whose LAST word is None is rewritten, so real
     * advice — which ends in a suggestion — can never be truncated by this.
     */
    @Test
    void realAdviceIsLeftAloneHoweverItIsWorded() {
        String goodAnswer = """
                **Apply this**

                Instruction rule: CLAUDE.md — Read a file once at the length you actually need.
                Tool swap: use Glob instead of Bash find for locating files by name
                Better wording: Name the file: say `fix the cache-read total in MetricPointRepository`.""";

        assertThat(TraceAnalysisService.normalizeApplyThisLines(goodAnswer)).isEqualTo(goodAnswer);
    }

    @Test
    void aLineThatIsAlreadyExactlyNoneIsUntouched() {
        String noneAnswer = "Instruction rule: None\nTool swap: None\nBetter wording: None";

        assertThat(TraceAnalysisService.normalizeApplyThisLines(noneAnswer)).isEqualTo(noneAnswer);
    }

    /** "None" has to be the whole last word — a suggestion about a nonexistent file is advice. */
    @Test
    void aValueMerelyContainingNoneInsideAWordIsNotReducible() {
        String answer = "Better wording: Say which file to read rather than pointing at a nonexistent one.";

        assertThat(TraceAnalysisService.normalizeApplyThisLines(answer)).isEqualTo(answer);
    }

    /**
     * A trace whose timeline needed 3 review windows produces 3 DRAFTING calls, merges their
     * findings in code ({@link TraceAnalysisFindingsMerge}), and runs the apply-this call exactly
     * ONCE against the merged result — not once per window. {@link
     * TraceAnalysisService#ensureFailedToolCallsReported} is asserted to run on the MERGED findings
     * (the injected failure appears exactly once in the stored analysis, not three times), and the
     * running answer-character counter handed to the progress listener is asserted monotonic across
     * every call in the run.
     */
    @Test
    void aPartitionedTraceMergesFindingsFromEveryWindowAndAppliesThemOnce() {
        ollamaProperties.setStructuredOutput(true);
        ollamaProperties.setMaxPromptChars(20_000);
        when(traceExplorerService.traceSummary(TRACE_ID)).thenReturn(Optional.of(traceSummary()));
        when(traceService.spansForTrace(TRACE_ID)).thenReturn(manyReadSpansWithOneFailure(800));
        when(logService.logsForTrace(TRACE_ID))
                .thenReturn(logsWithUserPromptAndManyToolResults(800));
        when(ollamaSettingsService.effectiveSettings()).thenReturn(defaultEffectiveSettings());
        // Every DRAFTING call reports no faults of its own -- the trace's one real failure (call 1)
        // is never cited by the model, so ensureFailedToolCallsReported has to inject it. Returning
        // it three times (once per window) and once for the apply-this call is what proves this is
        // genuinely a multi-window run: fewer stubbed answers than actual generate() calls would
        // throw a Mockito "not enough stubbed answers" NullPointerException on the JSON parse.
        when(ollamaClient.generate(anyString(), anyString(), anyString(), any(), any())).thenReturn(
                """
                {"wentWell": [], "wentWrong": []}""",
                """
                {"wentWell": [], "wentWrong": []}""",
                """
                {"wentWell": [], "wentWrong": []}""",
                """
                {"instructionRuleTarget": "None", "instructionRule": "None",
                 "toolSwap": "None", "betterWording": "None"}""");
        when(traceAnalysisRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Integer> reportedCharacterCounts = new java.util.ArrayList<>();
        TraceAnalysisProgressListener listener = new TraceAnalysisProgressListener() {
            @Override
            public void phaseStarted(TraceAnalysisPhase phase, PhaseScope scope) {
                // No-op: this test only cares about the character counter.
            }

            @Override
            public void answerAdvanced(TraceAnalysisPhase phase, String key, String appendedText, int totalCharacters) {
                reportedCharacterCounts.add(totalCharacters);
            }
        };

        Optional<TraceAnalysis> result = traceAnalysisService.regenerate(TRACE_ID, listener);

        assertThat(result).isPresent();
        // At least 3 DRAFTING calls (one per window) plus the one apply-this call -- this trace's
        // exact window count is a function of the template's current instruction size and is not
        // pinned here, only that windowing genuinely happened.
        verify(ollamaClient, org.mockito.Mockito.atLeast(4))
                .generate(anyString(), anyString(), anyString(), any(), any());
        assertThat(result.get().analysis())
                .as("the merged failure is injected once, not once per window")
                .containsOnlyOnce("Unaddressed failure");

        assertThat(reportedCharacterCounts).as("the character counter never resets across windows")
                .isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    /** {@code count} Read tool calls, the first of which FAILED -- everything after it succeeds. */
    private static List<Span> manyReadSpansWithOneFailure(int count) {
        List<Span> spans = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("tool_name", "Read");
            attributes.put("tool_use_id", "toolu_" + i);
            attributes.put("file_path", "/repo/src/File" + i + ".java");
            Span span = Span.builder()
                    .traceId(TRACE_ID)
                    .spanId("span-" + i)
                    .name("claude_code.tool")
                    .statusCode("ok")
                    .durationNanos(4_000_000L)
                    .attributes(attributes)
                    .build();
            spans.add(span);
        }
        return spans;
    }

    /** A user prompt, plus a {@code tool_result} log per span from {@link #manyReadSpansWithOneFailure}. */
    private static List<LogRecord> logsWithUserPromptAndManyToolResults(int count) {
        List<LogRecord> logs = new java.util.ArrayList<>(logsWithUserPrompt("Read every file and summarize"));
        for (int i = 0; i < count; i++) {
            boolean failed = i == 0;
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("event.name", "tool_result");
            attributes.put("tool_use_id", "toolu_" + i);
            attributes.put("tool_input", "{\"file_path\":\"/repo/src/File" + i + ".java\"}");
            attributes.put("success", !failed);
            if (failed) {
                attributes.put("error", "Shell command failed");
            }
            logs.add(LogRecord.builder().traceId(TRACE_ID).attributes(attributes).build());
        }
        return logs;
    }

    private static final String REVIEW = """
            **What went wrong**

            - **Wrong instrument** — call 3 ran `find . -name '*.tsx'` where Glob does the same \
            thing directly. Fix: use Glob for filename lookups and keep Bash for commands with \
            no dedicated tool.
            - **Redundant work** — call 12 re-read Foo.java at another offset. Fix: read a file \
            once at the length you need.

            **Apply this**

            - `Rule for CLAUDE.md:` Default to Glob instead of `find` in Bash.
            - `Do instead:` use Glob instead of Bash find for locating files by name.
            - `Prompt to use next time:` None""";

    /** A DB override is exercised separately by OllamaSettingsServiceTest; this stubs the default. */
    private static EffectiveOllamaSettings defaultEffectiveSettings() {
        OllamaProperties defaults = new OllamaProperties();
        return new EffectiveOllamaSettings(defaults.getBaseUrl(), defaults.getModel(), true, false);
    }

    private static TraceAnalysisEntity entity(String analysisText) {
        TraceAnalysisEntity entity = new TraceAnalysisEntity();
        entity.setTraceId(TRACE_ID);
        entity.setModel("llama3.1");
        entity.setAnalysisText(analysisText);
        entity.setGenerationDurationMs(1000L);
        entity.setGeneratedAt(Instant.parse("2026-08-23T11:48:19Z"));
        entity.setLastSpanEndTimestamp(Instant.parse("2026-08-23T11:47:58Z"));
        return entity;
    }

    private static TraceSummary traceSummary() {
        return TraceSummary.builder()
                .traceId(TRACE_ID)
                .rootSpanName("claude_code.interaction")
                .durationNanos(5_000_000_000L)
                .spanCount(3L)
                .errorCount(0L)
                .totalCostUsd(0.01)
                .endTimestamp(Instant.parse("2026-08-23T11:47:58Z"))
                .build();
    }

    /** A tool call and a model call, so the timeline carries two different call kinds to compare. */
    private static List<Span> spansWithToolAndModelCall() {
        Map<String, Object> modelAttributes = new HashMap<>();
        modelAttributes.put("model", "claude-opus-5");
        modelAttributes.put("output_tokens", 120L);
        Span modelSpan = Span.builder()
                .traceId(TRACE_ID)
                .spanId("2122232425262728")
                .name("claude_code.llm_request")
                .statusCode("ok")
                .durationNanos(900_000_000L)
                .attributes(modelAttributes)
                .build();
        List<Span> spans = new java.util.ArrayList<>(spansWithToolCall());
        spans.add(modelSpan);
        return spans;
    }

    private static List<Span> spansWithToolCall() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool_name", "Read");
        attributes.put("tool_input", "{\"file_path\":\"auth.js\"}");
        Span toolSpan = Span.builder()
                .traceId(TRACE_ID)
                .spanId("1112131415161718")
                .name("claude_code.tool")
                .statusCode("ok")
                .durationNanos(100_000_000L)
                .attributes(attributes)
                .build();
        return List.of(toolSpan);
    }

    /** One tool call that failed — carries the {@code tool_use_id} its {@code tool_result} log joins on. */
    private static List<Span> spansWithFailedToolCall() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool_name", "Bash");
        attributes.put("tool_use_id", "toolu_1");
        attributes.put("full_command", "for f in a b c; do grep \"$f\" file.txt; done");
        Span toolSpan = Span.builder()
                .traceId(TRACE_ID)
                .spanId("3132333435363738")
                .name("claude_code.tool")
                .statusCode("ok")
                .durationNanos(200_000_000L)
                .attributes(attributes)
                .build();
        return List.of(toolSpan);
    }

    /**
     * Two Read calls touching two different files — the shape of trace
     * {@code df8c757de3bfbfe9c1da2b29f431a192}, whose stored review cited both under one file's
     * name. Each span carries the {@code tool_use_id} its {@code tool_result} log
     * ({@link #logsWithUserPromptAndTwoFileReads}) joins on, since {@code file_path} only reaches
     * the rendered timeline through that log's own {@code tool_input} JSON.
     */
    private static List<Span> spansWithTwoReadsOnDifferentFiles() {
        Span firstSpan = readSpanWithToolUseId("6162636465666768", "toolu_read_1");
        Span secondSpan = readSpanWithToolUseId("7172737475767778", "toolu_read_2");
        return List.of(firstSpan, secondSpan);
    }

    private static Span readSpanWithToolUseId(String spanId, String toolUseId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool_name", "Read");
        attributes.put("tool_use_id", toolUseId);
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .name("claude_code.tool")
                .statusCode("ok")
                .durationNanos(100_000_000L)
                .attributes(attributes)
                .build();
    }

    /** A user prompt plus one {@code tool_result} log per {@link #spansWithTwoReadsOnDifferentFiles} span. */
    private static List<LogRecord> logsWithUserPromptAndTwoFileReads(String promptText) {
        List<LogRecord> logs = new java.util.ArrayList<>(logsWithUserPrompt(promptText));
        logs.add(toolResultLogWithInput("toolu_read_1", "{\"file_path\":\"AnalyzeTraceDialogView.tsx\"}"));
        logs.add(toolResultLogWithInput("toolu_read_2", "{\"file_path\":\"AnalyzeTraceDialog.tsx\"}"));
        return logs;
    }

    private static LogRecord toolResultLogWithInput(String toolUseId, String toolInputJson) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "tool_result");
        attributes.put("tool_use_id", toolUseId);
        attributes.put("success", true);
        attributes.put("tool_input", toolInputJson);
        return LogRecord.builder()
                .traceId(TRACE_ID)
                .attributes(attributes)
                .build();
    }

    /** A user prompt plus the failed {@code tool_result} log {@link #spansWithFailedToolCall} joins on. */
    private static List<LogRecord> logsWithUserPromptAndFailedToolResult(String promptText) {
        List<LogRecord> logs = new java.util.ArrayList<>(logsWithUserPrompt(promptText));
        Map<String, Object> toolResultAttributes = new HashMap<>();
        toolResultAttributes.put("event.name", "tool_result");
        toolResultAttributes.put("tool_use_id", "toolu_1");
        toolResultAttributes.put("success", false);
        toolResultAttributes.put("error", "Shell command failed");
        LogRecord toolResult = LogRecord.builder()
                .traceId(TRACE_ID)
                .attributes(toolResultAttributes)
                .build();
        logs.add(toolResult);
        return logs;
    }

    private static List<LogRecord> logsWithUserPrompt(String promptText) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "user_prompt");
        attributes.put("prompt", promptText);
        LogRecord userPrompt = LogRecord.builder()
                .traceId(TRACE_ID)
                .attributes(attributes)
                .build();
        return List.of(userPrompt);
    }

    private static List<LogRecord> logsWithSlashCommand(String promptText, String commandName) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "user_prompt");
        attributes.put("prompt", promptText);
        attributes.put("command_name", commandName);
        LogRecord userPrompt = LogRecord.builder()
                .traceId(TRACE_ID)
                .attributes(attributes)
                .build();
        return List.of(userPrompt);
    }
}
