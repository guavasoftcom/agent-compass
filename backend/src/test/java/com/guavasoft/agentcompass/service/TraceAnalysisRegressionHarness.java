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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.model.TraceAnalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scores the trace-analysis review against traces whose correct answer is already known, and prints
 * a scorecard. <b>A developer tool, not a CI test</b> — it needs a live Ollama and the operator's own
 * telemetry database, so it stays switched off unless {@code TRACE_ANALYSIS_HARNESS=true} is set:
 *
 * <pre>{@code
 * TRACE_ANALYSIS_HARNESS=true ./backend/mvnw -f backend/pom.xml test \
 *     -Dtest=TraceAnalysisRegressionHarness
 * }</pre>
 *
 * <p><b>Why it exists.</b> Every change to this feature so far — the slash-command fence, the
 * preceding-turn section, the {@code answersPrecedingQuestion} verdict, the outlier gating — was
 * judged by regenerating one trace and reading the answer. That works for the failure you already
 * know about and says nothing about the four you fixed last month. The corpus below is those
 * failures, each with the sentence it must no longer produce, so a change can be scored instead of
 * eyeballed. It is also the only way to decide {@code ollama.structured-output}: constrained
 * decoding makes the answer's shape unfalsifiable while possibly degrading its content, and which
 * effect wins is a property of the operator's model. Run it with the flag off, run it with the flag
 * on, compare the two scorecards.
 *
 * <p><b>The citation check is the highest-signal part and the reason the prompt is read back.</b>
 * Confident wrong citations are this review's documented failure mode — llama3.1 reporting "calls 21
 * and 26 are identical TodoWrite" when both are model calls — and the only correct oracle for them
 * is the timeline the model was actually shown, which is why {@link TraceAnalysisService#preparePrompt}
 * is a separate step rather than the harness renumbering the spans itself.
 *
 * <p><b>What it cannot do.</b> It scores what an answer must not say, never whether the finding it
 * did make was worth making — no automated check has an opinion about that, and pretending otherwise
 * would make a worse review look like a better one. Read the printed answers; the assertions are the
 * floor, not the verdict. Two runs of the same model on the same trace also differ, so treat one
 * violation as a prompt to re-run and a repeated one as a regression.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TRACE_ANALYSIS_HARNESS", matches = "true")
class TraceAnalysisRegressionHarness {

    /**
     * The traces whose correct answers are documented, each with what a right answer must not say.
     * Every entry is a real failure this feature shipped and then fixed; the phrases are lifted from
     * the answers those bugs actually produced (see {@code backend/CLAUDE.md}).
     *
     * <p>Phrases are matched case-insensitively as substrings, so they are deliberately short and
     * specific — a long quotation would pass the moment the model reworded its mistake.
     */
    private static final List<TraceExpectation> CORPUS = List.of(
            new TraceExpectation(
                    "5d6c9ca05d7c6ce12e41a84980693f10",
                    "a 31-second /ship with no failures and no revisits: judged the command's wording as if "
                            + "a person had written it, then reviewed work that had shipped in an earlier trace",
                    List.of("/ship", "too vague", "forcing the agent")),
            new TraceExpectation(
                    "83b37f35664d88848e00d20be9f737ae",
                    "\"yes fix it\" answering a question the previous turn asked: judged as under-specified "
                            + "wording and rewrote the request the reader was never asked to write",
                    List.of("too vague", "wander", "under-specified")),
            new TraceExpectation(
                    "1635329e1e7db7f934b007d90aba7d61",
                    "a <task-notification> continuation: fired the starting-context rule, whose remedy "
                            + "(start in a fresh session) is impossible for work already in this session",
                    List.of("fresh session")),
            new TraceExpectation(
                    "80e62a90dc49cec593af52f698d8dd6b",
                    "eight HTTP 429 \"Usage credits are required\" model-call failures inside a "
                            + "code-review subagent: reported four unrelated faults and never mentioned "
                            + "the errors that dominated the trace, then invented a halt-on-failure rule",
                    // Rules a model reaches for when it mistakes a quota wall for agent behaviour.
                    // Necessarily partial: no substring can catch every wrong rule, and the check
                    // that matters most here -- did it notice the 429s at all -- is not a phrase.
                    List.of("halt and report", "reduce token usage", "monitor token usage")),
            new TraceExpectation(
                    "73590130fdbec1b4f2c89217103fb3db",
                    "the request named an absolute directory path and the very first call (Bash "
                            + "\"ls -la <that path>\") went straight to it -- a directed start "
                            + "directedStartObservation could not see because it only read file_path, "
                            + "which no Bash span ever carries. Better wording was left un-gated by that "
                            + "miss and repurposed a different (validation-scope) finding as wording "
                            + "advice, dropping the path the original request had gotten right",
                    // The specific fabricated advice this trace produced. TraceAnalysisAnswerScorer's
                    // generalized wordingSettledViolations check (any trace, not just this one) is the
                    // stronger net -- these phrases document the concrete failure this entry is for.
                    List.of("run validation only on", "full project suite", "first confirm the file changes")),
            new TraceExpectation(
                    "dfe4ea1da356f008ae46b2790736e223",
                    "an exploratory request (\"is there anything missing in the implementation "
                            + "@docs/...\"), which has produced two separate wording failures. First the "
                            + "model copied the template's own description of the \"Better wording\" line "
                            + "onto the line itself and answered after it (\"...in one or two sentences: "
                            + "None\"), so a correct None rendered as a card full of the prompt's own "
                            + "words. Then, once that was fixed, it answered the question IN that slot -- "
                            + "telling the reader to have asked for `tool_result_size_bytes`, the very "
                            + "attribute the trace had just discovered was missing. A reader who knew that "
                            + "would not have asked",
                    // Two shapes, both of which must stay gone. The first is the leaked placeholder's
                    // own opening (echoedInstructionViolations catches it generally;
                    // normalizeApplyThisLines repairs the "...: None" form before storage). The
                    // second is hindsight: naming something only this trace could have taught the
                    // reader. `tool_result_size_bytes` is deliberately NOT a forbidden phrase --
                    // a correct answer names it in the findings, where it belongs.
                    List.of("how to word a request like this one next time", "explicitly request the schema")),
            new TraceExpectation(
                    "299f2704e7161e2271a5c3749cdf3551",
                    "cited its evidence by call number and invented one: \"Used `grep -n` for file "
                            + "searches at calls 22 and 155, which could be more efficiently handled by the "
                            + "dedicated `Read` tool\" -- call 22 is a model call, not the Read the sentence's "
                            + "own follow-up clause names it as",
                    // Only the fabricated pairing, not the theme -- a real grep-overuse finding on this
                    // trace is correct and must stay sayable (it has ~11 genuine grep -n calls). Note:
                    // the same stored review also mis-cited a redundant-read finding at "calls 93 and
                    // 101" (93 is a Bash grep; the real duplicate Read pair is 95/101), which is NOT
                    // covered by this entry -- that sentence's sameness word precedes the citation
                    // rather than following it, so neither TimelineCitations check catches it yet. See
                    // TimelineCitations' class javadoc for the follow-up this would need.
                    List.of("at calls 22 and 155")));

    // Comma-separated trace ids to score instead of the corpus. Those get the citation and answer-
    // shape checks only -- nobody has written down what they must not say.
    private static final String TRACE_IDS_VARIABLE = "TRACE_ANALYSIS_HARNESS_TRACES";

    @Autowired
    private TraceAnalysisService traceAnalysisService;

    @Autowired
    private OllamaProperties ollamaProperties;

    /**
     * The model the run ACTUALLY used, which {@link OllamaProperties#getModel()} does not report.
     * {@code TraceAnalysisService} resolves {@link OllamaSettingsService#effectiveSettings()} fresh on
     * every call, so a stored Settings-page override silently decides the model while the YAML default
     * is what the properties object still holds — this scorecard printed {@code llama3.1} for a run
     * that phi4 produced. A scorecard naming the wrong model is worse than one naming none, since the
     * whole point of the header is telling two runs apart.
     */
    @Autowired
    private OllamaSettingsService ollamaSettingsService;

    @Test
    void reviewsTheKnownCorpusWithoutReproducingAnyFixedFailure() {
        List<TraceExpectation> corpus = corpus();
        Map<String, List<String>> violationsByTrace = new LinkedHashMap<>();
        StringBuilder scorecard = new StringBuilder("\n=== Trace analysis regression harness ===\n")
                .append("model: ").append(ollamaSettingsService.effectiveSettings().model())
                .append("   structured-output: ").append(ollamaProperties.isStructuredOutput())
                // The second setting this harness exists to decide, and the one whose effect is
                // invisible in the answers alone: it changes how many DRAFTING calls a trace takes
                // and how much per-call detail each of them sees. Two scorecards are only comparable
                // if each says which setting produced it.
                .append("   timeline-detail: ").append(ollamaProperties.getTimelineDetailPreference())
                .append("\n");

        for (TraceExpectation expectation : corpus) {
            Optional<TraceAnalysisService.PreparedPrompt> prepared =
                    traceAnalysisService.preparePrompt(expectation.traceId());
            if (prepared.isEmpty()) {
                // Not a failure of the review: the operator's database simply does not hold this
                // trace (a fresh install, or one whose retention window has passed it by).
                scorecard.append("\nSKIPPED ").append(expectation.traceId())
                        .append(" — not in this database\n");
                continue;
            }

            long startedAtMillis = System.currentTimeMillis();
            TraceAnalysis analysis = traceAnalysisService.regenerate(expectation.traceId()).orElseThrow();
            long elapsedMillis = System.currentTimeMillis() - startedAtMillis;

            // Every window's prompt, joined, plus the apply-this call: the review may now be several
            // DRAFTING model calls (one per partitioned window, see
            // TraceAnalysisPromptBuilder#partitionToBudget) plus the applying call, and the scorer's
            // oracle is everything the model was actually shown across all of them. Call timelines
            // live in the DRAFTING prompts (citation checking), rule targets and the wording rules in
            // the apply-this prompt (target and echo checking) -- scoring against a subset alone
            // accuses correct answers of citing what it cannot see.
            String promptsShown = prepared.get().windows().stream()
                    .map(TraceAnalysisPromptBuilder.PromptWindow::text)
                    .collect(java.util.stream.Collectors.joining("\n"))
                    + "\n" + traceAnalysisService.applyThisPromptFor(prepared.get(), analysis.analysis());
            List<String> violations = TraceAnalysisAnswerScorer.score(
                    analysis.analysis(), promptsShown, expectation.forbiddenPhrases());
            violationsByTrace.put(expectation.traceId(), violations);
            appendReport(scorecard, expectation, analysis, elapsedMillis, violations);
        }

        System.out.println(scorecard);
        assertThat(violationsByTrace.values().stream().flatMap(List::stream).toList())
                .as("every violation is a failure this feature already fixed, reappearing")
                .isEmpty();
    }

    private static List<TraceExpectation> corpus() {
        String configured = System.getenv(TRACE_IDS_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return CORPUS;
        }
        return configured.lines()
                .flatMap(line -> List.of(line.split(",")).stream())
                .map(String::strip)
                .filter(traceId -> !traceId.isEmpty())
                // A named trace that IS in the corpus keeps its documented expectations rather than
                // being downgraded to shape checks. Scoring a handful of traces is how a MODEL gets
                // compared (running all seven against every installed model costs hours), and the
                // forbidden phrases are the sharpest part of that comparison -- dropping them because
                // the run named its traces explicitly would throw away the known answers precisely
                // when they are most wanted. An unknown id still scores shape and citations only.
                .map(traceId -> CORPUS.stream()
                        .filter(expectation -> expectation.traceId().equals(traceId))
                        .findFirst()
                        .orElseGet(() -> new TraceExpectation(traceId, "ad-hoc, shape checks only", List.of())))
                .toList();
    }

    private static void appendReport(
            StringBuilder scorecard,
            TraceExpectation expectation,
            TraceAnalysis analysis,
            long elapsedMillis,
            List<String> violations) {
        // Review passes are printed beside the elapsed time because they explain it: each one is a
        // full DRAFTING call, so comparing two ollama.timeline-detail-preference runs means reading
        // the answers against what they cost, not the answers alone.
        scorecard.append("\n").append(violations.isEmpty() ? "PASS " : "FAIL ")
                .append(expectation.traceId())
                .append("  (").append(elapsedMillis).append(" ms, ")
                .append(analysis.reviewPassCount()).append(" review pass(es))\n")
                .append("  known failure: ").append(expectation.note()).append("\n");
        for (String violation : violations) {
            scorecard.append("  ! ").append(violation).append("\n");
        }
        // The answer itself, always -- the checks are a floor, and whether the review was any good
        // is a question only a reader can answer.
        scorecard.append("  --- answer ---\n")
                .append(analysis.analysis().indent(2))
                .append("\n");
    }

    /**
     * One scored trace.
     *
     * @param note what went wrong here before it was fixed, printed alongside the result so a
     *     failure is legible without going back to the documentation
     * @param forbiddenPhrases substrings a correct answer must not contain, matched
     *     case-insensitively
     */
    private record TraceExpectation(String traceId, String note, List<String> forbiddenPhrases) {
    }
}
