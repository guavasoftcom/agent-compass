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
import com.samskivert.mustache.Template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceSummary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table tests for the prompt-building/compaction logic — the most logic-dense part of the trace
 * analysis feature and the easiest to get subtly wrong (see {@link TraceAnalysisPromptBuilder}'s
 * javadoc for the tool_input-on-the-span bug these guard against recurring).
 */
class TraceAnalysisPromptBuilderTest {

    private static final String TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
    private static final Instant BASE_TIME = Instant.parse("2026-08-30T10:00:00Z");

    private TraceAnalysisPromptBuilder promptBuilder;
    private OllamaProperties ollamaProperties;

    @BeforeEach
    void setUp() throws IOException {
        ollamaProperties = new OllamaProperties();
        TuningProperties tuningProperties = new TuningProperties();
        promptBuilder = new TraceAnalysisPromptBuilder(
                compileRealTemplate("templates/trace-analysis-prompt.mustache"),
                compileRealTemplate("templates/trace-analysis-apply-this.mustache"),
                tuningProperties,
                ollamaProperties,
                new SubagentCostAttributor(tuningProperties));
    }

    private static Template compileRealTemplate(String classpathLocation) throws IOException {
        ClassPathResource templateResource = new ClassPathResource(classpathLocation);
        return Mustache.compiler().escapeHTML(false).nullValue("")
                .compile(templateResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * The regression that motivated the rewrite: {@code tool_input} lives on the {@code tool_result}
     * LOG, and on 0 of 9,225 measured tool spans. Reading it off the span rendered every tool line
     * as a bare name with an empty target.
     */
    @Test
    void toolInputComesFromTheToolResultLogRatherThanTheSpan() {
        List<Span> spans = List.of(toolSpan("Read", "use-1"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"auth.js\"}", true, 12L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("1. Read {\"file_path\":\"auth.js\"} -> ok (12ms)");
    }

    /**
     * The review quotes back the shape it was shown, so a prompt reading "36411 ms" produced a
     * finding reading "36411 ms" and left the reader dividing in their head to learn the call took
     * half a minute. Every duration in the prompt is therefore rendered in the unit that reads —
     * whole milliseconds below a second, then seconds, minutes and hours.
     */
    @Test
    void durationsAreRenderedInTheUnitThatReadsRatherThanAsRawMilliseconds() {
        List<Span> spans = List.of(
                toolSpan("Read", "use-1"),
                toolSpan("Bash", "use-2"),
                toolSpan("Grep", "use-3"),
                toolSpan("Agent", "use-4"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"auth.js\"}", true, 812L, null),
                toolResultLog("Bash", "use-2", "{\"command\":\"yarn build\"}", true, 36_411L, null),
                toolResultLog("Grep", "use-3", "{\"pattern\":\"needle\"}", true, 125_000L, null),
                toolResultLog("Agent", "use-4", "{\"subagent_type\":\"Explore\"}", true, 4_500_000L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("(812ms)");
        assertThat(prompt).contains("(36.4s)");
        assertThat(prompt).contains("(2m 5s)");
        assertThat(prompt).contains("(1h 15m)");
    }

    @Test
    void aToolCallWithNoToolResultLogFallsBackToTheSpansOwnTarget() {
        Span bashSpan = toolSpan("Bash", "use-1");
        bashSpan.getAttributes().put("full_command", "grep -rn needle src/");

        String prompt = buildPrompt(List.of(bashSpan), List.of());

        assertThat(prompt).contains("1. Bash grep -rn needle src/");
    }

    /**
     * A tool_result carrying a large payload says so on its own timeline line -- the missing half
     * of the context-size story, since the overview can say the context grew steeply but nothing
     * previously said which call did it. Gated at the same 20,000-byte bar the markdown report's
     * own oversized-result list uses, so the two surfaces agree on what counts as large.
     */
    @Test
    void aLargeToolResultReportsItsSizeOnItsTimelineLine() {
        List<Span> spans = List.of(toolSpan("Grep", "use-1"));
        LogRecord log = toolResultLog("Grep", "use-1", "{\"pattern\":\"needle\"}", true, 40L, null);
        log.getAttributes().put("tool_result_size_bytes", 52_400L);

        String prompt = buildPrompt(spans, List.of(log));

        assertThat(prompt).contains("[52.4KB result]");
    }

    @Test
    void aSmallToolResultCarriesNoSizeAnnotation() {
        List<Span> spans = List.of(toolSpan("Read", "use-1"));
        LogRecord log = toolResultLog("Read", "use-1", "{\"file_path\":\"auth.js\"}", true, 12L, null);
        log.getAttributes().put("tool_result_size_bytes", 654L);

        String prompt = buildPrompt(spans, List.of(log));

        assertThat(prompt).doesNotContain("result]");
    }

    /**
     * The finding this prevents, from trace 5d6c9ca05d7c6ce12e41a84980693f10: the review reported
     * that the agent shipped "without explicitly confirming the success or failure status of the
     * critical preceding tool calls" and wrote a standing rule demanding a verification step — while
     * the call's own command ended in exactly that verification, past the 200-character head cut.
     * A chained command reads setup && action && verify, so head truncation drops the half that
     * decides whether the finding is true.
     */
    @Test
    void aLongShellCommandKeepsItsTailSoAChainedVerificationStepStaysVisible() {
        Span bashSpan = toolSpan("Bash", "use-1");
        bashSpan.getAttributes().put("full_command", "git add "
                + "backend/src/main/java/com/guavasoft/agentcompass/repository/LogRecordRepository.java "
                + "backend/src/main/java/com/guavasoft/agentcompass/service/LogService.java "
                + "backend/src/test/java/com/guavasoft/agentcompass/SessionsQueryIntegrationTest.java "
                + "&& git commit -m \"fix: apply trace-correlated model and token attribution\" "
                + "&& git push -u origin refactor/normalize-page-component-directories && git status");

        String prompt = buildPrompt(List.of(bashSpan), List.of());

        assertThat(prompt).contains("git add backend/src/main/java/com/guavasoft/agentcompass/rep");
        assertThat(prompt).contains("&& git status");
        assertThat(prompt).contains("chars omitted");
    }

    /**
     * Claude Code truncates long values inside tool_input before export, leaving a "…[N chars]"
     * marker — 531 of 6,826 Bash rows over 30 days. The span's full_command carries the same command
     * whole (0 of 6,847 truncated), so for commands the span wins the tie. Without this the fix
     * above is inert: middle-out over the log's copy would centre on the marker, not the command.
     */
    @Test
    void aCommandIsReadOffTheSpanRatherThanTheLogsUpstreamTruncatedCopy() {
        Span bashSpan = toolSpan("Bash", "use-1");
        bashSpan.getAttributes().put("full_command", "git add Foo.java && git commit -m x && git status");
        LogRecord toolResult = toolResultLog(
                "Bash", "use-1", "{\"command\":\"git add Foo…[1352 chars]\"}", true, 12L, null);

        String prompt = buildPrompt(List.of(bashSpan), List.of(toolResult));

        assertThat(prompt).contains("git add Foo.java && git commit -m x && git status");
        assertThat(prompt).doesNotContain("[1352 chars]");
    }

    @Test
    void aFailedToolCallRendersItsErrorInline() {
        List<Span> spans = List.of(toolSpan("Edit", "use-1"));
        List<LogRecord> logs = List.of(
                toolResultLog("Edit", "use-1", "{\"file_path\":\"gone.js\"}", false, 3L, "File does not exist"));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("-> FAILED: File does not exist");
        assertThat(prompt).contains("## Errors");
    }

    @Test
    void aRejectedToolCallIsMarkedOnItsOwnTimelineLine() {
        List<Span> spans = List.of(toolSpan("Bash", "use-1"));
        List<LogRecord> logs = List.of(
                toolResultLog("Bash", "use-1", "{\"command\":\"rm -rf build\"}", true, 5L, null),
                toolDecisionLog("use-1", "reject"));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("-> REJECTED by user");
    }

    @Test
    void consecutiveIdenticalCallsCollapseWithTheirRepeatCountVisible() {
        List<Span> spans = List.of(
                toolSpan("Read", "use-1"), toolSpan("Read", "use-2"), toolSpan("Read", "use-3"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Read", "use-3", "{\"file_path\":\"Foo.java\"}", true, 4L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("× 3");
    }

    /**
     * The redundancy shape adjacency-only collapsing missed: the same call re-issued at scattered
     * positions. The first occurrence names where it recurs and the later ones shrink to
     * back-references, so the finding is visible AND the timeline gets shorter.
     */
    @Test
    void nonAdjacentRepeatsAreFoldedIntoBackReferencesFromTheFirstOccurrence() {
        List<Span> spans = List.of(
                toolSpan("Read", "use-1"), toolSpan("Grep", "use-2"), toolSpan("Read", "use-3"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"needle\"}", true, 9L, null),
                toolResultLog("Read", "use-3", "{\"file_path\":\"Foo.java\"}", true, 4L, null));

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).contains("[identical call repeats at 3]");
        assertThat(timeline).contains("3. Read (repeat of call 1)");
        assertThat(timeline)
                .as("the repeated call's full input is rendered once, not on every occurrence")
                .containsOnlyOnce("{\"file_path\":\"Foo.java\"}");
    }

    @Test
    void aCallIssuedOnlyOnceCarriesNoRepeatAnnotation() {
        List<Span> spans = List.of(toolSpan("Read", "use-1"), toolSpan("Grep", "use-2"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"needle\"}", true, 9L, null));

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).doesNotContain("identical call repeats at");
        assertThat(timeline).doesNotContain("repeat of call");
    }

    /**
     * The redundancy shape exact-content matching misses entirely, and the more common one: the
     * same file read again at a different offset. Annotation only — the later line keeps its own
     * input, because a second offset is not the first one.
     */
    @Test
    void revisitingTheSameFileWithADifferentInputIsFlaggedOnTheFirstCall() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "Foo.java"),
                toolSpanForFile("Read", "use-2", "Foo.java"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\",\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"file_path\":\"Foo.java\",\"offset\":400}", true, 4L, null));

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).contains("[same file, different input, at 2]");
        assertThat(timeline)
                .as("the second call keeps its own input rather than collapsing to a back-reference")
                .contains("\"offset\":400");
    }

    /**
     * A measured real trace read one file 69 times with 53 distinct inputs; listing every call
     * number would put 50+ of them on a single marker. Past the cap the count carries the finding.
     */
    @Test
    void aLongRunOfRevisitsReportsACappedListPlusARemainder() {
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpanForFile("Read", toolUseId, "Foo.java"));
            logs.add(toolResultLog(
                    "Read", toolUseId, "{\"file_path\":\"Foo.java\",\"offset\":" + i * 100 + "}", true, 4L, null));
        }

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).contains("and 11 more]");
    }

