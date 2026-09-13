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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checker's own tests. {@link TraceAnalysisRegressionHarness} only runs against a live Ollama
 * and a real telemetry database, so without these a bug in the scoring rules would sit unnoticed and
 * either wave a regression through or accuse a correct answer of one.
 *
 * <p>The false-positive cases matter at least as much as the true ones here — a checker that cries
 * wolf gets switched off, which is a slower way of having no checker at all.
 */
class TraceAnalysisAnswerScorerTest {

    // A prompt fragment shaped exactly like the rendered timeline: numbered calls, a folded range,
    // and a subagent-prefixed line.
    private static final String PROMPT = """
            ## Call timeline

            1. Read /a/b/Foo.java -> ok (4ms)
            2. llm_request Opus 5 (1200ms, 400 tokens, $0.0100)
            3. Bash find . -name '*.tsx' -> ok (120ms)
            4-6. Read /a/b/Foo.java -> ok (4ms)
            7. [Explore] Grep pattern=session -> ok (30ms)

            Instruction rule targets: `skill:ship`, `CLAUDE.md`
            """;

    private static final String WELL_FORMED_APPLY_THIS = """

            **Apply this**

            Instruction rule: CLAUDE.md — Default to Glob instead of `find` in Bash.
            Tool swap: use Glob instead of Bash find for locating files by name
            Better wording: None""";

    // A timeline fragment reproducing this trace's actual shape (trace
    // df8c757de3bfbfe9c1da2b29f431a192): two Read calls touching two different files whose
    // directory-adjacent names differ only by "View" -- AnalyzeTraceDialogView.tsx and
    // AnalyzeTraceDialog.tsx -- plus a Bash call that carries no file_path at all.
    private static final String FILE_PROMPT = """
            ## Call timeline

            57. Read {"file_path":"/repo/src/components/AnalyzeTraceDialog/AnalyzeTraceDialogView.tsx"} -> ok (4ms)
            58. Bash git status -> ok (10ms)
            59. Read {"file_path":"/repo/src/components/AnalyzeTraceDialog/AnalyzeTraceDialog.tsx"} -> ok (4ms)
            """;

