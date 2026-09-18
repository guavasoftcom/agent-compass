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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * The one answer check that runs on the generation path. Its oracle is the rendered timeline, so
 * every case here builds one rather than describing calls in the abstract.
 */
class TimelineCitationsTest {

    /** The shape of trace 9ab1feeebdd15a449bbc4c9983dcb79d: model calls odd, Bash calls even. */
    private static final String TIMELINE = """
            1. llm_request model=claude-opus-5 duration=10.5s
            2. Bash {"command":"grep -ril phase"} -> ok (755ms)
            3. llm_request model=claude-opus-5 duration=3.7s
            4. Bash {"command":"grep -n phase"} -> ok (46ms)
            5. llm_request model=claude-opus-5 duration=5.5s
            6. Bash {"command":"sed -n 260,345p"} -> ok (39ms)
            7. llm_request model=claude-opus-5 duration=2.4s
            """;

    private static TimelineCitations.TimelineSameness sameness() {
        return TimelineCitations.samenessIn(TIMELINE);
    }

    private static java.util.List<String> violations(String fault) {
        return TimelineCitations.unsupportedSamenessViolations(fault, sameness());
    }

    @Test
    void theTimelineIsReadIntoACallKindMap() {
        assertThat(sameness().callKinds())
                .containsEntry(1, "llm_request")
                .containsEntry(2, "Bash")
                .containsEntry(5, "llm_request");
    }

    /**
     * Round one of the evidence: a sameness claim across a Bash call and a model call, which the
     * test-scope scorer scored clean because "are identical" names no call kind.
     */
    @Test
    void aSamenessClaimAcrossDifferentCallKindsIsAViolation() {
        assertThat(violations("Redundant work Calls 2 and 5 are identical."))
                .singleElement(STRING)
                .contains("[2, 5]")
                .contains("no repeat linking 2 and 5");
    }

    /**
     * Round two, and the reason kind-checking alone was not enough: seven model calls, so one kind,
     * so nothing for a kind check to say. They are the ordinary agent loop and the prompt links none
     * of them.
     */
    @Test
    void aSamenessClaimOverManyCallsOfOneKindIsAlsoAViolation() {
        String fault = "Redundant work Calls 1, 3, 5, 7 were unnecessary and repeated the same work.";

        assertThat(violations(fault)).singleElement(STRING).contains("no repeat linking 1 and 3");
    }

    /**
     * The two sentences trace {@code 9ab1feeebdd15a449bbc4c9983dcb79d} actually produced, verbatim,
     * against that trace's own 21-call shape — the first on the run that motivated the kind check,
     * the second on the run after it, which is why the check validates markers instead.
     */
    @Test
    void bothSentencesTheMotivatingTraceProducedAreCaught() {
        StringBuilder timeline = new StringBuilder();
        for (int callNumber = 1; callNumber <= 21; callNumber++) {
            timeline.append(callNumber).append(callNumber % 2 == 1
                    ? ". llm_request model=claude-opus-5 duration=2.4s\n"
                    : ". Bash {\"command\":\"sed -n 1,50p a.ts\"} -> ok (40ms)\n");
        }
        TimelineCitations.TimelineSameness realShape = TimelineCitations.samenessIn(timeline.toString());

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 2 and 5 are identical, and 6 is a repeat of 12.", realShape))
                .as("the first run's bullet")
                .isNotEmpty();
        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 7, 9, 11, 13, 15, 17, 19 were unnecessary and repeated the same work.", realShape))
                .as("the next run's bullet, which a kind check alone cannot see")
                .isNotEmpty();
    }

