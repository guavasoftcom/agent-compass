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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading call citations out of a review, and the claims about them this application checks on the
 * generation path: a sameness claim two calls do not support ({@link #unsupportedSamenessViolations}),
 * a call cited as the wrong kind ({@link #miscitedKindViolations}), a call cited as touching the
 * wrong file ({@link #miscitedFileViolations}), and a cost charged to a call that cannot carry one
 * ({@link #miscitedMetricViolations}).
 *
 * <p><b>The oracle is always the rendered prompt, never the spans.</b> That is the same choice
 * {@code TraceAnalysisRegressionHarness} makes and for the same reason: a second reconstruction of
 * the call numbering would be free to drift from the one the model was actually shown, and a
 * checker that accuses a correct answer is worse than no checker at all.
 *
 * <p>This class holds the primitives that used to live only in the test-scope
 * {@code TraceAnalysisAnswerScorer} — the timeline and citation patterns, the call-kind/call-target
 * maps, and the clause window. Kind- and file-mismatch checking moved here in full, not just their
 * primitives, once {@code TraceAnalysisService} started dropping such faults on the generation path
 * too — see trace {@code 299f2704e7161e2271a5c3749cdf3551}, whose stored review claimed "Used
 * `grep -n` for file searches at calls 22 and 155, which could be more efficiently handled by the
 * dedicated `Read` tool" — the timeline says call 22 is a model call, not the {@code Read} the
 * sentence's own follow-up clause names it as. {@code TraceAnalysisAnswerScorer} is now a thin
 * delegate over this class rather than a second implementation of the same regexes, the same
 * discipline {@code CITATION_PHRASE}'s widening already needed on both this side and the frontend's
 * {@code callCitations.ts}. Keep all three in step.
 *
 * <p><b>Known gap, deliberately not addressed here</b>: the same stored review also cited a
 * redundant-read finding at "calls 93 and 101" when 93 is a Bash grep and the real duplicate Read
 * pair is 95/101. Neither check catches that sentence — {@link #unsupportedSamenessViolations}'s
 * sameness word ("the same file") sits <i>before</i> the citation rather than in
 * {@link #clauseAfter}'s forward-only window, and the sentence names no call kind or backtick-quoted
 * file next to the citation for {@link #miscitedKindViolations} or {@link #miscitedFileViolations}
 * to check against. Widening the sameness check to read bidirectionally, the way
 * {@link #miscitedFileViolations}'s own clause window already does, would close this — left as a
 * follow-up rather than folded in here.
 */
final class TimelineCitations {

    // The heading trace-analysis-prompt.mustache always renders immediately before the numbered
    // call lines (line 146 at the time of writing), windowed or not -- see timelineSectionOf.
    private static final String CALL_TIMELINE_HEADING = "## Call timeline";

    // Any level-2 markdown heading -- how timelineSectionOf finds where the timeline section ENDS,
    // whichever section the template renders next (Verified observations, Errors, or What to look
    // for skip straight there when the trace has none of the former two).
    private static final Pattern NEXT_SECTION_HEADING = Pattern.compile("\\n## ");

    // A timeline line: "12. Read /a/b.java -> ok (4ms)", a folded range "12-14. Bash ...", or a
    // subagent's "31. [Explore] Grep ...". The leading number(s) are the citable call; the first
    // bare token after any subagent prefix is what that call IS -- a tool name, or llm_request.
    //
    // The optional "-\s+" skip exists because TraceAnalysisPromptBuilder#renderLlmRequestLine always
    // renders a model call as "- llm_request ...", a literal hyphen ahead of the real kind token --
    // confirmed against TraceAnalysisPromptBuilderTest, which asserts the rendered timeline contains
    // "1. - llm_request" verbatim. Without this skip, group 3 captures the bare "-" as the call's
    // "kind" for every model call in a REAL prompt (this class's own hand-written test fixtures never
    // include the dash, which is how this went unnoticed), and "-" then spuriously matches any
    // hyphenated word anywhere else in a citing sentence -- "re-reading", "well-tested" -- making
    // miscitedKindViolations see two candidate kinds in the clause and fall back to its ambiguity
    // rule, silently declining to score a genuine mismatch. Caught building this class's own test for
    // trace 299f2704e7161e2271a5c3749cdf3551, whose fix line ("avoid re-reading with Read") did
    // exactly this.
    static final Pattern TIMELINE_LINE =
            Pattern.compile("^(\\d+)(?:-(\\d+))?\\.\\s+(?:\\[[^\\]]*\\]\\s*)?(?:-\\s+)?(\\S+)", Pattern.MULTILINE);

    // One citation PHRASE: "call"/"calls", optionally "number(s)", then one or more numbers however
    // the model chained them -- "calls 12 and 26", "call numbers 57, 119, 136", "calls 3, 4 and 7".
    // A port of the frontend's CALL_CITATION_PHRASE (callCitations.ts); see
    // TraceAnalysisAnswerScorer's own note for the traces that forced the widening.
    static final Pattern CITATION_PHRASE = Pattern.compile(
            "\\bcalls?\\s+(?:numbers?\\s+)?#?\\d+(?:\\s*(?:,|and|&|through|to|–|-)\\s*#?\\d+)*",
            Pattern.CASE_INSENSITIVE);

    /** The individual call numbers inside one matched phrase — "calls 3, 4 and 7" is three numbers. */
    static final Pattern CALL_NUMBER = Pattern.compile("\\d+");

    // How far past a citation to look for a claim about it -- an upper bound on the clause, not the
    // clause itself: the window also stops at the end of the sentence, because "call 3 was
    // unnecessary. The trace also made a wasteful Read ..." says nothing about call 3 being a Read.
    //
    // Widened from 60 to 100 by measurement against a real miss, not by guesswork: trace
    // 299f2704e7161e2271a5c3749cdf3551's stored review reads "...at calls 22 and 155, which could be
    // more efficiently handled by the dedicated `Read` tool." -- one unbroken sentence, no earlier
    // ". " or newline to stop at, and the word `Read` sits 71 characters past the citation. At 60 the
    // window cuts off mid-sentence at "...the dedicated `" and never sees it, so the fabricated
    // citation (call 22 is a model call, not a Read) went unflagged. The sentence/newline stop is
    // still what actually guards against spilling into a DIFFERENT sentence about a different call
    // ("call 3 was unnecessary. The trace also made a wasteful Read ..."); the character cap is only
    // a backstop against an unbroken sentence running unreasonably long, so widening it to fit one
    // real, ordinary-length sentence carries the same risk profile as 60 did.
    private static final int CALL_KIND_WINDOW_CHARS = 100;
    private static final String SENTENCE_END = ". ";

    /**
     * Words that assert two calls were <b>the same call</b>, as opposed to merely related. Kept
     * deliberately short and unambiguous: every entry here has to mean sameness on its own, because
     * a loose match turns a true finding about two genuinely similar calls into a dropped bullet.
     * "similar", "related" and "again" are all excluded for that reason — they describe a pattern
     * without claiming identity, which is a judgment this check has no business overruling.
     */
    private static final List<String> SAMENESS_CLAIMS =
            List.of("identical", "the same", "duplicate", "repeat");

    /**
     * The three markers {@code TraceAnalysisPromptBuilder} writes onto a timeline line when two
     * calls really are related, plus the folded range in {@link #TIMELINE_LINE}'s own prefix. These
     * four are the complete set of ways the prompt tells the model that two calls go together, and
     * this class faults a sameness claim only when <b>none</b> of them links the calls cited.
     *
     * <p>{@code SAME_FILE_MARKER} counts as support even though it explicitly means the inputs
     * <i>differ</i>: a review saying two calls "repeat" work on one file is describing exactly what
     * that marker records, and faulting it would punish a correct finding. The identity claims this
     * exists to catch are the ones the prompt supports in no way at all.
     */
    private static final Pattern IDENTICAL_REPEATS_MARKER =
            Pattern.compile("\\[identical call repeats at ([^\\]]*)\\]");
    private static final Pattern REPEAT_BACK_REFERENCE = Pattern.compile("\\(repeat of call (\\d+)\\)");
    private static final Pattern SAME_FILE_MARKER =
            Pattern.compile("\\[same file, different input, at ([^\\]]*)\\]");

    /**
     * What {@code abbreviateCallNumbers} appends once a marker lists more than eight calls. A line
     * carrying it has links this class cannot see, so every call on it is exempted rather than
     * risked — the same "skip rather than guess" rule the rest of this checking follows.
     */
    private static final String TRUNCATED_MARKER_SUFFIX = " more";

    // "file_path":"..." inside a rendered timeline line -- see TraceAnalysisPromptBuilder#toolInputFor:
    // for Read/Edit/Write the tool_result log's tool_input is a JSON string carrying exactly this
    // shape. Requiring the closing quote is what makes a cut-off value (a tighter TimelineDetail
    // truncated tool_input before the path finished) fail to match rather than yield a mangled path --
    // the same "skip rather than guess" conservatism claimedKind already applies to an ambiguous
    // clause.
    private static final Pattern FILE_PATH_ATTRIBUTE_VALUE = Pattern.compile("\"file_path\"\\s*:\\s*\"([^\"]*)\"");

    // A bare filename in backticks -- this feature's own markdown convention for naming a file or
    // tool (see backend/CLAUDE.md's note on AnalyzeTraceDialogView's inline-code-chip styling).
    // Deliberately permissive on the name (`[\w.\-]+`) and the extension (1-10 alphanumerics), since
    // the point is finding the ONE such token in a sentence, not validating it as a real path.
    private static final Pattern BACKTICKED_FILE_TOKEN = Pattern.compile("`([\\w.\\-]+\\.[A-Za-z0-9]{1,10})`");

    // A dollar figure, as this application renders one and as a review quotes one back: "$0.0658".
    //
    // The decimal point is REQUIRED, not optional, and that is load-bearing rather than tidy.
    // TraceAnalysisPromptBuilder's COST_FORMAT is "%.4f", so every cost the prompt renders carries
    // one -- while a shell fragment quoted verbatim inside a finding does not, and findings do quote
    // them: trace 1635329e1e7db7f934b007d90aba7d61's review contains `grep "Tests run" "$f"`, and a
    // positional parameter ("awk '{print $2}'") would otherwise read as a cost of $2 charged to
    // whichever call the sentence cites. Measured across every stored review, requiring the decimal
    // costs nothing -- no real citation quotes a whole-dollar cost -- and removes that whole class of
    // false positive.
    private static final Pattern COST_FIGURE = Pattern.compile("\\$(\\d+\\.\\d+)");

    // "model call" is how the answer contract asks for an llm_request to be described in prose, so it
    // names that kind even though the raw label never appears in a written sentence -- see claimedKind.
    private static final String MODEL_CALL_LABEL = "llm_request";
    private static final String MODEL_CALL_PROSE = "model call";

    /**
     * The fixed attribution this application composes ahead of a DISPATCHING call's number — see
     * {@code SubagentCostAttributor}'s label methods and {@code TraceAnalysisPromptBuilder}'s
     * {@code " in subagent Explore (dispatched at call 12)"} suffix. Lower-cased; matched against a
     * lower-cased view of the text so {@code "Dispatched at call 19"} is caught too.
     */
    private static final String DISPATCH_ATTRIBUTION_PREFIX = "dispatched at ";

    private TimelineCitations() {
    }

    /**
     * Whether a citation phrase is naming the subagent DISPATCH a finding happened inside, rather
     * than citing the call the finding is about. Those are the same shape to {@link #CITATION_PHRASE}
     * and completely different claims, so every walker over that pattern skips the attribution form.
     *
     * <p><b>This application writes the sentence that broke the checker.</b>
     * {@code TraceAnalysisService#ensureFailedToolCallsReported} composes {@code "Call 201 in subagent
     * general-purpose (dispatched at call 19) (Bash) failed: ..."} — where 201 is the Bash that
     * failed and 19 is the Agent that dispatched it, both correct. {@link #claimedKind} then read the
     * clause after the SECOND citation, found {@code Bash} in it, and reported "calls call 19 a Bash;
     * the timeline says Agent" against a sentence that never said so. Measured on trace
     * {@code 80e62a90dc49cec593af52f698d8dd6b}, that false positive survived five harness runs across
     * two models, both {@code timeline-detail-preference} settings and two prompt budgets — the most
     * reproducible finding in the whole corpus, and wrong every time. The model copies the same
     * phrasing out of the verified observations into its own findings, so repairing the composed
     * sentence alone would not have covered it.
     *
     * <p><b>Skipping loses no coverage.</b> The attribution names a dispatching call, which is an
     * {@code Agent} tool call by construction, and the phrase says so in words — there is no kind
     * ambiguity left for {@link #miscitedKindViolations} to resolve. It is deliberately NOT mirrored
     * in the frontend's {@code callCitations.ts}: linking a reader to the dispatch that owns a call is
     * useful, while scoring a claim nobody made is not. The shared thing across those two files is
     * {@link #CITATION_PHRASE} itself, which is unchanged.
     */
    private static boolean namesADispatchRatherThanEvidence(String text, int phraseStart) {
        int prefixStart = phraseStart - DISPATCH_ATTRIBUTION_PREFIX.length();
        if (prefixStart < 0) {
            return false;
        }
        return text.substring(prefixStart, phraseStart).equalsIgnoreCase(DISPATCH_ATTRIBUTION_PREFIX);
    }

    /**
     * Every call number cited anywhere in one piece of text — the single implementation both {@code
     * TraceAnalysisService#citedCallNumbers} and {@code TraceAnalysisFindingsMerge} read a finding's
     * citations through, so the two can never drift into disagreeing about what "cited" means.
     */
    static Set<Integer> citedCallNumbers(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<Integer> citedCallNumbers = new LinkedHashSet<>();
        Matcher phrases = CITATION_PHRASE.matcher(text);
        while (phrases.find()) {
            if (namesADispatchRatherThanEvidence(text, phrases.start())) {
                continue;
            }
            Matcher numbers = CALL_NUMBER.matcher(phrases.group());
            while (numbers.find()) {
                citedCallNumbers.add(Integer.parseInt(numbers.group()));
            }
        }
        return citedCallNumbers;
    }

    /**
     * Everything the rendered timeline says about which calls go together, read once per review.
     *
     * @param callKinds call number to what kind of call it is
     * @param linkedCalls symmetric adjacency: calls the timeline records as repeats or revisits of
     *     each other, whether by a folded range, an identical-repeat marker, a back-reference, or a
     *     same-file marker
     * @param callsWithUnreadableLinks calls whose marker was abbreviated past what can be parsed, so
     *     nothing about them can be called unsupported
     */
    record TimelineSameness(
            Map<Integer, String> callKinds,
            Map<Integer, Set<Integer>> linkedCalls,
            Set<Integer> callsWithUnreadableLinks) {

        boolean linked(int firstCall, int secondCall) {
            Set<Integer> links = linkedCalls.get(firstCall);
            return links != null && links.contains(secondCall);
        }
    }

    /**
     * Reads the four sameness signals off the rendered prompt.
     *
     * <p>The markers survive every {@code TimelineDetail} level — that enum varies only the
     * tool-input length, the success outcome and the model-call breakdown, while the grouping that
     * produces these markers runs unconditionally — so an absent link means the model was shown no
     * link, which is what makes absence usable as evidence at all. A tighter level can only produce
     * <i>more</i> links (shorter inputs collapse into one group), never fewer, and this reads the
     * prompt that was actually sent rather than re-deriving one.
     */
    static TimelineSameness samenessIn(String prompt) {
        String timeline = timelineSectionOf(prompt);
        Map<Integer, String> callKinds = callKindsIn(prompt);
        Map<Integer, Set<Integer>> linkedCalls = new LinkedHashMap<>();
        Set<Integer> callsWithUnreadableLinks = new LinkedHashSet<>();

        Matcher lines = TIMELINE_LINE.matcher(timeline);
        while (lines.find()) {
            int firstCall = Integer.parseInt(lines.group(1));
            int lastCall = lines.group(2) == null ? firstCall : Integer.parseInt(lines.group(2));
            // A folded range IS the sameness claim: "12-14. Bash ... × 3" is one call made 3 times.
            for (int call = firstCall; call <= lastCall; call++) {
                for (int other = firstCall; other <= lastCall; other++) {
                    link(linkedCalls, call, other);
                }
            }
            String line = lineFrom(timeline, lines.start());
            linkAll(linkedCalls, callsWithUnreadableLinks, firstCall, IDENTICAL_REPEATS_MARKER, line);
            linkAll(linkedCalls, callsWithUnreadableLinks, firstCall, SAME_FILE_MARKER, line);
            Matcher backReference = REPEAT_BACK_REFERENCE.matcher(line);
            if (backReference.find()) {
                link(linkedCalls, firstCall, Integer.parseInt(backReference.group(1)));
            }
        }
        return new TimelineSameness(callKinds, linkedCalls, callsWithUnreadableLinks);
    }

    /**
     * The prompt narrowed to just its numbered call lines, so a numbered list anywhere ELSE in the
     * prompt cannot be mistaken for a citable call and silently overwrite one under the same number.
     *
     * <p>{@code trace-analysis-prompt.mustache}'s own answer-writing rules render as a numbered list
     * too — {@code "1. **Prefer the verified observations.**"}, {@code "2. **Never invent prompt or
     * response wording.**"} — well after the real timeline, and {@link #TIMELINE_LINE} cannot tell
     * the two apart: both are {@code ^\d+\.\s+\S+}. Unscoped, {@link #callKindsIn} scans the WHOLE
     * prompt and the later match wins {@code Map#put}'s overwrite, so a trace as small as two calls
     * comes back with {@code callKinds = {1: "**Prefer", 2: "**Never"}} — the genuine "Read" and
     * "llm_request" entries clobbered before either check ever runs. Caught building this class's own
     * test against a real regenerated prompt, not a hand-written fixture, which is exactly why the
     * existing fixtures never exposed it: none of them contain the answer-writing rules.
     *
     * <p><b>Why {@link #unsupportedSamenessViolations} still worked despite this.</b> It never
     * compares a call's kind VALUE to anything — only {@code containsKey} (a call exists) and the
     * separately-built {@code linkedCalls} adjacency. Corrupting {@code callKinds}' values changed
     * nothing it reads. {@link #miscitedKindViolations} is the first caller that compares kind
     * equality, which is what surfaced this.
     *
     * <p>Falls back to the whole input when {@link #CALL_TIMELINE_HEADING} is absent, rather than
     * returning empty: every real prompt carries it unconditionally, so absence means the caller
     * already handed in a timeline-only snippet — every hand-written fixture in this class's own test
     * and the scorer's — not a case to guess at.
     *
     * <p><b>EVERY timeline section is collected, not just the first.</b> One window's prompt carries
     * exactly one, so the live path ({@code TraceAnalysisService.dropMiscitedCallFaults}, which checks
     * each window against its own text) is unaffected either way. {@code
     * TraceAnalysisRegressionHarness} is the caller that is not: it joins every window's prompt into
     * one string precisely so the oracle is everything the model was shown, and reading only the first
     * section silently made every call past window 1 look fabricated. Measured on trace {@code
     * 80e62a90dc49cec593af52f698d8dd6b} the moment partitioning became reachable — four citations
     * (calls 207, 208, 218, 225) reported as "not in the timeline" against a trace that genuinely has
     * 228 calls, all of them real and all of them in window 2. A checker that accuses correct answers
     * is the failure this whole class is written to avoid.
     */
    private static String timelineSectionOf(String prompt) {
        int headingIndex = prompt.indexOf(CALL_TIMELINE_HEADING);
        if (headingIndex < 0) {
            return prompt;
        }
        StringBuilder sections = new StringBuilder();
        Matcher nextHeading = NEXT_SECTION_HEADING.matcher(prompt);
        while (headingIndex >= 0) {
            int searchFrom = headingIndex + CALL_TIMELINE_HEADING.length();
            int sectionEnd = nextHeading.find(searchFrom) ? nextHeading.start() : prompt.length();
            if (!sections.isEmpty()) {
                sections.append('\n');
            }
            sections.append(prompt, headingIndex, sectionEnd);
            headingIndex = prompt.indexOf(CALL_TIMELINE_HEADING, sectionEnd);
        }
        return sections.toString();
    }

    private static String lineFrom(String prompt, int lineStart) {
        int lineEnd = prompt.indexOf('\n', lineStart);
        return lineEnd < 0 ? prompt.substring(lineStart) : prompt.substring(lineStart, lineEnd);
    }

    private static void linkAll(
            Map<Integer, Set<Integer>> linkedCalls,
            Set<Integer> callsWithUnreadableLinks,
            int firstCall,
            Pattern marker,
            String line) {
        Matcher matcher = marker.matcher(line);
        if (!matcher.find()) {
            return;
        }
        String listedCalls = matcher.group(1);
        if (listedCalls.endsWith(TRUNCATED_MARKER_SUFFIX)) {
            callsWithUnreadableLinks.add(firstCall);
        }
        Matcher numbers = CALL_NUMBER.matcher(listedCalls);
        while (numbers.find()) {
            link(linkedCalls, firstCall, Integer.parseInt(numbers.group()));
        }
    }

    private static void link(Map<Integer, Set<Integer>> linkedCalls, int firstCall, int secondCall) {
        linkedCalls.computeIfAbsent(firstCall, call -> new LinkedHashSet<>()).add(secondCall);
        linkedCalls.computeIfAbsent(secondCall, call -> new LinkedHashSet<>()).add(firstCall);
    }

    /** Call number to what that call is, read off the rendered timeline. A folded range fills every number in it. */
    static Map<Integer, String> callKindsIn(String prompt) {
        Map<Integer, String> callKinds = new LinkedHashMap<>();
        Matcher lines = TIMELINE_LINE.matcher(timelineSectionOf(prompt));
        while (lines.find()) {
            int firstCall = Integer.parseInt(lines.group(1));
            int lastCall = lines.group(2) == null ? firstCall : Integer.parseInt(lines.group(2));
            for (int callNumber = firstCall; callNumber <= lastCall; callNumber++) {
                callKinds.put(callNumber, lines.group(3));
            }
        }
        return callKinds;
    }

    /**
     * Call number to the full file path that call's own timeline line names, read the same way
     * {@link #callKindsIn} reads what kind a call is — off the rendered prompt, never rebuilt from
     * spans, for the identical reason given on this class's own javadoc.
     *
     * <p>Confirmed against real data (trace {@code df8c757de3bfbfe9c1da2b29f431a192}):
     * {@code TraceAnalysisPromptBuilder#toolInputFor} renders a Read/Edit/Write call's tool_input as
     * the tool_result log's own JSON string, e.g. {@code {"file_path":"/Users/.../frontend/
     * CLAUDE.md"}}, directly into the timeline line — so {@link #FILE_PATH_ATTRIBUTE_VALUE} finds it
     * by searching the line text rather than re-deriving anything about the call. That JSON string is
     * truncated to the current {@code TimelineDetail}'s {@code toolInputLength} cap, so a long path
     * can be cut off before the closing quote; the regex requires that quote, so a truncated value
     * simply fails to match and the call is left with no target rather than a guessed, mangled one —
     * the same "skip rather than guess" rule {@link #claimedKind} already applies to an ambiguous
     * clause.
     */
    static Map<Integer, String> callTargetsIn(String prompt) {
        String timeline = timelineSectionOf(prompt);
        Map<Integer, String> callTargets = new LinkedHashMap<>();
        Matcher lines = TIMELINE_LINE.matcher(timeline);
        while (lines.find()) {
            int lineEnd = timeline.indexOf('\n', lines.end());
            if (lineEnd < 0) {
                lineEnd = timeline.length();
            }
            Matcher filePath = FILE_PATH_ATTRIBUTE_VALUE.matcher(timeline.substring(lines.start(), lineEnd));
            if (!filePath.find()) {
                continue;
            }
            String target = filePath.group(1);
            int firstCall = Integer.parseInt(lines.group(1));
            int lastCall = lines.group(2) == null ? firstCall : Integer.parseInt(lines.group(2));
            for (int callNumber = firstCall; callNumber <= lastCall; callNumber++) {
                callTargets.put(callNumber, target);
            }
        }
        return callTargets;
    }

    /**
     * Every call whose own rendered line carries a dollar figure — read the same way
     * {@link #callKindsIn} and {@link #callTargetsIn} read their facts, off the rendered prompt.
     *
     * <p>Exactly two things write one, and the second is why this is read off the line rather than
     * inferred from a call's kind. {@code TraceAnalysisPromptBuilder#renderLlmRequestLine} appends
     * {@code cost=$0.0658} to a model call; {@code dispatchSummary} appends
     * {@code [ran Explore: 12 model calls, 30 tool calls, $4.1200]} to the {@code Agent} <b>tool</b>
     * call that opened a subagent run. A dispatch is therefore a tool call that legitimately carries
     * a cost, and real reviews cite one that way without the {@code "dispatched at "} wording
     * {@link #namesADispatchRatherThanEvidence} already skips — trace
     * {@code 569e6beda9578c7a6d53ee06fe8249de}'s <i>"Subagent general-purpose (call 15) used model
     * claude-sonnet-5 with a high cost of $0.5799"</i> and trace
     * {@code d0c952b9e070c6d571b26db2e704fea0}'s <i>"The agent spent $0.6385 across 21 model calls
     * and 37 tool calls in the subagent [Explore] (call 4)"</i>. Both are correct findings that a
     * kind-only rule would have accused.
     *
     * <p>Only the figure's <b>presence</b> is recorded, never its value: the dispatch summary renders
     * {@code $4.1200} and a review quotes it back as {@code $4.12}, so comparing values here would
     * need a precision rule to avoid faulting a correct rounding. Matching a cited figure against the
     * one its call actually carries is the separate, broader check this leaves open — see
     * {@link #miscitedMetricViolations}.
     */
    static Set<Integer> callsWithRenderedCostIn(String prompt) {
        String timeline = timelineSectionOf(prompt);
        Set<Integer> callsWithRenderedCost = new LinkedHashSet<>();
        Matcher lines = TIMELINE_LINE.matcher(timeline);
        while (lines.find()) {
            if (!COST_FIGURE.matcher(lineFrom(timeline, lines.start())).find()) {
                continue;
            }
            int firstCall = Integer.parseInt(lines.group(1));
            int lastCall = lines.group(2) == null ? firstCall : Integer.parseInt(lines.group(2));
            for (int callNumber = firstCall; callNumber <= lastCall; callNumber++) {
                callsWithRenderedCost.add(callNumber);
            }
        }
        return callsWithRenderedCost;
    }

    /**
     * The rest of the sentence a citation sits in, capped at {@link #CALL_KIND_WINDOW_CHARS}. A
     * newline ends it too: the next bullet is a different finding about a different call.
     */
    static String clauseAfter(String text, int citationEnd) {
        String window = text.substring(citationEnd, Math.min(text.length(), citationEnd + CALL_KIND_WINDOW_CHARS));
        int sentenceEnd = window.indexOf(SENTENCE_END);
        int lineEnd = window.indexOf('\n');
        int clauseEnd = window.length();
        if (sentenceEnd >= 0) {
            clauseEnd = sentenceEnd;
        }
        if (lineEnd >= 0 && lineEnd < clauseEnd) {
            clauseEnd = lineEnd;
        }
        return window.substring(0, clauseEnd);
    }

    /**
     * The mechanical half of the review's accuracy, and the highest-signal check here: a cited call
     * must exist in the timeline, and must be the kind of call the answer says it is. Confident wrong
     * citations are this feature's documented failure mode — llama3.1 reporting "calls 21 and 26 are
     * identical TodoWrite" when both are model calls, and trace
     * {@code 299f2704e7161e2271a5c3749cdf3551} claiming "Used `grep -n` for file searches at calls 22
     * and 155, which could be more efficiently handled by the dedicated `Read` tool" — call 22 is a
     * model call, not the {@code Read} the sentence's own follow-up clause names it as.
     *
     * <p>The kind check fires only when the clause after a citation names exactly one call kind this
     * trace's own timeline uses. Deliberately conservative: a sentence naming two tools, or none, is
     * not evidence of anything, and a false accusation would send someone hunting a bug in a correct
     * answer.
     *
     * <p><b>Matches the whole citation phrase, not a bare {@code call N}.</b> {@link #CITATION_PHRASE}
     * is found first and every number inside it extracted with {@link #CALL_NUMBER}, so {@code "call
     * numbers 57, 119, 136"} scores all three numbers rather than none. Every number sharing one
     * phrase is checked against the same clause, since the sentence that follows the phrase describes
     * all of them equally (a group's clause runs from the phrase match to
     * {@link #CALL_KIND_WINDOW_CHARS} past its end).
     *
     * @param faultAbsentCalls whether a citation naming a call number absent from {@code callKinds} is
     *     itself a violation. {@code true} when scoring a whole review against its complete timeline
     *     (the harness's use, via {@code TraceAnalysisAnswerScorer}); {@code false} on the generation
     *     path's per-window live filter, where a later window's carry-over block legitimately names
     *     calls from before the window in a shape {@link #TIMELINE_LINE} does not match, so their
     *     absence from THIS window's map is not evidence of anything.
     */
    static List<String> miscitedKindViolations(String answer, Map<Integer, String> callKinds, boolean faultAbsentCalls) {
        if (callKinds.isEmpty()) {
            return List.of();
        }
        Set<String> vocabulary = new LinkedHashSet<>(callKinds.values());
        List<String> violations = new ArrayList<>();
        Matcher phrases = CITATION_PHRASE.matcher(answer);
        while (phrases.find()) {
            if (namesADispatchRatherThanEvidence(answer, phrases.start())) {
                continue;
            }
            String claimedKind = claimedKind(answer, phrases.end(), vocabulary);
            Matcher numbers = CALL_NUMBER.matcher(phrases.group());
            while (numbers.find()) {
                int citedCall = Integer.parseInt(numbers.group());
                String actualKind = callKinds.get(citedCall);
                if (actualKind == null) {
                    if (faultAbsentCalls) {
                        violations.add("cites call " + citedCall + ", which is not in the timeline");
                    }
                    continue;
                }
                if (claimedKind != null && !claimedKind.equals(actualKind)) {
                    violations.add(
                            "calls call " + citedCall + " a " + claimedKind + "; the timeline says " + actualKind);
                }
            }
        }
        return violations;
    }

    // The one call kind named in the clause following a citation, or null when the answer named none
    // or several -- see miscitedKindViolations for why ambiguity is deliberately not scored.
    private static String claimedKind(String answer, int citationEnd, Set<String> vocabulary) {
        String window = clauseAfter(answer, citationEnd).toLowerCase(Locale.ROOT);
        String found = null;
        for (String kind : vocabulary) {
            if (!window.contains(kind.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = kind;
        }
        // "model call" is how the answer contract asks for an llm_request to be described in prose,
        // so it names that kind even though the raw label never appears in a written sentence.
        if (found == null && window.contains(MODEL_CALL_PROSE) && vocabulary.contains(MODEL_CALL_LABEL)) {
            return MODEL_CALL_LABEL;
        }
        return found;
    }

    /**
     * A citation whose <b>file</b> disagrees with the file the cited call actually touched — the
     * class of error {@link #miscitedKindViolations} cannot see, because it only ever checks a call's
     * <i>kind</i> (tool vs. {@code llm_request}), never which file it named.
     *
     * <p>This is the bug that slipped through uncaught on trace {@code df8c757de3bfbfe9c1da2b29f431a192}:
     * the stored answer's first finding read <i>"The file `AnalyzeTraceDialogView.tsx` was read
     * multiple times (call numbers 57, 119, 136, 59, 148)"</i> — 2 of those 5 call numbers (59, 148)
     * actually named a <b>different</b> file, {@code AnalyzeTraceDialog.tsx} (no "View").
     *
     * <p><b>Reads the whole sentence around the citation, not just the clause after it.</b> A real
     * finding states the file name <i>before</i> the call-number list, as the example above shows, so
     * {@link #claimedKind}'s look-ahead window is the wrong shape here; {@link #sentenceAround}
     * expands both directions from the citation phrase to the nearest sentence/line boundary.
     * <b>Exactly one</b> backtick-quoted, file-extension-shaped token in that sentence is required —
     * zero or several is not evidence of anything, the same conservatism {@link #claimedKind} applies
     * to an ambiguous clause — and the cited call needs a <b>known</b> target from
     * {@link #callTargetsIn}; a tool call that carries no file, or a truncated one, is left unscored
     * rather than guessed at.
     */
    static List<String> miscitedFileViolations(String answer, Map<Integer, String> callTargets) {
        if (callTargets.isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        Matcher phrases = CITATION_PHRASE.matcher(answer);
        while (phrases.find()) {
            if (namesADispatchRatherThanEvidence(answer, phrases.start())) {
                continue;
            }
            String claimedFile = soleQuotedFileToken(sentenceAround(answer, phrases.start(), phrases.end()));
            if (claimedFile == null) {
                continue;
            }
            Matcher numbers = CALL_NUMBER.matcher(phrases.group());
            while (numbers.find()) {
                int citedCall = Integer.parseInt(numbers.group());
                String target = callTargets.get(citedCall);
                if (target == null) {
                    continue;
                }
                String actualBasename = lastPathSegment(target);
                if (!actualBasename.equals(claimedFile)) {
                    violations.add("cites call " + citedCall + " as `" + claimedFile + "`; the timeline says call "
                            + citedCall + " touched `" + actualBasename + "`");
                }
            }
        }
        return violations;
    }

    // The one backtick-quoted file-shaped token in the sentence, or null when there are zero or
    // several -- see miscitedFileViolations for why ambiguity is deliberately not scored.
    private static String soleQuotedFileToken(String sentence) {
        Matcher tokens = BACKTICKED_FILE_TOKEN.matcher(sentence);
        String found = null;
        while (tokens.find()) {
            if (found != null) {
                return null;
            }
            found = tokens.group(1);
        }
        return found;
    }

    // The sentence a citation phrase sits in, expanded in both directions to the nearest sentence or
    // line boundary -- mirrors clauseAfter's boundary logic, but bidirectional, because a real
    // finding names the file BEFORE the call-number list ("The file `X.tsx` was read multiple times
    // (call numbers 57, 119, ...)"), so only looking forward from the citation would miss it.
    private static String sentenceAround(String text, int matchStart, int matchEnd) {
        int start = 0;
        int precedingSentenceEnd = text.lastIndexOf(SENTENCE_END, matchStart);
        if (precedingSentenceEnd >= 0) {
            start = precedingSentenceEnd + SENTENCE_END.length();
        }
        int precedingLineStart = text.lastIndexOf('\n', matchStart - 1) + 1;
        if (precedingLineStart > start) {
            start = precedingLineStart;
        }
        int end = text.length();
        int followingSentenceEnd = text.indexOf(SENTENCE_END, matchEnd);
        if (followingSentenceEnd >= 0) {
            end = followingSentenceEnd + 1;
        }
        int followingLineEnd = text.indexOf('\n', matchEnd);
        if (followingLineEnd >= 0 && followingLineEnd < end) {
            end = followingLineEnd;
        }
        return text.substring(start, end);
    }

    // The file's last path segment -- callTargets carries the full path a timeline line rendered,
    // and a claimed file is written as a bare basename in backticks.
    private static String lastPathSegment(String filePath) {
        int lastSeparator = filePath.lastIndexOf('/');
        return lastSeparator < 0 ? filePath : filePath.substring(lastSeparator + 1);
    }

    /**
     * A cost charged to a call that cannot carry one — the review reads a model call's figures off
     * the timeline and pins them on the tool call rendered next to it.
     *
     * <p><b>Trace {@code 1975031e2963758c815c4b218f11adad} is the bill, twice in one review.</b> It
     * reported <i>"Call 52 (Edit) had a duration of 15.7s and cost $0.0658, which is higher than
     * typical for an edit operation"</i> and the same shape again for call 89. Call 52 is an
     * {@code Edit} that ran in <b>11ms</b> and carries no {@code request_id} at all; the 15.656s and
     * $0.0658474 are call <b>51</b>'s, the model call rendered on the line directly above it, matched
     * to four decimal places. Every existing check was silent, and correctly so: the sentence calls
     * 52 an {@code Edit} and it <i>is</i> an {@code Edit}, so {@link #miscitedKindViolations} passes;
     * it quotes no backticked filename, so {@link #miscitedFileViolations} has nothing to compare;
     * it claims no sameness, so {@link #unsupportedSamenessViolations} declines. Nothing here read a
     * <b>figure</b> cited next to a call number, which is the whole of the error.
     *
     * <p><b>What makes this provable rather than a judgment.</b> {@code cost=$} is written in exactly
     * one place, {@code TraceAnalysisPromptBuilder#renderLlmRequestLine}, and a tool call's line is
     * built by a different method that never writes one — at <i>every</i> {@code TimelineDetail}
     * level, since the cost sits behind {@code rendersModelCallBreakdown} on the model line and has
     * no tool-line equivalent to drop. So a tool call carrying a cost is a claim the prompt supports
     * in no way at all, the same argument {@link #unsupportedSamenessViolations} rests on. Note this
     * reasoning does <b>not</b> transfer to durations: a tool line does render one, at the looser
     * detail levels only, so absence there would mean "not rendered" rather than "cannot exist" —
     * which is why this checks costs alone and the general figure-matching version is left open.
     *
     * <p><b>Conservative in four ways, and the set was chosen by measurement rather than guesswork.</b>
     * Run against all 17 stored reviews on the live database that cite a call and mention a dollar
     * figure, it fires on exactly the two bullets above and nothing else. Each guard earns its place
     * against a real sentence in that corpus: a model call is skipped outright (the large majority —
     * every legitimate "call 1 cost $0.1770" finding); a call whose own line carries a figure is
     * skipped (the two subagent dispatches named on {@link #callsWithRenderedCostIn}); a sentence
     * carrying more than one figure is unscored, the same ambiguity rule {@link #soleQuotedFileToken}
     * applies, which is what spares trace {@code 9ab1feeebdd15a449bbc4c9983dcb79d}'s <i>"Call 1 cost
     * $0.1770, 25.1% of the $0.7056 this trace's 11 model calls account for"</i> and trace
     * {@code 73590130fdbec1b4f2c89217103fb3db}'s two-call "e.g." sentence; and {@link #COST_FIGURE}'s
     * required decimal point keeps a quoted shell parameter from reading as a figure.
     *
     * <p><b>Known gap, deliberately not closed here</b>: a finding that cites only tool calls while
     * quoting the <i>trace's</i> total ("the `find` calls at 2, 30, 76 added nothing to a trace that
     * cost $2.7168") would be faulted, since the figure is real but belongs to no call. No such
     * sentence exists in the measured corpus, and the fix — skipping a figure the prompt renders as a
     * total outside the timeline — is better built alongside per-call figure matching than guessed at
     * now. {@link #sentenceAround} is used rather than {@link #clauseAfter} because a cost can be
     * stated before its citation as easily as after it, the same reason the file check reads
     * bidirectionally.
     */
    static List<String> miscitedMetricViolations(
            String answer, Map<Integer, String> callKinds, Set<Integer> callsWithRenderedCost) {
        if (callKinds.isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        Matcher phrases = CITATION_PHRASE.matcher(answer);
        while (phrases.find()) {
            if (namesADispatchRatherThanEvidence(answer, phrases.start())) {
                continue;
            }
            String claimedCost = soleCostFigure(sentenceAround(answer, phrases.start(), phrases.end()));
            if (claimedCost == null) {
                continue;
            }
            Matcher numbers = CALL_NUMBER.matcher(phrases.group());
            while (numbers.find()) {
                int citedCall = Integer.parseInt(numbers.group());
                String actualKind = callKinds.get(citedCall);
                if (actualKind == null || MODEL_CALL_LABEL.equals(actualKind)
                        || callsWithRenderedCost.contains(citedCall)) {
                    continue;
                }
                violations.add("charges $" + claimedCost + " to call " + citedCall
                        + "; the timeline says call " + citedCall + " is a tool call (" + actualKind
                        + "), which carries no cost");
            }
        }
        return violations;
    }

    // The one dollar figure in the sentence, or null when there are zero or several -- see
    // miscitedMetricViolations for why ambiguity is deliberately not scored.
    private static String soleCostFigure(String sentence) {
        Matcher figures = COST_FIGURE.matcher(sentence);
        String found = null;
        while (figures.find()) {
            if (found != null) {
                return null;
            }
            found = figures.group(1);
        }
        return found;
    }

    /**
     * Sameness claims the rendered timeline does not support — the review says two or more calls
     * were the same call, and nothing the model was shown says they were.
     *
     * <p><b>Two rounds of evidence, both from the same trace.</b> The first shape was
     * <i>"Calls 2 and 5 are identical, and 6 is a repeat of 12"</i> on trace
     * {@code 9ab1feeebdd15a449bbc4c9983dcb79d} — call 2 a Bash grep, call 5 a model call — which the
     * whole test-scope scorer scored clean, verified by running it against that answer:
     * {@code claimedKind} only reads a claim about what <i>kind</i> a call is, and "are identical"
     * names no kind. Checking kinds alone fixed that sentence and the very next run produced
     * <i>"Calls 7, 9, 11, 13, 15, 17, 19 were unnecessary and repeated the same work"</i> — seven
     * model calls, so one kind, so a kind check has nothing to say. They are the ordinary agent loop
     * between tool calls, with outputs from 97 to 918 tokens; nothing repeats. Validating against
     * the markers covers both, because the prompt links neither set in any way.
     *
     * <p><b>What counts as support is deliberately generous</b> — all four signals in
     * {@link #samenessIn}, including the same-file marker whose whole point is that the inputs
     * differed. The claim being caught is not "these two calls were similar", which is a judgment;
     * it is "these were the same call" asserted where the prompt records no relationship at all.
     *
     * <p><b>Conservative in five ways, each one a class of false positive.</b> It fires only when one
     * citation phrase names two or more calls; only when the clause after it makes an explicit
     * {@link #SAMENESS_CLAIMS} claim; only over calls the timeline actually contains (an unknown
     * number is {@code citationViolations}' finding, and faulting one sentence twice helps nobody);
     * never when any cited call sits on a line whose marker was abbreviated past parsing; and never
     * on a claim about a single call, which has no pair to compare.
     *
     * @return one message per offending citation phrase, empty when nothing is provably wrong
     */
    static List<String> unsupportedSamenessViolations(String text, TimelineSameness sameness) {
        if (text == null || text.isBlank() || sameness.callKinds().isEmpty()) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        Matcher phrases = CITATION_PHRASE.matcher(text);
        while (phrases.find()) {
            if (namesADispatchRatherThanEvidence(text, phrases.start())
                    || !claimsSameness(clauseAfter(text, phrases.end()))) {
                continue;
            }
            List<Integer> citedCalls = new ArrayList<>();
            Matcher numbers = CALL_NUMBER.matcher(phrases.group());
            while (numbers.find()) {
                int citedCall = Integer.parseInt(numbers.group());
                if (sameness.callKinds().containsKey(citedCall) && !citedCalls.contains(citedCall)) {
                    citedCalls.add(citedCall);
                }
            }
            if (citedCalls.size() < 2 || anyLinksUnreadable(citedCalls, sameness)) {
                continue;
            }
            String unsupported = firstUnsupportedPair(citedCalls, sameness);
            if (unsupported != null) {
                violations.add("calls " + citedCalls + " the same call, but the timeline records no "
                        + "repeat linking " + unsupported);
            }
        }
        return violations;
    }

    /**
     * The first cited pair the timeline links in no way, or null when every pair is accounted for.
     *
     * <p>Every pair has to hold, not merely one: a claim over seven calls is a claim about all of
     * them, and letting one genuine repeat inside the list excuse the other twenty pairs is how a
     * checker stops catching the thing it was written for.
     */
    private static String firstUnsupportedPair(List<Integer> citedCalls, TimelineSameness sameness) {
        for (int index = 0; index < citedCalls.size(); index++) {
            for (int other = index + 1; other < citedCalls.size(); other++) {
                if (!sameness.linked(citedCalls.get(index), citedCalls.get(other))) {
                    return citedCalls.get(index) + " and " + citedCalls.get(other);
                }
            }
        }
        return null;
    }

    private static boolean anyLinksUnreadable(List<Integer> citedCalls, TimelineSameness sameness) {
        for (Integer citedCall : citedCalls) {
            if (sameness.callsWithUnreadableLinks().contains(citedCall)) {
                return true;
            }
        }
        return false;
    }

    private static boolean claimsSameness(String clause) {
        String lowercaseClause = clause.toLowerCase(Locale.ROOT);
        for (String claim : SAMENESS_CLAIMS) {
            if (lowercaseClause.contains(claim)) {
                return true;
            }
        }
        return false;
    }
}
