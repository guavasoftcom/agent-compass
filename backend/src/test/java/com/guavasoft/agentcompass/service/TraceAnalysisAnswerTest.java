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

import com.guavasoft.agentcompass.service.TraceAnalysisAnswer.Finding;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendered markdown is a contract with two readers — the person, and
 * {@code AnalyzeTraceDialogView}'s {@code parseApplyThis} — so these pin the shape that file
 * matches on: the literal {@code **Apply this**} heading preceded by a newline, and the three fixed
 * line prefixes with the rule's target separated by a spaced em dash.
 */
class TraceAnalysisAnswerTest {

    @Test
    void rendersTheMarkdownTheFrontendAlreadyParses() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(),
                List.of(new Finding("Wrong instrument", "call 3 ran `find` where Glob does the same thing",
                        "use Glob for filename lookups")),
                "skill:ship",
                "Default to Glob instead of `find` in Bash.",
                "use Glob instead of Bash find for locating files by name",
                "None");

        String markdown = answer.toMarkdown();

        assertThat(markdown).isEqualTo("""
                **What went wrong**

                - **Wrong instrument** — call 3 ran `find` where Glob does the same thing. \
                Fix: use Glob for filename lookups.

                **Apply this**

                Instruction rule: skill:ship — Default to Glob instead of `find` in Bash.
                Tool swap: use Glob instead of Bash find for locating files by name
                Better wording: None""");
    }

    /**
     * The model writes its own punctuation most of the time; when it does not, a detail running
     * straight into "Fix:" reads as one sentence saying something it does not.
     */
    @Test
    void punctuationIsAddedOnlyWhenTheModelLeftTheSentenceUnterminated() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(),
                List.of(new Finding("Label", "already ends in a period.", "so does this one.")),
                "None", "None", "None", "None");

        assertThat(answer.toMarkdown())
                .contains("— already ends in a period. Fix: so does this one.")
                .doesNotContain("..");
    }

    @Test
    void theWentWellSectionIsOmittedEntirelyWhenNoPositiveWasVerified() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(), List.of(), "None", "None", "None", "None");

        String markdown = answer.toMarkdown();

        assertThat(markdown).doesNotContain("What went well");
        assertThat(markdown).startsWith("**What went wrong**");
    }

    @Test
    void aPositiveRendersWithoutAFixBecauseKeepDoingXIsNotAStandingOrder() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(new Finding("Directed start", "the first call opened the file the request named", null)),
                List.of(), "None", "None", "None", "None");

        assertThat(answer.toMarkdown())
                .startsWith("**What went well**\n\n- **Directed start** — the first call opened the file")
                .doesNotContain("Fix:");
    }

    @Test
    void aTracWithNoFaultsSaysSoInOneSentenceRatherThanRenderingAnEmptySection() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(), List.of(), "None", "None", "None", "None");

        assertThat(answer.toMarkdown()).contains("**What went wrong**\n\nNothing in this trace is worth changing.");
    }

    /** A rule the model wrote but could not place has no home, so it is dropped rather than guessed at. */
    @Test
    void anInstructionRuleWithNoTargetDegradesToNone() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(), List.of(), "None", "Always run the typecheck.", "None", "None");

        assertThat(answer.toMarkdown()).contains("Instruction rule: None");
    }

    /**
     * The settled swap overrides the model's answer rather than filling a blank — on trace
     * {@code 9ab1feeebdd15a449bbc4c9983dcb79d} the model wrote a confident {@code None} over a
     * verified `sed` antipattern, which is the case this exists to survive.
     */
    @Test
    void aSettledToolSwapOverridesTheModelsOwnAnswer() {
        TraceAnalysisAnswer answer = TraceAnalysisAnswer.of(
                new TraceAnalysisAnswer.Findings(List.of(), List.of(), "instruction"),
                new TraceAnalysisAnswer.ApplyThis("CLAUDE.md", "Always run the typecheck.", "None", "None"),
                "use Read + Edit instead of `sed` in Bash — at call(s) 6, 12");

        assertThat(answer.toMarkdown())
                .contains("Tool swap: use Read + Edit instead of `sed` in Bash — at call(s) 6, 12");
    }

    /** With nothing settled, the model's own line stands exactly as it always did. */
    @Test
    void theModelsToolSwapStandsWhenNothingWasSettled() {
        TraceAnalysisAnswer answer = TraceAnalysisAnswer.of(
                new TraceAnalysisAnswer.Findings(List.of(), List.of(), "instruction"),
                new TraceAnalysisAnswer.ApplyThis("CLAUDE.md", "Always run the typecheck.", "use Glob", "None"),
                null);

        assertThat(answer.toMarkdown()).contains("Tool swap: use Glob");
    }

    @Test
    void blankAndNoneAreTheSameAbsentValue() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(), List.of(), "CLAUDE.md", "  ", "", null);

        assertThat(answer.toMarkdown()).contains("""
                Instruction rule: None
                Tool swap: None
                Better wording: None""");
    }

    @Test
    void bulletsAreCappedAtTwoPositivesAndFiveFaults() {
        List<Finding> positives = List.of(
                new Finding("A", "a", null), new Finding("B", "b", null), new Finding("C", "c", null));
        List<Finding> faults = List.of(
                new Finding("1", "one", "fix"), new Finding("2", "two", "fix"), new Finding("3", "three", "fix"),
                new Finding("4", "four", "fix"), new Finding("5", "five", "fix"), new Finding("6", "six", "fix"));

        String markdown = new TraceAnalysisAnswer(positives, faults, "None", "None", "None", "None").toMarkdown();

        assertThat(markdown).contains("**A**").contains("**B**").doesNotContain("**C**");
        assertThat(markdown).contains("**5**").doesNotContain("**6**");
    }

    /**
     * The whole point of the schema over a sentence asking the model to copy a target verbatim: an
     * off-list file is not a thing the grammar can produce.
     */
    @Test
    void theSchemaConstrainsTheRuleTargetToTheClosedListPlusNone() {
        Map<String, Object> schema = TraceAnalysisAnswer.jsonSchema(List.of("skill:ship", "CLAUDE.md"), false, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) properties.get("instructionRuleTarget");

        assertThat(target.get("enum")).isEqualTo(List.of("skill:ship", "CLAUDE.md", "None"));
        assertThat(schema.get("required"))
                .as("every field is required so the model always has a legal token to emit")
                .isEqualTo(List.of(
                        "wentWell", "wentWrong", "requestKind", "instructionRuleTarget", "instructionRule",
                        "toolSwap", "betterWording"));
    }

    /**
     * The classification the second call's wording rule turns on. Constrained to four tokens so the
     * model that reads the request commits to a verdict the program can act on, rather than leaving
     * the second call to reach it again — which is exactly what it did wrongly on trace
     * {@code dfe4ea1da356f008ae46b2790736e223}.
     */
    @Test
    void theFindingsSchemaMakesTheModelClassifyTheRequest() {
        Map<String, Object> schema = TraceAnalysisAnswer.findingsJsonSchema(true, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> requestKind = (Map<String, Object>) properties.get("requestKind");

        assertThat(requestKind.get("enum")).isEqualTo(List.of("instruction", "question", "both", "none"));
        assertThat(schema.get("required"))
                .as("the first call answers only what it is asked for")
                .isEqualTo(List.of("wentWell", "wentWrong", "requestKind"));
    }

    /**
     * The mechanical half of the fix for trace {@code adae1753270dd3088520435ae7f8af94}, which came
     * back with a "What went well" bullet crediting "a high effort model call that was well
     * justified" on a trace that verified no positive at all (and where every model call ran at high
     * effort, so no call was distinguishable that way). The template had gated its own section on
     * {@code hasPositiveObservations} all along; the schema had not, so on the structured path that
     * gating did nothing.
     */
    @Test
    void theFindingsSchemaDropsWentWellEntirelyWhenTheTraceVerifiedNoPositive() {
        Map<String, Object> schema = TraceAnalysisAnswer.findingsJsonSchema(false, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties)
                .as("a positive is not a thing the grammar can produce on such a trace")
                .doesNotContainKey("wentWell");
        assertThat(schema.get("required")).isEqualTo(List.of("wentWrong", "requestKind"));
    }

    /**
     * The windowing sibling of the positives gate above: a window that is not the one judging
     * section B (see {@code TraceAnalysisPromptBuilder.PromptWindow#judgesRequest}) cannot produce a
     * {@code requestKind} vote either, for the identical reason -- the field is a token the grammar
     * cannot emit, not a value the prompt merely discourages.
     */
    @Test
    void theFindingsSchemaDropsRequestKindEntirelyOnAWindowThatDoesNotJudgeTheRequest() {
        Map<String, Object> schema = TraceAnalysisAnswer.findingsJsonSchema(true, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties)
                .as("a request-kind verdict is not a thing the grammar can produce on this window")
                .doesNotContainKey("requestKind");
        assertThat(schema.get("required")).isEqualTo(List.of("wentWell", "wentWrong"));
    }

    /**
     * The absent field has to reach the reader as no section at all, not as a malformed one — the
     * parse side is what makes omitting it safe, so it is pinned rather than assumed.
     */
    @Test
    void findingsWithNoWentWellFieldRenderNoWentWellSection() {
        TraceAnalysisAnswer.Findings findings = new TraceAnalysisAnswer.Findings(
                null,
                List.of(new TraceAnalysisAnswer.Finding("Redundant work", "Read twice.", "Read once.")),
                "instruction");

        assertThat(findings.toMarkdown())
                .doesNotContain("What went well")
                .contains("**What went wrong**");
    }

    /**
     * On trace {@code adae1753270dd3088520435ae7f8af94} the second call distilled the findings by
     * copying: {@code Better wording} came back byte-identical to the first bullet's {@code Fix} on
     * two consecutive runs, spending one of three "Apply this" slots on a sentence the reader had
     * just read. Same argument as collapseDuplicatedBlock -- removing it can never take away text
     * the reader has not already been shown.
     */
    @Test
    void betterWordingThatRepeatsAFindingsOwnFixIsDroppedToNone() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(),
                List.of(new TraceAnalysisAnswer.Finding(
                        "Ambiguity", "The request was vague.", "Name the failing endpoint.")),
                "CLAUDE.md",
                "State the endpoint before asking for a fix.",
                "None",
                "Name the failing endpoint.");

        assertThat(answer.toMarkdown())
                .contains("Better wording: None")
                .doesNotContain("Better wording: Name the failing endpoint");
    }

    /** Advice that says something the findings did not is the whole point of the line. */
    @Test
    void betterWordingThatSaysSomethingNewSurvives() {
        TraceAnalysisAnswer answer = new TraceAnalysisAnswer(
                List.of(),
                List.of(new TraceAnalysisAnswer.Finding(
                        "Ambiguity", "The request was vague.", "Name the failing endpoint.")),
                "CLAUDE.md",
                "State the endpoint before asking for a fix.",
                "None",
                "Say which report page was broken rather than \"doesn't work at all\".");

        assertThat(answer.toMarkdown())
                .contains("Better wording: Say which report page was broken");
    }

    @Test
    void onlyAFaultCarriesAFixInTheSchema() {
        Map<String, Object> schema = TraceAnalysisAnswer.jsonSchema(List.of("CLAUDE.md"), false, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(requiredFieldsOfArrayItems(properties.get("wentWrong"))).contains("fix");
        assertThat(requiredFieldsOfArrayItems(properties.get("wentWell"))).doesNotContain("fix");
    }

    /**
     * The whole point of restricting {@code betterWording} on such a trace: on
     * {@code 73590130fdbec1b4f2c89217103fb3db} the prose contract already asked the model in words
     * not to write advice here and it did anyway. A single-value enum makes that answer
     * structurally unreachable rather than merely discouraged.
     */
    @Test
    void theSchemaRestrictsBetterWordingToNoneWhenTheRequestsWordingIsAlreadySettled() {
        Map<String, Object> schema = TraceAnalysisAnswer.jsonSchema(List.of("CLAUDE.md"), true, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> betterWording = (Map<String, Object>) properties.get("betterWording");

        assertThat(betterWording.get("enum")).isEqualTo(List.of("None"));
    }

    @Test
    void theSchemaLeavesBetterWordingFreeTextWhenTheRequestsWordingIsNotSettled() {
        Map<String, Object> schema = TraceAnalysisAnswer.jsonSchema(List.of("CLAUDE.md"), false, true);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> betterWording = (Map<String, Object>) properties.get("betterWording");

        assertThat(betterWording).doesNotContainKey("enum");
    }

    @SuppressWarnings("unchecked")
    private static List<String> requiredFieldsOfArrayItems(Object arraySchema) {
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) arraySchema).get("items");
        return (List<String>) items.get("required");
    }
}
