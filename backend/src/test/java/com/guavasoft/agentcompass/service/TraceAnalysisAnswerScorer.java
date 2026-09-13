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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores one trace review against the prompt it was generated from. The checking half of
 * {@link TraceAnalysisRegressionHarness}, kept as its own class for one reason: the harness only
 * runs against a live Ollama and an operator's own database, so a bug in these rules would never be
 * caught. Here they are pure functions over two strings and
 * {@link TraceAnalysisAnswerScorerTest} exercises them on every build.
 *
 * <p>Every check measures the answer against <b>the prompt the model was actually given</b> rather
 * than against anything rebuilt here. The timeline in that prompt is the only correct authority on
 * which call number is which — reconstructing the numbering would be a second implementation of it,
 * free to drift from the one the model saw and to then accuse a correct answer of being wrong.
 *
 * <p>Everything here scores what an answer must <b>not</b> say. Whether the finding it did make was
 * worth making is not automatable, and a checker that pretended otherwise would rank a worse review
 * above a better one.
 */
final class TraceAnalysisAnswerScorer {

    private static final List<String> APPLY_THIS_PREFIXES =
            List.of("Instruction rule:", "Tool swap:", "Better wording:");
    private static final String APPLY_THIS_HEADING = "**Apply this**";
    private static final String RULE_TARGET_SEPARATOR = " — ";
    private static final String NONE = "None";
    private static final String BETTER_WORDING_PREFIX = "Better wording:";

    // Emitted by the prompt template only when TraceAnalysisPromptBuilder's code-side verdict
    // (answered a preceding question, or the agent's first call went straight to a target the
    // request named) has already settled the request-quality question -- see
    // TraceAnalysisPromptBuilder#wordingSettledReason. Its presence in the PROMPT is therefore
    // authoritative for what the answer was told, the same reason citationViolations reads the
    // prompt's own timeline rather than rebuilding one.
    private static final String WORDING_ALREADY_SETTLED_MARKER = "the wording of this request has already been";

    // The answer's positives section, and the prompt marker that licenses it. buildObservations
    // prefixes every verified positive with "Went well: " (POSITIVE_PREFIX / POSITIVE_NOTE_PREFIX in
    // TraceAnalysisPromptBuilder), and the template renders that block into the prompt verbatim, so
    // the prompt's own text is authoritative for whether this trace had a positive to report -- the
    // same oracle choice citationViolations makes in reading the prompt's timeline rather than
    // rebuilding one. Matched without the bullet/indent prefix so either shape counts.
    private static final String WENT_WELL_HEADING = "**What went well**";
    private static final String POSITIVE_OBSERVATION_MARKER = "Went well:";

    // The answer contract's per-finding remedy marker, and the sentence punctuation stripped before
    // two of them are compared -- kept in step with TraceAnalysisAnswer's own constants.
    private static final String FIX_MARKER = "Fix:";
    private static final String SENTENCE_TERMINATORS = ".!?:";

    // The observation line naming failed TOOL calls, and the words any honest mention of one uses.
    // Deliberately a loose word list against a strict prompt trigger: the check exists to catch an
    // answer that ignored a failure wholesale, and a narrow matcher would turn a differently-worded
    // mention into a false accusation -- the failure mode this whole class is written to avoid.
    private static final String FAILED_CALLS_OBSERVATION = "- Tool calls that failed:";
    private static final List<String> FAILURE_WORDS = List.of("fail", "error");

    // How much of an Apply-this value has to appear verbatim in the prompt before it reads as
    // echoed instruction text rather than coincidence -- see echoedInstructionViolations. Long
    // enough to clear a quoted phrase from the request itself, which the advice is meant to quote.
    private static final int MINIMUM_ECHOED_RUN_CHARS = 40;

    private TraceAnalysisAnswerScorer() {
    }