    @Test
    void acceptsAnAnswerWhoseCitationsAllExistAndAreCalledWhatTheyAre() {
        String answer = "- **Wrong instrument** — call 3 ran a Bash find. Fix: use Glob." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.score(answer, PROMPT, List.of())).isEmpty();
    }

    @Test
    void flagsACitationOfACallThatIsNotInTheTimeline() {
        String answer = "- **Redundant work** — call 99 re-read the file. Fix: read once." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT))
                .containsExactly("cites call 99, which is not in the timeline");
    }

    /** The documented failure: a model call reported as a tool call. */
    @Test
    void flagsACallDescribedAsTheWrongKind() {
        String answer = "- **Redundant work** — call 2 ran Read on the same file. Fix: read once."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT))
                .containsExactly("calls call 2 a Read; the timeline says llm_request");
    }

    @Test
    void acceptsAModelCallDescribedInTheProseTheAnswerContractAsksFor() {
        String answer = "- **Slow decision** — call 2 was the longest model call in the trace. Fix: decide less."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT)).isEmpty();
    }

    @Test
    void flagsAToolCallDescribedAsAModelCall() {
        String answer = "- **Cost** — call 3 was an expensive model call. Fix: none." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT))
                .containsExactly("calls call 3 a llm_request; the timeline says Bash");
    }

    @Test
    void everyNumberOfAFoldedRangeIsCitable() {
        String answer = "- **Redundant work** — calls 4 and 6 re-read Foo.java with Read. Fix: read once."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT)).isEmpty();
    }

    @Test
    void aSubagentPrefixDoesNotHideWhatTheCallWas() {
        assertThat(TraceAnalysisAnswerScorer.timelineCallKinds(PROMPT))
                .containsEntry(7, "Grep")
                .containsEntry(4, "Read")
                .containsEntry(6, "Read");
    }

    /**
     * Ambiguity is deliberately unscored: a sentence naming two tools is not evidence that either is
     * the one being claimed, and guessing would accuse correct answers.
     */
    @Test
    void saysNothingWhenTheClauseAfterACitationNamesSeveralKinds() {
        String answer = "- **Thrash** — call 3 ran Bash where Read would do. Fix: use Read." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT)).isEmpty();
    }

    @Test
    void saysNothingWhenTheAnswerNamesNoKindAtAll() {
        String answer = "- **Thrash** — call 3 did not need to happen. Fix: drop it." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT)).isEmpty();
    }

    /**
     * The widened citation regex's own reason to exist: {@code "call numbers 2, 4 and 6"} is real,
     * observed phrasing (verified on trace df8c757de3bfbfe9c1da2b29f431a192, whose stored answer
     * reads "at call number 6" and "(call numbers 57, 119, 136, 59, 148)") that the original bare
     * {@code \bcalls?\s+(\d+)} pattern could not match at all, so the kind-check never ran on it.
     */
    @Test
    void aCitationPhraseNamingSeveralCallsByNumberStillTriggersTheKindCheck() {
        String answer = "- **Redundant work** — cited call numbers 2, 4 and 6 as Read calls. Fix: read once."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT))
                .containsExactly("calls call 2 a Read; the timeline says llm_request");
    }

    /** A claim about a call two sentences later is not a claim about this one. */
    @Test
    void doesNotReachPastTheClauseFollowingTheCitation() {
        String answer = "- **Thrash** — call 3 was unnecessary. The trace also made a wasteful Read of the same "
                + "file later on, which cost time. Fix: drop it." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.citationViolations(answer, PROMPT)).isEmpty();
    }

    @Test
    void flagsAForbiddenPhraseCaseInsensitively() {
        String answer = "- **Vague request** — the prompt `/ship` is Too Vague. Fix: say more."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.forbiddenPhraseViolations(answer, List.of("too vague")))
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("too vague");
    }

    @Test
    void flagsAnAnswerTheDialogCannotSplitIntoCards() {
        String answer = "- **Something** — happened. Fix: change it.";

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("Apply this");
    }

    @Test
    void flagsAMissingApplyThisLine() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Better wording: None""";

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT))
                .containsExactly("is missing the \"Tool swap:\" line");
    }

    /** The closed target list is the point of the schema; an off-list file is the failure it prevents. */
    @Test
    void flagsARuleTargetThePromptNeverOffered() {
        String answer = """
                **Apply this**

                Instruction rule: docs/CONTRIBUTING.md — Always run the typecheck.
                Tool swap: None
                Better wording: None""";

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT))
                .containsExactly("targets \"docs/CONTRIBUTING.md\", which the prompt never offered");
    }

    @Test
    void acceptsATargetTheseTraceSkillsMadeAvailable() {
        String answer = """
                **Apply this**

                Instruction rule: skill:ship — Verify the push before reporting success.
                Tool swap: None
                Better wording: None""";

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT)).isEmpty();
    }

    /**
     * The fabrication on trace {@code adae1753270dd3088520435ae7f8af94}: a "What went well" section
     * crediting "a high effort model call that was well justified" on a trace whose observations
     * verified no positive at all — its first tool call named no file and it dispatched no subagent,
     * the only two shapes that qualify. Praise is the easier thing to fabricate, since nothing
     * constrains it, which is why an ungated one is worth failing a run over.
     */
    @Test
    void flagsAPositivesSectionOnATraceThatVerifiedNoPositive() {
        String answer = """
                **What went well**

                - **Cost Distribution** — the main loop carried the cost with a high effort model
                  call that was well justified by the complexity of the task."""
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("What went well")
                .contains("verified no");
    }

    /** The same section is correct on a trace whose observations actually carry a positive. */
    @Test
    void acceptsAPositivesSectionWhenTheObservationsVerifiedOne() {
        String promptWithPositive = PROMPT + """

                ## Verified observations

                - Went well: the first tool call opened `Foo.java`, which the request names.
                """;
        String answer = """
                **What went well**

                - **Directed start** — call 1 opened `Foo.java` straight away."""
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, promptWithPositive)).isEmpty();
    }

    /** An answer with no positives section is the normal case and must not be scored for one. */
    @Test
    void acceptsAnAnswerThatWritesNoPositivesSectionAtAll() {
        String answer = "- **Wrong instrument** — call 3 ran a Bash find. Fix: use Glob."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT)).isEmpty();
    }

    /**
     * Trace {@code adae1753270dd3088520435ae7f8af94} spent one of three "Apply this" slots restating
     * a sentence the reader had just read as bullet one's Fix, on two consecutive runs.
     */
    @Test
    void flagsBetterWordingThatOnlyRepeatsAFindingsFix() {
        String answer = """
                - **Ambiguity** — the request was vague. Fix: Name the failing endpoint.

                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: Name the failing endpoint.""";

        assertThat(TraceAnalysisAnswerScorer.duplicatedWordingViolations(answer))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("repeats a finding's own Fix");
    }

    /** Advice that says something the findings did not is the line doing its job. */
    @Test
    void acceptsBetterWordingThatSaysSomethingTheFindingsDidNot() {
        String answer = """
                - **Ambiguity** — the request was vague. Fix: Name the failing endpoint.

                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: Say which report page was broken rather than "doesn't work at all".""";

        assertThat(TraceAnalysisAnswerScorer.duplicatedWordingViolations(answer)).isEmpty();
    }

    /**
     * The one coverage check here, and a deliberate exception to this class's "score only what an
     * answer must not say" rule — see the method's own javadoc. On trace
     * {@code adae1753270dd3088520435ae7f8af94} the trace's only failure went unmentioned across
     * three runs while the review found room for three other bullets.
     */
    @Test
    void flagsAnAnswerThatNeverMentionsAFailedCallTheObservationsReported() {
        String promptWithFailure = PROMPT + """

                ## Verified observations

                - Tool calls that failed: 3 (Bash): no such file
                """;
        String answer = "- **Redundant work** — calls 4 and 6 re-read Foo.java. Fix: read once."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.unreportedFailureViolations(answer, promptWithFailure))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("never mentions the failed call");
    }

    /** Any honest mention satisfies it — this does not judge whether the finding was a good one. */
    @Test
    void acceptsAnAnswerThatMentionsTheFailureAtAll() {
        String promptWithFailure = PROMPT + """

                ## Verified observations

                - Tool calls that failed: 3 (Bash): no such file
                """;
        String answer = "- **Recovery** — call 3 failed and the agent carried on. Fix: check the path first."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.unreportedFailureViolations(answer, promptWithFailure)).isEmpty();
    }

    /** A trace with no failure has nothing to report, so silence about one is not a violation. */
    @Test
    void doesNotAskForAFailureMentionOnATraceThatHadNone() {
        String answer = "- **Redundant work** — calls 4 and 6 re-read Foo.java. Fix: read once."
                + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.unreportedFailureViolations(answer, PROMPT)).isEmpty();
    }

    @Test
    void aNoneRuleLineCarriesNoTargetToCheck() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: None""";

        assertThat(TraceAnalysisAnswerScorer.answerShapeViolations(answer, PROMPT)).isEmpty();
    }

    // Prompt fragments shaped like the template's own wording-settled paragraph -- see
    // TraceAnalysisPromptBuilder#wordingSettledReason and the mustache block it feeds.
    private static final String PROMPT_WITH_WORDING_SETTLED = PROMPT + """

            **The wording of this request has already been settled and there is no fault to find in \
            it.** It named its target explicitly and the agent's very first tool call went straight to \
            it, so "unnamed target" and "ambiguity" do not apply here.
            """;

    /**
     * The mechanical form of the bug on trace {@code 73590130fdbec1b4f2c89217103fb3db}: told in the
     * prompt that this request's wording was already settled, the model wrote advice into
     * {@code Better wording} anyway instead of {@code None}.
     */
    @Test
    void flagsBetterWordingAdviceOnATraceWhoseWordingWasAlreadySettled() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: First confirm the file changes, then run narrower validation.""";

        assertThat(TraceAnalysisAnswerScorer.wordingSettledViolations(answer, PROMPT_WITH_WORDING_SETTLED))
                .containsExactly("prompt says this request's wording is already settled, but "
                        + "\"Better wording:\" is \"First confirm the file changes, then run narrower "
                        + "validation.\" instead of None");
    }

    @Test
    void acceptsNoneForBetterWordingOnATraceWhoseWordingWasAlreadySettled() {
        String answer = WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.wordingSettledViolations(answer, PROMPT_WITH_WORDING_SETTLED))
                .isEmpty();
    }

    @Test
    void saysNothingAboutBetterWordingWhenThePromptNeverSettledTheWording() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: Name the file explicitly next time.""";

        assertThat(TraceAnalysisAnswerScorer.wordingSettledViolations(answer, PROMPT)).isEmpty();
    }

    // The template's own description of the Better wording line -- what leaked into the answer on
    // trace dfe4ea1da356f008ae46b2790736e223.
    private static final String PROMPT_WITH_APPLY_THIS_INSTRUCTIONS = PROMPT + """

            Better wording — <advice> is how to word a request like this one next time, in one or two \
            sentences: the words from the request above that cost the agent work.
            """;

    @Test
    void flagsABetterWordingLineThatEchoesThePromptsOwnInstructions() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: how to word a request like this one next time, in one or two sentences: None""";

        assertThat(TraceAnalysisAnswerScorer.echoedInstructionViolations(
                answer, PROMPT_WITH_APPLY_THIS_INSTRUCTIONS))
                .hasSize(1)
                .allSatisfy(violation -> assertThat(violation)
                        .startsWith("\"Better wording:\" repeats the prompt's own instructions back:")
                        .contains("how to word a request like this one next time"));
    }

    /**
     * Real advice is supposed to quote the request, and the request is in the prompt — so a short
     * shared phrase must not score, or the check accuses every correct answer.
     */
    @Test
    void acceptsAdviceThatQuotesAShortPhraseFromTheRequest() {
        String answer = """
                **Apply this**

                Instruction rule: None
                Tool swap: None
                Better wording: Name the file: say `fix the cache-read total in MetricPointRepository`.""";

        assertThat(TraceAnalysisAnswerScorer.echoedInstructionViolations(
                answer, PROMPT_WITH_APPLY_THIS_INSTRUCTIONS)).isEmpty();
    }

    @Test
    void aNoneBetterWordingLineIsNeverAnEchoedInstruction() {
        assertThat(TraceAnalysisAnswerScorer.echoedInstructionViolations(
                WELL_FORMED_APPLY_THIS, PROMPT_WITH_APPLY_THIS_INSTRUCTIONS)).isEmpty();
    }

    /**
     * Reproduces the real bug on trace df8c757de3bfbfe9c1da2b29f431a192: the stored answer's first
     * finding read "The file `AnalyzeTraceDialogView.tsx` was read multiple times (call numbers 57,
     * 119, 136, 59, 148)" — 2 of those 5 call numbers (59, 148 here shortened to just 59) actually
     * named the sibling file `AnalyzeTraceDialog.tsx`. {@code citationViolations} could not catch
     * this even after the phrase widening, since it only ever checks a call's kind, never its file.
     */
    @Test
    void flagsACitationWhoseFileDoesNotMatchWhatTheCitedCallActuallyTouched() {
        String answer = "- **Redundant work** — The file `AnalyzeTraceDialogView.tsx` was read multiple times "
                + "(call numbers 57, 59). Fix: read once." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.fileCitationViolations(answer, FILE_PROMPT))
                .containsExactly("cites call 59 as `AnalyzeTraceDialogView.tsx`; the timeline says call 59 touched "
                        + "`AnalyzeTraceDialog.tsx`");
    }

    /** A citation that correctly names its call's file must not be flagged. */
    @Test
    void doesNotFlagACitationWhoseFileMatchesWhatTheCitedCallTouched() {
        String answer = "- **Redundant work** — The file `AnalyzeTraceDialog.tsx` was read multiple times "
                + "(call numbers 59). Fix: read once." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.fileCitationViolations(answer, FILE_PROMPT)).isEmpty();
    }

    /**
     * The cited call carries no known file target (a Bash call with no file_path) — there is no data
     * to check the claim against, so it must not be flagged rather than guessed at.
     */
    @Test
    void aCitationOfACallWithNoKnownFileTargetIsNotFlagged() {
        String answer = "- **Wrong step** — The file `AnalyzeTraceDialog.tsx` was checked at call number 58. "
                + "Fix: none." + WELL_FORMED_APPLY_THIS;

        assertThat(TraceAnalysisAnswerScorer.fileCitationViolations(answer, FILE_PROMPT)).isEmpty();
    }
}