    /** Read-then-Edit on one file is how an edit normally happens; flagging it would be noise. */
    @Test
    void touchingOneFileWithDifferentToolsIsNotFlaggedAsRevisiting() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "Foo.java"),
                toolSpanForFile("Edit", "use-2", "Foo.java"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Edit", "use-2", "{\"file_path\":\"Foo.java\",\"old_string\":\"a\"}", true, 4L, null));

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).doesNotContain("same file, different input");
    }

    /** An exact repeat is already named by its own marker; saying it twice reads as two findings. */
    @Test
    void anExactRepeatIsNotAlsoReportedAsARevisit() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "Foo.java"),
                toolSpanForFile("Grep", "use-2", null),
                toolSpanForFile("Read", "use-3", "Foo.java"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"needle\"}", true, 9L, null),
                toolResultLog("Read", "use-3", "{\"file_path\":\"Foo.java\"}", true, 4L, null));

        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(timeline).contains("[identical call repeats at 3]");
        assertThat(timeline).doesNotContain("same file, different input");
    }

    /**
     * A {@code <task-notification>} envelope is a harness-generated status message, not something a
     * person typed — 13.1% of user_prompt records here. Judging its "wording" is what produced a
     * fabricated "the prompt said 'I'm not sure if this is the right file'" finding on a real trace.
     */
    @Test
    void aMachineAuthoredTaskNotificationIsNotTreatedAsAPromptToJudge() {
        String envelope = "<task-notification>\n<task-id>abc</task-id>\n"
                + "<summary>Agent \"Backend wiring\" finished</summary>\n</task-notification>";

        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of(userPromptLog(envelope)));

        assertThat(prompt).contains("### B. Request quality — not applicable");
        assertThat(prompt).doesNotContain("## User prompt");
        assertThat(prompt).doesNotContain("<task-notification>");
    }

    /** Only a prompt that OPENS with the envelope is machine-authored; quoting one is real text. */
    @Test
    void aHumanPromptThatMerelyQuotesAnEnvelopeIsStillJudged() {
        String humanPrompt = "The subagent output confused me — it said <task-notification> in the middle. Why?";

        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of(userPromptLog(humanPrompt)));

        assertThat(prompt).contains("## User prompt");
        assertThat(prompt).contains("The subagent output confused me");
        assertThat(prompt).doesNotContain("not applicable");
    }

    /**
     * Detection lives in code because the small local models this targets mis-cite call numbers
     * when they scan the timeline themselves (llama3.1 called calls 21/26 "TodoWrite" when they are
     * model calls; qwen said "call 7 is identical to call 1" when call 1 is a model call). Handed
     * these verified lines instead, qwen's citations came out correct.
     */
    @Test
    void verifiedObservationsNameTheRevisitedFileAndTheFailingToolCall() {
        Span catCall = toolSpan("Bash", "use-3");
        catCall.getAttributes().put("full_command", "cat src/Foo.java");
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/SessionsQueryIntegrationTest.java"),
                toolSpanForFile("Read", "use-2", "/a/b/SessionsQueryIntegrationTest.java"),
                catCall);
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"offset\":400}", true, 4L, null),
                toolResultLog("Bash", "use-3", "{\"command\":\"cat src/Foo.java\"}", false, 7L, "no such file"));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("## Verified observations");
        assertThat(prompt).contains("Read b/SessionsQueryIntegrationTest.java touched 2 times, at call(s) 1, 2");
        assertThat(prompt)
                .as("reuses TuningProperties' configured bash-antipattern map, keyed on the first token")
                .contains("`cat` -> Read");
        assertThat(prompt)
                .as("the failing call names its tool, so the finding can say what failed")
                .contains("Tool calls that failed: 3 (Bash)");
    }

    /**
     * The other half of the same trace's evidence: {@code revisitedFiles} used to key on the bare
     * basename too, and the false "re-read CLAUDE.md at calls 52,53,55,190,196,204" observation this
     * produced on trace {@code df8c757de3bfbfe9c1da2b29f431a192} carried a {@code Suggested rule:}
     * line the answer contract copies verbatim into the reader's own CLAUDE.md — one of those six
     * call numbers actually named a different file. Two files sharing a basename in different
     * directories must not be merged into one revisit finding; a genuinely repeated single file must
     * still fire, under its two-segment label.
     */
    @Test
    void twoFilesSharingABasenameInDifferentDirectoriesAreNotMergedAsARevisit() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/frontend/CLAUDE.md"),
                toolSpanForFile("Read", "use-2", "/repo/frontend/src/pages/TraceDetailPage/CLAUDE.md"),
                toolSpanForFile("Read", "use-3", "/repo/frontend/src/pages/TraceDetailPage/CLAUDE.md"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-3", "{\"offset\":400}", true, 4L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt)
                .as("the two different CLAUDE.md files must not be reported as one file touched 3 times")
                .doesNotContain("touched 3 times");
        assertThat(prompt)
                .as("the genuinely repeated file still fires, under its disambiguating two-segment label")
                .contains("Read TraceDetailPage/CLAUDE.md touched 2 times, at call(s) 2, 3");
        assertThat(prompt).doesNotContain("frontend/CLAUDE.md touched");
    }

    /**
     * The paste-ready half of the review. A 7B model asked to invent project-instruction wording
     * writes vague wording; handed a ready-made rule it only has to decide whether the finding
     * applies, and the reader gets something they can actually paste.
     */
    @Test
    void mechanicalObservationsCarryAPasteReadyRuleForTheAnswerToCopy() {
        Span findCall = toolSpan("Bash", "use-3");
        findCall.getAttributes().put("full_command", "find . -name '*.tsx'");
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/Foo.java"),
                toolSpanForFile("Read", "use-2", "/a/b/Foo.java"),
                findCall);
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"offset\":400}", true, 4L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Suggested rule: Default to Glob instead of `find` in Bash.");
        assertThat(prompt).contains("Suggested rule: Read a file once at the length you actually need");
    }

    /**
     * The {@code Tool swap} line is decided in code and told to the second call, rather than left
     * for it to derive from prose that may no longer carry the fact.
     *
     * <p>Trace {@code 9ab1feeebdd15a449bbc4c9983dcb79d} is why: the antipattern fired for `sed` at
     * five of ten Bash calls, the observation and its ready-made rule were both in the prompt, and
     * the answer still came back {@code Tool swap: None} — the first call's prose had dropped the
     * observation, and the second call is contractually gated on what that prose reported.
     */
    @Test
    void theToolSwapLineIsSettledInCodeWhenAShellAntipatternFired() {
        Span sedCall = toolSpan("Bash", "use-1");
        sedCall.getAttributes().put("full_command", "sed -n 260,345p src/Foo.java");

        String prompt = buildApplyThisPrompt(List.of(sedCall), List.of());

        assertThat(prompt).contains("use Read + Edit instead of `sed` in Bash — at call(s) 1");
        assertThat(prompt).contains("do not write None here");
    }

    /** No antipattern, no settled line — the model is asked for the swap exactly as it always was. */
    @Test
    void theToolSwapLineIsLeftToTheModelWhenNoShellAntipatternFired() {
        Span grepCall = toolSpan("Bash", "use-1");
        grepCall.getAttributes().put("full_command", "grep -rn needle src/");

        String prompt = buildApplyThisPrompt(List.of(grepCall), List.of());

        assertThat(prompt).doesNotContain("do not write None here");
        assertThat(prompt).doesNotContain("already decided and is not yours to choose");
    }

    /**
     * One line, so the command with the most calls behind it wins — the others keep their own
     * observation and their own {@code Suggested rule:}, which is where the full set is read.
     */
    @Test
    void theSettledToolSwapNamesTheAntipatternWithTheMostCallsBehindIt() {
        Span catCall = toolSpan("Bash", "use-1");
        catCall.getAttributes().put("full_command", "cat src/Foo.java");
        Span firstSed = toolSpan("Bash", "use-2");
        firstSed.getAttributes().put("full_command", "sed -n 1,50p src/Foo.java");
        Span secondSed = toolSpan("Bash", "use-3");
        secondSed.getAttributes().put("full_command", "sed -n 60,90p src/Bar.java");

        String prompt = buildApplyThisPrompt(List.of(catCall, firstSed, secondSed), List.of());

        assertThat(prompt).contains("use Read + Edit instead of `sed` in Bash — at call(s) 2, 3");
        assertThat(prompt).doesNotContain("use Read instead of `cat`");
    }

    /** A repeated non-Read tool gets the general rule rather than one about reading file slices. */
    @Test
    void aRevisitedTargetOnANonReadToolGetsTheGeneralRuleInstead() {
        List<Span> spans = List.of(
                toolSpanForFile("Grep", "use-1", "/a/b/Foo.java"),
                toolSpanForFile("Grep", "use-2", "/a/b/Foo.java"));
        List<LogRecord> logs = List.of(
                toolResultLog("Grep", "use-1", "{\"pattern\":\"needle\"}", true, 4L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"other\"}", true, 4L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Suggested rule: Before running Grep against something you have already run it");
        assertThat(prompt).doesNotContain("Read a file once");
    }

    /**
     * The bare "Longest model call" line this replaced fired on every trace — every trace has a
     * longest model call — with nothing for the model to compare it against, and what came back was
     * the hedge this whole review exists to avoid ("it's unclear whether this was necessary"). The
     * outlier is only reported when it stands clear of the trace's own median, and carries the
     * output tokens that make it decidable.
     */
    @Test
    void anOutlierModelCallIsReportedWithTheMedianAndOutputTokensThatMakeItJudgeable() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(4_000_000_000L, 300),
                llmRequestSpanLasting(6_000_000_000L, 400),
                llmRequestSpanLasting(52_333_000_000L, 120),
                llmRequestSpanLasting(5_000_000_000L, 350));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains(
                "Outlier model call: call 3 took 52.3s and produced 120 output tokens on claude-sonnet-4");
        assertThat(prompt).contains("10.5x the median model call in this trace (5.0s across 4 model calls)");
    }

    /**
     * The fixture above is the <i>deciding</i> shape — 52.3s for 120 tokens, ~30x the trace's own
     * rate — so it stays an ordinary fault and must not be excused.
     */
    @Test
    void anOutlierThatSpentItsTimeDecidingIsStillAnOrdinaryFault() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(4_000_000_000L, 300),
                llmRequestSpanLasting(6_000_000_000L, 400),
                llmRequestSpanLasting(52_333_000_000L, 120),
                llmRequestSpanLasting(5_000_000_000L, 350));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains("Outlier model call: call 3");
        assertThat(prompt).doesNotContain("the duration is explained by what this call wrote");
    }

    /**
     * The failure this verdict was moved into code for: on trace
     * {@code adae1753270dd3088520435ae7f8af94} the outlier produced 4,676 output tokens — the most
     * of any call in that trace — at a normal rate, and the review reported it as "excessive
     * reasoning", reading the template's two-clause rule exactly backwards. Volume alone cannot
     * decide it (the slowest call is nearly always also the biggest-output call); the rate can.
     */
    @Test
    void anOutlierWhoseLengthIsExplainedByWhatItWroteIsNotReportedAtAll() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(4_000_000_000L, 300),
                llmRequestSpanLasting(6_000_000_000L, 400),
                llmRequestSpanLasting(53_800_000_000L, 4676),
                llmRequestSpanLasting(5_000_000_000L, 350));

        assertThat(buildPrompt(spans, List.of()))
                .as("dropped rather than marked: an earlier revision emitted it with a \"Not a finding:\" "
                        + "note and the review opened a fault about it anyway, twice")
                .doesNotContain("Outlier model call");
    }

    /** A long call that wrote nothing spent all of its time deciding — never the excused branch. */
    @Test
    void anOutlierThatProducedNoOutputAtAllIsNeverExcusedAsWriting() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(4_000_000_000L, 300),
                llmRequestSpanLasting(6_000_000_000L, 400),
                llmRequestSpanLasting(52_333_000_000L, 0),
                llmRequestSpanLasting(5_000_000_000L, 350));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains("Outlier model call: call 3");
        assertThat(prompt).doesNotContain("the duration is explained by what this call wrote");
    }

    @Test
    void theLongestModelCallIsNotReportedWhenItDoesNotStandOutFromTheOthers() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(30_000_000_000L, 900),
                llmRequestSpanLasting(34_000_000_000L, 950),
                llmRequestSpanLasting(38_000_000_000L, 980));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).doesNotContain("Outlier model call");
    }

    /** 2.5x a 900 ms median is not worth a reader's attention, however outlying it is. */
    @Test
    void aShortModelCallIsNeverAnOutlierHoweverFarAboveTheMedianItSits() {
        List<Span> spans = List.of(
                llmRequestSpanLasting(200_000_000L, 40),
                llmRequestSpanLasting(300_000_000L, 50),
                llmRequestSpanLasting(9_000_000_000L, 60));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).doesNotContain("Outlier model call");
    }

    @Test
    void aTraceWithNothingWorthObservingCarriesNoObservationsSection() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/Foo.java"));
        List<LogRecord> logs = List.of(toolResultLog("Read", "use-1", "{\"offset\":0}", true, 4L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).doesNotContain("## Verified observations");
    }

    /**
     * Every assistant turn, not just the last. Earlier revisions kept only the final message, which
     * discarded the agent's narration of what it thought it was doing at each step — the only
     * account of its own intent the trace has.
     */
    @Test
    void everyAssistantTurnIsIncludedWithTheFinalOneMarked() {
        List<LogRecord> logs = List.of(
                assistantResponseLog("First I will locate the failing test."),
                assistantResponseLog("The assertion compares the wrong field."),
                assistantResponseLog("Fixed it and the suite is green."));

        String prompt = buildPrompt(List.of(llmRequestSpan()), logs);

        assertThat(prompt).contains("## What the agent said, in order");
        assertThat(prompt).contains("Message 1: First I will locate the failing test.");
        assertThat(prompt).contains("Message 2: The assertion compares the wrong field.");
        assertThat(prompt).contains("Final message: Fixed it and the suite is green.");
    }

    @Test
    void aTraceWithNoAssistantResponseLogsCarriesNoNarrationSection() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of());

        assertThat(prompt).doesNotContain("## What the agent said, in order");
    }

    @Test
    void anLlmRequestLineCarriesModelTokensStopReasonAndRetries() {
        Span llmSpan = llmRequestSpan();
        llmSpan.getAttributes().put("attempt", "2");

        String prompt = buildPrompt(List.of(llmSpan), List.of());

        assertThat(prompt).contains("model=claude-sonnet-4");
        assertThat(prompt).contains("output_tokens=450");
        assertThat(prompt).contains("cache_read_tokens=12000");
        assertThat(prompt).contains("stop=end_turn");
        assertThat(prompt).contains("retry_attempt=2");
    }

    @Test
    void theOverviewCarriesTheSameHeadlineFiguresTheTraceDetailPageShows() {
        List<Span> spans = List.of(toolSpan("Read", "use-1"), llmRequestSpan());

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains("Tool calls: 1");
        assertThat(prompt).contains("Model calls: 1");
        assertThat(prompt).contains("claude-sonnet-4");
        assertThat(prompt).contains("Tokens: 800 input, 450 output, 12k cache read, 300 cache creation");
        assertThat(prompt).contains("Cost: $0.0100");
    }

    /**
     * The prompt-token mix, phrased as the question the reader has. Output tokens stay out of the
     * denominator: they are what the model wrote, not what it had to read, and folding them in
     * would let a chatty trace look cache-efficient.
     */
    @Test
    void theOverviewReportsTheCacheMixAndTheMainLoopsContextSize() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of());

        assertThat(prompt).contains("of the 13k tokens sent into the models, 91.6% were cache");
        assertThat(prompt).contains("the first model call sent 13k prompt tokens and\nthe largest sent 13k");
    }

    /**
     * The overview compares a call's token figures against this database's own benchmark text
     * ("typical traces here start around 100k and three quarters of them never exceed 170k"), so a
     * bare seven-digit integer would force that comparison to be done by the reader -- or by the
     * reviewing model in prose, which is exactly the arithmetic {@code formatDuration} already keeps
     * out of a 7B model's hands. Below 1,000 the exact count survives, since nothing in that range is
     * ever compared against a rounded benchmark.
     */
    @Test
    void tokenCountsInTheOverviewAreShownInTheSameUnitsAsTheirBenchmarkText() {
        String prompt = buildPrompt(List.of(llmRequestSpanWithContextTokens(2_300_000L)), List.of());
        // Scoped to the Overview block deliberately -- the "Verified observations" section below it
        // renders the identical figure through contextSizeObservations' own, independent formatting
        // (raw, for a different reason -- see backend/CLAUDE.md), so a whole-prompt check for the
        // absence of the raw number would be false by design, not a regression.
        String overview = prompt.substring(0, prompt.indexOf("## Verified observations"));

        assertThat(overview).contains("the first model call sent 2.3M prompt tokens");
        assertThat(overview).doesNotContain("2300000");
    }

    /**
     * Cost cannot be read off the {@code llm_request} span: measured over 7 days, every dollar in
     * the span_costs view sits on an interaction or tool.execution span, because request logs carry
     * whichever span was merely OPEN when the call was issued. The request_id join is what puts the
     * money on the call that spent it.
     */
    @Test
    void perCallCostIsJoinedFromTheApiRequestLogByRequestId() {
        Span modelCall = modelCall("llm-1", null, "req-1");

        String prompt = buildPrompt(List.of(modelCall), List.of(apiRequestLog("req-1", "sdk", 0.4213)));

        assertThat(prompt).contains("cost=$0.4213");
    }

    /**
     * Sub-agent work runs in this same trace, nested under the Agent call that dispatched it. Left
     * unmarked, a trace where one Explore run made most of the calls reads as a main agent that
     * could not stop searching — and the money it spent looks like the main loop's.
     */
    @Test
    void subagentCallsAreMarkedAndTheDispatchLineCarriesItsRunsTotals() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"),
                subagentToolSpan("Grep", "use-2", "exec-1"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90),
                toolResultLog("Agent", "use-1", "{\"subagent_type\":\"Explore\"}", true, 30_000L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"needle\"}", true, 9L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt)
                .as("the dispatching Agent call reports what the run it started did")
                .contains("[ran Explore: 1 model calls, 1 tool calls, $0.9000]");
        assertThat(prompt)
                .as("the sub-agent's own calls are attributed to it rather than to the main loop")
                .contains("[Explore] Grep");
        assertThat(prompt).contains("- Main loop: $0.1000 across 1 model calls");
        assertThat(prompt).contains("- Subagent Explore dispatched at call 2: $0.9000 across 1 model calls");
    }

    /**
     * The whole point of following the span into the sub-agent: saying WHICH one spent the money.
     * Gated like the outlier model call — every trace that dispatched a sub-agent spent something
     * on it, so an ungated line would report a tautology.
     */
    @Test
    void aSubagentThatCarriedMostOfTheSpendIsNamedAsWhatDroveTheCost() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Most of this trace's model spend went to one subagent: "
                + "Subagent Explore dispatched at call 2 cost $0.9000 of the $1.0000 "
                + "its model calls account for (90.0%)");
    }

    @Test
    void aSubagentThatCostAMinorityOfTheSpendIsNotReportedAsDrivingIt() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.90),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.10));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).doesNotContain("went to one subagent");
        assertThat(prompt)
                .as("the split itself is still shown — it is context, not a finding")
                .contains("Subagent Explore dispatched at call 2");
    }

    /** Session-title generation and compaction are the harness's spend, not the agent's choices. */
    @Test
    void auxiliaryRequestsAreSplitOutFromTheAgentsOwnWork() {
        List<Span> spans = List.of(modelCall("llm-1", null, "req-1"), modelCall("llm-2", null, "req-2"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.50),
                apiRequestLog("req-2", "generate_session_title", 0.01));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("- Main loop: $0.5000 across 1 model calls");
        assertThat(prompt).contains("- Auxiliary (session titles, compaction, web fetch): $0.0100");
    }

    /**
     * The median trace here already reads 96.8% of its prompt tokens from cache, so "cache reuse
     * was good" is not a finding — only the opposite is. Both directions are pinned.
     */
    @Test
    void poorCacheReuseIsObservedWithTheDatabaseMedianItIsMeasuredAgainst() {
        List<Span> spans = List.of(
                cacheShapedModelCall("llm-1", 1_000L, 500L, 20_000L),
                cacheShapedModelCall("llm-2", 1_000L, 500L, 20_000L),
                cacheShapedModelCall("llm-3", 1_000L, 500L, 20_000L));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains("Prompt cache was barely reused: only 2.3%");
        assertThat(prompt).contains("against a median of 96.8%");
    }

    @Test
    void healthyCacheReuseIsNotReportedAsAnObservationAtAll() {
        List<Span> spans = List.of(
                cacheShapedModelCall("llm-1", 500L, 40_000L, 1_000L),
                cacheShapedModelCall("llm-2", 500L, 40_000L, 1_000L),
                cacheShapedModelCall("llm-3", 500L, 40_000L, 1_000L));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).doesNotContain("Prompt cache was barely reused");
    }

    /**
     * A request that opens on a large inherited context re-sends that history on every call it
     * makes, and no rewording of the request fixes it — starting a fresh session does. One of only
     * three observations whose remedy is mechanical enough to carry its own rule.
     */
    @Test
    void aRequestThatBeganOnAnAlreadyLoadedContextIsReportedWithTheFreshSessionRule() {
        Span firstCall = cacheShapedModelCall("llm-1", 1_000L, 249_000L, 0L);

        String prompt = buildPrompt(List.of(firstCall), List.of());

        assertThat(prompt).contains("This request began on an already-loaded context: "
                + "its first model call sent 250000 prompt tokens");
        assertThat(prompt).contains("Suggested rule: Start a task that is unrelated to the one before it in a "
                + "fresh session");
    }

    @Test
    void anOrdinarySizedContextIsNotReportedAsAlreadyLoaded() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of());

        assertThat(prompt).doesNotContain("began on an already-loaded context");
    }

    /** Steep growth within one request usually means several jobs were asked for at once. */
    @Test
    void aContextThatGrewSteeplyWithinOneRequestIsReported() {
        List<Span> spans = List.of(
                cacheShapedModelCall("llm-1", 1_000L, 49_000L, 0L),
                cacheShapedModelCall("llm-2", 1_000L, 399_000L, 0L));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains("The context grew steeply while this request ran: from 50000 prompt tokens "
                + "on the first model call to 400000 at its largest");
    }

    /**
     * The one thing in this feature that tells a reader their <i>wording</i> worked. Gated to the
     * FIRST tool call: a match anywhere later proves nothing, because whatever ran before it is
     * exactly the searching this claims did not happen. Both directions are pinned, along with the
     * two ways the gate is meant to fail closed.
     */
    @Test
    void aRequestThatNamedItsTargetIsCreditedWhenTheAgentWentStraightThere() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/b/TraceService.java"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("Fix the null check in TraceService.java")));

        assertThat(prompt).contains("Went well: the request named its target and the agent went straight to it");
        assertThat(prompt).contains("(call 1, Read TraceService.java)");
    }

    @Test
    void aTargetTheAgentHadToSearchForFirstIsNotCreditedAsADirectedStart() {
        List<Span> spans = List.of(
                toolSpanForFile("Grep", "use-1", null),
                toolSpanForFile("Read", "use-2", "/a/b/TraceService.java"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("Fix the null check in TraceService.java")));

        assertThat(prompt).doesNotContain("Went well:");
    }

    @Test
    void aFileTheRequestNeverNamedIsNotCreditedHoweverDirectlyTheAgentOpenedIt() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/b/TraceService.java"));

        String prompt = buildPrompt(spans, List.of(userPromptLog("fix the failing test")));

        assertThat(prompt).doesNotContain("Went well:");
    }

    /**
     * The bug that motivated widening past {@code file_path}: on trace
     * {@code 73590130fdbec1b4f2c89217103fb3db} the request named an absolute directory and the very
     * first call was {@code Bash ls -la <that path>} — a directed start Bash spans can never carry
     * {@code file_path} for (see {@code shellAntipatternFor}'s note that it is a {@code full_command}
     * attribute instead) — and the old file-path-only gate reported nothing, misreading "no
     * file_path" as "was a search" for every Bash call including this one.
     */
    @Test
    void aBashCallTargetingThePathTheRequestNamedIsCreditedAsADirectedStart() {
        Span lsCall = toolSpan("Bash", "use-1");
        lsCall.getAttributes().put("full_command", "ls -la /Users/guadalupegarcia/handoff-analyze-trace-loading");

        String prompt = buildPrompt(
                List.of(lsCall),
                List.of(userPromptLog(
                        "apply the handoff in /Users/guadalupegarcia/handoff-analyze-trace-loading")));

        assertThat(prompt).contains("Went well: the request named its target and the agent went straight to it");
        assertThat(prompt).contains("(call 1, Bash handoff-analyze-trace-loading)");
    }

    /** A real Bash search still earns no credit: the command names no path the request gave it. */
    @Test
    void aBashSearchCommandIsNotCreditedAsADirectedStart() {
        Span grepCall = toolSpan("Bash", "use-1");
        grepCall.getAttributes().put("full_command", "grep -rn needle src/");

        String prompt = buildPrompt(
                List.of(grepCall), List.of(userPromptLog("find where needle is used in src/")));

        assertThat(prompt).doesNotContain("Went well:");
    }

    /**
     * Once the directed start is verified, the answer contract must be told in code, not just in
     * prose — a small model already lost this exact fight once (see
     * {@code answersPrecedingQuestion}'s "yes fix it" case). {@code wordingAlreadySettled} is the
     * code-side verdict this observation now also feeds, alongside
     * {@code answersPrecedingQuestion}.
     */
    @Test
    void aDirectedStartSettlesTheWordingSectionAndPreAnswersBetterWordingAsNone() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/b/TraceService.java"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("Fix the null check in TraceService.java")));

        assertThat(prompt)
                .as("the wording bullets have nothing left to judge once the target was verified as named")
                .doesNotContain("Unnamed target")
                .doesNotContain("Ambiguity that cost work");
        assertThat(prompt).contains("already been settled");
        assertThat(buildApplyThisPrompt(spans, List.of(userPromptLog("Fix the null check in TraceService.java"))))
                .as("and the second call, which owns the wording line, is told the answer is None")
                .contains("For this trace that line is None");
    }

    /**
     * The false positive this gate fixes. Trace {@code 24e1af7c5582529fe62dcacabe2d0a23}'s request —
     * "when toggling to disabled the save button is disabled too" — names no file and asks no
     * question, so neither {@code requestWellAimed} nor {@code requestIsQuestion} could ever settle
     * it, and the review Ollama actually produced faulted the wording anyway ("leading the agent to
     * perform multiple, sequential, and overlapping tasks ... instead of focusing on a single,
     * actionable fix") on a trace that is one coherent thread: grep the bug, read the file, fix it
     * and its own doc comment, grep twice more and read to check the same stale claim elsewhere, fix
     * that doc too, verify with a build. This fixture reproduces that shape. See
     * {@code focusedResolutionHolds}' own javadoc for the measured population behind the threshold.
     */
    @Test
    void anUnbrokenSearchThenEditChainSuppressesTheAmbiguityBulletButNotUnnamedTarget() {
        List<Span> spans = List.of(
                toolSpan("Bash", "use-1"),
                toolSpanForFile("Read", "use-2", "OllamaConfigurationCard.tsx"),
                toolSpanForFile("Edit", "use-3", "OllamaConfigurationCard.tsx"),
                toolSpanForFile("Edit", "use-4", "OllamaConfigurationCard.tsx"),
                toolSpan("Bash", "use-5"),
                toolSpan("Bash", "use-6"),
                toolSpanForFile("Read", "use-7", "SettingsPage/CLAUDE.md"),
                toolSpanForFile("Edit", "use-8", "SettingsPage/CLAUDE.md"),
                toolSpan("Bash", "use-9"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("when toggling to disabled the save button is disabled too")));

        assertThat(prompt)
                .as("the request never says whether the vagueness cost anything, and the trace shows it did not")
                .doesNotContain("wording open enough that the agent had to guess")
                .contains("Ambiguity that cost work** does not apply here");
        assertThat(prompt)
                .as("this verdict says nothing about whether the unnamed target is itself a fault -- that "
                        + "bullet stays open")
                .contains("Unnamed target");
    }

    /**
     * The negative case a suppression gate needs most: a trace that DOES wander must not get the
     * benefit of the doubt. The second {@code Edit} lands on a file nothing before it ever read or
     * searched, which is exactly the "focused on the wrong thing" shape {@code focusedResolutionHolds}
     * has to fail closed on.
     */
    @Test
    void aMutatingCallOnAFileNeverSearchedFirstDoesNotSuppressTheAmbiguityBullet() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "OllamaConfigurationCard.tsx"),
                toolSpanForFile("Edit", "use-2", "OllamaConfigurationCard.tsx"),
                toolSpanForFile("Edit", "use-3", "UnrelatedComponent.tsx"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("when toggling to disabled the save button is disabled too")));

        assertThat(prompt).contains("wording open enough that the agent had to guess");
    }

    /**
     * The read-only half of the same gate, and the trace that forced it. On
     * {@code 1c428d417f2e6f6ec1ae6ccc0f8b2ed9} — "review the plan and let me know if there are any
     * improvments that can be made" — the first tool call read exactly the plan the request meant
     * and nothing after it wrote a file, so {@code focusedResolutionHolds} could never fire
     * (it ends on {@code hasMutatingFileCall}) and the review faulted the wording for "not
     * specifying the exact target or scope". A later failure must NOT veto the verdict: the
     * evidence is the first call, and the failure has its own bullet.
     */
    @Test
    void aReadOnlyTraceThatWentStraightToItsFileSuppressesTheAmbiguityBulletDespiteALaterFailure() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/.design-docs/agent-compass-mcp-server-plan.md"),
                toolSpan("Bash", "use-2"),
                toolSpan("Bash", "use-3"));

        String prompt = buildPrompt(spans, List.of(
                userPromptLog("review the plan and let me know if there are any improvments that can be made"),
                toolResultLog("Read", "use-1", "{\"file_path\":\"/a/b/plan.md\"}", true, 5, null),
                toolResultLog("Bash", "use-2", "{}", true, 20, null),
                toolResultLog("Bash", "use-3", "{}", false, 62, "Shell command failed")));

        assertThat(prompt)
                .as("a trace that changed nothing can never clear the chain verdict, however focused it was")
                .doesNotContain("wording open enough that the agent had to guess")
                .contains("Ambiguity that cost work** does not apply here")
                .contains("the trace changed nothing, its very first tool call went straight to a named file");
        assertThat(prompt)
                .as("the sentence must describe the verdict that actually held -- the chain wording states a "
                        + "fact this trace can check and find false")
                .doesNotContain("every call that changed a file followed a search");
        assertThat(prompt)
                .as("suppressing the wording bullet hides neither the failure nor the unnamed target")
                .contains("Shell command failed")
                .contains("Unnamed target");
    }

    /**
     * The negative case this verdict needs most. A first call carrying no {@code file_path} is a
     * search — the agent working out where to look — which is exactly the cost the ambiguity bullet
     * exists to report, so it must not be credited as an arrival.
     */
    @Test
    void aReadOnlyTraceThatOpenedWithASearchDoesNotSuppressTheAmbiguityBullet() {
        List<Span> spans = List.of(
                toolSpan("Bash", "use-1"),
                toolSpanForFile("Read", "use-2", "/a/b/ReportService.java"));

        String prompt = buildPrompt(spans, List.of(
                userPromptLog("review the plan and let me know if there are any improvments that can be made")));

        assertThat(prompt).contains("wording open enough that the agent had to guess");
    }

    /**
     * The whole verdict rests on one call, so that call having failed leaves it with no evidence at
     * all — the read-only analogue of the sibling gate's no-failed-search rule.
     */
    @Test
    void aReadOnlyTraceWhoseFirstCallFailedDoesNotSuppressTheAmbiguityBullet() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/.design-docs/agent-compass-mcp-server-plan.md"),
                toolSpan("Bash", "use-2"));

        String prompt = buildPrompt(spans, List.of(
                userPromptLog("review the plan and let me know if there are any improvments that can be made"),
                toolResultLog("Read", "use-1", "{}", false, 3, "File does not exist")));

        assertThat(prompt).contains("wording open enough that the agent had to guess");
    }

    /**
     * The same fence the fault half already has: {@code /ship} is a command name, not wording
     * anyone chose, so it can no more earn credit for naming a target than it can be called vague.
     */
    @Test
    void aSlashCommandNeverEarnsCreditForNamingItsTarget() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/b/TraceService.java"));

        String prompt = buildPrompt(
                spans, List.of(slashCommandPromptLog("/review TraceService.java", "review")));

        assertThat(prompt).doesNotContain("Went well:");
    }

    /**
     * A dominant subagent has always been reported; what is new is saying WHY it was the right call
     * when the main loop's own context proves it. Without this the reader gets a big number with a
     * caveat attached, which reads as a fault.
     */
    @Test
    void aDominantSubagentIsCreditedWhenItKeptWorkOutOfAnAlreadyLoadedMainContext() {
        Span mainLoopCall = llmRequestSpanWithContextTokens(250_000L);
        mainLoopCall.setSpanId("llm-1");
        mainLoopCall.getAttributes().put("request_id", "req-1");
        List<Span> spans = List.of(
                mainLoopCall,
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Went well: the main loop was already carrying 250000 prompt tokens");
        assertThat(prompt).contains("The dominant share here is the dispatch doing its job, not a fault.");
    }

    @Test
    void aDominantSubagentOnASmallMainContextIsReportedWithoutThatCredit() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Most of this trace's model spend went to one subagent");
        assertThat(prompt).doesNotContain("Went well:");
    }

    /**
     * The section exists so a clean trace has somewhere true to put its output instead of
     * manufacturing a fault — but a section that appeared on every trace would be the ungated
     * praise line this class gates everything else to avoid. So it is rendered only when a positive
     * was actually computed, and the answer contract's section count moves with it.
     */
    @Test
    void aTraceWithNoVerifiedPositiveIsNeverAskedForAWhatWentWellSection() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of(userPromptLog("fix the failing test")));

        assertThat(prompt).doesNotContain("**What went well**");
        assertThat(prompt).contains("Write exactly the following one section");
    }

    @Test
    void aVerifiedPositiveAddsTheWhatWentWellSectionToTheAnswerContract() {
        List<Span> spans = List.of(toolSpanForFile("Read", "use-1", "/a/b/TraceService.java"));

        String prompt = buildPrompt(
                spans, List.of(userPromptLog("Fix the null check in TraceService.java")));

        assertThat(prompt).contains("Write exactly the following two sections");
        assertThat(prompt).contains("**What went well**");
        assertThat(prompt).contains("At most two bullets, drawn ONLY from the \"Went well:\" observations above");
        assertThat(prompt)
                .as("a positive must never turn into a standing rule the reader pastes anywhere")
                .contains("produces no rule, no tool swap and no wording advice");
    }

    /**
     * The false finding this fixes: "do 1 and 2" / "yes implement those two" read on their own look
     * like under-specified wording, and the review duly called them vague. They are exact answers
     * to the message that listed the options — a real pair from this database, whose previous turn
     * ends "Want me to implement those two?".
     */
    @Test
    void theTailOfTheTurnBeingRepliedToIsIncludedSoAFollowUpIsNotReadAsVague() {
        LogRecord precedingTurn = assistantResponseLog(
                "Two things would help. Whole-trace repeat grouping, and middle-out trimming. "
                        + "Want me to implement those two?");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("yes implement those two")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).contains("## What the agent said just before this request");
        assertThat(prompt).contains("4 minutes before this");
        assertThat(prompt).contains("Want me to implement those two?");
        assertThat(prompt)
                .as("and the review is told the wording question is settled rather than left to resolve it")
                .contains("The wording of this request has already been settled");
    }

    /**
     * The prose fence above was not enough, and this is the measured proof. Trace
     * 83b37f35664d88848e00d20be9f737ae rendered the preceding turn correctly — it ends "Want me to
     * fix `model` the same way …?", the request was "yes fix it", and both the scoping paragraph and
     * section B's "is NOT ambiguous on that ground" line were in the prompt — and the review still
     * reported "the user prompt 'yes fix it' is too vague" and spent its {@code Better wording:}
     * line re-specifying work the reader had just been asked a yes/no question about. Two fences of
     * prose lost to a three-word prompt, so the decision moves into code and the model is handed a
     * verdict instead of an instruction.
     */
    @Test
    void aShortReplyToATurnThatEndsInAQuestionHasItsWordingRuledOutAsAFinding() {
        LogRecord precedingTurn = assistantResponseLog(
                "Want me to fix `model` the same way — add a trace-scoped correction in "
                        + "applyTraceCorrelatedActivity? It's slightly trickier than tokens.");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("yes fix it")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).contains("The wording of this request has already been settled");
        assertThat(prompt).contains("Do not call it vague, short, under-specified");
        assertThat(prompt)
                .as("the two wording faults are removed outright, not merely qualified")
                .doesNotContain("**Unnamed target**")
                .doesNotContain("**Ambiguity that cost work**");
        assertThat(prompt)
                .as("drift survives -- going somewhere the exchange never invited is still a real fault")
                .contains("**Drift**");
        assertThat(prompt)
                .as("and it is stated as a verified fact, not left to the model to infer from prose")
                .contains("Went well: the request is an answer, not an ask");
        assertThat(promptBuilder.buildApplyThisPrompt(
                FINDINGS_FIXTURE,
                promptBuilder.build(
                        traceSummary(),
                        List.of(llmRequestSpan()),
                        List.of(userPromptLog("yes fix it")),
                        precedingTurn, null).applyThisInputs()))
                .as("and the second call, which owns the wording line, is pre-answered None")
                .contains("For this trace that line is None.");
    }

    /** A question mark in the preceding turn does not license suppressing a request long enough to
     * have specified its own target: the gate is the pair, and 62% of prompts in this database are
     * short. */
    @Test
    void aFullyWordedRequestAfterAQuestionIsStillJudgedOnItsWording() {
        LogRecord precedingTurn = assistantResponseLog("Want me to implement those two?");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("Ignore those two for now. Go through every repository in the "
                        + "backend and find the queries that still filter on the raw attribute "
                        + "expression instead of the generated column, then fix them.")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).contains("**Ambiguity that cost work**");
        assertThat(prompt).doesNotContain("The wording of this request has already been settled");
    }

    /** And a short request that answers nothing keeps its wording half too — a preceding turn that
     * merely reports what it did leaves the target unnamed anywhere. */
    @Test
    void aShortRequestAfterATurnThatAsksNothingIsStillJudgedOnItsWording() {
        LogRecord precedingTurn = assistantResponseLog("Model fix complete. All four are now trace-correlated.");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("now do the other one")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).contains("**Unnamed target**");
        assertThat(prompt).doesNotContain("The wording of this request has already been settled");
    }

    /**
     * A request that is itself a question has no target to name, so its wording is settled in code
     * rather than left to the template's prose rule — which lost. On trace
     * {@code 8f98bde5c347e9844362370f41f1c5ac} this exact request produced a {@code Better wording}
     * line telling the reader to list `SystemController.java` and `OllamaClient.java`: files that
     * trace existed to discover. See {@code requestIsQuestion}.
     */
    @Test
    void aRequestThatIsItselfAQuestionHasItsWordingSettledInCode() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("On the settings page for Ollama section can the models "
                        + "available be listed in a drop down?")),
                null, null).windows().get(0).text();

        assertThat(prompt).contains("The wording of this request has already been settled");
        assertThat(prompt).contains("It is a question, not an instruction");
        assertThat(prompt).doesNotContain("**Unnamed target**");
        assertThat(prompt).doesNotContain("**Ambiguity that cost work**");
    }

    /**
     * A question written without its mark is still a question. Trace
     * {@code 9ab1feeebdd15a449bbc4c9983dcb79d} is why this half exists: "can the phase timeline be
     * condensed any" is a yes/no question the agent answered "Yes —", and on one regeneration all
     * three findings were restatements of its supposed vagueness, two of them quoting section B's own
     * bullet names back as findings. Measured over 30 days, the opener half takes the gate from 156
     * of 751 human prompts to 209 — see {@code QUESTION_OPENERS}.
     */
    @Test
    void aQuestionWrittenWithoutItsMarkIsStillSettledInCode() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("can the phase timeline be condensed any")),
                null, null).windows().get(0).text();

        assertThat(prompt).contains("The wording of this request has already been settled");
        assertThat(prompt).doesNotContain("**Unnamed target**");
        assertThat(prompt).doesNotContain("**Ambiguity that cost work**");
    }

    /**
     * Bare {@code do} opens an imperative, not a question, and is the one opener left off the list
     * for it — all five matches in the measured window were instructions like this one.
     */
    @Test
    void anImperativeOpeningWithDoIsStillJudgedOnItsWording() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("do a code review on uncommitted changes")),
                null, null).windows().get(0).text();

        assertThat(prompt).doesNotContain("The wording of this request has already been settled");
        assertThat(prompt).contains("**Unnamed target**");
    }

    /** First word only — an instruction with a question word buried in it is still an instruction. */
    @Test
    void aQuestionWordAwayFromTheStartDoesNotSettleTheWording() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("fix the cache-read total, can you")),
                null, null).windows().get(0).text();

        assertThat(prompt).doesNotContain("The wording of this request has already been settled");
        assertThat(prompt).contains("**Unnamed target**");
    }

    /**
     * Ends-with, not contains: an instruction carrying a parenthetical aside is still an instruction,
     * and narrowing to the trailing mark is what keeps this gate off 16 of the 165 prompts a
     * contains-test would have caught.
     */
    @Test
    void anInstructionMerelyContainingAQuestionMarkIsStillJudgedOnItsWording() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("fix the token thing (did you see the failing test?) and then "
                        + "go clean up whatever else looks wrong in there")),
                null, null).windows().get(0).text();

        assertThat(prompt).doesNotContain("The wording of this request has already been settled");
        assertThat(prompt).contains("**Unnamed target**");
    }

    /**
     * Settling the wording must not manufacture praise. Unlike its two sibling verdicts this one
     * contributes no {@code Went well:} line — asking a question is not the reader's wording doing a
     * job worth crediting, and positives are gated harder than faults here.
     */
    @Test
    void aQuestionSettlesTheWordingWithoutContributingAPositiveObservation() {
        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("are the tool usage totals on that page correct?")),
                null, null).windows().get(0).text();

        assertThat(prompt).contains("The wording of this request has already been settled");
        assertThat(prompt).doesNotContain("Went well:");
        assertThat(prompt).doesNotContain("**What went well**");
    }

    @Test
    void aTraceWithNoPrecedingTurnCarriesNoSuchSection() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of(userPromptLog("Fix the bug in auth.js")));

        assertThat(prompt).doesNotContain("## What the agent said just before this request");
        assertThat(prompt).doesNotContain("is NOT ambiguous on that ground");
    }

    @Test
    void bothReviewSectionsAreAskedForWhenTheTraceHasAUserPrompt() {
        String prompt = buildPrompt(List.of(), List.of(userPromptLog("Fix the bug in auth.js")));

        assertThat(prompt).contains("### A. Agent execution quality");
        assertThat(prompt).contains("### B. Request quality");
        assertThat(prompt).contains("Fix the bug in auth.js");
        assertThat(prompt).doesNotContain("not applicable");
    }

    /**
     * The half of the review the reader actually acts on. Findings that stop at "this happened"
     * are what the answer contract exists to prevent, so the contract itself is pinned.
     */
    @Test
    void theAnswerContractDemandsAFixPerFindingAndAPasteReadyApplyThisSection() {
        List<LogRecord> logs = List.of(userPromptLog("Fix the bug in auth.js"));

        assertThat(buildPrompt(List.of(), logs))
                .as("the findings call owns the fix-per-finding rule and stops before Apply this")
                .contains("Every finding ends in a change.")
                .doesNotContain("**Apply this**");
        assertThat(buildApplyThisPrompt(List.of(), logs))
                .as("the three lines are the second call's whole job")
                .contains("Instruction rule:")
                .contains("Tool swap:")
                .contains("Better wording:");
    }

    /**
     * The regression this whole slash-command path exists for. Trace
     * 5d6c9ca05d7c6ce12e41a84980693f10 was started by {@code /ship} and the review reported "the
     * user prompt {@code /ship} is too vague", then told the reader to replace the command with
     * three literal git commands — abandoning the skill the command invokes. The prompt text is a
     * command name, not wording anyone chose, so the request half must be dropped exactly as it is
     * for a {@code <task-notification>} envelope.
     */
    @Test
    void aSlashCommandInvocationIsNotJudgedAsPromptWording() {
        String prompt = buildPrompt(
                List.of(llmRequestSpan()),
                List.of(slashCommandPromptLog("/ship", "ship"),
                        skillActivatedLog("ship", "projectSettings", "user-slash")));

        assertThat(prompt).contains("### A. Agent execution quality");
        assertThat(prompt).contains("### B. Request quality — not applicable");
        assertThat(prompt).contains("## How this trace was started");
        assertThat(prompt).contains("That command text is not a request anyone worded.");
        // The command itself is still shown -- dropping the request half must not also hide what
        // started the trace, which the execution half needs to make sense of the calls.
        assertThat(prompt).contains("`/ship`");
        assertThat(prompt).doesNotContain("## User prompt (full text)");
    }

    /**
     * The second finding the same trace produced, after the slash-command fix above closed the
     * first. With section B dropped and a clean 31-second trace leaving the observation block empty,
     * the preceding turn was the only free prose left in the prompt — and the review's sole bullet
     * became "the agent addressed two distinct architectural fixes (Token attribution and Model
     * attribution) in a single session", lifted whole from the previous turn's summary of work that
     * had already shipped in an earlier trace. A command has no wording to resolve, so the section
     * that exists to resolve wording must not be rendered for it.
     */
    @Test
    void aSlashCommandTraceIsNotShownThePrecedingTurnItHasNoWordingToResolveAgainst() {
        LogRecord precedingTurn = assistantResponseLog(
                "Model fix complete. Between the two fixes, cost, tools, tokens, and now model "
                        + "are all trace-correlated in the Sessions drawer.");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(slashCommandPromptLog("/ship", "ship"),
                        skillActivatedLog("ship", "projectSettings", "user-slash")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).doesNotContain("## What the agent said just before this request");
        assertThat(prompt)
                .as("the earlier turn's subject matter must not reach the model at all")
                .doesNotContain("trace-correlated");
        // The execution half is untouched -- suppressing the section removes context for judging
        // wording, not the trace's own calls.
        assertThat(prompt).contains("### A. Agent execution quality");
    }

    /**
     * The section stays for a real prompt, but is fenced: it is context for reading the request, not
     * a second body of work to review. Without the fence a clean trace with an unambiguous prompt
     * has the same empty-observation gap the slash-command case had.
     */
    @Test
    void thePrecedingTurnIsScopedToResolvingTheRequestAndIsNotItselfJudged() {
        LogRecord precedingTurn = assistantResponseLog("Want me to implement those two?");
        precedingTurn.setTimestamp(BASE_TIME.minusSeconds(240));

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog("yes implement those two")),
                precedingTurn, null).windows().get(0).text();

        assertThat(prompt).contains("is not this trace's work, and is not yours to judge");
        assertThat(prompt).contains("If the request below is not ambiguous, this section has nothing to contribute");
    }

    /**
     * A continuation trace is woken by a background task finishing, so it necessarily resumes a
     * loaded context. The starting-context observation carries a pre-written rule the answer
     * contract tells the model to copy verbatim — "start unrelated work in a fresh session" — and on
     * trace 1635329e1e7db7f934b007d90aba7d61 it did exactly that, advice impossible to follow when
     * the notification is about work in this very session.
     *
     * <p>Section C's own "Was this the right session?" bullet carried the identical unfollowable
     * advice by a second route — it was never gated on {@code isContinuation} at all, so a
     * continuation trace was still handed the fresh-session prescription as one of "What to look
     * for"'s own questions even after the observation above was fixed.
     */
    @Test
    void aContinuationTraceIsNotToldItsLoadedContextIsAFault() {
        String prompt = buildPrompt(
                List.of(llmRequestSpanWithContextTokens(204_326L)),
                List.of(userPromptLog(taskNotification("toolu_01Lw", "completed (exit code 0)"))));

        assertThat(prompt).doesNotContain("began on an already-loaded context");
        assertThat(prompt).doesNotContain("fresh session rather than continuing an existing one");
        assertThat(prompt).doesNotContain("Was this the right session?");
        assertThat(prompt).doesNotContain("start it in a fresh session");
        assertThat(prompt).contains("## What this trace is continuing");
        assertThat(prompt).contains("A large starting context is expected here and is NOT a finding");
    }

    /** The same context size on an ordinary trace is still a finding — the suppression is scoped. */
    @Test
    void anOrdinaryTraceStillReportsALargeStartingContext() {
        String prompt = buildPrompt(
                List.of(llmRequestSpanWithContextTokens(204_326L)),
                List.of(userPromptLog("Fix the bug in auth.js")));

        assertThat(prompt).contains("began on an already-loaded context");
        assertThat(prompt).contains("fresh session rather than continuing an existing one");
        assertThat(prompt).contains("Was this the right session?");
        assertThat(prompt).doesNotContain("## What this trace is continuing");
    }

    /**
     * The dispatching call lives in a different trace, so it is fetched by the tool_use_id the
     * envelope quotes back and passed in. It is the only thing pulled across that boundary: the
     * dispatching trace had 98 spans against this trace's 6, so merging its timeline would make the
     * review a review of the other trace.
     */
    @Test
    void aContinuationNamesTheCallThatDispatchedItWhenThatCallResolves() {
        LogRecord dispatchingCall = toolResultLog(
                "Bash",
                "toolu_01Lw",
                "{\"command\":\"./backend/mvnw verify\",\"run_in_background\":true}",
                true,
                9L,
                null);
        dispatchingCall.setTimestamp(BASE_TIME.minusSeconds(54));
        dispatchingCall.setTraceId("83b37f35664d88848e00d20be9f737ae");

        String prompt = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(userPromptLog(taskNotification("toolu_01Lw", "completed (exit code 0)"))),
                null,
                dispatchingCall).windows().get(0).text();

        assertThat(prompt).contains("./backend/mvnw verify");
        assertThat(prompt).contains("83b37f35664d88848e00d20be9f737ae");
        assertThat(prompt).contains("54 seconds earlier");
        assertThat(prompt).contains("The work being reported on happened in that other trace");
    }

    /** The id the service needs to fetch that call, read straight off the envelope. */
    @Test
    void theDispatchingToolUseIdIsReadOffTheNotificationEnvelope() {
        assertThat(promptBuilder.backgroundTaskToolUseId(
                List.of(userPromptLog(taskNotification("toolu_01Lw", "completed")))))
                .isEqualTo("toolu_01Lw");
        assertThat(promptBuilder.backgroundTaskToolUseId(List.of(userPromptLog("Fix the bug"))))
                .isNull();
    }

    /** A purged or unresolvable dispatch still gets the section — the constraints are what matter. */
    @Test
    void aContinuationWhoseDispatchCannotBeResolvedStillCarriesItsConstraints() {
        String prompt = buildPrompt(
                List.of(llmRequestSpan()),
                List.of(userPromptLog(taskNotification("toolu_gone", "completed"))));

        assertThat(prompt).contains("## What this trace is continuing");
        assertThat(prompt).contains("The work being reported on happened in that other trace");
        assertThat(prompt).doesNotContain("It was started by this call");
    }

    /** A skill the agent chose mid-request leaves a real human prompt intact to judge. */
    @Test
    void aProactivelyInvokedSkillStillLeavesTheRequestHalfInPlace() {
        String prompt = buildPrompt(
                List.of(llmRequestSpan()),
                List.of(userPromptLog("Fix the bug in auth.js"),
                        skillActivatedLog("claude-api", "bundled", "claude-proactive")));

        assertThat(prompt).contains("### B. Request quality");
        assertThat(prompt).doesNotContain("### B. Request quality — not applicable");
        assertThat(prompt).contains("## User prompt (full text)");
        assertThat(prompt).doesNotContain("## How this trace was started");
        assertThat(prompt).contains("claude-api (the agent chose to invoke it itself)");
    }

    /**
     * The rule target widens to a skill only for a skill the reader can edit. A bundled skill ships
     * with Claude Code, so "change that skill" is a fix its reader cannot apply.
     */
    @Test
    void onlyAnEditableSkillIsOfferedAsAnInstructionRuleTarget() {
        List<LogRecord> editableSkillLogs = List.of(skillActivatedLog("ship", "projectSettings", "user-slash"));
        assertThat(buildApplyThisPrompt(List.of(), editableSkillLogs)).contains("`skill:ship`, `CLAUDE.md`");
        assertThat(buildPrompt(List.of(), editableSkillLogs))
                .contains("defined in this project, so a rule may target it");

        List<LogRecord> bundledSkillLogs = List.of(skillActivatedLog("code-review", "bundled", "user-slash"));
        assertThat(buildApplyThisPrompt(List.of(), bundledSkillLogs)).doesNotContain("skill:code-review");
        assertThat(buildPrompt(List.of(), bundledSkillLogs))
                .contains("bundled with Claude Code — the reader cannot edit this one");
    }

    /**
     * A rule about what the agent did under a skill has to land on the skill, not on the project
     * file. On trace 5d6c9ca05d7c6ce12e41a84980693f10 both findings were about {@code /ship}'s own
     * behaviour, {@code skill:ship} was on the target list, and the rule still came back against
     * {@code CLAUDE.md} — where it fires on every unrelated trace in the repo and not on the one
     * file that could act on it. The list opened with {@code CLAUDE.md} and the choice rested on a
     * single trailing clause, so the model took the first, most general option.
     */
    @Test
    void anEditableSkillIsOfferedAheadOfTheProjectFileAndIsTheStatedDefault() {
        String prompt = buildApplyThisPrompt(
                List.of(llmRequestSpan()),
                List.of(slashCommandPromptLog("/ship", "ship"),
                        skillActivatedLog("ship", "projectSettings", "user-slash")));

        assertThat(prompt).contains("`skill:ship`, `CLAUDE.md`");
        assertThat(prompt).contains("**Default to the `skill:` target.**");
        assertThat(prompt)
                .as("and CLAUDE.md has to be argued for rather than being the path of least resistance")
                .contains("if you cannot say\n  why the rule should fire on unrelated work, it is not a `CLAUDE.md` rule");
    }

    /** With no editable skill there is no choice to make, and the prompt says so rather than
     * offering a default that cannot be followed. */
    @Test
    void aTraceWithOnlyABundledSkillIsToldTheProjectFileIsItsOnlyTarget() {
        String prompt = buildApplyThisPrompt(
                List.of(llmRequestSpan()),
                List.of(userPromptLog("Fix the bug in auth.js"),
                        skillActivatedLog("code-review", "bundled", "user-slash")));

        assertThat(prompt).contains("`CLAUDE.md` is the only target available for this trace.");
        assertThat(prompt).doesNotContain("**Default to the `skill:` target.**");
    }

    /** No skill ran, so the section is absent and the only rule target is the project's own file. */
    @Test
    void aTraceWithNoSkillActivationOffersOnlyTheProjectInstructionTarget() {
        List<LogRecord> logs = List.of(userPromptLog("Fix the bug in auth.js"));

        assertThat(buildPrompt(List.of(), logs)).doesNotContain("## Skills that ran");
        assertThat(buildApplyThisPrompt(List.of(), logs))
                .contains("must be copied EXACTLY from this list:\n  `CLAUDE.md`.");
    }

    /**
     * The measured false positive branch-keying exists to kill: the main loop reads a file, then a
     * subagent it dispatched reads the same file. That is not repeated work — a subagent runs in its
     * own fresh context, and keeping a long read out of the main one is the reason to dispatch it.
     * 277 of 1,812 revisit groups over 90 days (15.3%, 59 traces) crossed a branch this way, each
     * one carrying a copy-verbatim "Suggested rule:" line into the reader's CLAUDE.md.
     */
    @Test
    void aFileReadByBothTheMainLoopAndItsSubagentIsNotReportedAsRepeatedWork() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/src/auth.js"),
                toolSpan("Agent", "use-2"),
                executionSpan("exec-1", "use-2"),
                subagentToolSpanForFile("Read", "use-3", "exec-1", "/repo/src/auth.js"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"/repo/src/auth.js\"}", true, 5L, null),
                toolResultLog("Agent", "use-2", "{\"subagent_type\":\"Explore\"}", true, 30_000L, null),
                toolResultLog("Read", "use-3", "{\"file_path\":\"/repo/src/auth.js\"}", true, 5L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).doesNotContain("auth.js touched");
        assertThat(prompt).doesNotContain("Read a file once at the length you actually need");
    }

    /** Within one branch the finding still fires — branch-keying narrows it, it does not disable it. */
    @Test
    void aFileReadTwiceInsideTheSameSubagentIsStillReportedAsRepeatedWork() {
        // The dispatch's name is resolved from its own model calls' query_source, so a realistic
        // subagent run needs one -- calls are then Agent=1, model=2, the two Reads=3 and 4.
        List<Span> spans = List.of(
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-1", "exec-1", "req-1"),
                subagentToolSpanForFile("Read", "use-2", "exec-1", "/repo/src/auth.js"),
                subagentToolSpanForFile("Read", "use-3", "exec-1", "/repo/src/auth.js"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "agent:builtin:Explore", 0.50),
                toolResultLog("Agent", "use-1", "{\"subagent_type\":\"Explore\"}", true, 30_000L, null),
                toolResultLog("Read", "use-2", "{\"file_path\":\"/repo/src/auth.js\"}", true, 5L, null),
                toolResultLog("Read", "use-3", "{\"file_path\":\"/repo/src/auth.js\"}", true, 5L, null));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains(
                "Read src/auth.js, inside subagent Explore (dispatched at call 1), touched 2 times, at call(s) 3, 4");
        assertThat(prompt).contains("Read a file once at the length you actually need");
    }

    /** A failure is real wherever it happened, but the fix lands on a different actor. */
    @Test
    void aFailedCallInsideASubagentSaysWhichSubagentItWasIn() {
        // Agent=1, the subagent's model call=2, the failing Bash=3.
        List<Span> spans = List.of(
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-1", "exec-1", "req-1"),
                subagentToolSpan("Bash", "use-2", "exec-1"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "agent:builtin:Explore", 0.50),
                toolResultLog("Agent", "use-1", "{\"subagent_type\":\"Explore\"}", true, 30_000L, null),
                toolResultLog("Bash", "use-2", "{\"command\":\"ls\"}", false, 5L, "boom"));

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt).contains("Tool calls that failed: 3 in subagent Explore (dispatched at call 1) (Bash)");
    }

    /** A main-loop call number stays exactly as terse as it was before branches existed. */
    @Test
    void aMainLoopCallNumberCarriesNoBranchSuffix() {
        Span failingCall = toolSpan("Bash", "use-1");
        List<LogRecord> logs =
                List.of(toolResultLog("Bash", "use-1", "{\"command\":\"ls\"}", false, 5L, "boom"));

        String prompt = buildPrompt(List.of(failingCall), logs);

        assertThat(prompt).contains("Tool calls that failed: 1 (Bash)");
        assertThat(prompt).doesNotContain("in subagent");
    }

    /**
     * A sub-agent / resume trace has no prompt to judge, but the execution half still applies —
     * the degraded mode drops only section B rather than falling back to a single narrow question.
     */
    @Test
    void onlyThePromptHalfIsDroppedWhenTheTraceHasNoUserPromptRecord() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of());

        assertThat(prompt).contains("### A. Agent execution quality");
        assertThat(prompt).contains("### B. Request quality — not applicable");
        assertThat(prompt).contains("no human-written request attached");
        assertThat(prompt).doesNotContain("## User prompt");
    }

    /**
     * The budget can only ever squeeze the timeline: the template's own instructions, the full
     * prompt text and the full final response are never cut (they are what the judgment depends
     * on), so a budget below the fixed sections' combined size is simply exceeded rather than
     * honoured by mutilating them. That floor is ~10.5k characters of instructions — it moves
     * whenever the template does, which is why this and its sibling below pin a budget comfortably
     * clear of it (13000) rather than one just above the floor of the day. Still far below what 200
     * tool calls need, so it exercises real trimming.
     */
    @Test
    void theTimelineIsCutFirstWhenTheBudgetBindsWhilePromptAndResponseSurviveIntact() {
        ollamaProperties.setMaxPromptChars(20_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        logs.add(userPromptLog("PROMPT-SENTINEL fix the failing auth test"));
        logs.add(assistantResponseLog("RESPONSE-SENTINEL fixed it"));
        for (int i = 0; i < 200; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog("Read", toolUseId, "{\"file_path\":\"File" + i + ".java\"}", true, 4L, null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);

        assertThat(windows.size()).as("the trace no longer fits one window at this budget").isGreaterThanOrEqualTo(2);
        for (TraceAnalysisPromptBuilder.PromptWindow window : windows) {
            assertThat(window.text().length()).isLessThanOrEqualTo(ollamaProperties.getMaxPromptChars());
        }
        assertThat(windows.get(0).text()).contains("PROMPT-SENTINEL fix the failing auth test");
        assertThat(windows.get(windows.size() - 1).text()).contains("RESPONSE-SENTINEL fixed it");
    }

    /**
     * Sections B and C ask questions a partitioned trace's OTHER windows cannot change the answer
     * to — the request's wording does not vary by window, and neither do the cost figures — so
     * re-asking them once per window is pure fabrication risk with nothing to show for it. B goes to
     * the first window (the earliest calls are where a target-finding search would show up, and
     * {@code TraceAnalysisFindingsMerge#mergedRequestKind} already breaks a tie towards the first
     * window's vote); C goes to the last, whose carry-over already summarises spend and files across
     * every prior window.
     */
    @Test
    void sectionsBAndCAreAskedOnlyOnceAcrossAPartitionedTrace() {
        ollamaProperties.setMaxPromptChars(20_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        logs.add(userPromptLog("PROMPT-SENTINEL fix the failing auth test"));
        logs.add(assistantResponseLog("RESPONSE-SENTINEL fixed it"));
        for (int i = 0; i < 200; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog("Read", toolUseId, "{\"file_path\":\"File" + i + ".java\"}", true, 4L, null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);

        assertThat(windows.size()).as("the trace no longer fits one window at this budget").isGreaterThanOrEqualTo(2);

        TraceAnalysisPromptBuilder.PromptWindow firstWindow = windows.get(0);
        assertThat(firstWindow.judgesRequest()).as("the first window judges the request").isTrue();
        assertThat(firstWindow.text())
                .as("only the judging window asks the full section B questions")
                .contains("The text under \"User prompt\" was written by a person")
                .doesNotContain("### C. Cost, time and context\n\nWhat this trace spent");

        for (int index = 1; index < windows.size() - 1; index++) {
            TraceAnalysisPromptBuilder.PromptWindow middleWindow = windows.get(index);
            assertThat(middleWindow.judgesRequest()).as("a middle window judges neither B nor C").isFalse();
            assertThat(middleWindow.text())
                    .doesNotContain("The text under \"User prompt\" was written by a person")
                    .doesNotContain("What this trace spent")
                    .contains("### B. Request quality — judged in another pass. Skip it.")
                    .contains("Judged from another pass's carry-over. Skip it.");
        }

        TraceAnalysisPromptBuilder.PromptWindow lastWindow = windows.get(windows.size() - 1);
        assertThat(lastWindow.judgesRequest()).as("only the first window judges the request").isFalse();
        assertThat(lastWindow.text())
                .as("only the last window asks the full section C questions")
                .contains("What this trace spent")
                .doesNotContain("The text under \"User prompt\" was written by a person")
                .contains("### B. Request quality — judged in another pass. Skip it.");
    }

    /**
     * The ladder's whole point: a trace that overflows at full detail is re-rendered with thinner
     * lines rather than fewer of them, so every call keeps its number. A dropped call is the one
     * compaction the review cannot recover from — its verified observations and its citation
     * contract are both written against call numbers, so an elided call is a number the model is
     * invited to cite and cannot see.
     *
     * <p>Sized to sit in the band the ladder serves: 40 calls carrying inputs far past the 200-char
     * cap overflow a 16k budget at FULL detail (~9k of timeline over a ~10.5k instruction floor) and
     * fit comfortably at MINIMAL (~2k). Both margins are ~3k, so this does not become a tripwire on
     * the next template edit — the same reasoning the elision tests above give for their 12k.
     */
    @Test
    void anOverflowingTimelineGivesUpPerCallDetailRatherThanCalls() {
        ollamaProperties.setMaxPromptChars(16_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog(
                    "Read", toolUseId, "{\"file_path\":\"File" + i + ".java\",\"note\":\"" + "x".repeat(2_000) + "\"}",
                    true, 4L, null));
        }

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, logs, null, null);

        assertThat(result.windows()).hasSize(1);
        String text = result.windows().get(0).text();
        assertThat(text.length()).isLessThanOrEqualTo(ollamaProperties.getMaxPromptChars());
        // Every call number is still citable -- first, last, and the middle an elision would have
        // taken first.
        assertThat(text).contains("1. ").contains("20. ").contains("40. ");
    }

    /**
     * What each detail level gives up is chosen so that nothing a finding rests on is ever dropped.
     * A failure and its error body are evidence at any budget; the {@code -> ok} and duration on a
     * clean call are only evidence when there is room for them, so those are what get spent.
     */
    @Test
    void compactingTheTimelineKeepsFailuresAndTheirErrorsWhileDroppingCleanCallOutcomes() {
        ollamaProperties.setMaxPromptChars(16_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            boolean failed = i == 17;
            logs.add(toolResultLog(
                    "Read", toolUseId, "{\"file_path\":\"File" + i + ".java\",\"note\":\"" + "x".repeat(2_000) + "\"}",
                    !failed, 4L, failed ? "ERROR-SENTINEL file not found" : null));
        }

        String prompt = buildPrompt(spans, logs);

        assertThat(prompt.length()).isLessThanOrEqualTo(ollamaProperties.getMaxPromptChars());
        assertThat(prompt).contains("-> FAILED: ERROR-SENTINEL file not found");
        assertThat(prompt).doesNotContain("-> ok");
    }

    @Test
    void aFittingTraceIsOneWindow() {
        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), List.of(llmRequestSpan()), List.of(), null, null);

        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().get(0).text())
                .doesNotContain("pass 1 of")
                .doesNotContain("Before this window");
    }

    /**
     * The code-composed summary is never sent to Ollama — it lives only on {@link
     * TraceAnalysisPromptBuilder.PromptResult#summary}, not in the window text — but it has to
     * be built from the same facts the prompt renders: the request, the call/file shape, and the
     * outcome.
     */
    @Test
    void theSummaryNamesTheRequestTheWorkAndTheOutcome() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/src/TokenServiceTest.java"),
                llmRequestSpan());
        List<LogRecord> logs = List.of(
                userPromptLog("fix the flaky token test"),
                toolResultLog("Read", "use-1", "{\"file_path\":\"/repo/src/TokenServiceTest.java\"}", true, 4L, null),
                assistantResponseLog("Fixed it — the assertion compared cents to dollars."));

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, logs, null, null);

        assertThat(result.windows().get(0).text()).as("the summary is never part of what the model is shown")
                .doesNotContain("TokenServiceTest.java compared cents to dollars");
        assertThat(result.summary()).contains("Request: \"fix the flaky token test\"");
        assertThat(result.summary()).contains("1 tool call · 1 model call · ");
        assertThat(result.summary()).contains("no errors; ended: \"Fixed it — the assertion compared cents to dollars.\"");
    }

    /**
     * The detail the "Work" line used to carry as clauses of one run-on sentence — which tools,
     * which models, which files — is one labelled line each, so a reader scans it instead of
     * parsing it. Each is its own line precisely so a reader can answer one question at a time.
     */
    @Test
    void theSummaryBreaksTheWorkOutIntoPerToolPerModelAndPerFileLines() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/src/TokenService.java"),
                toolSpanForFile("Read", "use-2", "/repo/src/TokenService.java"),
                toolSpanForFile("Edit", "use-3", "/repo/src/TokenService.java"),
                toolSpanForFile("Read", "use-4", "/repo/AGENTS.md"),
                llmRequestSpan());

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, List.of(), null, null);

        assertThat(result.summary())
                .as("busiest tool first, and a single call carries no noisy ×1")
                .contains("Tools: Read ×3 · Edit");
        assertThat(result.summary()).contains("Models: claude-sonnet-4");
        assertThat(result.summary())
                .as("each file names what was done to it, in first-touch order")
                .contains("Files: src/TokenService.java (Read ×2, Edit) · repo/AGENTS.md (Read)");
    }

    /**
     * The bug fixed on trace {@code df8c757de3bfbfe9c1da2b29f431a192}: {@code frontend/CLAUDE.md}
     * (read once) and {@code frontend/src/pages/TraceDetailPage/CLAUDE.md} (read 5 times, edited 4)
     * are two structurally different files that share a basename, which this repository guarantees by
     * construction (one {@code CLAUDE.md} per {@code pages/<Name>Page/} folder). Keying {@code
     * fileUsageLine} on the bare basename merged them into one false "CLAUDE.md (Read ×6, Edit ×4)"
     * fact; keying on the full path and labelling with the last two segments keeps them apart while a
     * genuinely repeated single file still reports correctly under its own two-segment label.
     */
    @Test
    void twoFilesSharingABasenameInDifferentDirectoriesAreNotMergedInTheFilesLine() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/frontend/CLAUDE.md"),
                toolSpanForFile("Read", "use-2", "/repo/frontend/src/pages/TraceDetailPage/CLAUDE.md"),
                toolSpanForFile("Read", "use-3", "/repo/frontend/src/pages/TraceDetailPage/CLAUDE.md"),
                toolSpanForFile("Edit", "use-4", "/repo/frontend/src/pages/TraceDetailPage/CLAUDE.md"));

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, List.of(), null, null);

        assertThat(result.summary())
                .as("two different files sharing a basename must not collapse into one entry")
                .contains("Files: frontend/CLAUDE.md (Read) · TraceDetailPage/CLAUDE.md (Read ×2, Edit)");
    }

    /**
     * A file's own tool list is joined with a comma while the lines themselves are joined with the
     * middle dot, so the dialog's clause split can never cut a file away from what was done to it.
     */
    @Test
    void aFilesToolListIsNestedInsideItsClauseRatherThanSplittingTheLine() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/repo/src/TokenService.java"),
                toolSpanForFile("Edit", "use-2", "/repo/src/TokenService.java"));

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, List.of(), null, null);

        String filesLine = summaryLine(result.summary(), "Files");
        assertThat(filesLine.split(" · ")).containsExactly("src/TokenService.java (Read, Edit)");
    }

    /**
     * The reader's question is "what did the main agent spend before it started handing work out,
     * and what did each sub-agent cost" — so the split is a line of its own, and it names the
     * dispatch call number, the only thing telling two dispatches of one agent type apart.
     */
    @Test
    void theSummarySplitsCostAcrossTheMainLoopAndEachSubagentDispatch() {
        List<Span> spans = List.of(
                modelCall("llm-1", null, "req-1"),
                toolSpan("Agent", "use-1"),
                executionSpan("exec-1", "use-1"),
                modelCall("llm-2", "exec-1", "req-2"),
                subagentToolSpan("Grep", "use-2", "exec-1"));
        List<LogRecord> logs = List.of(
                apiRequestLog("req-1", "sdk", 0.10),
                apiRequestLog("req-2", "agent:builtin:Explore", 0.90),
                toolResultLog("Agent", "use-1", "{\"subagent_type\":\"Explore\"}", true, 30_000L, null),
                toolResultLog("Grep", "use-2", "{\"pattern\":\"needle\"}", true, 9L, null));

        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, logs, null, null);

        String costLine = summaryLine(result.summary(), "Cost");
        assertThat(costLine)
                .as("the authoritative trace total leads, and the attributed parts are labelled as measured")
                .startsWith("$0.0100 total · of which measured: main loop $0.1000 across 1 model call");
        assertThat(costLine).contains(
                "Explore (dispatched at call 2) $0.9000 across 1 model call and 1 tool call");
    }

    @Test
    void theSummaryNamesTheSkillsThatRanAndWhatInvokedEach() {
        TraceAnalysisPromptBuilder.PromptResult result = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(skillActivatedLog("ship", "projectSettings", "user-slash")),
                null,
                null);

        assertThat(result.summary()).contains("Skills: ship (the user invoked it as a slash command)");
    }

    /**
     * Compaction reframes everything above it — the calls after one were reasoning from a summary
     * rather than from what the reader saw the agent read — and no other figure in the dialog says
     * it happened.
     */
    @Test
    void theSummaryReportsACompactionAndHowMuchContextItReclaimed() {
        TraceAnalysisPromptBuilder.PromptResult result = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(compactionLog("manual", true, 145_579L, 11_442L, 120_935L, null)),
                null,
                null);

        assertThat(result.summary()).contains("Compaction: manual, 145,579 → 11,442 tokens in 2m 0s");
    }

    /**
     * The event fires for a compaction that died too, and an aborted one is the worse outcome: it
     * spent the time without reclaiming the room. Reporting its presence as a completed pass would
     * tell the reader their context shrank when it did not.
     */
    @Test
    void theSummaryReportsAFailedCompactionAsAFailureRatherThanAsAPass() {
        TraceAnalysisPromptBuilder.PromptResult result = promptBuilder.build(
                traceSummary(),
                List.of(llmRequestSpan()),
                List.of(compactionLog("auto", false, 196_177L, 0L, 57_779L, "aborted")),
                null,
                null);

        assertThat(result.summary()).contains("Compaction: auto, failed after 57.8s (aborted)");
    }

    /**
     * A line whose subject is simply absent from the trace renders not at all, rather than as a
     * "none" every trace that never dispatched, compacted or ran a skill would carry.
     */
    @Test
    void theSummaryOmitsLinesWithNothingToReport() {
        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), List.of(llmRequestSpan()), List.of(), null, null);

        assertThat(result.summary())
                .doesNotContain("Skills:")
                .doesNotContain("Compaction:")
                .doesNotContain("Files:");
    }

    /** The value of one labelled summary line, for asserting on its clause structure. */
    private static String summaryLine(String summary, String label) {
        return summary.lines()
                .filter(line -> line.startsWith(label + ": "))
                .map(line -> line.substring(label.length() + 2))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + label + " line in summary:\n" + summary));
    }

    @Test
    void theSummaryNamesASlashCommandRatherThanJudgingItsWording() {
        TraceAnalysisPromptBuilder.PromptResult result = promptBuilder.build(
                traceSummary(), List.of(llmRequestSpan()), List.of(slashCommandPromptLog("/ship", "ship")), null, null);

        assertThat(result.summary()).contains("Request: the `/ship` slash command.");
    }

    @Test
    void theSummaryReportsNoRequestWhenTheTraceCarriesNoUserWrittenPrompt() {
        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), List.of(llmRequestSpan()), List.of(), null, null);

        assertThat(result.summary()).contains("Request: none");
    }

    @Test
    void theSummaryReportsNoFinalMessageWhenTheTraceEndedMidToolCall() {
        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), List.of(llmRequestSpan()), List.of(), null, null);

        assertThat(result.summary()).contains("no errors; the trace has no final assistant message");
    }

    /**
     * Filling the budget front-to-back kept only a long trace's opening and dropped how it ended,
     * while the final response was still included — the model saw the answer but not the work that
     * produced it. Both ends must survive.
     */
    @Test
    void everyFileTouchedSurvivesSomewhereAcrossThePartitionedWindows() {
        ollamaProperties.setMaxPromptChars(20_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog("Read", toolUseId, "{\"file_path\":\"File" + i + ".java\"}", true, 4L, null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);

        for (String file : List.of("File0.java", "File100.java", "File199.java")) {
            long windowsContainingFile =
                    windows.stream().filter(window -> window.text().contains(file)).count();
            assertThat(windowsContainingFile).as(file + " must survive in exactly one window").isEqualTo(1);
        }
    }

    /**
     * The number a review cites and the number the trace detail page puts on a waterfall row have to
     * be the same number, and this is the only test that can say so: it reads the call numbers off
     * the RENDERED timeline — the text the model is actually shown — and compares them against the
     * mapping {@code TraceService} fills {@code Span#callNumber} from. Anything that renumbers one
     * side (a span name the timeline stops rendering, a structural span it starts counting) fails
     * here rather than silently pointing a citation at the wrong row.
     *
     * <p>The fixture is deliberately the shape that made the two diverge in the first place: a turn
     * root and a tool call's own execution child sit between the numbered calls, so a mapping that
     * counted every span would be off by four by the last line.
     */
    @Test
    void callNumbersInTheTimelineMatchTheOnesPutOnTheSpans() {
        TuningProperties tuningProperties = new TuningProperties();
        List<Span> spans = List.of(
                structuralSpan("root", "claude_code.interaction"),
                llmRequestSpanWithId("llm-1", 450L),
                toolSpan("Read", "use-1"),
                structuralSpan("exec-1", "claude_code.tool.execution"),
                llmRequestSpanWithId("llm-2", 120L),
                toolSpan("Bash", "use-2"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"file_path\":\"Foo.java\"}", true, 4L, null),
                toolResultLog("Bash", "use-2", "{\"command\":\"ls\"}", true, 8L, null));

        Map<String, Integer> callNumberBySpanId = TraceCallNumbering.callNumbersBySpanId(
                spans, tuningProperties.getToolSpanName(), tuningProperties.getLlmRequestSpanName());
        String timeline = timelineSectionOf(buildPrompt(spans, logs));

        assertThat(callNumberBySpanId)
                .as("only tool calls and model requests are numbered, in trace order")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("llm-1", 1, "use-1", 2, "llm-2", 3, "use-2", 4));
        assertThat(callNumberBySpanId).as("structural spans carry no call number")
                .doesNotContainKeys("root", "exec-1");
        assertThat(timeline).contains("1. - llm_request");
        assertThat(timeline).contains("2. Read");
        assertThat(timeline).contains("3. - llm_request");
        assertThat(timeline).contains("4. Bash");
    }

    /** A span the timeline never numbers: the turn root, or a tool call's own execution child. */
    private static Span structuralSpan(String spanId, String name) {
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .name(name)
                .statusCode("ok")
                .durationNanos(500_000_000L)
                .attributes(new HashMap<>())
                .build();
    }

    // Distinct output_tokens per call, so the two model calls do not fold into the exact-repeat
    // back-reference the timeline renders for byte-identical lines -- this test is about the
    // numbering, and a folded line states its number the same way but says less about which call it
    // is (that folding has its own tests above).
    private static Span llmRequestSpanWithId(String spanId, long outputTokens) {
        Span span = llmRequestSpan();
        span.setSpanId(spanId);
        span.getAttributes().put("output_tokens", outputTokens);
        return span;
    }

    // -------------------------------------------------------------------------
    // Windowing: TraceAnalysisPromptBuilder.packWindows / partitionToBudget
    // -------------------------------------------------------------------------

    @Test
    void packWindowsOnAnEmptyListProducesNoWindows() {
        assertThat(TraceAnalysisPromptBuilder.packWindows(List.of(), 5_000)).isEmpty();
    }

    @Test
    void packWindowsKeepsOneShortLineInOneWindow() {
        TraceAnalysisPromptBuilder.TimelineLine line =
                new TraceAnalysisPromptBuilder.TimelineLine("1. Read /a.java -> ok", 1, 1);
        List<List<TraceAnalysisPromptBuilder.TimelineLine>> windows =
                TraceAnalysisPromptBuilder.packWindows(List.of(line), 5_000);
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0)).containsExactly(line);
    }

    /**
     * A single line wider than the whole per-window budget is FITTED, never dropped -- the whole
     * point of {@link TraceAnalysisPromptBuilder#fitLine}. Its call-number prefix and tool name
     * survive; only the middle of its own text is what gives way.
     */
    @Test
    void packWindowsFitsALineWiderThanTheWholeBudgetRatherThanDroppingIt() {
        String longCommand = "x".repeat(10_000);
        TraceAnalysisPromptBuilder.TimelineLine line =
                new TraceAnalysisPromptBuilder.TimelineLine("7. Bash " + longCommand, 7, 7);
        List<List<TraceAnalysisPromptBuilder.TimelineLine>> windows =
                TraceAnalysisPromptBuilder.packWindows(List.of(line), 2_500);
        assertThat(windows).hasSize(1);
        String fitted = windows.get(0).get(0).text();
        // fitLine's own middle-out truncation (truncateMiddleOut) adds a short "... N chars
        // omitted ..." note ON TOP of the character budget it truncates to, the same trade every
        // other middle-out truncation in this class already makes -- so the fitted line sits close
        // to the budget, not strictly under it.
        assertThat(fitted.length()).isLessThanOrEqualTo(2_500 + 100);
        assertThat(fitted).as("the call number prefix survives").startsWith("7. ");
        assertThat(fitted).as("the tool name survives").contains("Bash");
    }

    @Test
    void packWindowsThrowsRatherThanEmitHundredsOfOneLineWindowsBelowTheMinimumBudget() {
        TraceAnalysisPromptBuilder.TimelineLine line =
                new TraceAnalysisPromptBuilder.TimelineLine("1. Read /a.java -> ok", 1, 1);
        assertThatThrownBy(() -> TraceAnalysisPromptBuilder.packWindows(List.of(line), 100))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The centerpiece: partitioning a timeline into windows must never drop, duplicate, or renumber
     * a call. Parses each window's timeline with the same production regex the model's citations are
     * checked against, expands folded ranges into individual call numbers, and requires the result to
     * be exactly {@code 1..N} with every count 1 -- plus that windows are contiguous and ordered.
     */
    @ParameterizedTest
    @CsvSource({
            "1, 20000", "2, 20000", "50, 20000", "200, 20000", "600, 20000",
            "50, 36000", "200, 36000", "600, 36000",
    })
    void everyCallNumberAppearsInExactlyOneWindow(int callCount, int budget) {
        ollamaProperties.setMaxPromptChars(budget);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < callCount; i++) {
            if (i % 5 == 4) {
                spans.add(llmRequestSpanWithId("llm-" + i, 100L + i));
                continue;
            }
            String toolUseId = "use-" + i;
            String toolName = i % 3 == 0 ? "Bash" : "Read";
            String input = i % 7 == 0
                    ? "{\"full_command\":\"" + "x".repeat(4_000) + "\"}"
                    : "{\"file_path\":\"File" + (i % 11) + ".java\"}";
            spans.add(toolSpan(toolName, toolUseId));
            logs.add(toolResultLog(toolName, toolUseId, input, true, 4L, null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);

        List<Integer> allCallNumbers = new java.util.ArrayList<>();
        int previousLastCall = 0;
        for (TraceAnalysisPromptBuilder.PromptWindow window : windows) {
            assertThat(window.text().length())
                    .as("every window fits the budget")
                    .isLessThanOrEqualTo(budget);
            assertThat(window.firstCallNumber())
                    .as("windows are consecutive and non-overlapping")
                    .isEqualTo(previousLastCall + 1);
            previousLastCall = window.lastCallNumber();

            // Restricted to the timeline section alone: the frame rendered identically in every
            // window (Overview/observations/errors/"How to answer") includes its own numbered list
            // ("1. Prefer the verified observations." ... "5. Never restate a number on its own."),
            // which the production TIMELINE_LINE pattern cannot distinguish from a real call line by
            // shape alone -- scanning the whole window text double-counts calls 1-5 in every window.
            java.util.regex.Matcher lines = TimelineCitations.TIMELINE_LINE.matcher(timelineSectionOf(window.text()));
            while (lines.find()) {
                int first = Integer.parseInt(lines.group(1));
                int last = lines.group(2) == null ? first : Integer.parseInt(lines.group(2));
                for (int callNumber = first; callNumber <= last; callNumber++) {
                    allCallNumbers.add(callNumber);
                }
            }
        }

        assertThat(previousLastCall).as("the last window ends on the trace's last call").isEqualTo(callCount);
        Map<Integer, Long> counts = allCallNumbers.stream()
                .collect(java.util.stream.Collectors.groupingBy(n -> n, java.util.stream.Collectors.counting()));
        for (int callNumber = 1; callNumber <= callCount; callNumber++) {
            assertThat(counts).as("call " + callNumber + " appears exactly once").containsEntry(callNumber, 1L);
        }
        assertThat(counts).hasSize(callCount);
    }

    @Test
    void theWindowHeaderAndCarryOverNeverExceedTheirReservedWidth() {
        ollamaProperties.setMaxPromptChars(16_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        // Wide inputs so the widest realistic file list, failure list and counts line are all
        // exercised together in the carry-over this trace produces before its later windows.
        for (int i = 0; i < 800; i++) {
            String toolUseId = "use-" + i;
            boolean failed = i % 13 == 0;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog(
                    "Read", toolUseId, "{\"file_path\":\"/repo/src/main/java/com/example/File" + i + ".java\"}",
                    !failed, 4L, failed ? "ERROR-SENTINEL file not found" : null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);
        assertThat(windows.size()).isGreaterThanOrEqualTo(2);
        // Every window after the first carries a header and carry-over -- both are bounded to the
        // reserved constants, verified indirectly here since the header/carryover text is a small,
        // fixed-format block and the window as a whole still fits the configured budget (checked in
        // the centerpiece test above); this test's job is only to confirm the reservation is real.
        for (int index = 1; index < windows.size(); index++) {
            assertThat(windows.get(index).text().length())
                    .isLessThanOrEqualTo(ollamaProperties.getMaxPromptChars());
        }
    }

    /** Window 2-of-3 carries a header and carry-over; window 1 carries neither, and no window
     * claims calls are missing -- that language belonged to the old elision fallback windowing
     * replaces. */
    @Test
    void windowFramingNamesThePassAndNeverClaimsCallsAreMissing() {
        ollamaProperties.setMaxPromptChars(15_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < 800; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog("Read", toolUseId, "{\"file_path\":\"File" + i + ".java\"}", true, 4L, null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);
        assertThat(windows.size()).isGreaterThanOrEqualTo(3);

        assertThat(windows.get(0).text())
                .as("window 1 has no carry-over")
                .doesNotContain("Before this window");
        assertThat(windows.get(1).text())
                .as("window 2 carries a header and carry-over")
                .contains("pass 2 of")
                .contains("Before this window");
        for (TraceAnalysisPromptBuilder.PromptWindow window : windows) {
            assertThat(window.text()).doesNotContain("calls omitted");
        }
    }

    /**
     * {@code ollama.timeline-detail-preference} decides which of the two remedies an oversized
     * timeline gives way with, and the default is the cheaper one rather than the better one -- see
     * {@code OllamaProperties.TimelineDetailPreference}. The same trace is built twice here so the
     * assertion is the CONTRAST between the two settings rather than either absolute window count:
     * both numbers move with the template's own fixed width, which is what every other budget test in
     * this class has had to be written around.
     */
    @Test
    void mostTimelineDetailPaysInWindowsWhereTheDefaultPaysInPerCallDetail() {
        ollamaProperties.setMaxPromptChars(20_000);
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        logs.add(userPromptLog("PROMPT-SENTINEL fix the failing auth test"));
        for (int i = 0; i < 120; i++) {
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            // Wide enough that FULL's 200-character tool-input cap and MINIMAL's 30-character one
            // render very differently sized timelines, which is the choice this property makes.
            // The index sits at the FRONT of the path on purpose: behind a long common prefix, every
            // input truncates to the same 200 characters and collapseAdjacentRepeats folds all 120
            // calls into one "x 120" line, which fits any budget and tests nothing.
            logs.add(toolResultLog(
                    "Read", toolUseId,
                    "{\"file_path\":\"/repo/File" + i + "/" + "nested/".repeat(40) + "source.java\"}", true, 4L,
                    null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> defaultWindows = buildWindows(spans, logs);

        ollamaProperties.setTimelineDetailPreference(OllamaProperties.TimelineDetailPreference.MOST_TIMELINE_DETAIL);
        List<TraceAnalysisPromptBuilder.PromptWindow> fullDetailWindows = buildWindows(spans, logs);

        assertThat(fullDetailWindows.size())
                .as("holding the timeline at FULL detail costs more review calls")
                .isGreaterThan(defaultWindows.size());
        assertThat(timelineSectionOf(fullDetailWindows.get(0).text()))
                .as("and buys back the per-call detail a tighter level drops")
                .contains(" -> ok");
        assertThat(timelineSectionOf(defaultWindows.get(0).text()))
                .as("which the default gave up in order to stay in fewer windows")
                .doesNotContain(" -> ok");
        for (TraceAnalysisPromptBuilder.PromptWindow window : fullDetailWindows) {
            assertThat(window.text().length()).isLessThanOrEqualTo(ollamaProperties.getMaxPromptChars());
        }
    }

    /**
     * The guarantee the preference must not weaken: no call is dropped, duplicated or renumbered.
     * Checked on the MOST_TIMELINE_DETAIL path specifically, since that is the one producing the most
     * windows and therefore the most opportunities to lose a call at a boundary. Same oracle as the
     * centerpiece test above -- the production citation regex over each window's timeline section.
     */
    @Test
    void everyCallStillAppearsExactlyOnceUnderMostTimelineDetail() {
        ollamaProperties.setMaxPromptChars(20_000);
        ollamaProperties.setTimelineDetailPreference(OllamaProperties.TimelineDetailPreference.MOST_TIMELINE_DETAIL);
        int callCount = 150;
        List<Span> spans = new java.util.ArrayList<>();
        List<LogRecord> logs = new java.util.ArrayList<>();
        for (int i = 0; i < callCount; i++) {
            if (i % 5 == 4) {
                spans.add(llmRequestSpanWithId("llm-" + i, 100L + i));
                continue;
            }
            String toolUseId = "use-" + i;
            spans.add(toolSpan("Read", toolUseId));
            logs.add(toolResultLog(
                    "Read", toolUseId,
                    "{\"file_path\":\"/repo/File" + i + "/" + "nested/".repeat(40) + "source.java\"}", true, 4L,
                    null));
        }

        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logs);
        assertThat(windows.size()).as("this trace partitions at FULL detail").isGreaterThanOrEqualTo(2);

        List<Integer> allCallNumbers = new java.util.ArrayList<>();
        int previousLastCall = 0;
        for (TraceAnalysisPromptBuilder.PromptWindow window : windows) {
            assertThat(window.firstCallNumber())
                    .as("windows are consecutive and non-overlapping")
                    .isEqualTo(previousLastCall + 1);
            previousLastCall = window.lastCallNumber();
            java.util.regex.Matcher lines = TimelineCitations.TIMELINE_LINE.matcher(timelineSectionOf(window.text()));
            while (lines.find()) {
                int first = Integer.parseInt(lines.group(1));
                int last = lines.group(2) == null ? first : Integer.parseInt(lines.group(2));
                for (int callNumber = first; callNumber <= last; callNumber++) {
                    allCallNumbers.add(callNumber);
                }
            }
        }

        assertThat(previousLastCall).as("the last window ends on the trace's last call").isEqualTo(callCount);
        Map<Integer, Long> counts = allCallNumbers.stream()
                .collect(java.util.stream.Collectors.groupingBy(n -> n, java.util.stream.Collectors.counting()));
        for (int callNumber = 1; callNumber <= callCount; callNumber++) {
            assertThat(counts).as("call " + callNumber + " appears exactly once").containsEntry(callNumber, 1L);
        }
        assertThat(counts).hasSize(callCount);
    }

    /**
     * The rendered timeline only. The "## Call timeline" heading is followed by a legend that
     * deliberately quotes example markers ("[identical call repeats at 40, 90]", "(repeat of call
     * 3)") to teach the model how to read them, so asserting those are ABSENT has to look past the
     * legend at the generated lines — otherwise every negative assertion matches the legend itself.
     */
    private static String timelineSectionOf(String prompt) {
        int legendEnd = prompt.indexOf(LEGEND_LAST_LINE);
        assertThat(legendEnd).as("timeline legend should precede the rendered lines").isNotNegative();
        int timelineStart = legendEnd + LEGEND_LAST_LINE.length();
        int timelineEnd = prompt.indexOf("\n## ", timelineStart);
        return prompt.substring(timelineStart, timelineEnd < 0 ? prompt.length() : timelineEnd);
    }

    /**
     * The two answer contracts are mutually exclusive: with structured output on, telling the model
     * to write markdown headings while the grammar only permits JSON is an instruction it cannot
     * follow, and the fields it must fill go unexplained.
     */
    @Test
    void structuredOutputSwapsTheAnswerContractForTheJsonFieldGuide() {
        ollamaProperties.setStructuredOutput(true);

        String prompt = buildPrompt(List.of(), List.of(userPromptLog("Fix the bug in auth.js")));

        assertThat(prompt)
                .contains("Answer as a single JSON document matching the schema")
                .doesNotContain("**What went wrong**")
                .doesNotContain("**Apply this**");
        assertThat(buildApplyThisPrompt(List.of(), List.of(userPromptLog("Fix the bug in auth.js"))))
                .as("the rule target is the second call's field, and it is described there")
                .contains("`instructionRuleTarget` — which file has to carry that instruction");
    }

    @Test
    void theProseAnswerContractIsWhatRendersByDefault() {
        String prompt = buildPrompt(List.of(), List.of(userPromptLog("Fix the bug in auth.js")));

        assertThat(prompt)
                .contains("**What went wrong**")
                .doesNotContain("Answer as a single JSON document");
    }

    /**
     * The gap this closes: {@code buildObservations} counted a model call and then skipped it before
     * the failure check, so a trace whose dominant event was eight dead model calls offered the
     * reviewing model nothing verified about them — and llama3.1 duly reported four other things.
     */
    @Test
    void failedModelCallsBecomeAVerifiedObservationNamingTheirCallNumbersAndStatus() {
        List<Span> spans = List.of(
                llmRequestSpan(),
                failedLlmRequestSpan("llm-2", 429, "Usage credits are required for this model."),
                failedLlmRequestSpan("llm-3", 429, "Usage credits are required for this model."));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains(
                "- Model calls that failed: 2 of 3, at call(s) 2, 3 — HTTP 429: "
                + "Usage credits are required for this model.");
    }

    /**
     * A long wait on user approval is named on the call it happened at -- the missing detail
     * behind the overview's "Blocked on user: X%" line, which never said which call waited.
     * Gated at 10s (the per-call floor) so the routine sub-second waits most traces have don't
     * clutter every tool line -- see BLOCKED_ON_USER_CALL_FLOOR_MS.
     */
    @Test
    void aLongWaitOnUserApprovalIsNamedOnItsOwnTimelineLine() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span blockedSpan = blockedOnUserSpan("blocked-1", toolSpan.getSpanId(), 15_000_000_000L);
        LogRecord log = toolResultLog("Bash", "use-1", "{\"command\":\"./deploy.sh\"}", true, 20L, null);

        String prompt = buildPrompt(List.of(toolSpan, blockedSpan), List.of(log));

        assertThat(prompt).contains("[blocked 15.0s on user approval]");
    }

    @Test
    void aShortWaitOnUserApprovalCarriesNoAnnotation() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span blockedSpan = blockedOnUserSpan("blocked-1", toolSpan.getSpanId(), 2_000_000_000L);
        LogRecord log = toolResultLog("Bash", "use-1", "{\"command\":\"./deploy.sh\"}", true, 20L, null);

        String prompt = buildPrompt(List.of(toolSpan, blockedSpan), List.of(log));

        assertThat(prompt).doesNotContain("on user approval]");
    }

    /**
     * A write to the file ends its revisit window: calls only group with other calls that saw the
     * same content. On trace {@code adae1753270dd3088520435ae7f8af94} the observation reported
     * "touched 3 times, at calls 16, 18, 26" while its own remedy reads "unless an edit changed it"
     * -- and call 26 followed the Edit at call 20 on that very file, so the one call the rule
     * excuses was being counted as evidence for it.
     */
    @Test
    void aReadAfterAnEditIsNotGroupedWithTheReadsThatCameBeforeIt() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Read", "use-2", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Edit", "use-3", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Read", "use-4", "/a/b/LogRecordRepository.java"));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt)
                .as("only the two reads of the same content group; the post-edit re-read is justified")
                .contains("Read b/LogRecordRepository.java touched 2 times, at call(s) 1, 2")
                .doesNotContain("touched 3 times");
    }

    /**
     * The inline marker had the same defect the observation was fixed for, and it mattered more than
     * it looked: on trace {@code adae1753270dd3088520435ae7f8af94} the corrected observation said
     * calls 16, 18 while the marker still read {@code [same file, different input, at 18, 26]}, and
     * the review wrote its finding -- and hung its Instruction rule on it -- from the marker.
     */
    @Test
    void theInlineRevisitMarkerDoesNotReachAcrossAnEdit() {
        List<Span> spans = List.of(
                toolSpanForFile("Read", "use-1", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Read", "use-2", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Edit", "use-3", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Read", "use-4", "/a/b/LogRecordRepository.java"));
        List<LogRecord> logs = List.of(
                toolResultLog("Read", "use-1", "{\"offset\":0}", true, 4L, null),
                toolResultLog("Read", "use-2", "{\"offset\":400}", true, 4L, null),
                toolResultLog("Edit", "use-3", "{\"old_string\":\"a\"}", true, 4L, null),
                toolResultLog("Read", "use-4", "{\"offset\":800}", true, 4L, null));

        assertThat(buildPrompt(spans, logs))
                .contains("[same file, different input, at 2]")
                .doesNotContain("[same file, different input, at 2, 4]");
    }

    /**
     * Two sequential edits to one file is how an edit normally happens, not redundancy -- and the
     * general (non-Read) form of the rule this observation carries reads as nonsense for an Edit:
     * "re-read the result you already have instead of running it again" is advice not to make the
     * second edit. Measured over 30 days, 676 of 1,203 revisit groups were on a mutating tool and
     * the window leaves none of them.
     */
    @Test
    void twoEditsToOneFileAreNotARevisit() {
        List<Span> spans = List.of(
                toolSpanForFile("Edit", "use-1", "/a/b/LogRecordRepository.java"),
                toolSpanForFile("Edit", "use-2", "/a/b/LogRecordRepository.java"));

        assertThat(buildPrompt(spans, List.of())).doesNotContain("touched 2 times");
    }

    /**
     * One tool failure reached buildErrorLines twice -- once on the tool.execution span and once on
     * the tool_result log describing the same failure -- and the two ErrorLine keys differed only in
     * source, so they never collapsed. On trace {@code adae1753270dd3088520435ae7f8af94} that
     * produced two Errors lines for one failure and an outcome reading "1 error (2 distinct)".
     */
    @Test
    void oneToolFailureProducesOneErrorLineNotOnePerSignal() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span executionSpan = Span.builder()
                .traceId(TRACE_ID)
                .spanId("exec-1")
                .parentSpanId(toolSpan.getSpanId())
                .name("claude_code.tool.execution")
                .statusCode("error")
                .statusMessage("Shell command failed")
                .durationNanos(2_000_000_000L)
                .attributes(new HashMap<>())
                .build();
        LogRecord log = toolResultLog(
                "Bash", "use-1", "{\"command\":\"psql\"}", false, 20L, "Shell command failed");

        String prompt = buildPrompt(List.of(toolSpan, executionSpan), List.of(log));

        assertThat(prompt)
                .contains("- Bash: Shell command failed")
                .as("the execution span is derivative of the log that describes the same failure")
                .doesNotContain("claude_code.tool.execution: Shell command failed");
    }

    /**
     * A call number and a tool name say that something failed but nothing about what, so there is no
     * thread for the answer contract's recovery question to pull on. On trace
     * {@code adae1753270dd3088520435ae7f8af94} the sole failure rendered as a bare "40 (Bash)" and
     * went unmentioned across three runs.
     */
    @Test
    void aFailedCallObservationCarriesTheErrorItFailedWith() {
        Span toolSpan = toolSpan("Bash", "use-1");
        LogRecord log = toolResultLog(
                "Bash", "use-1", "{\"command\":\"psql\"}", false, 20L, "Shell command failed");

        assertThat(buildPrompt(List.of(toolSpan), List.of(log)))
                .contains("Tool calls that failed: 1 (Bash): Shell command failed");
    }

    /**
     * The trace-level observation, gated on the wait actually being a pattern worth a standing
     * rule rather than the routine sub-second pause most traces have -- see
     * BLOCKED_ON_USER_TRACE_SHARE_FLOOR. It carries a paste-ready Suggested rule, the same bar
     * the other mechanical-fix observations (shell antipatterns, revisits) are held to.
     */
    @Test
    void aTraceDominatedByUserApprovalWaitsGetsAnObservationWithASuggestedRule() {
        String prompt = buildPrompt(repeatedApprovalPromptSpans(), repeatedApprovalPromptLogs());

        assertThat(prompt)
                .contains("waiting on user approval, including a wait of at least 10.0s at call(s) 1, 2, 3")
                .as("naming the longest wait's own call is what stops the list reading as an even spread")
                .contains("the longest single wait was 12.0s at call 1")
                .contains("Suggested rule: Pre-authorize or batch the permission prompts");
    }

    /**
     * The same total, one pause. On trace {@code adae1753270dd3088520435ae7f8af94} 23m 25s of the
     * 25m 47s sat in a single wait at call 30, and the review reported six calls as an even pattern
     * of "inefficient user interaction" and prescribed batching -- a remedy for a problem that trace
     * did not have. A person stepping away from the keyboard is not the agent choosing badly, so the
     * ready-made rule is withheld rather than the model being talked out of one it has been handed.
     */
    @Test
    void aTraceWhoseWaitIsOneLongPauseGetsNoApprovalObservationAtAll() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span blockedSpan = blockedOnUserSpan("blocked-1", toolSpan.getSpanId(), 36_000_000_000L);
        LogRecord log = toolResultLog("Bash", "use-1", "{\"command\":\"./deploy.sh\"}", true, 20L, null);

        String prompt = buildPrompt(List.of(toolSpan, blockedSpan), List.of(log));

        assertThat(prompt)
                .as("dropped rather than marked, for the same reason the outlier is")
                .doesNotContain("waiting on user approval")
                .doesNotContain("Suggested rule: Pre-authorize or batch the permission prompts");
        assertThat(prompt)
                .as("the fact survives on the call it happened to, which is where it is unambiguous")
                .contains("[blocked 36.0s on user approval]");
    }

    /**
     * The rule that observation carries is not one any instructions file can act on: pre-authorising
     * a tool call is a permissions setting, not something the agent reads. On trace
     * {@code adae1753270dd3088520435ae7f8af94} it came back as
     * {@code Instruction rule: CLAUDE.md — Pre-authorize or batch the permission prompts…}, advice
     * the reader could not follow in the file it named. The target is evidence-built like
     * {@code skill:<name>} — offered only because this trace really did block — and sits ahead of
     * {@code CLAUDE.md}, the comparison it has to win, but behind the skills, so the
     * skill-versus-CLAUDE.md default is left exactly as it was.
     */
    @Test
    void aTraceThatBlockedOnUserApprovalIsOfferedThePermissionsRuleTarget() {
        String applyThisPrompt =
                buildApplyThisPrompt(repeatedApprovalPromptSpans(), repeatedApprovalPromptLogs());

        assertThat(applyThisPrompt).contains("`.claude/settings.json`, `CLAUDE.md`");
        assertThat(applyThisPrompt)
                .as("the model is told which rule that target is for, and that it is for no other")
                .contains("A rule about waiting on permission prompts targets `.claude/settings.json`, "
                        + "never `CLAUDE.md`.");
    }

    /** Ordering is load-bearing: skills stay first, CLAUDE.md stays last, permissions slots between. */
    @Test
    void thePermissionsTargetSitsBehindAnEditableSkillAndAheadOfProjectInstructions() {
        List<LogRecord> logs = new ArrayList<>(repeatedApprovalPromptLogs());
        logs.add(skillActivatedLog("ship", "projectSettings", "user-slash"));

        assertThat(buildApplyThisPrompt(repeatedApprovalPromptSpans(), logs))
                .contains("`skill:ship`, `.claude/settings.json`, `CLAUDE.md`");
    }

    /**
     * A standing option is exactly what this must not become — a model that cannot see the repo
     * invents plausible paths, which is why every target is built from evidence.
     */
    @Test
    void aTraceThatNeverBlockedOnApprovalIsNotOfferedThePermissionsRuleTarget() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span blockedSpan = blockedOnUserSpan("blocked-1", toolSpan.getSpanId(), 500_000_000L);
        LogRecord log = toolResultLog("Bash", "use-1", "{\"command\":\"./deploy.sh\"}", true, 20L, null);

        String applyThisPrompt = buildApplyThisPrompt(List.of(toolSpan, blockedSpan), List.of(log));

        assertThat(applyThisPrompt)
                .doesNotContain(".claude/settings.json")
                .contains("`CLAUDE.md` is the only target available for this trace.");
    }

    @Test
    void aTraceWithOnlyBriefApprovalWaitsGetsNoBlockedOnUserObservation() {
        Span toolSpan = toolSpan("Bash", "use-1");
        Span blockedSpan = blockedOnUserSpan("blocked-1", toolSpan.getSpanId(), 500_000_000L);
        LogRecord log = toolResultLog("Bash", "use-1", "{\"command\":\"./deploy.sh\"}", true, 20L, null);

        String prompt = buildPrompt(List.of(toolSpan, blockedSpan), List.of(log));

        assertThat(prompt).doesNotContain("waiting on user approval");
    }

    /** A quota wall is not agent behaviour, and a rule written against one cannot fire. */
    @Test
    void anEnvironmentalModelCallFailureIsMarkedNotAFindingAndTheTemplateExplainsThePrefix() {
        List<Span> spans = List.of(failedLlmRequestSpan("llm-1", 429, "Usage credits are required."));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt)
                .contains("  Not a finding: these are the environment refusing the call")
                .contains("A \"Not a finding:\" line marks an observation that is true but is NOT a fault");
    }

    /**
     * The inverse, and the reason the classification is by status code rather than by "it was a
     * model call": a 400 is the agent asking for something malformed, which a rule genuinely can
     * prevent. Excusing it would suppress a real finding.
     */
    @Test
    void aClientErrorModelCallFailureIsLeftAsAnOrdinaryFaultToJudge() {
        List<Span> spans = List.of(failedLlmRequestSpan("llm-1", 400, "prompt is too long"));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt)
                .contains("- Model calls that failed: 1 of 1, at call(s) 1 — HTTP 400: prompt is too long")
                .doesNotContain("Not a finding:");
    }

    /** A missing status code is unclassified, not excused — see ModelCallFailure#isEnvironmental. */
    @Test
    void aModelCallFailureWithNoStatusCodeIsNotTreatedAsEnvironmental() {
        List<Span> spans = List.of(failedLlmRequestSpan("llm-1", null, "connection reset"));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt)
                .contains("- Model calls that failed: 1 of 1, at call(s) 1 — connection reset")
                .doesNotContain("Not a finding:");
    }

    /**
     * Eight repetitions of one sentence read as noise and got skipped wholesale. One counted line
     * carrying the model and status is the same information the reviewing model can act on.
     */
    @Test
    void identicalErrorsCollapseToOneCountedLineNamingTheModelAndStatus() {
        List<Span> spans = List.of(
                failedLlmRequestSpan("llm-1", 429, "Usage credits are required."),
                failedLlmRequestSpan("llm-2", 429, "Usage credits are required."),
                failedLlmRequestSpan("llm-3", 429, "Usage credits are required."));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt).contains(
                "- llm_request (claude-sonnet-4, HTTP 429) ×3: Usage credits are required.");
    }

    @Test
    void errorsThatDifferAreNotCollapsedTogether() {
        List<Span> spans = List.of(
                failedLlmRequestSpan("llm-1", 429, "Usage credits are required."),
                failedLlmRequestSpan("llm-2", 500, "Internal server error"));

        String prompt = buildPrompt(spans, List.of());

        assertThat(prompt)
                .contains("- llm_request (claude-sonnet-4, HTTP 429): Usage credits are required.")
                .contains("- llm_request (claude-sonnet-4, HTTP 500): Internal server error")
                // A collapsed pair would have rendered one "×2:" line instead of those two.
                .doesNotContain("×2");
    }

    @Test
    void aFailedModelCallCarriesItsHttpStatusOnItsTimelineLine() {
        String timeline = timelineSectionOf(buildPrompt(
                List.of(failedLlmRequestSpan("llm-1", 429, "Usage credits are required.")), List.of()));

        assertThat(timeline).contains("http_status=429 ERROR=Usage credits are required.");
    }

    /** On a successful call the status is a constant 200 and pure prompt weight. */
    @Test
    void aSuccessfulModelCallDoesNotCarryAStatusOnItsTimelineLine() {
        Span span = llmRequestSpan();
        span.getAttributes().put("status_code", 200);

        String timeline = timelineSectionOf(buildPrompt(List.of(span), List.of()));

        assertThat(timeline).doesNotContain("http_status=");
    }

    @Test
    void theErrorsSectionTellsTheModelNotToWriteRulesForEnvironmentalFailures() {
        String prompt = buildPrompt(
                List.of(failedLlmRequestSpan("llm-1", 429, "Usage credits are required.")), List.of());

        assertThat(prompt)
                .contains("**Not every error here is the agent's fault.**")
                .contains("never propose a change for the failure\nitself");
    }

    @Test
    void aCleanTraceCarriesNeitherTheErrorsSectionNorItsGuidance() {
        String prompt = buildPrompt(List.of(llmRequestSpan()), List.of());

        assertThat(prompt)
                .doesNotContain("## Errors")
                .doesNotContain("**Not every error here is the agent's fault.**")
                .doesNotContain("Not a finding:");
    }

    private static final String LEGEND_LAST_LINE = "totals of the sub-agent run it started.";

    /**
     * Every test with no preceding turn to supply goes through here rather than passing null.
     *
     * <p>Asserts {@code windows().size() == 1} before handing back that single window's text, so
     * this helper doubles as the standing guard that an ordinary trace stays one window — a
     * regression here means windowing kicked in where it should not have, which is a hint the
     * fixture grew too large or the budget shrank, not something a caller should have to notice
     * for itself.
     */
    private String buildPrompt(List<Span> spans, List<LogRecord> logRecords) {
        List<TraceAnalysisPromptBuilder.PromptWindow> windows = buildWindows(spans, logRecords);
        assertThat(windows).hasSize(1);
        // A single-window trace always judges its own request and cost -- there is no other window
        // to defer either question to. Every other test in this file that calls buildPrompt already
        // exercises this path, so this assertion is the one place it is pinned explicitly rather than
        // left to be true only because nothing here happens to break it.
        assertThat(windows.get(0).judgesRequest()).isTrue();
        return windows.get(0).text();
    }

    /** Windowing-specific tests read the raw window list rather than assuming there is one. */
    private List<TraceAnalysisPromptBuilder.PromptWindow> buildWindows(List<Span> spans, List<LogRecord> logRecords) {
        return promptBuilder.build(traceSummary(), spans, logRecords, null, null).windows();
    }

    /**
     * The SECOND call's prompt for the same trace — the one that turns findings into the three
     * "Apply this" lines. Built from the first call's own {@code ApplyThisInputs} exactly as
     * {@code TraceAnalysisService} does, so a test asserting where a rule target or a wording rule
     * lives is asserting against the prompt the model is really shown.
     */
    private String buildApplyThisPrompt(List<Span> spans, List<LogRecord> logRecords) {
        TraceAnalysisPromptBuilder.PromptResult result =
                promptBuilder.build(traceSummary(), spans, logRecords, null, null);
        return promptBuilder.buildApplyThisPrompt(FINDINGS_FIXTURE, result.applyThisInputs());
    }

    private static final String FINDINGS_FIXTURE = """
            **What went wrong**

            - **Wrong instrument** — call 3 ran `find`. Fix: use Glob for filename lookups.""";

    private static TraceSummary traceSummary() {
        return TraceSummary.builder()
                .traceId(TRACE_ID)
                .rootSpanName("claude_code.interaction")
                .durationNanos(5_000_000_000L)
                .spanCount(3L)
                .errorCount(0L)
                .totalCostUsd(0.01)
                .startTimestamp(BASE_TIME)
                .endTimestamp(BASE_TIME.plusSeconds(5))
                .build();
    }

    /** A tool span carrying the {@code file_path} attribute the revisit grouping keys on. */
    private static Span toolSpanForFile(String toolName, String toolUseId, String filePath) {
        Span span = toolSpan(toolName, toolUseId);
        if (filePath != null) {
            span.getAttributes().put("file_path", filePath);
        }
        return span;
    }

    private static Span toolSpan(String toolName, String toolUseId) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tool_name", toolName);
        attributes.put("tool_use_id", toolUseId);
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(toolUseId)
                .name("claude_code.tool")
                .statusCode("ok")
                .durationNanos(100_000_000L)
                .attributes(attributes)
                .build();
    }

    private static Span llmRequestSpan() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("model", "claude-sonnet-4");
        attributes.put("input_tokens", 800);
        attributes.put("output_tokens", 450);
        attributes.put("cache_read_tokens", 12000);
        attributes.put("cache_creation_tokens", 300);
        attributes.put("stop_reason", "end_turn");
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId("llm-1")
                .name("claude_code.llm_request")
                .statusCode("ok")
                .durationNanos(3_200_000_000L)
                .attributes(attributes)
                .build();
    }

    /**
     * A model call the API refused. Everything the classification needs is on the span itself —
     * {@code error}, {@code status_code} and the error status — with no tool_use_id join to make.
     *
     * @param statusCode null for a failure that reported none, which must stay distinguishable from
     *     a zero status
     */
    private static Span failedLlmRequestSpan(String spanId, Integer statusCode, String message) {
        Span span = llmRequestSpan();
        span.setSpanId(spanId);
        span.setStatusCode("error");
        span.setStatusMessage(message);
        span.getAttributes().put("error", message);
        if (statusCode != null) {
            span.getAttributes().put("status_code", statusCode);
        }
        return span;
    }

    /** A main-loop model call opening on a given prompt-token load, for the context observations. */
    private static Span llmRequestSpanWithContextTokens(long promptTokens) {
        Span span = llmRequestSpan();
        span.getAttributes().put("input_tokens", promptTokens);
        span.getAttributes().put("cache_read_tokens", 0);
        span.getAttributes().put("cache_creation_tokens", 0);
        return span;
    }

    /** The envelope the harness delivers when a background task finishes and wakes the session. */
    private static String taskNotification(String toolUseId, String status) {
        return """
                <task-notification>
                <task-id>b2w59xac5</task-id>
                <tool-use-id>%s</tool-use-id>
                <status>%s</status>
                <summary>Background command "Run full backend verify" completed</summary>
                </task-notification>""".formatted(toolUseId, status);
    }

    /** A model call with its own identity, parent and request id — the shape the cost join needs. */
    private static Span modelCall(String spanId, String parentSpanId, String requestId) {
        Span span = llmRequestSpan();
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.getAttributes().put("request_id", requestId);
        return span;
    }

    /** A model call whose prompt-token mix is what the test is about. */
    private static Span cacheShapedModelCall(
            String spanId, long inputTokens, long cacheReadTokens, long cacheCreationTokens) {
        Span span = modelCall(spanId, null, spanId);
        span.getAttributes().put("input_tokens", inputTokens);
        span.getAttributes().put("cache_read_tokens", cacheReadTokens);
        span.getAttributes().put("cache_creation_tokens", cacheCreationTokens);
        return span;
    }

    /**
     * The span Claude Code opens for a tool's actual execution. For an Agent call it is the parent
     * every span the sub-agent goes on to emit hangs from, which is what makes dispatch membership
     * an ancestry question rather than an attribute lookup.
     */
    private static Span executionSpan(String spanId, String parentSpanId) {
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .name("claude_code.tool.execution")
                .statusCode("ok")
                .durationNanos(30_000_000_000L)
                .attributes(new HashMap<>())
                .build();
    }

    /** The child span Claude Code opens while a tool call waits on a real permission prompt. */
    /**
     * Three tool calls, each interrupted by its own 12s approval prompt: the same 36s of waiting as
     * the single-pause fixture, spread over a run of prompts. That shape is what the batching rule
     * is a remedy for -- see singlePauseDominates.
     */
    private static List<Span> repeatedApprovalPromptSpans() {
        List<Span> spans = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            Span toolSpan = toolSpan("Bash", "use-" + index);
            spans.add(toolSpan);
            spans.add(blockedOnUserSpan("blocked-" + index, toolSpan.getSpanId(), 12_000_000_000L));
        }
        return spans;
    }

    private static List<LogRecord> repeatedApprovalPromptLogs() {
        List<LogRecord> logs = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            logs.add(toolResultLog("Bash", "use-" + index, "{\"command\":\"./deploy.sh\"}", true, 20L, null));
        }
        return logs;
    }

    private static Span blockedOnUserSpan(String spanId, String parentSpanId, long durationNanos) {
        return Span.builder()
                .traceId(TRACE_ID)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .name("claude_code.tool.blocked_on_user")
                .statusCode("ok")
                .durationNanos(durationNanos)
                .attributes(new HashMap<>())
                .build();
    }

    private static Span subagentToolSpan(String toolName, String toolUseId, String parentSpanId) {
        Span span = toolSpan(toolName, toolUseId);
        span.setParentSpanId(parentSpanId);
        return span;
    }

    /** A tool call made inside a subagent dispatch, carrying the revisit grouping's file_path. */
    private static Span subagentToolSpanForFile(
            String toolName, String toolUseId, String parentSpanId, String filePath) {
        Span span = subagentToolSpan(toolName, toolUseId, parentSpanId);
        span.getAttributes().put("file_path", filePath);
        return span;
    }

    /** A model call with its own duration and output-token count, for the outlier gating. */
    private static Span llmRequestSpanLasting(long durationNanos, long outputTokens) {
        Span span = llmRequestSpan();
        span.setDurationNanos(durationNanos);
        span.getAttributes().put("output_tokens", outputTokens);
        return span;
    }

    private static LogRecord toolResultLog(
            String toolName, String toolUseId, String toolInput, boolean success, long durationMs, String error) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "tool_result");
        attributes.put("tool_name", toolName);
        attributes.put("tool_use_id", toolUseId);
        attributes.put("tool_input", toolInput);
        attributes.put("success", String.valueOf(success));
        attributes.put("duration_ms", durationMs);
        if (error != null) {
            attributes.put("error", error);
        }
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    /**
     * The per-call record cost, effort and query_source live on. Joined to its model call by
     * request_id, never by span_id: request logs carry whichever span was OPEN when the call was
     * issued, so a span_id join attaches every one of them to the wrong span while looking right.
     */
    private static LogRecord apiRequestLog(String requestId, String querySource, double costUsd) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "api_request");
        attributes.put("request_id", requestId);
        attributes.put("query_source", querySource);
        attributes.put("cost_usd", costUsd);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    private static LogRecord toolDecisionLog(String toolUseId, String decision) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "tool_decision");
        attributes.put("tool_use_id", toolUseId);
        attributes.put("decision", decision);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    private static LogRecord userPromptLog(String promptText) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "user_prompt");
        attributes.put("prompt", promptText);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    private static LogRecord slashCommandPromptLog(String promptText, String commandName) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "user_prompt");
        attributes.put("prompt", promptText);
        attributes.put("command_name", commandName);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    /**
     * The record Claude Code emits once per context compaction. {@code success} arrives as the
     * STRING "true"/"false" on real telemetry, not a boolean, which is why the reader parses it
     * rather than casting; a compaction that failed carries an {@code error} and no post_tokens.
     */
    private static LogRecord compactionLog(
            String trigger, boolean success, long preTokens, long postTokens, long durationMs, String error) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "compaction");
        attributes.put("trigger", trigger);
        attributes.put("success", String.valueOf(success));
        attributes.put("pre_tokens", String.valueOf(preTokens));
        attributes.put("duration_ms", durationMs);
        if (postTokens > 0) {
            attributes.put("post_tokens", String.valueOf(postTokens));
        }
        if (error != null) {
            attributes.put("error", error);
        }
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    private static LogRecord skillActivatedLog(String skillName, String skillSource, String trigger) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "skill_activated");
        attributes.put("skill.name", skillName);
        attributes.put("skill.source", skillSource);
        attributes.put("invocation_trigger", trigger);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }

    private static LogRecord assistantResponseLog(String responseText) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("event.name", "assistant_response");
        attributes.put("response", responseText);
        return LogRecord.builder().traceId(TRACE_ID).timestamp(BASE_TIME).attributes(attributes).build();
    }
}