    /** An exact-repeat marker is the prompt saying those calls ARE the same call. */
    @Test
    void aSamenessClaimTheIdenticalRepeatMarkerSupportsIsLeftAlone() {
        String timeline = """
                1. llm_request duration=1.0s
                2. Bash {"command":"ls"} -> ok (5ms)   [identical call repeats at 4]
                3. llm_request duration=1.0s
                4. Bash (repeat of call 2)
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 2 and 4 are identical.", TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    /** The back-reference alone carries the same fact from the later call's side. */
    @Test
    void aBackReferenceSupportsTheClaimOnItsOwn() {
        String timeline = """
                1. Bash {"command":"ls"} -> ok (5ms)
                2. Bash (repeat of call 1)
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 1 and 2 are duplicates.", TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    /** A folded adjacent range IS a repeat claim: "2-4. Bash ... × 3" is one call made three times. */
    @Test
    void aFoldedRangeSupportsTheClaim() {
        String timeline = """
                1. llm_request duration=1.0s
                2-4. Bash {"command":"ls"} × 3 -> ok (5ms)
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 2, 3 and 4 are identical.", TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    /**
     * The same-file marker means the inputs DIFFER, and still counts as support: a review calling
     * that "repeated work on one file" is describing exactly what the marker records.
     */
    @Test
    void theSameFileMarkerSupportsALooseRepeatClaim() {
        String timeline = """
                1. Read {"file_path":"a.js"} -> ok (4ms)   [same file, different input, at 3]
                2. llm_request duration=1.0s
                3. Read {"file_path":"a.js"} -> ok (4ms)
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 1 and 3 repeat the same read.", TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    /**
     * Every cited pair has to hold. One genuine repeat inside a longer list must not excuse the
     * calls the prompt links to nothing.
     */
    @Test
    void oneSupportedPairDoesNotExcuseTheRestOfTheList() {
        String timeline = """
                1. Bash {"command":"ls"} -> ok (5ms)   [identical call repeats at 2]
                2. Bash (repeat of call 1)
                3. llm_request duration=1.0s
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 1, 2 and 3 are identical.", TimelineCitations.samenessIn(timeline)))
                .singleElement(STRING)
                .contains("no repeat linking 1 and 3");
    }

    /** An abbreviated marker hides links this cannot read, so nothing on that line is faulted. */
    @Test
    void anAbbreviatedMarkerExemptsTheCallsItNames() {
        String timeline = """
                1. Read {"file_path":"a.js"} -> ok (4ms)   [identical call repeats at 2, 3, 4, 5, 6, 7, 8, 9 and 4 more]
                2. Read (repeat of call 1)
                3. llm_request duration=1.0s
                """;

        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 1 and 3 are identical.", TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    /** Citing two calls without claiming they are the same says nothing this check judges. */
    @Test
    void citingCallsWithoutClaimingSamenessIsNotAViolation() {
        assertThat(violations("Calls 2 and 5 together account for most of the trace's duration.")).isEmpty();
    }

    /** One call cannot be the same as itself in the sense this checks — there is no pair to compare. */
    @Test
    void aSamenessClaimAboutASingleCallIsLeftAlone() {
        assertThat(violations("Call 2 is a duplicate of earlier work.")).isEmpty();
    }

    /**
     * A call number the timeline does not contain is {@code citationViolations}' finding to report;
     * reporting it here too would fault one sentence twice.
     */
    @Test
    void callNumbersOutsideTheTimelineAreIgnoredRatherThanReported() {
        assertThat(violations("Calls 2 and 99 are identical.")).isEmpty();
    }

    /** "similar" describes a pattern without claiming identity, and is deliberately not matched. */
    @Test
    void aWeakerWordThanSamenessIsNotMatched() {
        assertThat(violations("Calls 2 and 5 are similar in what they were trying to establish.")).isEmpty();
    }

    /** The clause stops at the sentence end, so a later sentence cannot make an earlier one a fault. */
    @Test
    void aSamenessWordInTheFollowingSentenceDoesNotFaultTheCitation() {
        assertThat(violations("Calls 2 and 5 were both slow. The trace has an identical pair elsewhere."))
                .isEmpty();
    }

    @Test
    void anEmptyTimelineScoresNothing() {
        assertThat(TimelineCitations.unsupportedSamenessViolations(
                "Calls 2 and 5 are identical.", TimelineCitations.samenessIn("")))
                .isEmpty();
    }

    // --- miscitedKindViolations --------------------------------------------------------------

    /** The shape of trace 299f2704e7161e2271a5c3749cdf3551: call 22 a model call, call 155 a Read. */
    private static final String KIND_TIMELINE = """
            22. llm_request model=claude-opus-5 duration=2.4s
            155. Read {"file_path":"/repo/backend/.../SystemRepository.java"} -> ok (4ms)
            """;

    private static java.util.Map<Integer, String> kinds() {
        return TimelineCitations.callKindsIn(KIND_TIMELINE);
    }

    /**
     * The exact stored sentence from trace 299f2704e7161e2271a5c3749cdf3551. The citation itself
     * ("calls 22 and 155") names no kind, but the follow-up clause naming `Read` as the replacement
     * tool is what the check reads -- and the timeline says call 22 is a model call, not a Read.
     *
     * <p>Also pins {@code CALL_KIND_WINDOW_CHARS}: `Read` sits 71 characters past the citation in
     * this real, unbroken sentence, past the original 60-character cap -- this test would have
     * silently passed for the wrong reason (nothing flagged) before that constant was widened.
     */
    @Test
    void aFaultNamingTheWrongKindInItsFollowUpClauseIsFlagged() {
        String fault = "Used `grep -n` for file searches at calls 22 and 155, which could be more "
                + "efficiently handled by the dedicated `Read` tool.";

        assertThat(TimelineCitations.miscitedKindViolations(fault, kinds(), true))
                .singleElement(STRING)
                .contains("calls call 22 a Read")
                .contains("the timeline says llm_request");
    }

    /** Call 155 really is a Read, so naming it as one right after the citation is not a violation. */
    @Test
    void aFaultNamingTheRightKindIsNotFlagged() {
        String fault = "Call 155 re-read the same file, using the Read tool a second time.";

        assertThat(TimelineCitations.miscitedKindViolations(fault, kinds(), true)).isEmpty();
    }

    /** A sentence naming two kinds is ambiguous -- see claimedKind -- so it is left unscored. */
    @Test
    void aClauseNamingTwoKindsIsNotScored() {
        String fault = "Calls 22 and 155 mix a Read and an llm_request call.";

        assertThat(TimelineCitations.miscitedKindViolations(fault, kinds(), true)).isEmpty();
    }

    /**
     * A carry-over block in a later partitioned window legitimately names a call outside that
     * window's own timeline -- {@code faultAbsentCalls=false} is what the live generation-path
     * filter passes, so such a citation is not treated as evidence of anything.
     */
    @Test
    void anAbsentCallIsNotFlaggedWhenAbsentCallsAreNotFaulted() {
        assertThat(TimelineCitations.miscitedKindViolations("See call 999 for context.", kinds(), false))
                .isEmpty();
    }

    /** The harness scores a whole review against its complete timeline, so absence there IS a fault. */
    @Test
    void anAbsentCallIsFlaggedWhenAbsentCallsAreFaulted() {
        assertThat(TimelineCitations.miscitedKindViolations("See call 999 for context.", kinds(), true))
                .singleElement(STRING)
                .contains("cites call 999, which is not in the timeline");
    }

    @Test
    void anEmptyCallKindMapScoresNothing() {
        assertThat(TimelineCitations.miscitedKindViolations("Calls 1 and 2 ran Bash.", java.util.Map.of(), true))
                .isEmpty();
    }

    // --- miscitedFileViolations ---------------------------------------------------------------

    /**
     * The shape of trace df8c757de3bfbfe9c1da2b29f431a192: two Read calls touching two different
     * files whose directory-adjacent names differ only by "View" -- AnalyzeTraceDialogView.tsx and
     * AnalyzeTraceDialog.tsx -- plus a Bash call carrying no file_path at all.
     */
    private static final String FILE_TIMELINE = """
            57. Read {"file_path":"/repo/src/components/AnalyzeTraceDialog/AnalyzeTraceDialogView.tsx"} -> ok (4ms)
            58. Bash git status -> ok (10ms)
            59. Read {"file_path":"/repo/src/components/AnalyzeTraceDialog/AnalyzeTraceDialog.tsx"} -> ok (4ms)
            """;

    private static java.util.Map<Integer, String> fileTargets() {
        return TimelineCitations.callTargetsIn(FILE_TIMELINE);
    }

    /** The real bug on df8c757d...: 2 of the 5 cited calls actually touched the sibling file. */
    @Test
    void aCitationWhoseFileDoesNotMatchWhatTheCitedCallActuallyTouchedIsFlagged() {
        String fault = "The file `AnalyzeTraceDialogView.tsx` was read multiple times "
                + "(call numbers 57, 59).";

        assertThat(TimelineCitations.miscitedFileViolations(fault, fileTargets()))
                .singleElement(STRING)
                .contains("cites call 59 as `AnalyzeTraceDialogView.tsx`")
                .contains("touched `AnalyzeTraceDialog.tsx`");
    }

    @Test
    void aCitationWhoseFileMatchesWhatTheCitedCallTouchedIsNotFlagged() {
        String fault = "The file `AnalyzeTraceDialogView.tsx` was read multiple times (call number 57).";

        assertThat(TimelineCitations.miscitedFileViolations(fault, fileTargets())).isEmpty();
    }

    /** Call 58 carries no file_path at all, so a citation of it is left unscored, not guessed at. */
    @Test
    void aCitationOfACallWithNoKnownFileTargetIsNotFlagged() {
        String fault = "The file `AnalyzeTraceDialogView.tsx` was touched at calls 57 and 58.";

        assertThat(TimelineCitations.miscitedFileViolations(fault, fileTargets())).isEmpty();
    }

    /** Zero or several backtick-quoted file tokens in the sentence is not evidence of anything. */
    @Test
    void aSentenceWithNoBacktickedFileTokenIsNotScored() {
        String fault = "The file was read multiple times (call numbers 57, 59).";

        assertThat(TimelineCitations.miscitedFileViolations(fault, fileTargets())).isEmpty();
    }

    /**
     * The real "calls 93 and 101" mis-citation on trace 299f2704e7161e2271a5c3749cdf3551 evades
     * this check too: no backtick-quoted file name appears anywhere in the sentence, so there is
     * nothing to compare against -- a documented gap, not a bug in this check's own conservatism.
     */
    @Test
    void aSentenceWithNoQuotedFileAtAllIsNotScoredEvenWhenTheCitationIsWrong() {
        String fault = "Repeatedly searching the same file with different inputs at calls 93 and 101, "
                + "suggesting inefficiency in file handling.";
        String timeline = """
                93. Bash grep -n X SystemRepository.java -> ok (10ms)
                95. Read {"file_path":"/repo/backend/.../SystemRepository.java"} -> ok (4ms)
                101. Read {"file_path":"/repo/backend/.../SystemRepository.java"} -> ok (4ms)
                """;

        assertThat(TimelineCitations.miscitedFileViolations(fault, TimelineCitations.callTargetsIn(timeline)))
                .isEmpty();
        assertThat(TimelineCitations.miscitedKindViolations(fault, TimelineCitations.callKindsIn(timeline), true))
                .isEmpty();
        assertThat(TimelineCitations.unsupportedSamenessViolations(fault, TimelineCitations.samenessIn(timeline)))
                .isEmpty();
    }

    @Test
    void anEmptyCallTargetMapScoresNothing() {
        assertThat(TimelineCitations.miscitedFileViolations("Calls 1 and 2 touched `a.js`.", java.util.Map.of()))
                .isEmpty();
    }

    // --- miscitedMetricViolations ---------------------------------------------------------------

    /**
     * The shape of trace 1975031e2963758c815c4b218f11adad's calls 51-52: a model call carrying the
     * cost, and the Edit it decided rendered on the very next line carrying none. Call 53 is the
     * other case that has to survive -- an Agent dispatch, a TOOL call whose line legitimately
     * carries a cost -- and call 54 quotes a shell fragment whose "$2" must not read as one.
     */
    private static final String COST_TIMELINE = """
            51. - llm_request model=claude-sonnet-5 effort=medium duration=15.7s output_tokens=1473 cost=$0.0658
            52. Edit {"file_path":"/repo/frontend/src/components/DonutCard/DonutCard.tsx"} -> ok (12ms)
            53. Agent {"subagent_type":"Explore"} -> ok (42.1s)   [ran Explore: 12 model calls, 30 tool calls, $4.1200]
            54. Bash {"command":"awk '{print $2}' totals.txt"} -> ok (18ms)
            """;

    private static java.util.List<String> costViolations(String fault) {
        return TimelineCitations.miscitedMetricViolations(
                fault,
                TimelineCitations.callKindsIn(COST_TIMELINE),
                TimelineCitations.callsWithRenderedCostIn(COST_TIMELINE));
    }

    /** Only the model call and the dispatch carry a figure; the Edit and the Bash do not. */
    @Test
    void onlyTheLinesThatRenderADollarFigureAreRecordedAsCarryingACost() {
        assertThat(TimelineCitations.callsWithRenderedCostIn(COST_TIMELINE)).containsExactly(51, 53);
    }

    /** The real bullet, verbatim: the 15.7s and $0.0658 are call 51's, and call 52 ran in 11ms. */
    @Test
    void aCostChargedToAToolCallIsFlagged() {
        String fault = "Call 52 (Edit) had a duration of 15.7s and cost $0.0658, which is higher than "
                + "typical for an edit operation.";

        assertThat(costViolations(fault))
                .singleElement(STRING)
                .contains("charges $0.0658 to call 52")
                .contains("call 52 is a tool call (Edit), which carries no cost");
    }

    /**
     * Why this check had to be written rather than the existing two widened: all three are silent on
     * the sentence above, each for its own correct reason -- it names the right kind, quotes no file,
     * and claims no sameness.
     */
    @Test
    void theExistingChecksAreSilentOnAMisattributedCost() {
        String fault = "Call 52 (Edit) had a duration of 15.7s and cost $0.0658, which is higher than "
                + "typical for an edit operation.";

        assertThat(TimelineCitations.miscitedKindViolations(
                fault, TimelineCitations.callKindsIn(COST_TIMELINE), true))
                .as("the sentence calls 52 an Edit, and it is one")
                .isEmpty();
        assertThat(TimelineCitations.miscitedFileViolations(fault, TimelineCitations.callTargetsIn(COST_TIMELINE)))
                .as("no backticked file token to compare against")
                .isEmpty();
        assertThat(TimelineCitations.unsupportedSamenessViolations(
                fault, TimelineCitations.samenessIn(COST_TIMELINE)))
                .as("no sameness claimed")
                .isEmpty();
    }

    @Test
    void aCostChargedToTheModelCallThatActuallyCarriedItIsNotFlagged() {
        assertThat(costViolations("Call 51 cost $0.0658, the most of any call in this trace."))
                .isEmpty();
    }

    /**
     * The shape of trace 569e6beda9578c7a6d53ee06fe8249de ("Subagent general-purpose (call 15) used
     * model claude-sonnet-5 with a high cost of $0.5799") -- a correct finding naming a dispatch as a
     * plain citation rather than with the "dispatched at" wording that is skipped elsewhere. The
     * rounding is deliberate: the line renders $4.1200 and the review writes $4.12, which is why
     * callsWithRenderedCostIn records presence and never compares values.
     */
    @Test
    void aDispatchsOwnCostIsNotFlaggedWhenTheAgentCallIsCitedDirectly() {
        assertThat(costViolations("Subagent Explore (call 53) ran up a high cost of $4.12."))
                .isEmpty();
    }

    /**
     * Trace 9ab1feeebdd15a449bbc4c9983dcb79d's real bullet shape: a per-call cost stated against the
     * trace total. Two figures in one sentence is ambiguity, and ambiguity is left unscored.
     */
    @Test
    void aSentenceCarryingTwoCostFiguresIsNotScored() {
        String fault = "Call 52 cost $0.0658, 25.1% of the $0.7056 this trace's 11 model calls account for.";

        assertThat(costViolations(fault)).isEmpty();
    }

    /**
     * Trace 1635329e1e7db7f934b007d90aba7d61's review quotes a shell command containing "$f"; the
     * same shape with a positional parameter would read as a $2 cost without COST_FIGURE's required
     * decimal point.
     */
    @Test
    void aShellParameterInAQuotedCommandIsNotReadAsACost() {
        assertThat(costViolations("Call 54 ran `awk '{print $2}'` where a dedicated tool exists."))
                .isEmpty();
    }

    @Test
    void aCostChargedToACallOutsideTheTimelineIsNotScored() {
        assertThat(costViolations("Call 99 cost $0.0658.")).isEmpty();
    }

    @Test
    void anEmptyCallKindMapScoresNoCostViolations() {
        assertThat(TimelineCitations.miscitedMetricViolations(
                "Call 52 cost $0.0658.", java.util.Map.of(), java.util.Set.of()))
                .isEmpty();
    }

    /**
     * The exact sentence {@code TraceAnalysisService#ensureFailedToolCallsReported} composes for an
     * uncited failure inside a subagent, and the false positive it produced for five straight harness
     * runs on trace {@code 80e62a90dc49cec593af52f698d8dd6b}. Call 201 IS the Bash that failed; call
     * 19 is the Agent that dispatched it, and the sentence says so in words. Reading the dispatch
     * attribution as a citation put the clause's "Bash" onto call 19 and reported the application's
     * own correct sentence as a miscitation.
     */
    @Test
    void aDispatchAttributionIsNotReadAsAClaimAboutTheDispatchingCall() {
        String fault = "Call 201 in subagent general-purpose (dispatched at call 19) (Bash) failed: "
                + "Shell command failed, and the review above did not address it.";
        java.util.Map<Integer, String> kinds = java.util.Map.of(19, "Agent", 201, "Bash");

        assertThat(TimelineCitations.miscitedKindViolations(fault, kinds, true))
                .as("the dispatching call is named, not claimed to be a Bash")
                .isEmpty();
        assertThat(TimelineCitations.citedCallNumbers(fault))
                .as("the evidence is call 201; call 19 is context for where it happened")
                .containsExactly(201);
    }

    /**
     * The other half of the same rule: skipping the attribution must not blind the check to a genuine
     * mismatch about a call that really is cited as evidence.
     *
     * <p>The fixture keeps the dispatch attribution and the faulted citation in separate clauses
     * because {@code "subagent"} contains {@code "agent"}: a clause carrying both that word and a
     * second kind name reads as two candidate kinds, and {@code claimedKind} then declines to score on
     * ambiguity. That conservatism is pre-existing and is why the real sentence's own {@code "Call
     * 201"} citation was never faulted — only the attribution behind it was.
     */
    @Test
    void aMiscitedKindIsStillCaughtInASentenceThatAlsoNamesADispatch() {
        String fault = "The subagent dispatched at call 12 re-ran work: call 19 was a Read that "
                + "duplicated earlier work.";
        java.util.Map<Integer, String> kinds = java.util.Map.of(12, "Agent", 19, "Bash", 30, "Read");

        assertThat(TimelineCitations.miscitedKindViolations(fault, kinds, true))
                .singleElement(STRING)
                .contains("calls call 19 a Read")
                .contains("the timeline says Bash");
    }

    /**
     * A partitioned trace's windows joined into one oracle, which is what {@code
     * TraceAnalysisRegressionHarness} hands in: reading only the FIRST "## Call timeline" section made
     * every call past window 1 look fabricated. Found on trace {@code 80e62a90dc49cec593af52f698d8dd6b}
     * as soon as {@code ollama.timeline-detail-preference} made partitioning reachable — four real
     * calls reported as "not in the timeline". The fixture reproduces the shape rather than the trace:
     * two window prompts, each with its own timeline section and its own trailing section, so the
     * scan has to resume after the first window's next heading to see window 2 at all.
     */
    @Test
    void everyWindowsTimelineIsReadWhenAPartitionedPromptIsScoredAsOne() {
        String joinedWindows = """
                ## Call timeline

                1. Read {"file_path":"/repo/First.java"} -> ok (4ms)
                2. Bash grep -n X First.java -> ok (10ms)

                ## Errors

                none

                ## Call timeline

                207. Read {"file_path":"/repo/Second.java"} -> ok (4ms)
                208. llm_request claude-fable-5

                ## Errors

                none
                """;

        assertThat(TimelineCitations.callKindsIn(joinedWindows))
                .containsEntry(1, "Read")
                .containsEntry(207, "Read")
                .containsEntry(208, "llm_request");
        assertThat(TimelineCitations.miscitedKindViolations(
                "The redundant read is at call 207.", TimelineCitations.callKindsIn(joinedWindows), true))
                .as("a real call in the second window is no longer accused of not existing")
                .isEmpty();
    }
}