    /**
     * Every violation in one list, so a scorecard reports all of a trace's problems rather than the
     * first one found.
     *
     * @param forbiddenPhrases substrings this particular trace's correct answer must not contain,
     *     matched case-insensitively; empty for a trace nobody has written expectations for
     */
    static List<String> score(String answer, String prompt, List<String> forbiddenPhrases) {
        List<String> violations = new ArrayList<>();
        violations.addAll(citationViolations(answer, prompt));
        violations.addAll(fileCitationViolations(answer, prompt));
        violations.addAll(forbiddenPhraseViolations(answer, forbiddenPhrases));
        violations.addAll(answerShapeViolations(answer, prompt));
        violations.addAll(wordingSettledViolations(answer, prompt));
        violations.addAll(echoedInstructionViolations(answer, prompt));
        violations.addAll(duplicatedWordingViolations(answer));
        violations.addAll(unreportedFailureViolations(answer, prompt));
        return violations;
    }

    /**
     * {@code Better wording:} restating a fault's own {@code Fix:} rather than saying something new.
     * Trace {@code adae1753270dd3088520435ae7f8af94} did this on two consecutive runs, spending one
     * of three "Apply this" slots on a sentence the reader had just read one bullet earlier.
     *
     * <p>{@code TraceAnalysisAnswer} now renders such a line as {@code None} on the structured path,
     * so this check is here for the prose path and as the regression net under that rendering.
     */
    static List<String> duplicatedWordingViolations(String answer) {
        String betterWording = betterWordingLine(answer);
        if (betterWording == null || NONE.equalsIgnoreCase(betterWording)) {
            return List.of();
        }
        String candidate = normalizedSentence(betterWording);
        boolean repeatsAFix = answer.lines()
                .map(String::strip)
                .filter(line -> line.contains(FIX_MARKER))
                .map(line -> normalizedSentence(line.substring(line.indexOf(FIX_MARKER) + FIX_MARKER.length())))
                .anyMatch(candidate::equals);
        if (!repeatsAFix) {
            return List.of();
        }
        return List.of("\"Better wording:\" repeats a finding's own Fix verbatim: \"" + betterWording + "\"");
    }

    /**
     * A trace whose observations named a failed call, and an answer that never mentions one.
     *
     * <p><b>This is a deliberate exception to the rule the rest of this class follows.</b> Everything
     * else here scores what an answer must NOT say, precisely because whether a finding was worth
     * making is not automatable. A coverage check is the opposite shape, and it is here because the
     * answer contract makes failure recovery a named obligation ("for each FAILED or REJECTED call
     * ... do the calls after it address that specific cause?") rather than one candidate among many
     * — on {@code adae1753270dd3088520435ae7f8af94} the trace's only failure went unmentioned across
     * three runs while the review found room for three other bullets.
     *
     * <p>Kept as narrow as that justification: it fires only when the prompt's own observations
     * report a failed <b>tool</b> call, and any mention of failing at all satisfies it. It does not
     * ask that the mention be a finding, be correct, or cite the right call — that would be judging
     * the finding's worth, which is the line this class does not cross.
     */
    static List<String> unreportedFailureViolations(String answer, String prompt) {
        if (!prompt.contains(FAILED_CALLS_OBSERVATION)) {
            return List.of();
        }
        String lowercaseAnswer = answer.toLowerCase(Locale.ROOT);
        if (FAILURE_WORDS.stream().anyMatch(lowercaseAnswer::contains)) {
            return List.of();
        }
        return List.of("never mentions the failed call(s) the observations reported");
    }

