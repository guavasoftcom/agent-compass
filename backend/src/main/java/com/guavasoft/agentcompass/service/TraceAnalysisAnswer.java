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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The structured shape of a trace review, when {@code ollama.structured-output} is on: what the
 * model is constrained to emit, and the markdown this application renders from it.
 *
 * <p><b>The schema and {@link #toMarkdown} live in the same file on purpose.</b> They are two halves
 * of one contract — the schema says what comes back, the renderer says what the reader and the
 * frontend's {@code parseApplyThis} see — and splitting them is how they drift into disagreeing
 * about a field name.
 *
 * <p><b>What this buys is that the answer's shape stops being something the model can get wrong.</b>
 * Three documented failures of the prose path are structurally impossible here rather than
 * instructed against: an answer cannot be written twice (the whole reason
 * {@link TraceAnalysisService#collapseDuplicatedBlock} exists), an "Apply this" line cannot go
 * missing or arrive under a heading the frontend does not know, and
 * {@link #instructionRuleTarget()} cannot name a file outside the closed list
 * {@code TraceAnalysisPromptBuilder} built from evidence — it is a schema {@code enum}, not a
 * sentence asking a 7B model to copy one of N strings verbatim. On trace
 * {@code 5d6c9ca05d7c6ce12e41a84980693f10} that sentence lost to the model's preference for the
 * first, most general option even after the list was reordered to put the skill first.
 *
 * <p><b>The caps are enforced here, not in the schema.</b> "At most two positives, at most five
 * faults" could be written as {@code maxItems}, but schema-to-grammar conversion support for the
 * array-length keywords varies across Ollama releases and an unsupported keyword is not worth a
 * silent behaviour change on an operator's machine. Truncating in {@link #toMarkdown} is also the
 * honest place for it: the cap is this application's editorial decision about what a reader can act
 * on, the same way {@code buildObservations} gates which observations are worth showing at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceAnalysisAnswer(
        List<Finding> wentWell,
        List<Finding> wentWrong,
        String instructionRuleTarget,
        String instructionRule,
        String toolSwap,
        String betterWording) {

    /**
     * One bullet. {@code fix} is the concrete change the answer contract demands of every fault and
     * deliberately does not apply to a positive — "keep doing X" is not a usable standing order, so
     * a {@code wentWell} entry leaves it blank and {@link #toMarkdown} renders no {@code Fix:}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(String label, String detail, String fix) {
    }

    /**
     * The first model call's answer — see {@link #findingsJsonSchema(boolean, boolean)}. Its own record rather than
     * a partially-filled {@link TraceAnalysisAnswer} so a half-built review is not representable:
     * the combined answer only exists once both calls have returned.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Findings(List<Finding> wentWell, List<Finding> wentWrong, String requestKind) {

        /**
         * The findings alone, as the markdown the second call is shown — the same shape the prose
         * path hands it, so that call cannot tell which path produced its input.
         */
        public String toMarkdown() {
            return findingSections(wentWell, wentWrong);
        }
    }

    /** The second model call's answer — see {@link #applyThisJsonSchema}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApplyThis(
            String instructionRuleTarget, String instructionRule, String toolSwap, String betterWording) {
    }

    /** Joins the two calls' answers into the one document {@link #toMarkdown()} renders. */
    public static TraceAnalysisAnswer of(Findings findings, ApplyThis applyThis) {
        return of(findings, applyThis, null);
    }

    /**
     * The same join, with the {@code Tool swap} line taken from {@code settledToolSwap} when the
     * analysis computed one — see {@code TraceAnalysisPromptBuilder#settledToolSwapFor}.
     *
     * <p><b>The settled value overrides the model's, it does not merely fill a blank.</b> The second
     * call is told the line in its own prompt, and this is the fence under that instruction rather
     * than a fallback for when it is ignored: every prose fence in this feature that left the wrong
     * answer reachable has eventually been reached. It is also the only way the line survives at all
     * on the trace that motivated it — {@code 9ab1feeebdd15a449bbc4c9983dcb79d}, where the shell
     * antipattern fired for `sed` at five calls, the first call's prose dropped it, and the second
     * call then wrote {@code Tool swap: None} in correct compliance with a contract that gates the
     * line on what the review reported.
     *
     * <p>Deliberately narrow. It settles the one line whose content is fully determined by evidence
     * the code already holds, and touches neither {@code instructionRule} nor {@code betterWording}
     * — both are judgments about which fault matters most, which is the model's job here.
     */
    public static TraceAnalysisAnswer of(Findings findings, ApplyThis applyThis, String settledToolSwap) {
        boolean hasSettledToolSwap = settledToolSwap != null && !settledToolSwap.isBlank();
        return new TraceAnalysisAnswer(
                findings.wentWell(),
                findings.wentWrong(),
                applyThis.instructionRuleTarget(),
                applyThis.instructionRule(),
                hasSettledToolSwap ? settledToolSwap : applyThis.toolSwap(),
                applyThis.betterWording());
    }

    private static final int MAXIMUM_POSITIVE_BULLETS = 2;
    private static final int MAXIMUM_FAULT_BULLETS = 5;

    // The literal the answer contract uses for "no advice here", matched case-insensitively on the
    // way in and re-emitted in this exact casing so the frontend's `text.toLowerCase() !== 'none'`
    // check drops the item rather than rendering a card reading "None".
    private static final String NONE = "None";

    /**
     * The closed set {@code requestKind} may take. {@code QUESTION_REQUEST_KIND} is the one that
     * changes behaviour — see {@code TraceAnalysisService#requestWasQuestion} — and the other three
     * exist so the model always has a legal token, including when it genuinely cannot tell.
     */
    static final String QUESTION_REQUEST_KIND = "question";
    private static final List<String> REQUEST_KINDS =
            List.of("instruction", QUESTION_REQUEST_KIND, "both", "none");

    private static final String WENT_WELL_HEADING = "**What went well**";
    private static final String WENT_WRONG_HEADING = "**What went wrong**";
    private static final String APPLY_THIS_HEADING = "**Apply this**";
    private static final String NO_FAULTS_SENTENCE =
            "Nothing in this trace is worth changing.";

    // The three fixed prefixes AnalyzeTraceDialogView's parseApplyThis looks for, each on its own
    // line. Keep them and the target separator in step with that file -- they are one contract.
    private static final String INSTRUCTION_RULE_PREFIX = "Instruction rule: ";
    private static final String TOOL_SWAP_PREFIX = "Tool swap: ";
    // Renamed from the older "Rewritten request: ", which framed the advice as a request to paste
    // and re-run -- useless against a trace that has already finished. The frontend still parses
    // the old prefix, since stored analyses are re-read on every dialog open.
    private static final String BETTER_WORDING_PREFIX = "Better wording: ";
    private static final String TARGET_SEPARATOR = " — ";

    // Characters that already end a sentence -- see sentence(). The colon is here because a detail
    // ending in one is introducing the quote that follows it, not trailing off.
    private static final String SENTENCE_TERMINATORS = ".!?:";

    private static final String BULLET_PREFIX = "- **";
    private static final String LABEL_SEPARATOR = "** — ";
    private static final String FIX_PREFIX = " Fix: ";
    private static final String SECTION_SEPARATOR = "\n\n";

    /**
     * Renders the answer as the markdown that is stored in {@code trace_analyses} and parsed by the
     * frontend — byte-for-byte the shape the prose path asks the model for, so a stored analysis
     * gives no clue which path produced it and no frontend change is needed to read one.
     *
     * <p>The "What went well" section is omitted entirely when there is no positive, matching the
     * prose contract's own rule: that section exists because an answer with nowhere to put anything
     * but criticism produces criticism, not because every trace deserves balance.
     */
    public String toMarkdown() {
        return findingSections(wentWell, wentWrong)
                + SECTION_SEPARATOR + APPLY_THIS_HEADING + SECTION_SEPARATOR + applyThisLines();
    }

    /**
     * The "What went well" / "What went wrong" half, shared by the whole document and by
     * {@link Findings#toMarkdown()} — so the findings the second call reads and the findings the
     * reader ends up with are rendered by one piece of code rather than two that agree today.
     */
    private static String findingSections(List<Finding> wentWell, List<Finding> wentWrong) {
        List<String> sections = new ArrayList<>();
        List<Finding> positives = capped(wentWell, MAXIMUM_POSITIVE_BULLETS);
        if (!positives.isEmpty()) {
            sections.add(WENT_WELL_HEADING + SECTION_SEPARATOR + bullets(positives));
        }
        List<Finding> faults = capped(wentWrong, MAXIMUM_FAULT_BULLETS);
        sections.add(WENT_WRONG_HEADING + SECTION_SEPARATOR
                + (faults.isEmpty() ? NO_FAULTS_SENTENCE : bullets(faults)));
        return String.join(SECTION_SEPARATOR, sections);
    }

    private static List<Finding> capped(List<Finding> findings, int maximum) {
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
                .filter(finding -> finding != null && StringUtils.isNotBlank(finding.detail()))
                .limit(maximum)
                .toList();
    }

    private static String bullets(List<Finding> findings) {
        return findings.stream().map(TraceAnalysisAnswer::bullet).collect(Collectors.joining("\n"));
    }

    private static String bullet(Finding finding) {
        String label = StringUtils.isBlank(finding.label()) ? "Finding" : finding.label().strip();
        String bullet = BULLET_PREFIX + label + LABEL_SEPARATOR + sentence(finding.detail());
        if (isBlankOrNone(finding.fix())) {
            return bullet;
        }
        return bullet + FIX_PREFIX + sentence(finding.fix());
    }

    // The prose contract asks for "<what happened>. Fix: <the change>." and the model usually writes
    // the punctuation itself -- but not always, and a detail running straight into "Fix:" reads as
    // one sentence saying something it does not. Terminating it here is the whole of the
    // normalization: nothing else about the model's wording is touched.
    private static String sentence(String text) {
        String stripped = text.strip();
        char lastCharacter = stripped.charAt(stripped.length() - 1);
        return SENTENCE_TERMINATORS.indexOf(lastCharacter) >= 0 ? stripped : stripped + ".";
    }

    /**
     * The three "Apply this" lines. The rule line carries its target as {@code <target> — <rule>},
     * which is what the frontend peels off into a chip; a rule with no usable target degrades to
     * {@code None} rather than being written against a file nobody named, since an instruction whose
     * home is unknown is not one the reader can apply.
     *
     * <p>Two of the three are text to paste as it stands. {@link #betterWording} is deliberately not
     * — the request it is about has already run, so there is nothing to paste it into; the frontend
     * renders it against the stored original wording as a before/after instead.
     */
    private String applyThisLines() {
        return String.join("\n",
                INSTRUCTION_RULE_PREFIX + instructionRuleLine(),
                TOOL_SWAP_PREFIX + orNone(toolSwap),
                BETTER_WORDING_PREFIX + orNone(betterWordingUnlessAlreadySaid()));
    }

    /**
     * {@link #betterWording}, or {@code None} when it is a copy of a fault's own {@code Fix:}.
     *
     * <p>The second call is handed the findings and asked to distil them, and it distils by copying:
     * on trace {@code adae1753270dd3088520435ae7f8af94} the wording line came back byte-identical to
     * the first bullet's {@code Fix} on two consecutive runs, so a three-bullet review spent one of
     * its three "Apply this" slots restating a sentence the reader had just read. The reader gains
     * nothing from the second copy, which is exactly the argument
     * {@link TraceAnalysisService#collapseDuplicatedBlock} already makes for removing byte-identical
     * repeats — <i>it can never delete text the reader has not already been shown</i>.
     *
     * <p>Matched on the normalized text rather than byte-for-byte, since trailing punctuation is
     * added by {@link #sentence} on one path and not the other, but no looser than that: a wording
     * line that merely <i>overlaps</i> a fix is still doing its own job, and a near-miss matcher
     * would silently eat it.
     */
    private String betterWordingUnlessAlreadySaid() {
        if (isBlankOrNone(betterWording) || wentWrong == null) {
            return betterWording;
        }
        String candidate = normalizedForComparison(betterWording);
        boolean repeatsAFix = wentWrong.stream()
                .filter(finding -> finding != null && !isBlankOrNone(finding.fix()))
                .anyMatch(finding -> normalizedForComparison(finding.fix()).equals(candidate));
        return repeatsAFix ? NONE : betterWording;
    }

    private static String normalizedForComparison(String text) {
        String stripped = text.strip();
        while (!stripped.isEmpty() && SENTENCE_TERMINATORS.indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
            stripped = stripped.substring(0, stripped.length() - 1).strip();
        }
        return stripped.toLowerCase(Locale.ROOT);
    }

    private String instructionRuleLine() {
        if (isBlankOrNone(instructionRule) || isBlankOrNone(instructionRuleTarget)) {
            return NONE;
        }
        return instructionRuleTarget.strip() + TARGET_SEPARATOR + instructionRule.strip();
    }

    private static String orNone(String value) {
        return isBlankOrNone(value) ? NONE : value.strip();
    }

    private static boolean isBlankOrNone(String value) {
        return StringUtils.isBlank(value) || isNone(value);
    }

    private static boolean isNone(String value) {
        return NONE.equalsIgnoreCase(value.strip());
    }

    /**
     * The JSON Schema handed to Ollama as {@code format}, with {@code ruleTargets} — the closed,
     * evidence-built list from {@code TraceAnalysisPromptBuilder} — as the rule target's
     * {@code enum}.
     *
     * <p>{@code None} is a member of that enum rather than the field being optional: every field is
     * required, so the model always has a legal token to emit and never has to choose between
     * inventing advice and producing an incomplete document. That is the same trade the prose
     * contract makes when it says each line is "either text the reader can paste as it stands, or
     * the single word None".
     *
     * @param wordingAlreadySettled mirrors {@code TraceAnalysisPromptBuilder.PromptResult}'s field of
     *     the same name — true when this trace's request answered a question that already named the
     *     work, named its own target and the agent's first call went straight there (see
     *     {@code directedStartObservation}), or is itself a question and so has no target to name
     *     (see {@code requestIsQuestion}). When true, {@code betterWording} is restricted to the
     *     single-value enum {@code ["None"]} rather than a free string: on trace
     *     {@code 73590130fdbec1b4f2c89217103fb3db} the prose contract already told the model this in
     *     words ("Better wording: ... or None if the request was already clear") and it filled the
     *     slot anyway, restating a different finding as "advice" that dropped the absolute path the
     *     original request had gotten right. A structurally illegal token is stronger than an
     *     instruction not to write one — the same trade {@code instructionRuleTarget}'s enum already
     *     makes for a rule target outside the closed list.
     * @param positivesAllowed whether this trace computed a {@code Went well:} observation at all —
     *     see {@link #findingsJsonSchema(boolean, boolean)}, which carries the reasoning.
     */
    public static Map<String, Object> jsonSchema(
            List<String> ruleTargets, boolean wordingAlreadySettled, boolean positivesAllowed) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // Always judges the request -- this combines both calls' schemas into the single-window
        // shape, and windowing (where a call may NOT judge the request) only exists on the split
        // findingsJsonSchema/applyThisJsonSchema path. See findingsJsonSchema's judgesRequest.
        properties.putAll(findingsProperties(positivesAllowed, true));
        properties.putAll(applyThisProperties(ruleTargets, wordingAlreadySettled));
        return objectSchema(properties);
    }

    /**
     * The first model call's schema: the findings alone.
     *
     * <p>The review is generated in two calls — findings, then the "Apply this" distillation — so
     * each call is constrained to exactly the fields it is being asked for. A model that cannot
     * emit an {@code instructionRule} while it is writing findings cannot half-write one, and the
     * second call's grammar is four small fields rather than six mixed ones.
     *
     * @param positivesAllowed whether {@code buildObservations} computed a {@code Went well:} line
     *     for this trace. When false the {@code wentWell} field is <b>omitted from the schema
     *     entirely</b>, so a positive is not a thing the grammar can produce.
     *     <p>This closes the gap that let trace {@code adae1753270dd3088520435ae7f8af94} — a trace
     *     with no verified positive: its first tool call was a {@code Bash} grep naming no file, and
     *     it dispatched no subagent — come back with a fabricated <i>"Cost Distribution — the main
     *     loop carried the cost with a high effort model call that was well justified by the
     *     complexity of the task"</i>. Nothing supported it: all 26 of that trace's model calls ran
     *     at {@code effort=high}, so no call was distinguishable that way, and the bullet reads as an
     *     inversion of the cost-driver observation (call 1 took 28.6% of spend) into praise.
     *     <p>The prompt template <b>already</b> gated its "What went well" section on
     *     {@code hasPositiveObservations} and told the model in words that a positive "belongs in the
     *     'What went well' section below and nowhere else" — and with structured output on, that
     *     gating was dead weight, because the schema handed the model a legal {@code wentWell} array
     *     on every trace regardless. Same failure, same fix, as {@code betterWording} on trace
     *     {@code 73590130fdbec1b4f2c89217103fb3db}: a structurally illegal token is stronger than an
     *     instruction not to write one.
     *     <p><b>Omitted rather than capped with {@code maxItems: 0}</b>, for the reason this class's
     *     own javadoc gives for enforcing the two/five caps in {@link #toMarkdown} instead of the
     *     schema: array-length keyword support varies across Ollama's schema-to-grammar conversion,
     *     and a keyword silently ignored on an operator's machine would leave no fence at all. An
     *     absent field needs no keyword support. The parse side already handles it — Jackson passes
     *     null for a field the model never emitted and {@link #capped} maps null to an empty list, so
     *     {@link #findingSections} omits the section exactly as it does for an empty array.
     * @param judgesRequest whether THIS window's call is the one judging section B and classifying
     *     the request — see {@code TraceAnalysisPromptBuilder.PromptWindow#judgesRequest}. Sections
     *     B and C ask questions the trace's OTHER windows cannot change the answer to (the request's
     *     wording, the cost figures), so only one window per trace is asked them; every other window
     *     omits {@code requestKind} from its schema the same way a trace with no verified positive
     *     omits {@code wentWell} — a token the grammar cannot produce rather than an instruction not
     *     to write one. {@code TraceAnalysisFindingsMerge#mergedRequestKind} already tolerates a
     *     null/blank vote from a non-judging window, so no merge-side change was needed for this.
     */
    public static Map<String, Object> findingsJsonSchema(boolean positivesAllowed, boolean judgesRequest) {
        return objectSchema(findingsProperties(positivesAllowed, judgesRequest));
    }

    /** The second model call's schema: the three "Apply this" lines, plus the rule's target. */
    public static Map<String, Object> applyThisJsonSchema(
            List<String> ruleTargets, boolean wordingAlreadySettled) {
        return objectSchema(applyThisProperties(ruleTargets, wordingAlreadySettled));
    }

    private static Map<String, Object> findingsProperties(boolean positivesAllowed, boolean judgesRequest) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // Absent, not empty-capped, when this trace verified no positive -- see findingsJsonSchema.
        if (positivesAllowed) {
            properties.put("wentWell", arrayOf(findingSchema(false)));
        }
        properties.put("wentWrong", arrayOf(findingSchema(true)));
        // The classification the SECOND call's wording rule turns on, made by the model that can
        // actually read the request and constrained to four tokens so it cannot come back as prose.
        // See TraceAnalysisService#requestWasQuestion for why the judgment is made here and enforced
        // there rather than being re-decided by the second call. Omitted, not defaulted, on a window
        // that is not the one judging the request -- see findingsJsonSchema's judgesRequest.
        if (judgesRequest) {
            properties.put("requestKind", enumOf(REQUEST_KINDS));
        }
        return properties;
    }

    private static Map<String, Object> applyThisProperties(
            List<String> ruleTargets, boolean wordingAlreadySettled) {
        List<String> targetEnum = new ArrayList<>(ruleTargets);
        targetEnum.add(NONE);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("instructionRuleTarget", enumOf(targetEnum));
        properties.put("instructionRule", stringSchema());
        properties.put("toolSwap", stringSchema());
        properties.put("betterWording", wordingAlreadySettled ? enumOf(List.of(NONE)) : stringSchema());
        return properties;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return schema;
    }

    private static Map<String, Object> findingSchema(boolean withFix) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("label", stringSchema());
        properties.put("detail", stringSchema());
        if (withFix) {
            properties.put("fix", stringSchema());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        return schema;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> itemSchema) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", itemSchema);
        return schema;
    }

    private static Map<String, Object> enumOf(List<String> values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("enum", List.copyOf(values));
        return schema;
    }

    private static Map<String, Object> stringSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        return schema;
    }
}