    // Normalized the same way TraceAnalysisAnswer#normalizedForComparison does -- trailing sentence
    // punctuation off, case-folded -- so the two agree on what "the same sentence" means.
    private static String normalizedSentence(String text) {
        String stripped = text.strip();
        while (!stripped.isEmpty()
                && SENTENCE_TERMINATORS.indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    /**
     * An {@code Apply this} line whose value is lifted verbatim out of the prompt's own
     * instructions rather than written as an answer.
     *
     * <p>Trace {@code dfe4ea1da356f008ae46b2790736e223} stored <i>"Better wording: how to word a
     * request like this one next time, in one or two sentences: None"</i> — the opening of the
     * template's own description of that line, followed by the real answer. The reader's side has no
     * way to tell that from advice: the dialog drops the card only on an exact {@code None}, so a
     * leaked prefix renders the prompt's words back at them as though they were a judgment about
     * their request.
     *
     * <p><b>Scoped to the wording line alone, and to a substantial run of text.</b> The rule line is
     * deliberately excluded: the contract tells the model to copy an observation's
     * {@code Suggested rule:} line word for word, so a verbatim match there is compliance, not a
     * leak. {@link #MINIMUM_ECHOED_RUN_CHARS} keeps a short shared phrase ("the request") from
     * scoring — advice is supposed to quote the request, and the request is in the prompt.
     */
    static List<String> echoedInstructionViolations(String answer, String prompt) {
        String betterWording = betterWordingLine(answer);
        if (betterWording == null || NONE.equalsIgnoreCase(betterWording)) {
            return List.of();
        }
        String longestEchoedRun = longestEchoedRun(betterWording, prompt);
        if (longestEchoedRun == null) {
            return List.of();
        }
        return List.of("\"Better wording:\" repeats the prompt's own instructions back: \""
                + longestEchoedRun + "\"");
    }

    // The longest leading run of the value's own words that appears verbatim in the prompt, or null
    // when no run reaches the floor. Grown from the START of the value because that is where a
    // leaked placeholder sits -- the model echoes the description, then answers after it.
    private static String longestEchoedRun(String value, String prompt) {
        String[] words = value.split("\\s+");
        String longestRun = null;
        StringBuilder run = new StringBuilder();
        for (String word : words) {
            if (!run.isEmpty()) {
                run.append(' ');
            }
            run.append(word);
            if (!prompt.contains(run)) {
                break;
            }
            if (run.length() >= MINIMUM_ECHOED_RUN_CHARS) {
                longestRun = run.toString();
            }
        }
        return longestRun;
    }

    /**
     * The mechanical form of the bug on trace {@code 73590130fdbec1b4f2c89217103fb3db}: the prompt
     * told the model in code that this request's wording was already settled and to write
     * {@code Better wording: None}, and the model wrote advice there instead — repurposing the slot
     * to restate a different, execution-quality finding and dropping the absolute path the original
     * request had gotten right. Generalized rather than added only as a corpus phrase, because the
     * failure is detectable from the prompt/answer pair alone on ANY trace carrying the marker, not
     * just this one.
     */
    static List<String> wordingSettledViolations(String answer, String prompt) {
        if (!prompt.toLowerCase(Locale.ROOT).contains(WORDING_ALREADY_SETTLED_MARKER)) {
            return List.of();
        }
        String betterWording = betterWordingLine(answer);
        if (betterWording != null && !NONE.equalsIgnoreCase(betterWording)) {
            return List.of("prompt says this request's wording is already settled, but \"Better wording:\" is \""
                    + betterWording + "\" instead of None");
        }
        return List.of();
    }

    // The Better wording line's own value, or null when the line is missing entirely -- a separate,
    // already-scored shape violation (answerShapeViolations), not this check's to report again.
    private static String betterWordingLine(String answer) {
        return answer.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(BETTER_WORDING_PREFIX))
                .map(line -> line.substring(BETTER_WORDING_PREFIX.length()).strip())
                .findFirst()
                .orElse(null);
    }

    /**
     * The mechanical half of the review's accuracy — a cited call must exist in the timeline and
     * must be the kind of call the answer says it is. Delegates to
     * {@link TimelineCitations#miscitedKindViolations}, which owns this check since the generation
     * path started dropping the same faults live — see that method's javadoc for the conservatism
     * and the traces that forced it. {@code faultAbsentCalls} is {@code true} here: this class
     * always scores a whole review against its complete timeline, unlike the generation path's
     * per-window live filter.
     */
    static List<String> citationViolations(String answer, String prompt) {
        return TimelineCitations.miscitedKindViolations(answer, timelineCallKinds(prompt), true);
    }

    /**
     * Call number to what that call is, read off the rendered timeline.
     *
     * <p>Delegates to {@link TimelineCitations}, which owns this since the generation path started
     * checking citations too — see that class for why there is deliberately only one copy.
     */
    static Map<Integer, String> timelineCallKinds(String prompt) {
        return TimelineCitations.callKindsIn(prompt);
    }

    /**
     * Call number to the full file path that call's own timeline line names, read the same way
     * {@link #timelineCallKinds} reads what kind a call is.
     *
     * <p>Delegates to {@link TimelineCitations}, for the identical reason {@link #timelineCallKinds}
     * does.
     */
    static Map<Integer, String> timelineCallTargets(String prompt) {
        return TimelineCitations.callTargetsIn(prompt);
    }

    /**
     * A citation whose <b>file</b> disagrees with the file the cited call actually touched.
     * Delegates to {@link TimelineCitations#miscitedFileViolations}, which owns this check for the
     * same reason {@link #citationViolations} does — see that method's javadoc for the conservatism
     * and the trace ({@code df8c757de3bfbfe9c1da2b29f431a192}) that forced it.
     */
    static List<String> fileCitationViolations(String answer, String prompt) {
        return TimelineCitations.miscitedFileViolations(answer, timelineCallTargets(prompt));
    }

    static List<String> forbiddenPhraseViolations(String answer, List<String> forbiddenPhrases) {
        String lowercaseAnswer = answer.toLowerCase(Locale.ROOT);
        return forbiddenPhrases.stream()
                .filter(phrase -> lowercaseAnswer.contains(phrase.toLowerCase(Locale.ROOT)))
                .map(phrase -> "says \"" + phrase + "\" — the failure this trace is in the corpus for")
                .toList();
    }

    /**
     * That the answer is still the thing the dialog can render: the "Apply this" heading its parser
     * splits on, all three line prefixes, a rule target the prompt actually offered, and no
     * "What went well" section on a trace that verified nothing to put in one.
     *
     * <p>The target is checked against the prompt rather than a list rebuilt here for the same
     * reason the citations are — the prompt is what the model was given, so it is the only thing an
     * off-list answer can be measured against.
     *
     * <p><b>The positives check is the mechanical form of the fabrication on trace
     * {@code adae1753270dd3088520435ae7f8af94}</b>, whose review opened with a "What went well"
     * bullet crediting "a high effort model call that was well justified" on a trace where every
     * model call ran at high effort and no positive had been computed at all. Praise is the easier
     * thing to fabricate — nothing constrains it — which is why {@code buildObservations} gates
     * positives harder than faults and why an ungated one is worth failing a run over.
     *
     * <p>{@link TraceAnalysisAnswer#findingsJsonSchema(boolean, boolean)} now makes this unreachable on the
     * structured path by dropping the field from the schema. This check stays because it is the only
     * thing covering the <b>prose</b> path, where the section is gated by template prose alone and
     * prose fences are what this feature's history is a record of losing.
     */
    static List<String> answerShapeViolations(String answer, String prompt) {
        List<String> violations = new ArrayList<>();
        if (answer.contains(WENT_WELL_HEADING) && !prompt.contains(POSITIVE_OBSERVATION_MARKER)) {
            violations.add("has a \"" + WENT_WELL_HEADING
                    + "\" section, but the prompt verified no \"" + POSITIVE_OBSERVATION_MARKER
                    + "\" observation for it to report");
        }
        if (!answer.contains(APPLY_THIS_HEADING)) {
            violations.add("has no \"" + APPLY_THIS_HEADING + "\" heading — the dialog renders it as raw markdown");
            return violations;
        }
        for (String prefix : APPLY_THIS_PREFIXES) {
            if (!answer.contains(prefix)) {
                violations.add("is missing the \"" + prefix + "\" line");
            }
        }
        String target = ruleTarget(answer);
        if (target != null && !prompt.contains(target)) {
            violations.add("targets \"" + target + "\", which the prompt never offered");
        }
        return violations;
    }

    // The file named on the Instruction rule line, or null when that line is None or carries no
    // target at all (which is what every analysis stored before targets existed looks like).
    private static String ruleTarget(String answer) {
        return answer.lines()
                .map(String::strip)
                .filter(line -> line.startsWith(APPLY_THIS_PREFIXES.get(0)))
                .map(line -> line.substring(APPLY_THIS_PREFIXES.get(0).length()).strip())
                .filter(rule -> rule.contains(RULE_TARGET_SEPARATOR) && !NONE.equalsIgnoreCase(rule))
                .map(rule -> rule.substring(0, rule.indexOf(RULE_TARGET_SEPARATOR)).strip())
                .findFirst()
                .orElse(null);
    }
}
