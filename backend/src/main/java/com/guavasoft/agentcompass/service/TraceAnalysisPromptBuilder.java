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

import com.samskivert.mustache.Template;

import com.guavasoft.agentcompass.config.OllamaProperties;
import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.SubagentCostAttributor.CallAttribution;
import com.guavasoft.agentcompass.service.SubagentCostAttributor.SubagentDispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Builds the Ollama prompt for one trace's analysis from the same span/log lists the trace detail
 * page already fetches — no new queries. Kept as its own package-visible collaborator (rather than
 * inline in {@link TraceAnalysisService}) because the compaction/truncation logic here is the most
 * logic-dense part of the whole feature and the easiest to get subtly wrong; extracting it makes it
 * directly table-testable.
 *
 * <p><b>The timeline reads tool inputs from {@code tool_result} LOGS, never from the tool span.</b>
 * An earlier revision read a {@code tool_input} attribute off the {@code claude_code.tool} span:
 * measured against this database, that attribute is present on <b>0 of 9,225</b> tool spans, so
 * every tool line rendered as a bare name with an empty target and the "compare what was searched
 * for against what the prompt asked for" analysis had no data behind it. Tool spans carry
 * {@code file_path} / {@code full_command} (44% / 48% coverage) while the {@code tool_result} log
 * carries the full {@code tool_input} JSON at 100% coverage, along with {@code success},
 * {@code duration_ms} and {@code error}. So spans stay the ordering backbone (they are always
 * present, and give the trace its shape) and each tool span is enriched from its {@code tool_result}
 * log by the exact {@code tool_use_id} both signals carry — the same "correlate the two signals on
 * their exact key" approach {@code LogService#resolveLeafSpans} already takes. A tool span with no
 * matching log (event logging disabled) degrades to the span's own {@code file_path}/
 * {@code full_command} rather than disappearing.
 *
 * <p>Likewise, {@code llm_request} spans carry {@code model} / {@code input_tokens} /
 * {@code output_tokens} / {@code cache_read_tokens} / {@code ttft_ms} / {@code stop_reason}
 * directly (100% coverage), so the per-call model detail needs no join back to {@code api_request}
 * logs at all.
 *
 * <p><b>Per-call COST is the exception that does need that join.</b> {@code Span#costUsd} comes
 * from the {@code span_costs} view, which groups request logs by the span that was merely
 * <i>open</i> when each request was issued: measured over 7 days, every dollar in that view sits on
 * a {@code claude_code.interaction} or {@code claude_code.tool.execution} span and not one lands on
 * the {@code llm_request} span that actually made the call. So money is attributed here the same
 * way {@code span_efforts} (V15) attributes effort — by the {@code request_id} both signals carry,
 * which pairs 14,047/14,055 rows exactly. That join is what makes "what drove the cost" answerable
 * per call rather than per trace.
 *
 * <p><b>Subagent work lives in this same trace, nested under the dispatching Agent call.</b> A
 * subagent's model calls are {@code llm_request} spans whose ancestor chain runs through the
 * {@code claude_code.tool.execution} child of the {@code claude_code.tool} span with
 * {@code tool_name=Agent}, and their {@code api_request} logs carry a {@code query_source} of
 * {@code agent:builtin:<type>} / {@code agent:custom}. Both facts are read here: ancestry says
 * <i>which dispatch</i> a call belongs to (so the cost lands on a call number the reader can find),
 * {@code query_source} says <i>which agent</i> it was. This matters at real scale — over 7 days,
 * $86 of $280 of measured span cost sat on tool.execution spans, i.e. inside subagents — and
 * without it a trace whose spend was one runaway Explore run looked identical to one that spent the
 * same money on its own main loop.
 */
class TraceAnalysisPromptBuilder {

    // Literal jsonb keys read from already-fetched Java objects, never used to build a new SQL
    // query -- the same "inline literal" treatment tool attribute keys already get elsewhere here.
    // Only names that TuningProperties genuinely owns (event names, tool/request-id attributes) are
    // read from it; these per-call payload keys are not part of that catalog.
    private static final String TOOL_INPUT_ATTRIBUTE = "tool_input";
    private static final String TOOL_USE_ID_ATTRIBUTE = "tool_use_id";
    private static final String SUCCESS_ATTRIBUTE = "success";
    private static final String ERROR_ATTRIBUTE = "error";
    private static final String DECISION_ATTRIBUTE = "decision";
    private static final String MODEL_ATTRIBUTE = "model";
    private static final String OUTPUT_TOKENS_ATTRIBUTE = "output_tokens";
    private static final String STOP_REASON_ATTRIBUTE = "stop_reason";
    private static final String ATTEMPT_ATTRIBUTE = "attempt";
    private static final String DURATION_MS_ATTRIBUTE = "duration_ms";
    private static final String STATUS_CODE_ATTRIBUTE = "status_code";
    private static final String FILE_PATH_ATTRIBUTE = "file_path";
    private static final String FULL_COMMAND_ATTRIBUTE = "full_command";
    private static final String TOOL_RESULT_SIZE_BYTES_ATTRIBUTE = "tool_result_size_bytes";

    // Attributes carried by a compaction log record -- see TuningProperties#compactionEventName.
    private static final String TRIGGER_ATTRIBUTE = "trigger";
    private static final String PRE_TOKENS_ATTRIBUTE = "pre_tokens";
    private static final String POST_TOKENS_ATTRIBUTE = "post_tokens";

    private static final String BLOCKED_ON_USER_SPAN_NAME = "claude_code.tool.blocked_on_user";

    private static final String ACCEPTED_DECISION = "accept";
    private static final String ERROR_STATUS_CODE = "error";
    private static final String TASK_NOTIFICATION_OPENING_TAG = "<task-notification>";

    // Fields read back out of that envelope -- see backgroundTaskToolUseId and putContinuation.
    private static final String TOOL_USE_ID_TAG = "tool-use-id";
    private static final String STATUS_TAG = "status";
    private static final String SUMMARY_TAG = "summary";

    // invocation_trigger values, rendered into prose for the "Skills that ran" section.
    private static final String USER_SLASH_TRIGGER = "user-slash";
    private static final String PROACTIVE_TRIGGER = "claude-proactive";
    private static final String NESTED_SKILL_TRIGGER = "nested-skill";

    // The closed set of targets the "Instruction rule" line may name. A skill target is only ever
    // offered for a skill this trace shows ran AND whose definition the reader can edit -- see
    // SkillActivation#editable. Free-form targets are deliberately not allowed: asked to name any
    // file, a small model invents plausible paths that do not exist in the reader's repo.
    private static final String PROJECT_INSTRUCTIONS_TARGET = "CLAUDE.md";

    // Where a rule about waiting on permission prompts actually goes. Offered ONLY when this trace
    // blocked on user approval long enough for blockedOnUserObservation to fire -- an evidence-built
    // target like skill:<name>, never a standing option -- because the pre-written rule that
    // observation carries ("Pre-authorize or batch the permission prompts ...") is not something any
    // instructions file can act on: pre-authorising a tool call is a permissions setting the harness
    // reads, not an instruction the agent reads. On trace adae1753270dd3088520435ae7f8af94 that rule
    // came back targeting CLAUDE.md, which cannot grant a permission however it is worded -- the same
    // unfollowable-remedy shape as telling a <task-notification> continuation to start a fresh
    // session.
    private static final String PERMISSIONS_TARGET = ".claude/settings.json";
    private static final String SKILL_TARGET_PREFIX = "skill:";
    private static final String FIRST_ATTEMPT = "1";

    private static final int TOOL_INPUT_TRUNCATION_LENGTH = 200;
    // The two tighter tool-input caps the timeline falls back to when the prompt overflows -- see
    // TimelineDetail. 80 still shows a whole repo-relative path and the head of a shell command; 30
    // is enough for a file's last segment plus a command's first token, which is what revisit and
    // shell-antipattern findings are read off.
    private static final int COMPACT_TOOL_INPUT_TRUNCATION_LENGTH = 80;
    private static final int MINIMAL_TOOL_INPUT_TRUNCATION_LENGTH = 30;
    private static final int ERROR_TRUNCATION_LENGTH = 300;
    private static final int MAX_DEPTH_ITERATIONS = 64;
    private static final int MAX_LISTED_CALL_NUMBERS = 8;

    // Timeline windowing -- see partitionToBudget/packWindows. The header and carry-over are
    // rendered per window rather than measured, since header/carry-over content varies by window
    // (call range, files touched so far) and a fixed-point render-then-remeasure loop over window
    // count is not worth it: these are reservations, sized generously, not measurements.
    // theWindowHeaderAndCarryOverNeverExceedTheirReservedWidth pins the real rendered width against
    // this budget.
    private static final int WINDOW_HEADER_RESERVE_CHARS = 500;
    private static final int CARRY_OVER_BUDGET_CHARS = 1_200;
    // Below this, a window carries so little of the trace that the review it produces cannot see
    // enough to say anything -- a max-prompt-chars this tight is operator misconfiguration, and
    // partitionToBudget fails loudly rather than emitting hundreds of one-line windows.
    private static final int MINIMUM_TIMELINE_CHARS_PER_WINDOW = 2_000;
    private static final int MAX_CARRY_OVER_FILES = 12;
    private static final int MAX_CARRY_OVER_FAILURES = 5;
    private static final int MAX_CARRY_OVER_DISPATCHES = 5;

    // Sizing for the code-composed trace summary -- see buildTraceSummary. The request and outcome
    // caps stay short on purpose: those two are re-orientation lines, not a second copy of the full
    // request or final message that are already rendered elsewhere in the prompt/dialog. The file
    // cap is sized off the data instead -- measured over 30 days and 695 traces, a trace touches 2
    // distinct files at p50 and 10 at p90, so 12 renders nine traces in ten whole, while the p99 of
    // 48 (max 118) is why the cap exists at all.
    private static final int MAX_SUMMARY_FILE_NAMES = 12;
    private static final int SUMMARY_REQUEST_TRUNCATION_LENGTH = 200;
    private static final int SUMMARY_OUTCOME_TRUNCATION_LENGTH = 200;

    /**
     * Separates the facts inside one summary line, and the one thing the dialog's own parser splits
     * a line into bullets on (see {@code AnalyzeTraceDialogView}'s {@code summaryRows}).
     *
     * <p>Deliberately not a comma. File names, shell commands, model ids and — above all — the
     * quoted request and final message carry commas of their own, so a comma split shredded a
     * one-sentence prompt into bullets mid-clause. A middle dot appears in none of them, which is
     * what lets one separator serve every line including the free-prose ones.
     */
    private static final String SUMMARY_CLAUSE_SEPARATOR = " · ";
    // Nested one level down, inside a file's own "(Read ×2, Edit)" tool list, where the clause
    // separator cannot be reused without the dialog splitting a file's entry away from its name.
    private static final String SUMMARY_NESTED_SEPARATOR = ", ";
    private static final String SUMMARY_COUNT_MARKER = " ×";

    // Gates the outlier-model-call observation -- see modelCallOutlierObservation for why an
    // ungated "longest model call" line is worse than no line at all. A trace needs enough model
    // calls for a median to mean anything, the outlier has to stand well clear of that median, and
    // it has to be long in absolute terms: 2.5x a 900 ms median is not worth a reader's attention.
    private static final int MINIMUM_MODEL_CALLS_FOR_OUTLIER = 3;
    private static final double MODEL_CALL_OUTLIER_MEDIAN_MULTIPLE = 2.5;
    private static final long MODEL_CALL_OUTLIER_FLOOR_MS = 15_000L;

    // How far past the trace's own median milliseconds-per-output-token the outlier has to sit
    // before its duration counts as time NOT explained by what it wrote -- see
    // modelCallOutlierObservation, which decides "writing" vs "deciding" in code rather than asking
    // the model to. Measured over 30 days and the 351 traces whose slowest call clears the two gates
    // above: the outlier's rate against its trace's median rate is p50 0.88, p75 1.00, p90 1.05, so
    // the slowest call is normally as efficient per token as the trace's typical one and is simply
    // longer because it wrote more. The tail is sharply separated -- 10 of 351 (2.8%) sit at or past
    // 2.0x and NOTHING at all falls between 1.2x and 2.0x -- so any floor in that gap scores the
    // same 10 traces, and 2.0x is chosen as its conservative end.
    private static final double MODEL_CALL_DECIDING_RATE_MULTIPLE = 2.0;
    private static final String SUGGESTED_RULE_PREFIX = "  Suggested rule: ";

    // Marks an observation that is TRUE but is not the agent's to fix, and so must not become a
    // finding. Deliberately not a "Suggested rule:" line reading "None": that prefix is copied
    // verbatim into the answer's "Instruction rule:" line, which has a strict "<target> — <rule>"
    // shape, so a rule of "None — quota failures are..." would be prose in a slot the dialog parses.
    private static final String NOT_A_FINDING_PREFIX = "  Not a finding: ";
    private static final String ENVIRONMENTAL_FAILURE_NOTE =
            "these are the environment refusing the call — a quota, a rate limit, an overloaded "
            + "server — not a choice the agent made. No instruction, tool swap or rewording prevents "
            + "one. Judge only what the agent did next, if anything.";
    // Share of a trace's total blocked time that one pause has to carry before the waiting stops
    // being a pattern and becomes a single event. Measured over 30 days across the 154 traces where
    // blockedOnUserObservation fires: the largest single wait as a share of total blocked time is
    // p10 0.34, p25 0.48, p50 0.72, p75 0.98, and 65 of those 154 (42%) sit at or above 0.80 (54 at
    // 0.90). There is no gap to aim at, so the floor is set on meaning rather than shape: at 80% the
    // one pause IS the waiting, and 48% of these traces have only a single wait past the 10s floor
    // to begin with.
    private static final double BLOCKED_ON_USER_SINGLE_PAUSE_DOMINANCE = 0.80;

    // Model-call HTTP statuses that mean "the environment said no", as opposed to a malformed
    // request the agent's own behaviour produced (a 400 for an oversized payload is the agent's to
    // fix; a 429 is not). 408 and every 5xx join 429 here: timeouts and server errors are equally
    // outside the reach of anything written in an instruction file.
    private static final int REQUEST_TIMEOUT_STATUS = 408;
    private static final int RATE_LIMIT_STATUS = 429;
    private static final int FIRST_SERVER_ERROR_STATUS = 500;
    private static final String READ_TOOL_NAME = "Read";

    // Tools that CHANGE the file they name, which is what ends a revisit window -- see
    // revisitedFiles. Deliberately a name list rather than a "not Read" test: Grep and Glob take a
    // file_path and read it, and treating an unrecognised tool as mutating would silently split
    // every group it appears in and hide real redundancy.
    private static final Set<String> MUTATING_TOOL_NAMES = Set.of("Edit", "Write", "NotebookEdit", "MultiEdit");

    // Tools that LOOK at a file without changing it -- the other half of the pair MUTATING_TOOL_NAMES
    // defines, and what focusedResolutionHolds walks backward from a mutating call to find. Grep and
    // Glob are included alongside Read because all three take a file_path and read it (see the
    // MUTATING_TOOL_NAMES comment above), and any of the three finding the file first is equally "the
    // agent looked before it touched".
    private static final Set<String> SEARCH_TOOL_NAMES = Set.of("Read", "Grep", "Glob");

    // How many calls back focusedResolutionHolds will look from a mutating call for a same-file
    // SEARCH_TOOL_NAMES call before deciding the edit did not follow from one it can credit. Measured
    // over this database's own traces (see focusedResolutionHolds' javadoc for the full population):
    // among mutating calls that DO have an earlier same-file search anywhere in their trace, the gap
    // between the two sits at p50 6 / p75 17 / p90 41 calls, so 15 sits just under the p75 -- close
    // enough to credit the ordinary "search, then act on what it found" shape while excluding the long
    // tail where the search is far enough back that it may no longer describe the file's current
    // content.
    private static final int FOCUSED_RESOLUTION_CHAIN_WINDOW_CALLS = 15;

    // Marks a verified observation as something the trace got RIGHT. Two forms: a standalone
    // positive observation, and an indented note qualifying the observation above it -- see
    // buildObservations for why positives are gated at least as hard as the faults are.
    private static final String POSITIVE_PREFIX = "- Went well: ";
    private static final String POSITIVE_NOTE_PREFIX = "  Went well: ";

    // Distinctiveness floor for the file name the directed-start observation matches against the
    // request text. A short name is likelier to appear inside an unrelated word of the prompt than
    // to be the target the reader actually named, and a false positive here is a review telling a
    // reader their vague request was well aimed.
    private static final int MINIMUM_NAMED_TARGET_LENGTH = 5;

    // Cost-attribution gates. Every trace has a most expensive call and, if it dispatched one, a
    // subagent that cost something -- reporting either unconditionally is the same non-finding the
    // outlier gating exists to kill. These fire only when one line item genuinely dominates.
    private static final double DOMINANT_SUBAGENT_COST_SHARE = 0.4;
    private static final double DOMINANT_CALL_COST_SHARE = 0.25;
    private static final int MINIMUM_MODEL_CALLS_FOR_COST_CONCENTRATION = 4;

    // Cache-reuse gate, against this database's own distribution of per-trace cache-read share of
    // prompt tokens (30 days, 891 traces): p50 96.8%, p25 86.2%, p10 0% -- the bottom decile being
    // single-call traces with nothing to reuse yet. So a trace only reads as WASTING cache well
    // below the first quartile, and only once it has had enough calls for reuse to be possible.
    private static final double POOR_CACHE_REUSE_RATIO = 0.70;
    private static final int MINIMUM_MODEL_CALLS_FOR_CACHE_JUDGMENT = 3;

    // Context-size gates, against the same window's main-loop distribution: a trace's FIRST model
    // call carries p50 104k / p90 355k prompt tokens, and its largest carries p50 96k / p75 168k /
    // p90 280k. A trace that opens above 200k is therefore already carrying more history than three
    // quarters of traces ever reach, and a peak past 300k sits in the top decile. Growth is only
    // interesting once the peak is large in absolute terms -- 5k growing to 20k is 4x and fine.
    private static final long LARGE_STARTING_CONTEXT_TOKENS = 200_000L;
    private static final long LARGE_PEAK_CONTEXT_TOKENS = 300_000L;
    private static final double LARGE_CONTEXT_GROWTH_MULTIPLE = 2.0;

    // Same bar ReportService.OVERSIZED_BYTES_THRESHOLD uses for the markdown report's oversized-
    // result list, reused here so a reader who reads both surfaces sees one number, not two.
    private static final long LARGE_TOOL_RESULT_BYTES = 20_000L;

    // Per-call wait floor for a claude_code.tool.blocked_on_user span, and the trace-level share
    // that makes the pattern worth a standing rule. Measured over 30 days: blocked spans have a p50
    // of 0.0s and a p90 of 1.6s (most waits are trivial), but 180 of 677 traces that ever block
    // contain a single wait over 10s, and 132 spend over a quarter of their own duration blocked --
    // that is the population this gate is meant to isolate, not the routine short pause.
    private static final long BLOCKED_ON_USER_CALL_FLOOR_MS = 10_000L;
    private static final long BLOCKED_ON_USER_SINGLE_WAIT_FLOOR_MS = 30_000L;
    private static final double BLOCKED_ON_USER_TRACE_SHARE_FLOOR = 0.25;

    // Assistant narration sizing, against the measured p50 of 1.1k / p95 12.5k characters per
    // trace: intermediate turns are usually short status narration and get the tighter cap, while
    // the final message is what ask-vs-delivered is judged against and keeps room to be judged.
    private static final int INTERMEDIATE_ASSISTANT_TURN_TRUNCATION_LENGTH = 1_500;
    private static final int FINAL_ASSISTANT_TURN_TRUNCATION_LENGTH = 4_000;
    private static final int ASSISTANT_NARRATION_BUDGET_CHARS = 12_000;

    // The preceding turn is kept from its END, not its start: what a follow-up request answers is
    // the question or option list the previous message closed with, and the opening paragraphs of a
    // long answer are the part that says least about it.
    private static final int PRECEDING_TURN_TRUNCATION_LENGTH = 1_500;

    // Gate for "this request is an answer, not a vague ask" -- see answersPrecedingQuestion. Both
    // halves are measured against this database's own 30-day distribution of 758 human-written,
    // non-slash prompts: 470 are at or under 120 characters (so length ALONE would suppress the
    // wording half of section B on 62% of traces, which is far too broad), while 129 of them are
    // both that short AND follow a rendered preceding-turn tail containing a question mark -- 17%,
    // and that is the population where "yes fix it" is an exact answer rather than a vague request.
    private static final int SHORT_FOLLOW_UP_REQUEST_LENGTH = 120;
    private static final char QUESTION_MARK = '?';

    /**
     * Words that open a question even when the writer left the mark off — see
     * {@link #requestIsQuestion}, which is why this exists at all.
     *
     * <p><b>Measured over 30 days and 751 human-written non-slash prompts.</b> Ending in {@code ?}
     * gates 156 of them (20.8%); adding these openers gates 209 (27.8%). Per opener, in that window:
     * {@code can} 15, {@code what} 14, {@code how} 6, {@code does} 5, {@code is} 5, {@code should} 4,
     * {@code why} 3, {@code will} 1. {@code could}, {@code would}, {@code did} and {@code are}
     * matched nothing and are carried for coverage rather than on evidence — each is unambiguously
     * interrogative in opening position, which is the bar for being on this list.
     *
     * <p><b>Bare {@code do} is deliberately absent, and {@code does}/{@code did} are not.</b> It was
     * the one opener whose matches were imperative rather than interrogative, and all five in the
     * window were: "do 1 and 2", "do a code review on uncommitted changes", "do fix 1 and fix 2",
     * "do not automatically add changes to git", "do not use any 70b models". {@code any} was
     * considered and dropped for the same ambiguity ("any way to …" asks, "any changes should …"
     * instructs) with no measured matches to justify the risk.
     *
     * <p><b>The residual false positive is the polite instruction</b> — "can you close all those
     * dependabot alerts", "can you create a full pg_dump" — roughly 6 of the 53 added (11%), since
     * {@code can/should} + {@code you/we/i} is genuinely mixed ("can i see the full example data" is
     * a real question). They are accepted on the same reasoning the {@code ?} rule already accepts
     * its own: wrongly gating an instruction costs one wording tip, while wrongly failing to gate a
     * question produces "unnamed target" advice telling the reader to have already known what the
     * trace discovered — the single most common false finding this feature has produced. Trace
     * {@code 9ab1feeebdd15a449bbc4c9983dcb79d} is the case that forced the widening: its request
     * "can the phase timeline be condensed any" is a yes/no question the agent answered "Yes —", and
     * on one regeneration all three findings were restatements of that non-fault, two of them
     * carrying section B's own bullet names ("unnamed target", "ambiguity that cost work") back as
     * findings.
     */
    private static final List<String> QUESTION_OPENERS = List.of(
            "can", "could", "would", "will", "should", "is", "are", "does", "did", "why", "what", "how");

    private static final String RESOLVED_FOLLOW_UP_OBSERVATION = POSITIVE_PREFIX
            + "the request is an answer, not an ask: it is short and the assistant turn it follows "
            + "(shown above under \"What the agent said just before this request\") ends by asking "
            + "the reader something, so the target of the work is named there rather than in the "
            + "request. Its brevity is correct wording and is NOT a finding";

    // Separates tool from file in a revisit-grouping key. A character that cannot occur in either
    // half, so "Read" + "a b" and "Read a" + "b" can never collide into one key the way a space
    // separator would allow. Written as the escape, never as a raw NUL in the source: a literal one
    // compiles fine but turns the whole file binary to grep and diff, and is invisible in review.
    private static final char TARGET_KEY_SEPARATOR = '\0';
    private static final double NANOS_PER_MILLI = 1_000_000.0d;
    private static final double PERCENT_SCALE = 100.0;
    private static final String TRUNCATION_SUFFIX = "…";
    private static final String TRUNCATION_PREFIX = "… ";

    // Middle-out truncation -- see truncateMiddleOut. The tail gets the larger share because the
    // head only identifies the operation while the tail carries the chained verification steps.
    private static final String OMITTED_CHARS_NOTE = " … %d chars omitted … ";
    private static final double MIDDLE_OUT_TAIL_SHARE = 0.6;
    // The assistant-narration section's own middle-out note -- see trimListMiddleOut. Distinct from
    // the timeline, which no longer elides at all: the timeline gives way by partitioning into
    // windows (see partitionToBudget), never by dropping a call.
    private static final String OMITTED_MESSAGES_NOTE = "… %d messages omitted from the middle of this section …";
    private static final String LLM_REQUEST_LABEL = "llm_request";
    private static final String COST_FORMAT = "%.4f";
    // Grouped token counts, for the compaction line -- "145,579 → 11,442" is a reclaim a reader can
    // see at a glance where "145579 → 11442" has to be counted digit by digit.
    private static final String THOUSANDS_FORMAT = "%,d";
    private static final String PERCENT_FORMAT = "%.1f";

    // Subagent/branch-identity constants (BUILTIN_SUBAGENT_QUERY_SOURCE_INFIX, UNNAMED_SUBAGENT,
    // MAIN_LOOP_BRANCH_KEY, BRANCH_KEY_SEPARATOR) moved to SubagentCostAttributor along with the
    // attribution pass that used them. MAIN_LOOP_LABEL/AUXILIARY_LABEL stay here -- costSummaryLine
    // below still renders its own prose Cost: line from them.
    private static final String MAIN_LOOP_LABEL = "Main loop";
    private static final String AUXILIARY_LABEL = "Auxiliary (session titles, compaction, web fetch)";

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long MINUTES_PER_HOUR = 60L;
    private static final long HOURS_PER_DAY = 24L;

    // Duration rendering -- see formatDuration. Compact and unit-suffixed, the shape the timeline's
    // tool lines already used, so one style covers the overview, the timeline and the observations.
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final String MILLIS_DURATION_FORMAT = "%dms";
    private static final String SECONDS_DURATION_FORMAT = "%.1fs";
    private static final String MINUTES_DURATION_FORMAT = "%dm %ds";
    private static final String HOURS_DURATION_FORMAT = "%dh %dm";

    // Token-count rendering -- see formatTokens. The overview's own benchmark text ("typical traces
    // here start around 100k") is already in these units; a raw seven-digit integer would force the
    // reader, and the reviewing model, to do that rounding themselves before the comparison means
    // anything.
    private static final long TOKENS_PER_THOUSAND = 1_000L;
    private static final long TOKENS_PER_MILLION = 1_000_000L;
    private static final String THOUSANDS_TOKEN_FORMAT = "%dk";
    private static final String MILLIONS_TOKEN_FORMAT = "%.1fM";

    private final Template template;
    private final Template applyThisTemplate;
    private final TuningProperties tuningProperties;
    private final OllamaProperties ollamaProperties;
    private final SubagentCostAttributor subagentCostAttributor;

    TraceAnalysisPromptBuilder(
            Template template,
            Template applyThisTemplate,
            TuningProperties tuningProperties,
            OllamaProperties ollamaProperties,
            SubagentCostAttributor subagentCostAttributor) {
        this.template = template;
        this.applyThisTemplate = applyThisTemplate;
        this.tuningProperties = tuningProperties;
        this.ollamaProperties = ollamaProperties;
        this.subagentCostAttributor = subagentCostAttributor;
    }

    /**
     * Renders the full prompt. When {@code logRecords} carry no {@code user_prompt} log (sub-agent
     * or resume/heartbeat traces), only the prompt-dependent half of the review is dropped — the
     * execution-quality half applies regardless, since judging tool choice, redundancy and error
     * recovery needs no prompt to compare against.
     *
     * @param precedingAssistantResponse the last assistant turn of the SAME session before this
     *     trace opened, or null when there is none. Nullable by design: it is what makes a
     *     follow-up request ("do 1 and 2") judgeable instead of vague, and its absence simply
     *     removes that section.
     * @param dispatchingToolCall the tool call that launched the background task whose completion
     *     woke this trace, or null when it was not started by a {@code <task-notification>} (or the
     *     dispatching turn has been purged). Fetched by the caller from
     *     {@link #backgroundTaskToolUseId}, since it lives in the dispatching trace rather than this
     *     one — see {@link #putContinuation} for why nothing else is pulled across that boundary.
     */
    /**
     * The rendered prompt, as one or more consecutive, non-overlapping review windows — see
     * {@link #partitionToBudget}, which replaces the old middle-elision behaviour: an oversized
     * timeline is split into complete review calls instead of having a stretch of it dropped, so no
     * call number is ever uncitable. {@code timelineCallCount} is the trace's total call count
     * (unaffected by which {@link TimelineDetail} level was chosen or how many windows resulted),
     * surfaced so the caller and the stored row can report how much of the trace this analysis
     * actually covers.
     *
     * @param wordingAlreadySettled whether this trace's request-quality verdict already forces
     *     {@code Better wording} to {@code None} — see {@code build}'s own {@code wordingAlreadySettled}
     *     local. Surfaced so {@link TraceAnalysisService} can restrict the structured-output schema's
     *     {@code betterWording} enum to exactly {@code ["None"]} on such a trace, the same trade
     *     {@code instructionRuleTarget}'s enum already makes: an answer that cannot legally repeat the
     *     "yes fix it" / {@code 73590130fdbec1b4f2c89217103fb3db} failure is stronger than a prompt
     *     instruction asking the model not to.
     * @param hasPositiveObservations whether {@link #buildObservations} verified anything this trace
     *     got RIGHT — the same flag the template gates its "What went well" section on. Surfaced for
     *     the identical reason {@code wordingAlreadySettled} is: {@link TraceAnalysisService} hands it
     *     to {@link TraceAnalysisAnswer#findingsJsonSchema(boolean, boolean)}, which drops the {@code wentWell}
     *     field from the schema outright when no positive was computed, so the structured path cannot
     *     produce the fabricated positive trace {@code adae1753270dd3088520435ae7f8af94} came back
     *     with. The template's own gating had no effect on that path — see that method's javadoc.
     * @param summary a plain-text "what happened" recap — request, work, outcome — computed entirely
     *     in code from the same facts the prompt above renders, and NOT sent to Ollama at all. See
     *     {@link #buildTraceSummary} for why: a narrated retelling is exactly the shape this feature's
     *     documented failure mode (fabricated citations, restated numbers) hits hardest, and unlike a
     *     finding it carries no {@code Fix:} to make it checkable. Stored and shown ahead of the
     *     model's own findings so a reader re-opening a trace can re-orient before reading verdicts.
     * @param failedToolCalls every tool call this trace's spans/logs show failed — see
     *     {@link VerifiedObservations#failedToolCalls}. {@code TraceAnalysisService} checks each one
     *     against the model's own findings once they come back and injects a fallback fault for any
     *     that went uncited, on the structured-output path only — see
     *     {@code TraceAnalysisService#ensureFailedToolCallsReported}.
     */
    record PromptResult(
            List<PromptWindow> windows,
            int timelineCallCount,
            boolean wordingAlreadySettled,
            boolean hasPositiveObservations,
            ApplyThisInputs applyThisInputs,
            String summary,
            List<UnrecoveredFailure> failedToolCalls) {
    }

    /**
     * One full review call's worth of prompt text — the timeline lossy-elided into a single opaque
     * block is what {@link #partitionToBudget} replaces this with: an oversized timeline is instead
     * split into consecutive, non-overlapping windows, each a complete prompt in its own right
     * (carrying its own header/carry-over, the whole overview/observations/errors, and its own slice
     * of the timeline) and therefore its own full model call. {@code windows} is never empty — a
     * trace that fits whole is exactly one window, rendered byte-identical to the pre-windowing
     * prompt (see {@link #partitionToBudget}'s javadoc).
     *
     * @param windowNumber 1-based position of this window among {@code windowCount}
     * @param firstCallNumber the first call this window's timeline slice covers
     * @param lastCallNumber the last call this window's timeline slice covers
     * @param judgesRequest whether THIS window's call is the one asking section B's request-quality
     *     questions and emitting a {@code requestKind} verdict — the first window on a partitioned
     *     trace, every window otherwise. {@code TraceAnalysisService} reads it to build this
     *     window's findings schema, since {@code requestKind} is omitted from the grammar entirely
     *     on a window that does not judge it, the same "absent, not empty" treatment
     *     {@code findingsJsonSchema}'s {@code positivesAllowed} already gives {@code wentWell}.
     */
    record PromptWindow(
            String text, int windowNumber, int windowCount, int firstCallNumber, int lastCallNumber,
            boolean judgesRequest) {
    }

    /**
     * Everything the <b>second</b> call needs, computed during the first call's build so it is
     * derived once from one read of the trace rather than recomputed against a second one.
     *
     * <p>The review is produced in two model calls: this class's main template asks for the findings
     * ("What went well" / "What went wrong"), and {@link #buildApplyThisPrompt} then asks a much
     * smaller prompt to distil those findings into the three "Apply this" lines. The split is a
     * measured response to where this feature actually fails — every wording/rule defect found so
     * far has been in that three-line block while the findings themselves read correctly, and the
     * block had grown to ~3.8k characters of rules competing for a small model's attention with the
     * whole trace. The second prompt carries no timeline, no overview and no observations, so the
     * model writing those three lines is looking at the review and nothing else.
     *
     * @param suggestedRules the {@code Suggested rule:} lines the observations supplied, stripped of
     *     their prefix. They travel because the answer contract tells the model to copy one verbatim
     *     when it addresses a reported fault, and the second call cannot see the observation block
     *     they came from.
     * @param ruleTargets the closed target list as values, not as the rendered display string — see
     *     {@link #ruleTargets}. It travels here so the second prompt's list and the structured
     *     schema's {@code enum} are the <b>same</b> list rather than two computations that agree
     *     today: the list now depends on whether an observation fired ({@link #PERMISSIONS_TARGET}),
     *     which only {@code build} knows, so recomputing it in {@link TraceAnalysisService} would
     *     mean a second implementation of that gating — the drift this record's own javadoc, and
     *     {@code TraceCallNumbering}, both exist to prevent.
     */
    record ApplyThisInputs(
            String promptText,
            boolean hasPrompt,
            List<String> ruleTargets,
            boolean hasEditableSkillTargets,
            boolean wordingAlreadySettled,
            String wordingSettledReason,
            List<String> suggestedRules,
            String settledToolSwap) {

        /**
         * Whether the {@code Tool swap} line is already decided — see
         * {@link TraceAnalysisPromptBuilder#settledToolSwapFor}. Derived rather than stored beside
         * the line so the two can never disagree about whether there is one.
         */
        boolean toolSwapAlreadySettled() {
            return settledToolSwap != null && !settledToolSwap.isBlank();
        }

        /**
         * The same inputs with the wording question settled — what {@code TraceAnalysisService}
         * applies once the first call has classified the request as a question. The reason travels
         * with it so the second prompt can say WHY the line is None rather than only that it is.
         */
        ApplyThisInputs withWordingSettled(boolean settled) {
            if (settled == wordingAlreadySettled) {
                return this;
            }
            return new ApplyThisInputs(
                    promptText, hasPrompt, ruleTargets, hasEditableSkillTargets, settled,
                    settled && wordingSettledReason.isBlank()
                            ? "The request asked a question rather than giving an instruction, so it named no "
                                    + "target to hunt for and there is no wording fault in it."
                            : wordingSettledReason,
                    suggestedRules,
                    settledToolSwap);
        }
    }

    PromptResult build(
            TraceSummary traceSummary,
            List<Span> spans,
            List<LogRecord> logRecords,
            LogRecord precedingAssistantResponse,
            LogRecord dispatchingToolCall) {
        Map<String, Object> context = new HashMap<>();
        // Swaps the answer contract between "write these markdown sections" and "fill in these JSON
        // fields". Everything above it -- the trace, the observations, what to judge -- is identical
        // on both paths, which is what makes the two comparable in the regression harness.
        context.put("structuredOutput", ollamaProperties.isStructuredOutput());

        LogRecord userPromptRecord = findUserPromptRecord(logRecords);
        String promptText = findUserPromptText(userPromptRecord);
        String slashCommandName = slashCommandName(userPromptRecord);
        boolean isCommandInvocation = slashCommandName != null;
        // A slash command's text is not a request someone worded, so it drops the request-quality
        // half exactly as a machine-authored prompt does -- see slashCommandName for the false
        // finding this prevents. The command itself is still shown, in its own section, because
        // "this trace was started by /ship" is context the execution half needs.
        boolean hasPrompt = isJudgeablePrompt(promptText, isCommandInvocation);
        context.put("hasPrompt", hasPrompt);
        context.put("promptText", hasPrompt ? promptText : "");
        context.put("isCommandInvocation", isCommandInvocation);
        context.put("commandName", isCommandInvocation ? slashCommandName : "");
        context.put("commandText", isCommandInvocation ? promptText.strip() : "");
        List<SkillActivation> skillActivations = findSkillActivations(logRecords);
        putSkills(context, skillActivations);
        String precedingTurnTail = putPrecedingTurn(context, traceSummary, precedingAssistantResponse, hasPrompt);
        // Whether the request is an ANSWER to that turn rather than an ask of its own. Computed
        // here, in code, because the prose fence alone did not hold -- see answersPrecedingQuestion.
        boolean answersPrecedingQuestion = answersPrecedingQuestion(promptText, precedingTurnTail);
        context.put("answersPrecedingQuestion", answersPrecedingQuestion);
        putContinuation(context, logRecords, traceSummary, dispatchingToolCall);
        boolean isContinuation = Boolean.TRUE.equals(context.get("isContinuation"));

        CallAttribution callAttribution = subagentCostAttributor.attributeCalls(spans, logRecords);

        // Computed here rather than inside buildObservations because the answer contract's wording
        // gate (below) needs to know the verdict BEFORE the template renders section B -- the same
        // reason answersPrecedingQuestion is a code-side decision rather than a prose fence. See
        // directedStartObservation's javadoc for why this is not a fault-half positive: it is the one
        // fact that ALSO settles whether "Better wording" has anything left to say.
        String directedStartLine = directedStartObservation(spans, hasPrompt ? promptText : null, callAttribution);
        boolean requestWellAimed = directedStartLine != null;
        // Three independent ways a request's wording can already be settled: it answered a question
        // that named the work, it named its own target and the agent went straight there, or it is
        // itself a question and so has no target to name. Any one means section B's "unnamed
        // target"/"ambiguity" bullets have nothing to judge, and Better wording can only be None --
        // see the template's wordingAlreadySettled block and TraceAnalysisAnswer.jsonSchema's
        // betterWording enum restriction.
        //
        // The third was previously left to the model on the grounds that classifying a request as a
        // QUESTION or an INSTRUCTION is a judgment about language rather than a fact about the trace.
        // Reading the mark the request ends with is not that judgment, and leaving it to prose cost a
        // reader advice they could not act on -- see requestIsQuestion for the trace, the three
        // fences that lost, and why only 4.9% of real requests can clear requestWellAimed at all.
        boolean requestIsQuestion = hasPrompt && requestIsQuestion(promptText);
        boolean wordingAlreadySettled = answersPrecedingQuestion || requestWellAimed || requestIsQuestion;
        context.put("wordingAlreadySettled", wordingAlreadySettled);
        context.put("wordingSettledReason",
                wordingSettledReason(answersPrecedingQuestion, requestWellAimed, requestIsQuestion));

        long traceDurationMs = traceSummary.getDurationNanos() == null
                ? 0L
                : Math.round(traceSummary.getDurationNanos() / NANOS_PER_MILLI);
        putOverview(context, traceSummary, spans, callAttribution);

        Map<String, LogRecord> toolResultsByUseId = indexToolResultsByUseId(logRecords);
        Set<String> rejectedToolUseIds = rejectedToolUseIds(logRecords);
        Map<String, Long> blockedMsByToolSpanId = blockedMsByToolSpanId(spans);

        // Independent of wordingAlreadySettled on purpose -- this does not settle the request's
        // wording, it only proves that whatever ambiguity the wording left, the execution shows it
        // never cost anything. See focusedResolutionHolds' own javadoc for the measured population
        // behind FOCUSED_RESOLUTION_CHAIN_WINDOW_CALLS and why Drift and "Unnamed target" do not read
        // this flag.
        context.put("focusedResolutionHolds",
                focusedResolutionHolds(spans, toolResultsByUseId, callAttribution));
        // Bound to a renderer rather than rendered once, because partitionToBudget re-renders the
        // timeline at tighter detail levels when the prompt overflows -- it thins the lines instead
        // of dropping calls. See TimelineDetail.
        Function<TimelineDetail, List<TimelineLine>> timelineRenderer = detail -> buildTimelineLines(
                spans, toolResultsByUseId, rejectedToolUseIds, callAttribution, blockedMsByToolSpanId, detail);
        List<TimelineLine> timelineLines = timelineRenderer.apply(TimelineDetail.FULL);
        context.put("timeline", renderText(timelineLines));
        context.put("hasWindows", false);
        context.put("hasCarryOver", false);
        // Placeholders so the template's {{#hasWindows}} section (referenced by frameLength's own
        // empty-timeline probe with hasWindows forced true, to measure the header's width) always
        // finds every variable it names, even before a real window is ever rendered.
        context.put("windowNumber", 0);
        context.put("windowCount", 0);
        context.put("windowFirstCall", 0);
        context.put("windowLastCall", 0);
        context.put("timelineCallCount", 0);
        context.put("carryOver", "");
        // Sections B and C are re-asked in every window on a partitioned trace by default, over
        // evidence -- the request's wording, the cost figures -- that cannot change across windows.
        // partitionToBudget's multi-window branch narrows these to one window each (first for B,
        // last for C, since the last window's carry-over already summarises spend across every prior
        // one); every other path (a trace that fits in one window, frameLength's own probe) leaves
        // both true so a single-window trace renders byte-identical to before this flag existed.
        context.put("judgesRequest", true);
        context.put("judgesCost", true);

        VerifiedObservations observations = buildObservations(
                spans,
                toolResultsByUseId,
                callAttribution,
                isContinuation,
                answersPrecedingQuestion,
                directedStartLine,
                blockedMsByToolSpanId,
                traceDurationMs);
        context.put("hasObservations", !observations.lines().isEmpty());
        context.put("observations", String.join("\n", observations.lines()));
        context.put("hasPositiveObservations", observations.hasPositives());
        // Read back off the rendered lines rather than threaded out of buildObservations, because
        // this flag exists for exactly one purpose -- to explain the "Not a finding:" prefix to a
        // reader who can see one -- so "a line carries that prefix" is its truest definition.
        context.put("hasNotAFindingObservations",
                observations.lines().stream().anyMatch(line -> line.startsWith(NOT_A_FINDING_PREFIX)));

        List<String> errorLines = buildErrorLines(spans, logRecords);
        context.put("hasErrors", !errorLines.isEmpty());
        context.put("errors", String.join("\n", errorLines));

        List<String> assistantTurnLines = buildAssistantTurnLines(logRecords);
        context.put("hasAssistantTurns", !assistantTurnLines.isEmpty());
        context.put("assistantTurns", String.join("\n\n", assistantTurnLines));

        // Resolved here rather than in putSkills because the permissions target is gated on an
        // observation, which does not exist until buildObservations has run.
        List<String> ruleTargets = ruleTargets(skillActivations, observations.permissionsRuleApplies());
        context.put("ruleTargets", renderRuleTargets(ruleTargets));

        ApplyThisInputs applyThisInputs = new ApplyThisInputs(
                hasPrompt ? promptText : null,
                hasPrompt,
                ruleTargets,
                Boolean.TRUE.equals(context.get("hasEditableSkillTargets")),
                wordingAlreadySettled,
                String.valueOf(context.get("wordingSettledReason")),
                suggestedRulesOf(observations.lines()),
                observations.settledToolSwap());

        RequestShape requestShape = new RequestShape(
                hasPrompt, promptText, isCommandInvocation, slashCommandName,
                isContinuation, (String) context.get("continuationSummary"));
        String summary = buildTraceSummary(
                traceSummary, spans, logRecords, context, requestShape, skillActivations, callAttribution,
                errorLines.size(), finalAssistantResponseText(logRecords));

        CarryOverState carryOverState =
                buildCarryOverState(spans, callAttribution, observations.failedToolCalls());
        String renderedPrompt = template.execute(context);
        return partitionToBudget(
                context, renderedPrompt, timelineLines, timelineRenderer, carryOverState, wordingAlreadySettled,
                observations.hasPositives(), applyThisInputs, summary, observations.failedToolCalls());
    }

    // The pre-written rules the observation block offered, with the "  Suggested rule: " prefix
    // stripped -- the second call is shown them as a plain list, since it never sees the
    // observations they were attached to.
    private static List<String> suggestedRulesOf(List<String> observationLines) {
        return observationLines.stream()
                .filter(line -> line.startsWith(SUGGESTED_RULE_PREFIX))
                .map(line -> line.substring(SUGGESTED_RULE_PREFIX.length()).strip())
                .distinct()
                .toList();
    }

    /**
     * The second call's prompt: distil the findings the first call wrote into the three
     * {@code Apply this} lines. See {@link ApplyThisInputs} for why this is its own model call.
     *
     * <p>It is handed the review text and nothing else about the trace — no timeline, no overview,
     * no observations — which is the whole point: the three lines are a summarisation job, and the
     * evidence for them is the review itself. Anything it cannot justify from that text it must not
     * write, and the prompt says so.
     */
    String buildApplyThisPrompt(String findings, ApplyThisInputs inputs) {
        Map<String, Object> context = new HashMap<>();
        context.put("structuredOutput", ollamaProperties.isStructuredOutput());
        context.put("findings", findings.strip());
        context.put("hasPrompt", inputs.hasPrompt());
        context.put("promptText", inputs.hasPrompt() ? inputs.promptText() : "");
        context.put("ruleTargets", renderRuleTargets(inputs.ruleTargets()));
        context.put("hasEditableSkillTargets", inputs.hasEditableSkillTargets());
        context.put("hasPermissionsTarget", inputs.ruleTargets().contains(PERMISSIONS_TARGET));
        context.put("permissionsTarget", PERMISSIONS_TARGET);
        context.put("wordingAlreadySettled", inputs.wordingAlreadySettled());
        context.put("wordingSettledReason", inputs.wordingSettledReason());
        context.put("hasSuggestedRules", !inputs.suggestedRules().isEmpty());
        context.put("suggestedRules", inputs.suggestedRules().stream()
                .map("- %s"::formatted)
                .collect(Collectors.joining("\n")));
        // Told to the model as a decided fact, and enforced afterwards in TraceAnalysisAnswer --
        // the template branch is here so the answer's other two lines are written knowing the swap
        // was reported, not so the model can be trusted to copy it.
        context.put("toolSwapAlreadySettled", inputs.toolSwapAlreadySettled());
        context.put("settledToolSwap", inputs.toolSwapAlreadySettled() ? inputs.settledToolSwap() : "");
        return applyThisTemplate.execute(context);
    }

    // The same headline figures the trace detail page's SummaryStrip shows a human reviewer --
    // cost (with the background split), duration, spans, tool/model call counts, depth, errors,
    // and the four-way token breakdown -- plus the three things a reader cannot get from that strip
    // and asked for here: where the money went, how well the prompt cache was reused, and how big
    // the context was when the trace started versus when it ended.
    private void putOverview(
            Map<String, Object> context,
            TraceSummary traceSummary,
            List<Span> spans,
            CallAttribution callAttribution) {
        long durationMs = traceSummary.getDurationNanos() == null
                ? 0L
                : Math.round(traceSummary.getDurationNanos() / NANOS_PER_MILLI);
        context.put("rootSpanName", nullToUnknown(traceSummary.getRootSpanName()));
        context.put("duration", formatDuration(durationMs));
        context.put("spanCount", traceSummary.getSpanCount());
        context.put("errorCount", traceSummary.getErrorCount());
        context.put("totalCostUsd", String.format(COST_FORMAT, traceSummary.getTotalCostUsd()));
        context.put("hasBackgroundCost", traceSummary.getBackgroundCostUsd() > 0);
        context.put("backgroundCostUsd", String.format(COST_FORMAT, traceSummary.getBackgroundCostUsd()));
        context.put("maxDepth", maxDepth(spans));

        long toolCallCount = 0L;
        long modelCallCount = 0L;
        long inputTokens = 0L;
        long outputTokens = 0L;
        long cacheReadTokens = 0L;
        long cacheCreationTokens = 0L;
        Set<String> models = new LinkedHashSet<>();
        Set<String> efforts = new LinkedHashSet<>();
        for (Span span : spans) {
            if (tuningProperties.getToolSpanName().equals(span.getName())) {
                toolCallCount++;
            } else if (tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                modelCallCount++;
                inputTokens += JsonAttributeReaders.longAttribute(
                        span.getAttributes(), JsonAttributeReaders.INPUT_TOKENS_ATTRIBUTE);
                outputTokens += JsonAttributeReaders.longAttribute(span.getAttributes(), OUTPUT_TOKENS_ATTRIBUTE);
                cacheReadTokens += JsonAttributeReaders.longAttribute(
                        span.getAttributes(), JsonAttributeReaders.CACHE_READ_TOKENS_ATTRIBUTE);
                cacheCreationTokens += JsonAttributeReaders.longAttribute(
                        span.getAttributes(), JsonAttributeReaders.CACHE_CREATION_TOKENS_ATTRIBUTE);
                Object model = JsonAttributeReaders.attribute(span.getAttributes(), MODEL_ATTRIBUTE);
                if (model != null) {
                    models.add(String.valueOf(model));
                }
                // Null effort means "not recorded", which is a different fact from "ran at the
                // default level" -- see the span_efforts note in backend/CLAUDE.md -- so an absent
                // one contributes nothing rather than a made-up default.
                if (span.getEffort() != null) {
                    efforts.add(span.getEffort());
                }
            }
        }
        context.put("toolCallCount", toolCallCount);
        context.put("modelCallCount", modelCallCount);
        context.put("inputTokens", formatTokens(inputTokens));
        context.put("outputTokens", formatTokens(outputTokens));
        context.put("cacheReadTokens", formatTokens(cacheReadTokens));
        context.put("cacheCreationTokens", formatTokens(cacheCreationTokens));
        context.put("models", models.isEmpty() ? "unknown" : String.join(", ", models));
        context.put("hasEfforts", !efforts.isEmpty());
        context.put("efforts", String.join(", ", efforts));

        putCacheReuse(context, inputTokens, cacheReadTokens, cacheCreationTokens);
        putContextSize(context, callAttribution);
        putCostSplit(context, callAttribution);

        TimeAttribution timeAttribution = computeTimeAttribution(spans, durationMs);
        context.put("llmTime", formatDuration(timeAttribution.llmTimeMs()));
        context.put("llmTimePercent", String.format(PERCENT_FORMAT, timeAttribution.llmTimePercent()));
        context.put("toolTime", formatDuration(timeAttribution.toolTimeMs()));
        context.put("toolTimePercent", String.format(PERCENT_FORMAT, timeAttribution.toolTimePercent()));
        context.put("blockedTime", formatDuration(timeAttribution.blockedTimeMs()));
        context.put("blockedTimePercent", String.format(PERCENT_FORMAT, timeAttribution.blockedTimePercent()));
    }

    /**
     * A plain-text "what happened" recap — request, work, outcome — composed entirely in code from
     * facts this method already has in hand, and deliberately kept OUT of the prompt: it is never
     * sent to Ollama, never judged, and never rewritten by a model call.
     *
     * <p>Every other piece of prose this feature produces is judged output with a {@code Fix:} behind
     * it, or is a verified fact a reader can trace back to a call number. A narrated retelling has
     * neither property, and it is exactly the shape this feature's documented failure mode hits
     * hardest — {@code buildObservations}' own javadoc records a small model reporting two model
     * calls as "identical TodoWrite" tool calls when merely asked to describe what it saw. So this is
     * assembled the same way {@code formatDuration} converts milliseconds: arithmetic and string
     * concatenation over data this class already computed for the overview and the timeline, not a
     * new inference. It exists to answer one question the rest of the dialog does not: a reader
     * re-opening a trace from days ago has to reconstruct "what was this even about" before the
     * model's findings mean anything, and today that reconstruction is the first thing they do by
     * hand, every time.
     */
    private String buildTraceSummary(
            TraceSummary traceSummary,
            List<Span> spans,
            List<LogRecord> logRecords,
            Map<String, Object> context,
            RequestShape requestShape,
            List<SkillActivation> skillActivations,
            CallAttribution callAttribution,
            int errorLineCount,
            String finalAssistantMessage) {
        List<String> lines = new ArrayList<>();
        lines.add("Request: " + requestSummaryLine(requestShape));
        lines.add("Work: " + workSummaryLine(context));
        addSummaryLine(lines, "Tools", toolUsageLine(spans));
        addSummaryLine(lines, "Models", modelUsageLine(spans));
        addSummaryLine(lines, "Files", fileUsageLine(spans));
        addSummaryLine(lines, "Cost", costSummaryLine(traceSummary, callAttribution));
        addSummaryLine(lines, "Skills", skillSummaryLine(skillActivations));
        addSummaryLine(lines, "Compaction", compactionSummaryLine(logRecords));
        lines.add("Outcome: " + outcomeSummaryLine(traceSummary, errorLineCount, finalAssistantMessage));
        return String.join("\n", lines);
    }

    /**
     * A line whose whole subject may be absent from a trace renders only when it has something to
     * say, rather than as a "none" the reader has to read past on every trace that never dispatched
     * a subagent, ran a skill or compacted. The three lines that always render — request, work,
     * outcome — say so explicitly instead, because for those an absence IS the fact (no prompt was
     * captured; the trace ended mid-tool-call).
     */
    private static void addSummaryLine(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value);
        }
    }

    /** What resolved this trace's request, in the priority order {@link #requestSummaryLine} reads. */
    private record RequestShape(
            boolean hasPrompt,
            String promptText,
            boolean isCommandInvocation,
            String commandName,
            boolean isContinuation,
            String continuationSummary) {}

    // The request line names what this trace was FOR, in the same priority order the rest of the
    // prompt already resolves it: a continuation's envelope, a slash command's own text, an authored
    // prompt, or -- for a subagent run or a trace that predates prompt capture -- an explicit "none"
    // rather than silence, so the reader knows that absence is a fact, not a rendering gap.
    private static String requestSummaryLine(RequestShape requestShape) {
        if (requestShape.isContinuation()) {
            return "continuation of earlier background work — "
                    + nullToUnknown(requestShape.continuationSummary()) + ".";
        }
        if (requestShape.isCommandInvocation()) {
            String commandText = requestShape.promptText() == null
                    ? "/" + requestShape.commandName()
                    : requestShape.promptText().strip();
            return "the `" + truncate(collapseWhitespace(commandText), SUMMARY_REQUEST_TRUNCATION_LENGTH)
                    + "` slash command.";
        }
        if (requestShape.hasPrompt()) {
            return "\"" + truncate(
                    collapseWhitespace(requestShape.promptText().strip()), SUMMARY_REQUEST_TRUNCATION_LENGTH) + "\"";
        }
        return "none — no human-written prompt in this trace (a sub-agent run, or a trace that "
                + "predates prompt capture).";
    }

    // The work line is the shape of the trace at a glance -- how much it did, over how long -- read
    // back off the counts putOverview already computed rather than recounted a second way. What it
    // deliberately no longer carries is the detail: which tools, which models, which files, what the
    // spend split into, which skills ran. Those were clauses of one run-on sentence, which is the
    // form that hides them; each is its own line below, where it can be scanned rather than parsed.
    private static String workSummaryLine(Map<String, Object> context) {
        long toolCallCount = (Long) context.get("toolCallCount");
        long modelCallCount = (Long) context.get("modelCallCount");
        return toolCallCount + " tool call" + JsonAttributeReaders.plural(toolCallCount)
                + SUMMARY_CLAUSE_SEPARATOR + modelCallCount + " model call" + JsonAttributeReaders.plural(modelCallCount)
                + SUMMARY_CLAUSE_SEPARATOR + context.get("duration");
    }

    // Every tool the trace called and how often, busiest first. Names are the raw span tool_name --
    // an MCP call's prefixed mcp__<server>__<tool> form included -- so a tool named here is named
    // exactly as the timeline names it and a reader can find the calls behind the count.
    private String toolUsageLine(List<Span> spans) {
        Map<String, Integer> callsByTool = new LinkedHashMap<>();
        for (Span span : spans) {
            if (tuningProperties.getToolSpanName().equals(span.getName())) {
                callsByTool.merge(toolNameOf(span), 1, Integer::sum);
            }
        }
        return joinByDescendingCount(callsByTool, SUMMARY_CLAUSE_SEPARATOR);
    }

    // Every model the trace called and how many calls each took. Raw model ids, matching the
    // overview's own "Model calls: N -- <models>" line, so the two can never disagree about which
    // model ran. A call whose span carries no model attribute contributes nothing rather than an
    // "unknown" bucket -- the count above it already says how many calls there were in total.
    private String modelUsageLine(List<Span> spans) {
        Map<String, Integer> callsByModel = new LinkedHashMap<>();
        for (Span span : spans) {
            if (!tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                continue;
            }
            String model = JsonAttributeReaders.stringAttribute(span.getAttributes(), MODEL_ATTRIBUTE);
            if (model != null && !model.isBlank()) {
                callsByModel.merge(model, 1, Integer::sum);
            }
        }
        return joinByDescendingCount(callsByModel, SUMMARY_CLAUSE_SEPARATOR);
    }

    /**
     * Every file the trace touched and what it did to each — {@code TraceService.java (Read ×2,
     * Edit)} — in first-touch order, so the line reads down the trace rather than by a ranking the
     * reader would have to reconcile against the timeline.
     *
     * <p>The file comes from the tool span's own {@code file_path} attribute. <b>The grouping key is
     * the full path, and the display label is not</b> — the two used to be the same thing (the bare
     * last path segment), which is wrong whenever two structurally different files share a basename.
     * This repository guarantees that collision by construction: it keeps one {@code CLAUDE.md} per
     * {@code pages/<Name>Page/} folder, plus root-level ones (see {@code frontend/CLAUDE.md}'s "Page
     * structure" section). Verified on trace {@code df8c757de3bfbfe9c1da2b29f431a192}:
     * {@code frontend/CLAUDE.md} was read once (that trace's call 52) while
     * {@code frontend/src/pages/TraceDetailPage/CLAUDE.md} was read 5 times and edited 4 times, all
     * on one branch — a genuinely redundant re-read pattern, real and worth flagging — and the old
     * basename-only grouping merged the two into one false "{@code CLAUDE.md} (Read ×7, Edit ×4)"
     * fact, which the model then dutifully repeated back as its own finding. Keying on the full path
     * fixes the count; the label still can't afford the whole repo-absolute path (~80 characters per
     * line for something the reader only needs to recognise, not resolve), so {@link
     * #disambiguatedFileLabel} renders the last <b>two</b> segments — {@code TraceDetailPage/CLAUDE.md}
     * versus {@code frontend/CLAUDE.md} — which is enough to disambiguate this repo's actual collision
     * shape (per-page {@code CLAUDE.md}, {@code index.ts} barrels) far more cheaply than a two-pass
     * "only widen the colliding ones" scheme, and with no dependency on scan order. {@code
     * revisitedFiles} keys and labels itself the same way, for the same reason; {@code
     * directedStartObservation} is unaffected — it inspects only a single span, never aggregates
     * across files, so no basename collision is possible there.
     */
    private String fileUsageLine(List<Span> spans) {
        Map<String, Map<String, Integer>> toolsByFile = new LinkedHashMap<>();
        for (Span span : spans) {
            if (!tuningProperties.getToolSpanName().equals(span.getName())) {
                continue;
            }
            Object filePath = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
            if (filePath == null) {
                continue;
            }
            String fullPath = String.valueOf(filePath);
            if (!fullPath.isBlank()) {
                toolsByFile.computeIfAbsent(fullPath, path -> new LinkedHashMap<>())
                        .merge(toolNameOf(span), 1, Integer::sum);
            }
        }
        if (toolsByFile.isEmpty()) {
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> file : toolsByFile.entrySet()) {
            if (entries.size() == MAX_SUMMARY_FILE_NAMES) {
                entries.add("and " + (toolsByFile.size() - MAX_SUMMARY_FILE_NAMES) + " more");
                break;
            }
            entries.add(disambiguatedFileLabel(file.getKey())
                    + " (" + joinByDescendingCount(file.getValue(), SUMMARY_NESTED_SEPARATOR) + ")");
        }
        return String.join(SUMMARY_CLAUSE_SEPARATOR, entries);
    }

    /**
     * What the trace cost, and where that money went: the main loop's own spend up to the point it
     * started handing work out, then one figure per subagent dispatch, then the auxiliary work the
     * harness did on its own account.
     *
     * <p><b>The parts are not guaranteed to sum to the total, and the line says which is which.</b>
     * The total is the trace's authoritative figure from the {@code trace_costs} view, which counts
     * every request log stamped with this trace id; the split is the measured attribution from
     * {@link #attributeCalls}, which can only place a request that joined to a model call span on
     * {@code request_id}. Rendering the total first and labelling the rest "of which measured" is
     * deliberate — silently normalising the parts to the total would invent an attribution the data
     * does not support, which is the same trap the two-pipelines note in backend/CLAUDE.md warns
     * about for tokens.
     */
    private static String costSummaryLine(TraceSummary traceSummary, CallAttribution callAttribution) {
        double totalCostUsd = traceSummary.getTotalCostUsd();
        if (totalCostUsd <= 0 && callAttribution.measuredCostUsd() <= 0) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        parts.add("$" + String.format(COST_FORMAT, totalCostUsd) + " total");
        if (callAttribution.mainLoopModelCallCount() > 0) {
            parts.add("of which measured: " + MAIN_LOOP_LABEL.toLowerCase(Locale.ROOT) + " $"
                    + String.format(COST_FORMAT, callAttribution.mainLoopCostUsd()) + " across "
                    + callAttribution.mainLoopModelCallCount() + " model call"
                    + JsonAttributeReaders.plural(callAttribution.mainLoopModelCallCount()));
        }
        for (SubagentDispatch dispatch : callAttribution.dispatches()) {
            parts.add(dispatch.summaryLabel());
        }
        if (callAttribution.auxiliaryModelCallCount() > 0) {
            parts.add(AUXILIARY_LABEL + " $" + String.format(COST_FORMAT, callAttribution.auxiliaryCostUsd())
                    + " across " + callAttribution.auxiliaryModelCallCount() + " model call"
                    + JsonAttributeReaders.plural(callAttribution.auxiliaryModelCallCount()));
        }
        return String.join(SUMMARY_CLAUSE_SEPARATOR, parts);
    }

    // The skills that were in force, and what invoked each. Reuses describeTrigger so the summary
    // and the prompt's own "Skills that ran" section describe an activation the same way.
    private static String skillSummaryLine(List<SkillActivation> skillActivations) {
        if (skillActivations.isEmpty()) {
            return null;
        }
        return skillActivations.stream()
                .map(activation -> activation.name() + describeTrigger(activation.trigger()))
                .collect(Collectors.joining(SUMMARY_CLAUSE_SEPARATOR));
    }

    /**
     * Whether the context was compacted while this trace ran, and what that cost — a fact no other
     * figure in the dialog carries, and one that reframes everything above it: a trace that
     * compacted spent minutes and a model call reclaiming room rather than doing the work asked of
     * it, and the calls after the compaction were reasoning from a summary rather than from what
     * the reader actually saw it read.
     *
     * <p>Reports a FAILED compaction as a failure rather than as a pass — the event fires either
     * way, and an aborted run costs the time without reclaiming the room, which is the worse
     * outcome and the one most worth surfacing.
     */
    private String compactionSummaryLine(List<LogRecord> logRecords) {
        List<String> runs = new ArrayList<>();
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getCompactionEventName())) {
                continue;
            }
            Map<String, Object> attributes = logRecord.getAttributes();
            String trigger = JsonAttributeReaders.stringAttribute(attributes, TRIGGER_ATTRIBUTE);
            String success = JsonAttributeReaders.stringAttribute(attributes, SUCCESS_ATTRIBUTE);
            long durationMs = JsonAttributeReaders.longAttribute(attributes, DURATION_MS_ATTRIBUTE);
            StringBuilder run = new StringBuilder(trigger == null ? "ran" : trigger);
            // Absent success means the exporter did not report one, which is not the same fact as a
            // failure -- only an explicit false is reported as one.
            if (success != null && !Boolean.parseBoolean(success)) {
                run.append(", failed after ").append(formatDuration(durationMs));
                appendCompactionError(run, attributes);
            } else {
                run.append(", ").append(compactionTokenChange(attributes))
                        .append(" in ").append(formatDuration(durationMs));
            }
            runs.add(run.toString());
        }
        return runs.isEmpty() ? null : String.join(SUMMARY_CLAUSE_SEPARATOR, runs);
    }

    private static void appendCompactionError(StringBuilder run, Map<String, Object> attributes) {
        String error = JsonAttributeReaders.stringAttribute(attributes, ERROR_ATTRIBUTE);
        if (error != null && !error.isBlank()) {
            run.append(" (").append(truncate(collapseWhitespace(error), ERROR_TRUNCATION_LENGTH)).append(')');
        }
    }

    // "145,579 → 11,442 tokens", or just the starting size when the exporter reported no post_tokens
    // -- the reclaim is the point, so a missing half is said rather than shown as a drop to zero.
    private static String compactionTokenChange(Map<String, Object> attributes) {
        long preTokens = JsonAttributeReaders.longAttribute(attributes, PRE_TOKENS_ATTRIBUTE);
        long postTokens = JsonAttributeReaders.longAttribute(attributes, POST_TOKENS_ATTRIBUTE);
        if (preTokens <= 0) {
            return "context size not reported";
        }
        if (postTokens <= 0) {
            return String.format(THOUSANDS_FORMAT, preTokens) + " tokens compacted";
        }
        return String.format(THOUSANDS_FORMAT, preTokens) + " → "
                + String.format(THOUSANDS_FORMAT, postTokens) + " tokens";
    }

    /**
     * {@code Read ×3, Edit} — busiest first, with a single call rendered as the bare name. The
     * {@code ×1} carries no information and is pure noise on the long tail of a file list, where
     * most entries are exactly one call.
     */
    private static String joinByDescendingCount(Map<String, Integer> countsByName, String separator) {
        if (countsByName.isEmpty()) {
            return null;
        }
        return countsByName.entrySet().stream()
                // Stable sort, so names tying on count keep their first-touch order.
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> entry) -> entry.getValue()).reversed())
                .map(entry -> entry.getValue() == 1
                        ? entry.getKey()
                        : entry.getKey() + SUMMARY_COUNT_MARKER + entry.getValue())
                .collect(Collectors.joining(separator));
    }

    // The outcome line is deliberately just the error count and the last thing the agent said, not a
    // verdict -- judging whether that outcome was good is the model's job in "What went wrong", and
    // this line exists so a reader can tell whether it is worth reading that judgment at all.
    private static String outcomeSummaryLine(TraceSummary traceSummary, int errorLineCount, String finalAssistantMessage) {
        StringBuilder line = new StringBuilder();
        long errorCount = traceSummary.getErrorCount();
        if (errorCount > 0) {
            line.append(errorCount).append(" error").append(JsonAttributeReaders.plural(errorCount));
            // Only ever a NARROWING: "8 errors (2 distinct)" tells a reader the eight are repeats of
            // two things, while "1 error (2 distinct)" -- which the double-counted span/log legs
            // really did produce on adae1753270dd3088520435ae7f8af94 -- says nothing a reader can
            // parse, since distinct can never exceed the total it qualifies. The leg fix above stops
            // that count diverging upward; this stops the sentence being written either way, because
            // the two figures come from different passes (TraceSummary's count, and these rendered
            // lines) and nothing structurally ties them.
            if (errorLineCount > 0 && errorLineCount < errorCount) {
                line.append(" (").append(errorLineCount).append(" distinct)");
            }
        } else {
            line.append("no errors");
        }
        if (finalAssistantMessage == null || finalAssistantMessage.isBlank()) {
            line.append("; the trace has no final assistant message (still running, or ended inside a "
                    + "tool call).");
        } else {
            line.append("; ended: \"")
                    .append(truncate(collapseWhitespace(finalAssistantMessage), SUMMARY_OUTCOME_TRUNCATION_LENGTH))
                    .append("\"");
        }
        return line.toString();
    }

    // The newest assistant_response log's text, or null when this trace carries none -- a normal
    // outcome for a trace that ended mid-tool-call, or one recorded before that logging existed.
    // Deliberately a second small scan of logRecords rather than threading a value out of
    // buildAssistantTurnLines: this class already scans logRecords once per fact it needs
    // (findUserPromptRecord, indexToolResultsByUseId, rejectedToolUseIds, ...), and the alternative --
    // returning the untruncated final turn out of a method whose job is building truncated prompt
    // lines -- would overload that method's contract for one caller.
    private String finalAssistantResponseText(List<LogRecord> logRecords) {
        String finalResponseText = null;
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getAssistantResponseEventName())) {
                continue;
            }
            Object responseValue = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getResponseAttribute());
            if (responseValue != null && !String.valueOf(responseValue).isBlank()) {
                finalResponseText = String.valueOf(responseValue).strip();
            }
        }
        return finalResponseText;
    }

    // Flattens a multi-line prompt/response into one line for the summary -- the full text with its
    // original formatting is already rendered elsewhere in the prompt and, for the request, in the
    // dialog's own "before" card off trace_analyses.user_prompt.
    private static String collapseWhitespace(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /**
     * Renders the skills that ran, and — the reason this section exists at all — the closed set of
     * targets the "Instruction rule" line is allowed to name.
     *
     * <p>A rule that belongs in a skill's own definition is useless when written against
     * {@code CLAUDE.md}, and vice versa. But the target may only widen to a skill once the trace has
     * <i>shown</i> that skill ran and that the reader can edit it: a bundled skill ships with Claude
     * Code and is not the reader's file to change, and inviting a free choice of target from a model
     * that cannot see the repo produces invented paths. So the allowed list is built here from
     * evidence, and the template tells the model to copy one of these strings verbatim.
     *
     * <p><b>Editable skills are listed before {@code CLAUDE.md}, not after.</b> The choice between
     * them was previously carried by one trailing clause of template prose against a list that
     * opened with {@code CLAUDE.md}, and the model took the first, most general option: on trace
     * {@code 5d6c9ca05d7c6ce12e41a84980693f10} — a {@code /ship} run whose every call happened while
     * the project-defined {@code ship} skill was in force, with {@code skill:ship} on the list — both
     * findings were about what that skill does and the rule still landed on {@code CLAUDE.md}, where
     * it would fire on every unrelated trace in the repo and not on the one file that could act on
     * it. Order is the cheap half of the fix; the template's "default to the skill" rule is the
     * other half, and neither is reliable alone.
     */
    private static void putSkills(Map<String, Object> context, List<SkillActivation> skillActivations) {
        List<String> skillLines = new ArrayList<>();
        for (SkillActivation activation : skillActivations) {
            String editability = activation.editable()
                    ? "defined in this project, so a rule may target it"
                    : "bundled with Claude Code — the reader cannot edit this one";
            skillLines.add("- %s%s — %s".formatted(
                    activation.name(), describeTrigger(activation.trigger()), editability));
        }
        context.put("hasEditableSkillTargets", skillActivations.stream().anyMatch(SkillActivation::editable));
        context.put("hasSkills", !skillActivations.isEmpty());
        context.put("skills", String.join("\n", skillLines));
    }

    /**
     * The closed list of targets an {@code Instruction rule:} line may name, in the order the
     * prompt offers them: one {@code skill:<name>} per editable skill this trace ran, then
     * {@link #PERMISSIONS_TARGET} when this trace actually blocked on user approval, then
     * {@code CLAUDE.md} last.
     *
     * <p>Every entry is <b>built from evidence</b> — a skill this trace ran, an approval wait this
     * trace served — because a model that cannot see the repo invents plausible paths that are not
     * in it. {@link #PERMISSIONS_TARGET} is therefore not a standing option: on a trace that never
     * waited on a permission prompt there is no rule for it to carry, and offering it anyway would
     * be inviting exactly that invention.
     *
     * <p><b>The ordering is load-bearing</b> and unchanged where it was already fought for: a small
     * model takes the first, most general option it is offered, so {@code CLAUDE.md} stays last and
     * editable skills stay first (see {@link #putSkills} for the trace that proved it). The
     * permissions target slots between them — ahead of {@code CLAUDE.md}, which is the comparison it
     * has to win, and behind the skills, so this change cannot disturb the skill-versus-CLAUDE.md
     * default that took both an ordering and a template rule to get right.
     */
    private static List<String> ruleTargets(
            List<SkillActivation> skillActivations, boolean blockedOnUserApproval) {
        List<String> ruleTargets = skillActivations.stream()
                .filter(SkillActivation::editable)
                .map(activation -> SKILL_TARGET_PREFIX + activation.name())
                .collect(Collectors.toCollection(ArrayList::new));
        if (blockedOnUserApproval) {
            ruleTargets.add(PERMISSIONS_TARGET);
        }
        ruleTargets.add(PROJECT_INSTRUCTIONS_TARGET);
        return ruleTargets;
    }

    // The same closed list as the prompt's own display copy, so the two prompts and the schema enum
    // can never offer a different set -- see ApplyThisInputs#ruleTargets.
    private static String renderRuleTargets(List<String> ruleTargets) {
        return ruleTargets.stream().map("`%s`"::formatted).collect(Collectors.joining(", "));
    }

    /**
     * Prompt-token mix, phrased as the question a reader actually has: of everything this trace
     * sent <i>into</i> the models, how much was served from cache. Output tokens are excluded from
     * the denominator on purpose — they are what the model wrote, not what it was asked to read,
     * and folding them in makes a chatty trace look cache-efficient.
     */
    private static void putCacheReuse(
            Map<String, Object> context, long inputTokens, long cacheReadTokens, long cacheCreationTokens) {
        long promptTokens = inputTokens + cacheReadTokens + cacheCreationTokens;
        context.put("hasCacheReuse", promptTokens > 0);
        context.put("promptTokens", formatTokens(promptTokens));
        context.put("cacheReadPercent", String.format(PERCENT_FORMAT, percentOf(cacheReadTokens, promptTokens)));
        context.put(
                "cacheCreationPercent", String.format(PERCENT_FORMAT, percentOf(cacheCreationTokens, promptTokens)));
        context.put("uncachedPercent", String.format(PERCENT_FORMAT, percentOf(inputTokens, promptTokens)));
    }

    // Main-loop calls only. A subagent runs in its own fresh context (a few thousand tokens), so
    // mixing those in drags the "how loaded was this conversation" figure down towards nothing --
    // which is the opposite of the answer, since a big main-loop context is exactly what makes
    // dispatching a subagent the right move.
    private static void putContextSize(Map<String, Object> context, CallAttribution callAttribution) {
        List<Long> mainLoopContextTokens = callAttribution.mainLoopContextTokens();
        context.put("hasContextSize", !mainLoopContextTokens.isEmpty());
        if (mainLoopContextTokens.isEmpty()) {
            return;
        }
        context.put("startingContextTokens", formatTokens(mainLoopContextTokens.get(0)));
        context.put("peakContextTokens", formatTokens(maximum(mainLoopContextTokens)));
        context.put("mainLoopModelCallCount", mainLoopContextTokens.size());
    }

    private static void putCostSplit(Map<String, Object> context, CallAttribution callAttribution) {
        List<String> costSplitLines = callAttribution.costSplitLines();
        context.put("hasCostSplit", !costSplitLines.isEmpty());
        context.put("costSplit", String.join("\n", costSplitLines));
    }

    /**
     * The tail of the assistant turn this request is replying to, when there is one.
     *
     * <p>Without it the review reads a follow-up out of context and calls it vague — the single
     * most common false finding this feature produced. "yes implement those two" is not
     * under-specified wording; it is an exact answer to a message that ended "Want me to implement
     * those two?", and both halves of that exchange are in this database. The gap is rendered
     * alongside it because a reply sent 40 seconds later and one sent two days later are different
     * situations for the same words.
     *
     * <p><b>Rendered only when there is prompt wording to resolve it against</b> ({@code hasPrompt}),
     * which excludes a slash command, a {@code <task-notification>} envelope, and a trace with no
     * {@code user_prompt} at all. Those three already drop section B, so the preceding turn has no
     * question left to answer there — but it stays the one block of free prose in the prompt, and on
     * a clean trace the observation block is empty and the answer contract still demands a finding.
     * Measured: on trace {@code 5d6c9ca05d7c6ce12e41a84980693f10}, a 31-second {@code /ship} with no
     * failures and no revisits, the review's sole bullet was "the agent addressed two distinct
     * architectural fixes (Token attribution and Model attribution) in a single session" — every noun
     * of it lifted from the previous turn's summary of work that had already shipped, none of it
     * present anywhere in the trace. Suppressing the section is the fix; the template's scoping
     * paragraph is the net under it, since neither alone holds a 7B model.
     *
     * @return the tail exactly as rendered into the prompt, or null when the section was not
     *     rendered at all. Returned rather than kept internal because
     *     {@link #answersPrecedingQuestion} has to judge the request against the text the model can
     *     actually see, not against the untruncated response.
     */
    private String putPrecedingTurn(
            Map<String, Object> context,
            TraceSummary traceSummary,
            LogRecord precedingAssistantResponse,
            boolean hasPrompt) {
        context.put("hasPrecedingTurn", false);
        if (precedingAssistantResponse == null || !hasPrompt) {
            return null;
        }
        String precedingText = JsonAttributeReaders.stringAttribute(
                precedingAssistantResponse.getAttributes(), tuningProperties.getResponseAttribute());
        if (precedingText == null || precedingText.isBlank()) {
            return null;
        }
        String precedingTurnTail = truncateToTail(precedingText.strip(), PRECEDING_TURN_TRUNCATION_LENGTH);
        context.put("hasPrecedingTurn", true);
        context.put("precedingTurnText", precedingTurnTail);
        context.put(
                "precedingTurnGap",
                describeGap(precedingAssistantResponse.getTimestamp(), traceSummary.getStartTimestamp()));
        return precedingTurnTail;
    }

    /**
     * Whether the request is an <b>answer</b> to the preceding turn rather than an ask of its own —
     * a short reply following a message that closed by asking something.
     *
     * <p>This exists because the prose fence was not enough, and that is a measured result rather
     * than a worry. Trace {@code 83b37f35664d88848e00d20be9f737ae} rendered the preceding-turn
     * section correctly — the previous message ended "Want me to fix `model` the same way …?", the
     * request was "yes fix it", and the section carried both the scoping paragraph and section B's
     * "a request that answers the preceding message is NOT ambiguous on that ground" line — and the
     * review still reported <i>"the user prompt 'yes fix it' is too vague"</i> and spent its
     * wording line (then {@code Rewritten request:}, now {@code Better wording:}) re-specifying, in
     * the reader's place, work the reader had just
     * been asked a yes/no question about. Two prose fences in the same prompt lost to a three-word
     * prompt, which is the same lesson {@code buildObservations} is built on: decide it in code and
     * hand the model a decision, do not hand it an instruction and hope.
     *
     * <p>Both halves of the gate are measured over 30 days and 758 human-written, non-slash prompts.
     * Length alone is useless as a signal — 470 of those (62%) are at or under
     * {@link #SHORT_FOLLOW_UP_REQUEST_LENGTH} characters, and suppressing the wording half of
     * section B on 62% of traces would cost far more real findings than it saves false ones. Pairing
     * it with a question mark somewhere in the <b>rendered tail</b> of the preceding turn narrows it
     * to 129 (17%). The question mark is looked for anywhere in that tail rather than at its very
     * end (which would match only 99, and would have missed this trace): an answer that offers an
     * option and then qualifies it — "Want me to fix `model` the same way? It's slightly trickier
     * than tokens: …" — is still a question being answered.
     *
     * <p>The tail, not the whole response, is deliberately the text searched: the model is only shown
     * the tail, so a question mark 8,000 characters above it explains nothing the model can see, and
     * a rule the reader cannot verify against the prompt is worse than no rule.
     */
    private static boolean answersPrecedingQuestion(String promptText, String precedingTurnTail) {
        if (promptText == null || precedingTurnTail == null) {
            return false;
        }
        return promptText.strip().length() <= SHORT_FOLLOW_UP_REQUEST_LENGTH
                && precedingTurnTail.indexOf(QUESTION_MARK) >= 0;
    }

    /**
     * Whether the request is itself a question, which settles its wording the same way
     * {@link #answersPrecedingQuestion} does — a third code-side verdict feeding
     * {@code wordingAlreadySettled}.
     *
     * <p>The template has always said a question "is not underspecified for being open ... it has no
     * target to name, so neither fault below applies to it". This is not new policy; it is that rule
     * moved out of prose, because prose lost. On trace {@code 8f98bde5c347e9844362370f41f1c5ac} the
     * request was <i>"On the settings page for Ollama section can the models available be listed in a
     * drop down?"</i> and the review's {@code Better wording} line came back telling the reader to
     * <i>"specify the scope by listing the files that need modification (e.g. SystemController.java,
     * OllamaClient.java)"</i> — files the reader had no way to know, named by a trace whose whole job
     * was finding them. Three separate fences should have stopped it: this question rule, the
     * "a short instruction is not automatically an underspecified one" rule, and the second call's
     * "use only what the reader could have written before that trace ran". All three lost, which is
     * the same lesson {@code answersPrecedingQuestion} was built on: decide it in code, hand the model
     * a verdict, do not hand it an instruction and hope.
     *
     * <p><b>The structural half is why prose could not hold.</b> {@code requestWellAimed} — the only
     * other way wording gets settled without a preceding question — fires off
     * {@link #directedStartObservation}, which requires the request to name a <i>file</i>. Measured
     * over 30 days and 692 human-written non-slash prompts, only <b>34 (4.9%)</b> name a file at all.
     * So 95% of real requests reach section B with the "unnamed target" fault live and the only
     * remedy it knows how to write pointing at paths the reader did not have — the review had no way
     * to recognise that "the Ollama section of the settings page" <i>is</i> a named target.
     *
     * <p><b>Ends with the mark, not merely contains one, and no length bound.</b> Ends-with excludes a
     * long instruction carrying a parenthetical aside; that narrows 165 matching prompts to <b>149
     * (21.5%)</b>. A length bound was considered and rejected on the data: sampling both bands, the
     * 49 questions longer than 120 characters ("Does the trace analysis look at what the errors are?",
     * "why is this happening?") are as genuinely questions as the short ones, so the ≤120 gate
     * {@code answersPrecedingQuestion} uses would only lose real matches — it earns its place there
     * for a different job, spotting a terse <i>reply</i>. The cost of the remaining false positives is
     * near zero: the few instruction-shaped questions in the sample ("change the 1000000000 to
     * something more reasonable like 100mb?") name their target exactly, so there was no wording fault
     * to lose. And only the wording half is suppressed — drift stays open, as do sections A and C.
     *
     * <p>This deliberately adds <b>no</b> {@code Went well:} positive, unlike its two siblings. Asking
     * a question is not the reader's wording doing a job worth crediting, and positives are gated
     * harder than faults here precisely because praise is the easier thing to fabricate.
     */
    private static boolean requestIsQuestion(String promptText) {
        if (promptText == null) {
            return false;
        }
        String strippedPromptText = promptText.strip();
        if (strippedPromptText.isEmpty()) {
            return false;
        }
        if (strippedPromptText.charAt(strippedPromptText.length() - 1) == QUESTION_MARK) {
            return true;
        }
        return opensWithAQuestionWord(strippedPromptText);
    }

    /**
     * Whether the request's first word is one of {@link #QUESTION_OPENERS} — the half of
     * {@link #requestIsQuestion} that catches a question written without its mark.
     *
     * <p><b>Ends-with was never the rule, it was the available proxy.</b> The mark is punctuation a
     * writer can simply omit, and this reader routinely does; what the gate is actually asking is
     * whether the request poses a question rather than naming work, and an interrogative first word
     * answers that as directly as the mark does. Matched on the <b>first word only</b>, for the same
     * reason the mark is required at the end rather than anywhere: "fix the thing, can you" is an
     * instruction with a question word in it, and a contains-test would gate it.
     */
    private static boolean opensWithAQuestionWord(String strippedPromptText) {
        int firstWordEnd = 0;
        while (firstWordEnd < strippedPromptText.length()
                && Character.isLetter(strippedPromptText.charAt(firstWordEnd))) {
            firstWordEnd++;
        }
        // A first "word" running to the end of the text is a single word with no request after it,
        // which is not a question however it opens.
        if (firstWordEnd == 0 || firstWordEnd == strippedPromptText.length()) {
            return false;
        }
        String firstWord = strippedPromptText.substring(0, firstWordEnd).toLowerCase(Locale.ROOT);
        return QUESTION_OPENERS.contains(firstWord);
    }

    // "4 minutes", "2 hours" -- coarse on purpose: the reader is deciding whether this was an
    // immediate reply or a return to a cold conversation, not measuring latency.
    private static String describeGap(Instant precedingTimestamp, Instant traceStart) {
        if (precedingTimestamp == null || traceStart == null || traceStart.isBefore(precedingTimestamp)) {
            return "shortly";
        }
        Duration gap = Duration.between(precedingTimestamp, traceStart);
        if (gap.toSeconds() < SECONDS_PER_MINUTE) {
            return gap.toSeconds() + " seconds";
        }
        if (gap.toMinutes() < MINUTES_PER_HOUR) {
            return gap.toMinutes() + " minutes";
        }
        if (gap.toHours() < HOURS_PER_DAY) {
            return gap.toHours() + " hours";
        }
        return gap.toDays() + " days";
    }

    /**
     * A duration the way a reader says one: {@code 812ms}, {@code 36.4s}, {@code 2m 5s},
     * {@code 1h 12m}. <b>Every duration this prompt renders goes through here</b> — the overview,
     * the time attribution, each timeline line, and the outlier observation.
     *
     * <p>The reason is that the review quotes back the shape it was shown. A prompt reading
     * {@code Duration: 36411 ms} produced answers reading "36411 ms", leaving the reader to do the
     * division themselves to learn the one thing that figure was for — that the call took half a
     * minute. Asking the model to convert instead is the move this class exists to avoid: it is
     * arithmetic handed to a 7B model in prose, i.e. another chance to be confidently wrong, when
     * the conversion is three lines of Java here. Same reasoning as
     * {@link #buildObservations} — decide it in code, hand the model the decided fact.
     *
     * <p>Sub-second calls keep whole milliseconds because that is the resolution the underlying
     * attribute has, and a tool call is normally in that range; above a second the extra digits are
     * false precision in a sentence, so seconds carry one decimal and minutes and hours none.
     */
    private static String formatDuration(long millis) {
        if (millis < MILLIS_PER_SECOND) {
            return String.format(MILLIS_DURATION_FORMAT, millis);
        }
        if (millis < MILLIS_PER_MINUTE) {
            return String.format(SECONDS_DURATION_FORMAT, (double) millis / MILLIS_PER_SECOND);
        }
        if (millis < MILLIS_PER_HOUR) {
            return String.format(MINUTES_DURATION_FORMAT,
                    millis / MILLIS_PER_MINUTE, millis % MILLIS_PER_MINUTE / MILLIS_PER_SECOND);
        }
        return String.format(HOURS_DURATION_FORMAT,
                millis / MILLIS_PER_HOUR, millis % MILLIS_PER_HOUR / MILLIS_PER_MINUTE);
    }

    /**
     * A token count the way the overview's own benchmark text states one: {@code 800}, {@code 12k},
     * {@code 1.2M}. The overview compares a call's token figures against this database's measured
     * distribution ("typical traces here start around 100k and three quarters of them never exceed
     * 170k"), and a bare integer forces that comparison to be done by the reader -- or, worse, by
     * the reviewing model in prose, which is exactly the arithmetic {@link #formatDuration} exists
     * to keep out of a 7B model's hands. Below 1,000 the exact count is shown, since nothing in this
     * range gets compared against a rounded benchmark.
     */
    private static String formatTokens(long tokens) {
        if (tokens < TOKENS_PER_THOUSAND) {
            return String.valueOf(tokens);
        }
        if (tokens < TOKENS_PER_MILLION) {
            return String.format(THOUSANDS_TOKEN_FORMAT, Math.round(tokens / (double) TOKENS_PER_THOUSAND));
        }
        return String.format(MILLIONS_TOKEN_FORMAT, tokens / (double) TOKENS_PER_MILLION);
    }

    // Deepest parent chain in the trace, the same "Depth" tile the detail page shows. Resolved
    // iteratively from the parent links rather than by building a tree, and capped at
    // MAX_DEPTH_ITERATIONS so a malformed trace (a cycle, or a parent id pointing outside this
    // trace) can never spin here.
    private static int maxDepth(List<Span> spans) {
        Map<String, String> parentBySpanId = parentBySpanId(spans);
        int maxDepth = 0;
        for (Span span : spans) {
            int depth = 0;
            String currentSpanId = span.getSpanId();
            while (depth < MAX_DEPTH_ITERATIONS) {
                String parentSpanId = parentBySpanId.get(currentSpanId);
                if (parentSpanId == null || !parentBySpanId.containsKey(parentSpanId)) {
                    break;
                }
                depth++;
                currentSpanId = parentSpanId;
            }
            maxDepth = Math.max(maxDepth, depth);
        }
        return maxDepth;
    }

    private static Map<String, String> parentBySpanId(List<Span> spans) {
        Map<String, String> parentBySpanId = new HashMap<>();
        for (Span span : spans) {
            if (span.getSpanId() != null) {
                parentBySpanId.put(span.getSpanId(), span.getParentSpanId());
            }
        }
        return parentBySpanId;
    }

    /**
     * The {@code user_prompt} record this trace's request came from, or null when it carries none
     * (a sub-agent run, a resume heartbeat) or only machine-authored ones. Returned as the record
     * rather than its text because the same row also carries
     * {@link TuningProperties#getPromptCommandNameAttribute()}, which decides whether that text is
     * prose at all — see {@link #slashCommandName}.
     */
    private LogRecord findUserPromptRecord(List<LogRecord> logRecords) {
        for (LogRecord logRecord : logRecords) {
            if (JsonAttributeReaders.isEvent(logRecord, tuningProperties.getUserPromptEventName())) {
                Object promptValue = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getPromptAttribute());
                if (promptValue != null && !isMachineAuthoredPrompt(String.valueOf(promptValue))) {
                    return logRecord;
                }
            }
        }
        return null;
    }

    private String findUserPromptText(LogRecord userPromptRecord) {
        if (userPromptRecord == null) {
            return null;
        }
        Object promptValue = JsonAttributeReaders.attribute(userPromptRecord.getAttributes(), tuningProperties.getPromptAttribute());
        return promptValue == null ? null : String.valueOf(promptValue);
    }

    /**
     * The request this trace's review will judge the wording of, or null when it has none — the
     * exact text {@link #build} renders into the prompt's "User prompt (full text)" section.
     *
     * <p>Exposed so {@code TraceAnalysisService} can store that text alongside the answer and the
     * dialog can show the model's suggested wording <b>against</b> it. The "before" half of that
     * comparison has to be what was actually typed: the answer contract already spends a rule on
     * "never invent prompt wording", and asking the model to quote the request back would put the
     * one half this application can supply for free back into the half it has to police.
     *
     * <p>Shares {@link #isJudgeablePrompt} with {@code build}'s own {@code hasPrompt} rather than
     * re-deriving the gate, so the two cannot drift into a card offering a "before" for a trace
     * whose review was never shown a request — a slash command, a {@code <task-notification>}
     * envelope, or a sub-agent run.
     */
    String judgedPromptText(List<LogRecord> logRecords) {
        LogRecord userPromptRecord = findUserPromptRecord(logRecords);
        String promptText = findUserPromptText(userPromptRecord);
        return isJudgeablePrompt(promptText, slashCommandName(userPromptRecord) != null) ? promptText : null;
    }

    /**
     * Whether there is request wording here for the review's section B to judge. A slash command's
     * text is a name standing in for a skill definition, not prose someone wrote — see
     * {@link #slashCommandName}; a machine-authored envelope never reaches here at all, having
     * already been rejected by {@link #findUserPromptRecord}.
     */
    private static boolean isJudgeablePrompt(String promptText, boolean isCommandInvocation) {
        return promptText != null && !promptText.isBlank() && !isCommandInvocation;
    }

    /**
     * The slash command behind a {@code user_prompt}, or null when a person actually typed the text.
     *
     * <p>This is the second shape of "a {@code user_prompt} record whose text nobody wrote as a
     * request" — see {@link #isMachineAuthoredPrompt} for the first. A slash command's prompt text is
     * the command itself: {@code /ship} is five characters standing in for a skill definition that
     * lives in a file this trace does not contain. Measured here, 57 of 796 prompt-bearing traces
     * over 30 days are slash commands and 51 of those are 30 characters or shorter.
     *
     * <p>Judging that text as prompt wording produces a confidently wrong finding, not a weak one. On
     * a real {@code /ship} trace the review reported <i>"the user prompt {@code /ship} is too vague,
     * forcing the agent to infer the necessary final steps"</i> and its one actionable output was a
     * rewritten request replacing the command with three literal git commands — advice to abandon the
     * very skill the command exists to invoke, complete with an invented commit message and branch
     * name. So a command invocation drops the request-quality half exactly as a machine-authored
     * prompt does, and the "Skills that ran" section carries what actually drove the trace instead.
     */
    private String slashCommandName(LogRecord userPromptRecord) {
        if (userPromptRecord == null) {
            return null;
        }
        Object commandName = JsonAttributeReaders.attribute(
                userPromptRecord.getAttributes(), tuningProperties.getPromptCommandNameAttribute());
        if (commandName == null || String.valueOf(commandName).isBlank()) {
            return null;
        }
        return String.valueOf(commandName);
    }

    /**
     * A {@code user_prompt} record whose text nobody typed. The harness delivers a
     * {@code <task-notification>} envelope when a background subagent finishes, and it lands on a
     * {@code user_prompt} log like any other — 101 of 773 (13.1%) over 30 days here. Feeding one to
     * the prompt-quality half asks the model to critique the phrasing of a machine-generated status
     * message and suggest a better-worded version of it, which is exactly what produced a fabricated
     * "the prompt said 'I'm not sure if this is the right file'" finding on a trace whose prompt was
     * an envelope. Treating it as "no prompt" instead degrades to the execution-quality half, which
     * is the honest read of such a trace.
     *
     * <p>Mirrors {@code frontend/src/lib/promptSummary.ts}, deliberately including its
     * <b>starts-with</b> test rather than a contains: a real envelope always opens with the tag,
     * while a human prompt that merely quotes one further in is genuine text worth judging. Keep the
     * two in step — another non-authored prompt shape needs adding in both places.
     */
    private static boolean isMachineAuthoredPrompt(String promptText) {
        return promptText.stripLeading().startsWith(TASK_NOTIFICATION_OPENING_TAG);
    }

    /**
     * The {@code tool_use_id} a {@code <task-notification>} envelope quotes back, naming the call
     * that launched the background task whose completion woke this trace — or null when the trace
     * was not started by one. Read by {@link TraceAnalysisService} to fetch that call, which lives
     * in the dispatching trace and so is not among this trace's own logs.
     *
     * <p>Measured over 30 days: 103 of 106 notification traces carry an id, and every one of those
     * resolves to a real {@code tool_result}.
     */
    String backgroundTaskToolUseId(List<LogRecord> logRecords) {
        String notificationText = notificationPromptText(logRecords);
        return notificationText == null ? null : tagValue(notificationText, TOOL_USE_ID_TAG);
    }

    private String notificationPromptText(List<LogRecord> logRecords) {
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getUserPromptEventName())) {
                continue;
            }
            Object promptValue = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getPromptAttribute());
            if (promptValue != null && isMachineAuthoredPrompt(String.valueOf(promptValue))) {
                return String.valueOf(promptValue);
            }
        }
        return null;
    }

    // Plain substring extraction rather than a regex or an XML parse: the envelope is written by the
    // harness to a fixed shape, and a malformed one has to degrade to "no continuation section"
    // rather than throw on a dialog the reader is waiting on.
    private static String tagValue(String text, String tagName) {
        String openingTag = "<" + tagName + ">";
        String closingTag = "</" + tagName + ">";
        int valueStart = text.indexOf(openingTag);
        if (valueStart < 0) {
            return null;
        }
        valueStart += openingTag.length();
        int valueEnd = text.indexOf(closingTag, valueStart);
        if (valueEnd < 0) {
            return null;
        }
        String value = text.substring(valueStart, valueEnd).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Names what this trace is a continuation of, when a background task's completion woke it.
     *
     * <p>Without this the trace reads as a request that arrived from nowhere and then did very
     * little: on {@code 1635329e1e7db7f934b007d90aba7d61}, six spans that grep a test report and
     * report a fix complete. The dispatching call is what makes that legible, and it is deliberately
     * the <b>only</b> thing pulled across the trace boundary — the dispatching trace itself had 98
     * spans against this trace's 6, so merging its timeline would make the review a review of the
     * other trace, and every headline figure (cost, cache reuse, tokens, revisits) is computed off
     * the span list and would then describe two traces while the page shows one.
     */
    private void putContinuation(
            Map<String, Object> context,
            List<LogRecord> logRecords,
            TraceSummary traceSummary,
            LogRecord dispatchingToolCall) {
        context.put("isContinuation", false);
        String notificationText = notificationPromptText(logRecords);
        if (notificationText == null) {
            return;
        }
        context.put("isContinuation", true);
        context.put("continuationStatus", nullToUnknown(tagValue(notificationText, STATUS_TAG)));
        context.put("continuationSummary", nullToUnknown(tagValue(notificationText, SUMMARY_TAG)));
        context.put("hasDispatchingCall", dispatchingToolCall != null);
        if (dispatchingToolCall == null) {
            return;
        }
        Object toolInput = JsonAttributeReaders.attribute(dispatchingToolCall.getAttributes(), TOOL_INPUT_ATTRIBUTE);
        context.put("dispatchingToolName", nullToUnknown(
                JsonAttributeReaders.stringAttribute(dispatchingToolCall.getAttributes(), tuningProperties.getToolAttribute())));
        context.put("dispatchingToolInput",
                toolInput == null ? "" : truncateMiddleOut(String.valueOf(toolInput), TOOL_INPUT_TRUNCATION_LENGTH));
        context.put("dispatchingTraceId", nullToUnknown(dispatchingToolCall.getTraceId()));
        context.put("dispatchingGap", describeGap(dispatchingToolCall.getTimestamp(), traceSummary.getStartTimestamp()));
    }

    /**
     * One skill activation: which skill, where its definition lives, and what set it off.
     *
     * @param editable whether the reader can actually change this skill's definition. False for a
     *     {@code bundled} skill, which ships with Claude Code — suggesting an edit to one of those
     *     is a fix the reader cannot apply, the same dead end as suggesting they reword {@code /ship}.
     */
    private record SkillActivation(String name, String source, String trigger, boolean editable) {}

    /**
     * The skills that ran in this trace, de-duplicated by name and in activation order.
     *
     * <p>Read from the dedicated {@code skill_activated} event rather than from
     * {@link TuningProperties#getSkillEventName()}: that one is stamped on <i>every</i> model call
     * made while a skill runs (which is why the markdown report has to de-duplicate it per prompt),
     * whereas this fires once per activation and carries the source and trigger alongside the name.
     * For "which skill drove this trace, and may the reader edit it" that is the exact signal.
     */
    private List<SkillActivation> findSkillActivations(List<LogRecord> logRecords) {
        Map<String, SkillActivation> activationsByName = new LinkedHashMap<>();
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getSkillActivatedEventName())) {
                continue;
            }
            Object skillName = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getSkillNameAttribute());
            if (skillName == null || String.valueOf(skillName).isBlank()) {
                continue;
            }
            Object source = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getSkillSourceAttribute());
            Object trigger = JsonAttributeReaders.attribute(
                    logRecord.getAttributes(), tuningProperties.getSkillInvocationTriggerAttribute());
            String sourceText = source == null ? null : String.valueOf(source);
            activationsByName.putIfAbsent(String.valueOf(skillName), new SkillActivation(
                    String.valueOf(skillName),
                    sourceText,
                    trigger == null ? null : String.valueOf(trigger),
                    tuningProperties.getEditableSkillSource().equals(sourceText)));
        }
        return List.copyOf(activationsByName.values());
    }

    private static String describeTrigger(String trigger) {
        if (trigger == null) {
            return "";
        }
        return switch (trigger) {
            case USER_SLASH_TRIGGER -> " (the user invoked it as a slash command)";
            case PROACTIVE_TRIGGER -> " (the agent chose to invoke it itself)";
            case NESTED_SKILL_TRIGGER -> " (invoked by another skill)";
            default -> " (" + trigger + ")";
        };
    }

    /**
     * Every assistant turn in order, not just the last — what the agent said it was doing as it
     * went is the only narration of its own intent the trace carries, and an earlier revision threw
     * all but the final message away.
     *
     * <p><b>Deliberately NOT read from {@code api_response_body}, and {@code api_request_body} is
     * deliberately not read at all.</b> Measured over 14 days: a trace's request bodies total 2.0M
     * characters at p50 and 39.4M at p95, because every request re-sends the whole conversation —
     * summarizing that with a local 7B model would take hours per trace. Response bodies are
     * smaller but 35% base64 {@code signature} blobs with {@code thinking} already
     * {@code <REDACTED>}, and the assistant prose in them is 7.6% of their bytes. Extracting that
     * prose gives p50 1,146 / p95 12,648 characters per trace — which is, to within 1%, exactly
     * what these {@code assistant_response} logs already carry (p50 1,135 / p95 12,483) in a clean
     * attribute, with no JSON parsing, no base64, and no {@code \\u0000} escapes to break a
     * {@code ::jsonb} cast. The bodies add nothing this does not already give for free.
     *
     * <p>Earlier turns are capped tighter than the final one, which ask-vs-delivered is judged
     * against; the section is then middle-out trimmed like the timeline.
     */
    private List<String> buildAssistantTurnLines(List<LogRecord> logRecords) {
        List<String> turnTexts = new ArrayList<>();
        for (LogRecord logRecord : logRecords) {
            if (JsonAttributeReaders.isEvent(logRecord, tuningProperties.getAssistantResponseEventName())) {
                Object responseValue =
                        JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getResponseAttribute());
                if (responseValue != null && !String.valueOf(responseValue).isBlank()) {
                    turnTexts.add(String.valueOf(responseValue).strip());
                }
            }
        }

        List<String> lines = new ArrayList<>();
        for (int index = 0; index < turnTexts.size(); index++) {
            boolean isFinalTurn = index == turnTexts.size() - 1;
            lines.add((isFinalTurn ? "Final message: " : "Message " + (index + 1) + ": ")
                    + truncate(turnTexts.get(index), isFinalTurn
                            ? FINAL_ASSISTANT_TURN_TRUNCATION_LENGTH
                            : INTERMEDIATE_ASSISTANT_TURN_TRUNCATION_LENGTH));
        }
        return trimListMiddleOut(lines, ASSISTANT_NARRATION_BUDGET_CHARS, OMITTED_MESSAGES_NOTE).lines();
    }

    /** The trimmed lines, plus how many were dropped from the middle — see {@link #trimListMiddleOut}. */
    private record ListTrim(List<String> lines, int omittedCount) {
    }

    /**
     * Keeps the head AND tail of a list of lines, eliding the middle -- the assistant-narration
     * section's own compaction, kept separate from the timeline's (see {@link #partitionToBudget})
     * since the two no longer share a fallback: the timeline gives way by partitioning into windows,
     * never by dropping content, while narration has no such thing as "a second review call" to
     * split across, so it still elides. Filling the budget front-to-back kept only the opening turns
     * and dropped the end -- including the final response the ask-vs-delivered question is judged
     * against -- so the middle is what gives way instead.
     */
    private static ListTrim trimListMiddleOut(List<String> lines, int budget, String noteFormat) {
        List<String> head = new ArrayList<>();
        int usedLength = 0;
        int headIndex = 0;
        int headBudget = budget / 2;
        while (headIndex < lines.size()) {
            int lineLength = lines.get(headIndex).length() + 1;
            if (usedLength + lineLength > headBudget) {
                break;
            }
            head.add(lines.get(headIndex));
            usedLength += lineLength;
            headIndex++;
        }

        List<String> tail = new ArrayList<>();
        int tailIndex = lines.size() - 1;
        while (tailIndex >= headIndex) {
            int lineLength = lines.get(tailIndex).length() + 1;
            if (usedLength + lineLength > budget) {
                break;
            }
            tail.add(0, lines.get(tailIndex));
            usedLength += lineLength;
            tailIndex--;
        }

        int omittedCount = tailIndex - headIndex + 1;
        if (omittedCount <= 0) {
            return new ListTrim(lines, 0);
        }
        List<String> trimmedLines = new ArrayList<>(head);
        trimmedLines.add(noteFormat.formatted(omittedCount));
        trimmedLines.addAll(tail);
        return new ListTrim(trimmedLines, omittedCount);
    }

    private Map<String, LogRecord> indexToolResultsByUseId(List<LogRecord> logRecords) {
        Map<String, LogRecord> toolResultsByUseId = new HashMap<>();
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getToolEventName())) {
                continue;
            }
            Object toolUseId = JsonAttributeReaders.attribute(logRecord.getAttributes(), TOOL_USE_ID_ATTRIBUTE);
            if (toolUseId != null) {
                toolResultsByUseId.put(String.valueOf(toolUseId), logRecord);
            }
        }
        return toolResultsByUseId;
    }

    // A rejected tool_decision means the user refused the call the agent chose to make -- a
    // first-class signal about that choice, so it is rendered onto the call's own timeline line
    // rather than as a separate section the model has to correlate itself.
    private Set<String> rejectedToolUseIds(List<LogRecord> logRecords) {
        Set<String> rejectedToolUseIds = new LinkedHashSet<>();
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getToolDecisionEventName())) {
                continue;
            }
            Object decision = JsonAttributeReaders.attribute(logRecord.getAttributes(), DECISION_ATTRIBUTE);
            Object toolUseId = JsonAttributeReaders.attribute(logRecord.getAttributes(), TOOL_USE_ID_ATTRIBUTE);
            if (toolUseId != null && decision != null && !ACCEPTED_DECISION.equals(String.valueOf(decision))) {
                rejectedToolUseIds.add(String.valueOf(toolUseId));
            }
        }
        return rejectedToolUseIds;
    }

    private record TimeAttribution(
            long llmTimeMs, double llmTimePercent,
            long toolTimeMs, double toolTimePercent,
            long blockedTimeMs, double blockedTimePercent) {
    }

    private TimeAttribution computeTimeAttribution(List<Span> spans, long traceDurationMs) {
        long llmTimeMs = 0L;
        long toolTimeMs = 0L;
        long blockedTimeMs = 0L;
        for (Span span : spans) {
            long spanDurationMs = spanDurationMs(span);
            if (tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                llmTimeMs += spanDurationMs;
            } else if (tuningProperties.getToolExecutionSpanName().equals(span.getName())) {
                toolTimeMs += spanDurationMs;
            } else if (BLOCKED_ON_USER_SPAN_NAME.equals(span.getName())) {
                blockedTimeMs += spanDurationMs;
            }
        }
        return new TimeAttribution(
                llmTimeMs, percentOf(llmTimeMs, traceDurationMs),
                toolTimeMs, percentOf(toolTimeMs, traceDurationMs),
                blockedTimeMs, percentOf(blockedTimeMs, traceDurationMs));
    }

    private static double percentOf(long partMs, long totalMs) {
        return totalMs <= 0 ? 0.0 : PERCENT_SCALE * partMs / totalMs;
    }

    private static double shareOf(double part, double total) {
        return total <= 0 ? 0.0 : part / total;
    }

    // ---------------------------------------------------------------------------------------
    // Cost / subagent attribution
    // ---------------------------------------------------------------------------------------
    //
    // SubagentDispatch, CallAttribution and the attributeCalls() pass that builds one now live on
    // SubagentCostAttributor -- extracted so the per-subagent cost breakdown can be exposed as
    // structured data (GET /api/traces/{traceId}/cost-breakdown) without requiring an Ollama trace
    // analysis to ever have been generated. This class keeps calling into that same shared
    // implementation via subagentCostAttributor rather than a second copy, so the prose Cost: line
    // rendered below and the structured breakdown can never disagree about a dispatch's numbers.

    // Delegated rather than tested inline so the timeline's notion of a call and the callNumber the
    // trace detail page puts on a waterfall row are one definition -- see TraceCallNumbering.
    private boolean isCall(Span span) {
        return TraceCallNumbering.isCall(
                span, tuningProperties.getToolSpanName(), tuningProperties.getLlmRequestSpanName());
    }

    /**
     * One rendered call, the short label a later repeat refers back to it by, and the file it acted
     * on (null for calls that name no file). {@code target} is read straight off the tool span's
     * {@code file_path} attribute rather than parsed out of the {@code tool_input} JSON: measured
     * over 13,488 calls the two agree on 5,842 and the span carries it slightly more often (5,858
     * vs 5,847), and the span attribute is immune to {@link #TOOL_INPUT_TRUNCATION_LENGTH} cutting
     * a long input off mid-path — so parsing JSON here would be more work for a worse key.
     */
    private record TimelineEntry(String content, String shortLabel, String target, boolean mutatesTarget) {
    }

    /** A run of adjacent identical calls, numbered from {@code firstCallNumber} (1-based). */
    private record TimelineGroup(
            String content, String shortLabel, String target, boolean mutatesTarget,
            int firstCallNumber, int callCount) {
    }

    /**
     * One rendered timeline line, carrying the call number(s) it names — what {@link #packWindows}
     * partitions on. A folded range (a repeated call rendered as {@code "12-14. Bash ... × 3"}) has
     * {@code firstCallNumber != lastCallNumber}; every other line has the two equal.
     */
    record TimelineLine(String text, int firstCallNumber, int lastCallNumber) {
    }

    // Joins rendered lines back into the block {{timeline}} renders -- kept as one helper so a
    // fitting trace's prompt text (windows().size() == 1, no header, no carry-over) stays byte-for-
    // byte what buildTimelineLines produced before windowing existed.
    private static String renderText(List<TimelineLine> lines) {
        return lines.stream().map(TimelineLine::text).collect(Collectors.joining("\n"));
    }

    private static int firstCallNumberOf(List<TimelineLine> lines) {
        return lines.isEmpty() ? 0 : lines.get(0).firstCallNumber();
    }

    private static int lastCallNumberOf(List<TimelineLine> lines) {
        return lines.isEmpty() ? 0 : lines.get(lines.size() - 1).lastCallNumber();
    }

    /**
     * How much per-line detail the timeline renders. {@link #partitionToBudget} re-renders at each
     * level in order until the prompt fits, so an oversized trace loses <b>detail</b> rather than
     * <b>calls</b> — every span that would ever appear in the timeline still appears at every level,
     * carrying its own call number.
     *
     * <p>This is the point of the ladder. Eliding calls was the original response to an overflow, and
     * it is the one form of compaction that cannot be recovered from downstream: the observations in
     * {@code buildObservations} and the answer contract are both written against call numbers, so a
     * dropped call is a number the model is invited to cite and cannot see, and the redundancy and
     * error-recovery questions are asked precisely about the stretch of work an elided middle removes.
     * Measured on trace {@code 8f98bde5c347e9844362370f41f1c5ac} — 95 timeline calls, 63 of them
     * inside one {@code general-purpose} dispatch — the overflow was marginal (17 calls lost) and the
     * review that came back cited "calls 31 through 95" as one opaque block it could not read.
     *
     * <p>What each level gives up is chosen so that <b>nothing a finding rests on is ever dropped</b>.
     * A failure, a rejection, an error body, a blocked-on-user wait, an oversized result and a
     * subagent dispatch summary render identically at all three levels; what thins out is the detail
     * that is only evidence when nothing went wrong — the tail of a long tool input, the {@code -> ok}
     * and duration on a clean call, and a model call's token/cost breakdown.
     *
     * <p>Call numbers are unaffected by the level: {@link #collapseAdjacentRepeats} numbers by an
     * entry's position in the span sequence, not by its rendered content, and {@code buildObservations}
     * walks the same sequence independently. A tighter level can fold two calls that differ only in
     * their dropped detail into one group, which still states both numbers.
     */
    private enum TimelineDetail {
        FULL(TOOL_INPUT_TRUNCATION_LENGTH, true, true),
        COMPACT(COMPACT_TOOL_INPUT_TRUNCATION_LENGTH, false, true),
        MINIMAL(MINIMAL_TOOL_INPUT_TRUNCATION_LENGTH, false, false);

        private final int toolInputLength;
        private final boolean rendersSuccessfulCallOutcome;
        private final boolean rendersModelCallBreakdown;

        TimelineDetail(int toolInputLength, boolean rendersSuccessfulCallOutcome, boolean rendersModelCallBreakdown) {
            this.toolInputLength = toolInputLength;
            this.rendersSuccessfulCallOutcome = rendersSuccessfulCallOutcome;
            this.rendersModelCallBreakdown = rendersModelCallBreakdown;
        }
    }

    // Condensed timeline: tool-call and llm_request spans in trace order, each tool call enriched
    // from its tool_result log (see the class javadoc for why the log, not the span, is where the
    // input lives). Calls are numbered so the model can cite them ("call 12"), so a middle-elided
    // timeline still shows WHERE the gap is, and so a repeat can point back at the call it repeats.
    private List<TimelineLine> buildTimelineLines(
            List<Span> spans,
            Map<String, LogRecord> toolResultsByUseId,
            Set<String> rejectedToolUseIds,
            CallAttribution callAttribution,
            Map<String, Long> blockedMsByToolSpanId,
            TimelineDetail detail) {
        List<TimelineEntry> entries = new ArrayList<>();
        for (Span span : spans) {
            if (tuningProperties.getToolSpanName().equals(span.getName())) {
                entries.add(toolCallEntry(
                        span, toolResultsByUseId, rejectedToolUseIds, callAttribution, blockedMsByToolSpanId, detail));
            } else if (tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                entries.add(new TimelineEntry(
                        subagentPrefix(span, callAttribution) + renderLlmRequestLine(span, callAttribution, detail),
                        LLM_REQUEST_LABEL,
                        null,
                        false));
            }
        }
        return renderGroups(collapseAdjacentRepeats(entries));
    }

    // Calls that ran inside a subagent are marked rather than left to read as the main loop's own
    // work -- without this a trace where one Explore run made 44 of the 87 model calls looks like an
    // agent that could not stop searching.
    private static String subagentPrefix(Span span, CallAttribution callAttribution) {
        SubagentDispatch dispatch = callAttribution.dispatchBySpanId().get(span.getSpanId());
        return dispatch == null ? "" : "[" + dispatch.label + "] ";
    }

    private TimelineEntry toolCallEntry(
            Span span,
            Map<String, LogRecord> toolResultsByUseId,
            Set<String> rejectedToolUseIds,
            CallAttribution callAttribution,
            Map<String, Long> blockedMsByToolSpanId,
            TimelineDetail detail) {
        String toolName = toolNameOf(span);

        Object toolUseIdValue = JsonAttributeReaders.attribute(span.getAttributes(), TOOL_USE_ID_ATTRIBUTE);
        String toolUseId = toolUseIdValue == null ? null : String.valueOf(toolUseIdValue);
        LogRecord toolResult = toolUseId == null ? null : toolResultsByUseId.get(toolUseId);

        Object filePathValue = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
        String target = filePathValue == null ? null : String.valueOf(filePathValue);

        String prefix = subagentPrefix(span, callAttribution);
        StringBuilder line = new StringBuilder(prefix).append(toolName);

        String toolInput = toolInputFor(span, toolResult, detail.toolInputLength);
        if (!toolInput.isEmpty()) {
            line.append(' ').append(toolInput);
        }

        if (toolUseId != null && rejectedToolUseIds.contains(toolUseId)) {
            line.append(" -> REJECTED by user");
            return new TimelineEntry(
                    line.toString(), prefix + toolName, target, MUTATING_TOOL_NAMES.contains(toolName));
        }
        if (toolResult != null) {
            Object success = JsonAttributeReaders.attribute(toolResult.getAttributes(), SUCCESS_ATTRIBUTE);
            boolean failed = success != null && !Boolean.parseBoolean(String.valueOf(success));
            if (failed) {
                Object error = JsonAttributeReaders.attribute(toolResult.getAttributes(), ERROR_ATTRIBUTE);
                line.append(" -> FAILED");
                if (error != null) {
                    line.append(": ").append(truncate(String.valueOf(error), ERROR_TRUNCATION_LENGTH));
                }
            } else if (detail.rendersSuccessfulCallOutcome) {
                line.append(" -> ok");
            }
            // A failed call keeps its duration at every level -- how long the agent waited before the
            // failure is part of reading the recovery. On a call that succeeded, duration is only
            // evidence when the prompt has room for it.
            long durationMs = JsonAttributeReaders.longAttribute(toolResult.getAttributes(), DURATION_MS_ATTRIBUTE);
            if (durationMs > 0 && (failed || detail.rendersSuccessfulCallOutcome)) {
                line.append(" (").append(formatDuration(durationMs)).append(')');
            }
            long resultBytes = JsonAttributeReaders.longAttribute(toolResult.getAttributes(), TOOL_RESULT_SIZE_BYTES_ATTRIBUTE);
            if (resultBytes >= LARGE_TOOL_RESULT_BYTES) {
                line.append(" [").append(formatBytes(resultBytes)).append(" result]");
            }
        }
        Long blockedMs = blockedMsByToolSpanId.get(span.getSpanId());
        if (blockedMs != null && blockedMs >= BLOCKED_ON_USER_CALL_FLOOR_MS) {
            line.append(" [blocked ").append(formatDuration(blockedMs)).append(" on user approval]");
        }
        line.append(dispatchSummary(span, callAttribution));
        return new TimelineEntry(
                    line.toString(), prefix + toolName, target, MUTATING_TOOL_NAMES.contains(toolName));
    }

    // claude_code.tool.blocked_on_user is a direct child of the claude_code.tool span it interrupted
    // (6,051 of 6,054 measured over 7 days) -- summed here once so the timeline line and the
    // trace-level observation below never need a second walk of the span list for the same fact.
    private static Map<String, Long> blockedMsByToolSpanId(List<Span> spans) {
        Map<String, Long> blockedMsByToolSpanId = new HashMap<>();
        for (Span span : spans) {
            if (BLOCKED_ON_USER_SPAN_NAME.equals(span.getName()) && span.getParentSpanId() != null) {
                blockedMsByToolSpanId.merge(span.getParentSpanId(), spanDurationMs(span), Long::sum);
            }
        }
        return blockedMsByToolSpanId;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_000L) {
            return bytes + "B";
        }
        if (bytes < 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1_000.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1_000_000.0);
    }

    // An Agent call's own line carries what the run it started cost, so "which subagent drove the
    // cost" is answerable at the call the reader is already looking at. A dispatch is keyed by the
    // span id of the Agent tool call that opened it, so this is a direct lookup rather than a scan.
    private static String dispatchSummary(Span span, CallAttribution callAttribution) {
        SubagentDispatch dispatch = callAttribution.dispatchByDispatchSpanId().get(span.getSpanId());
        if (dispatch == null || dispatch.modelCallCount == 0) {
            return "";
        }
        return "   [ran " + dispatch.label + ": " + dispatch.modelCallCount + " model calls, "
                + dispatch.toolCallCount + " tool calls, $" + String.format(COST_FORMAT, dispatch.costUsd) + "]";
    }

    /**
     * The target each call acted on, preferring whichever signal carries it <i>whole</i>.
     *
     * <p><b>A shell command is read off the span, ahead of the log.</b> The tool_result's
     * {@code tool_input} is the richer field for most tools (100% coverage, and it carries the
     * agent's own description), but Claude Code truncates long values inside it before export,
     * leaving a {@code …[N chars]} marker where the body was: measured over 30 days that hits
     * <b>531 of 6,826 Bash rows (7.8%)</b>, and 2,295 of 13,843 tool_results overall across seven
     * tools. The span's {@code full_command} is never truncated — <b>0 of 6,847</b> Bash spans carry
     * the marker, at up to 12,135 characters — so for commands the log is a lossy copy of something
     * the span already holds intact. This is the same reasoning revisit detection already uses to
     * take {@code file_path} off the span rather than parsing it out of {@code tool_input}.
     *
     * <p>Commands are then truncated <b>middle-out</b> rather than head-first, because a chained
     * shell command reads {@code setup && action && verify} and head truncation drops the verify.
     * That failure is one-directional: it cannot hide a missing check, it can only invent one. On
     * trace {@code 5d6c9ca05d7c6ce12e41a84980693f10} the review reported that the agent "reports
     * Shipped without confirming the status of the critical preceding tool calls" and wrote a
     * standing rule demanding a verification step — while the call's own command ended
     * {@code && git push -u origin … && git status}, past the 200-character cut. Note neither half
     * of this fixes that alone: preferring the span still lost the tail to head truncation, and
     * middle-out over the log's copy would only have centred on {@code …[1352 chars]}.
     *
     * <p>{@code inputLength} is the cap the timeline's current {@link TimelineDetail} allows, not a
     * constant: an overflowing prompt tightens it rather than dropping whole calls. The middle-out
     * rule above is what makes a tighter cap safe on a command — it narrows toward the middle from
     * both ends, so a shorter budget still shows the command's head and its verify tail.
     */
    private static String toolInputFor(Span span, LogRecord toolResult, int inputLength) {
        Object fullCommand = JsonAttributeReaders.attribute(span.getAttributes(), FULL_COMMAND_ATTRIBUTE);
        if (fullCommand != null) {
            return truncateMiddleOut(String.valueOf(fullCommand), inputLength);
        }
        if (toolResult != null) {
            Object toolInput = JsonAttributeReaders.attribute(toolResult.getAttributes(), TOOL_INPUT_ATTRIBUTE);
            if (toolInput != null) {
                return truncate(String.valueOf(toolInput), inputLength);
            }
        }
        Object filePath = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
        if (filePath != null) {
            return truncate(String.valueOf(filePath), inputLength);
        }
        return "";
    }

    // Everything here is on the llm_request span itself (100% coverage measured on this database)
    // except cost, which the request_id join supplies -- see the class javadoc for why span_costs
    // cannot answer per model call.
    private String renderLlmRequestLine(Span span, CallAttribution callAttribution, TimelineDetail detail) {
        Map<String, Object> attributes = span.getAttributes();
        StringBuilder line = new StringBuilder("- llm_request");
        Object model = JsonAttributeReaders.attribute(attributes, MODEL_ATTRIBUTE);
        if (model != null) {
            line.append(" model=").append(model);
        }
        // Model, duration and the error block below survive every detail level; the token/cost
        // breakdown does not. The outlier, cost-driver and cache-reuse findings are all computed in
        // buildObservations from the spans themselves and handed to the model pre-decided, so this
        // breakdown is corroboration a reader can check rather than the only place those numbers
        // exist -- which is what makes it the right thing to spend first when the prompt overflows.
        if (detail.rendersModelCallBreakdown && span.getEffort() != null) {
            line.append(" effort=").append(span.getEffort());
        }
        line.append(" duration=").append(formatDuration(spanDurationMs(span)));
        if (detail.rendersModelCallBreakdown) {
            line.append(" output_tokens=").append(JsonAttributeReaders.longAttribute(attributes, OUTPUT_TOKENS_ATTRIBUTE));
            line.append(" cache_read_tokens=").append(JsonAttributeReaders.longAttribute(
                    attributes, JsonAttributeReaders.CACHE_READ_TOKENS_ATTRIBUTE));
            Double costUsd = callAttribution.costUsdBySpanId().get(span.getSpanId());
            if (costUsd != null) {
                line.append(" cost=$").append(String.format(COST_FORMAT, costUsd));
            }
            Object stopReason = JsonAttributeReaders.attribute(attributes, STOP_REASON_ATTRIBUTE);
            if (stopReason != null) {
                line.append(" stop=").append(stopReason);
            }
        }
        Object attempt = JsonAttributeReaders.attribute(attributes, ATTEMPT_ATTRIBUTE);
        if (attempt != null && !FIRST_ATTEMPT.equals(String.valueOf(attempt))) {
            line.append(" retry_attempt=").append(attempt);
        }
        Object error = JsonAttributeReaders.attribute(attributes, ERROR_ATTRIBUTE);
        if (error != null) {
            // Rendered only alongside the error, where it is the fact that separates "the agent
            // asked for something impossible" from "the environment was out of credit". On a
            // successful call it is a constant 200 and pure prompt weight.
            Integer statusCode = statusCodeOf(attributes);
            if (statusCode != null) {
                line.append(" http_status=").append(statusCode);
            }
            line.append(" ERROR=").append(truncate(String.valueOf(error), ERROR_TRUNCATION_LENGTH));
        }
        return line.toString();
    }

    // Collapses runs of ADJACENT identical calls into one numbered group.
    private static List<TimelineGroup> collapseAdjacentRepeats(List<TimelineEntry> entries) {
        List<TimelineGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < entries.size()) {
            TimelineEntry current = entries.get(index);
            int repeatCount = 1;
            while (index + repeatCount < entries.size()
                    && entries.get(index + repeatCount).content().equals(current.content())) {
                repeatCount++;
            }
            groups.add(new TimelineGroup(
                    current.content(), current.shortLabel(), current.target(), current.mutatesTarget(),
                    index + 1, repeatCount));
            index += repeatCount;
        }
        return groups;
    }

    /**
     * Renders groups, folding NON-adjacent repeats too. Adjacency-only collapsing left the same
     * call re-issued at positions 3, 40 and 90 as three separate full-width lines scattered down
     * the timeline, where it reads as unrelated noise — the single most common redundancy shape
     * there is, and the one hardest for a reader to spot. The first occurrence now names where it
     * recurs, and each later occurrence shrinks to a back-reference (~35 characters against ~250),
     * which both surfaces the finding and is where most of the compaction on repeat-heavy traces
     * comes from. Ordering is preserved throughout — a later repeat keeps its own place in the
     * sequence rather than being deleted, since "what happened between" is exactly what the
     * error-recovery and ask-vs-delivered questions read.
     */
    private static List<TimelineLine> renderGroups(List<TimelineGroup> groups) {
        Map<String, List<TimelineGroup>> groupsByContent = new LinkedHashMap<>();
        Map<String, List<TimelineGroup>> groupsByTarget = new LinkedHashMap<>();
        Map<String, Integer> generationByTarget = new HashMap<>();
        Map<Integer, String> targetKeyByFirstCall = new HashMap<>();
        for (TimelineGroup group : groups) {
            groupsByContent.computeIfAbsent(group.content(), content -> new ArrayList<>()).add(group);
            if (group.target() != null) {
                String key = targetKey(group, generationByTarget.getOrDefault(group.target(), 0));
                targetKeyByFirstCall.put(group.firstCallNumber(), key);
                groupsByTarget.computeIfAbsent(key, target -> new ArrayList<>()).add(group);
                if (group.mutatesTarget()) {
                    generationByTarget.merge(group.target(), 1, Integer::sum);
                }
            }
        }

        List<TimelineLine> lines = new ArrayList<>();
        for (TimelineGroup group : groups) {
            List<TimelineGroup> sameContent = groupsByContent.get(group.content());
            TimelineGroup firstOccurrence = sameContent.get(0);
            String text = firstOccurrence.firstCallNumber() == group.firstCallNumber()
                    ? renderFirstOccurrence(group, sameContent,
                            revisitedCallNumbers(group, groupsByTarget, targetKeyByFirstCall))
                    : renderLaterOccurrence(group, firstOccurrence);
            lines.add(new TimelineLine(
                    text, group.firstCallNumber(), group.firstCallNumber() + group.callCount() - 1));
        }
        return lines;
    }

    // Keyed on tool AND file: the same file Read twice is redundant work, whereas Read-then-Edit on
    // one file is the normal way an edit happens and flagging it would be noise. The generation is
    // the same revisit window revisitedFiles keys on -- it increments whenever a mutating tool
    // touches this file -- so a call only ever pairs with calls that saw the same content. Without
    // it this marker kept the bug the observation had already been fixed for, and on trace
    // adae1753270dd3088520435ae7f8af94 the model wrote its finding from the MARKER rather than the
    // corrected observation, citing a post-edit re-read the rule it copied explicitly excuses.
    private static String targetKey(TimelineGroup group, int generation) {
        return group.shortLabel() + TARGET_KEY_SEPARATOR + group.target() + TARGET_KEY_SEPARATOR + generation;
    }

    /**
     * Call numbers where this group's tool touches this group's file again with a DIFFERENT input —
     * the same file re-read at another offset, re-searched with another pattern. Measured over
     * 13,488 calls this catches 2,332 repeat calls against exact-content matching's 517, because
     * byte-identical repeats are rare while revisiting one file is not. Deliberately annotation
     * only: the inputs genuinely differ (a second offset is not the first one), so unlike an exact
     * repeat these lines cannot collapse to a back-reference without losing what actually differed.
     * Groups whose content is identical are excluded — the exact-repeat marker already names those,
     * and saying it twice on one line reads as two separate findings.
     */
    private static List<String> revisitedCallNumbers(
            TimelineGroup group,
            Map<String, List<TimelineGroup>> groupsByTarget,
            Map<Integer, String> targetKeyByFirstCall) {
        if (group.target() == null) {
            return List.of();
        }
        List<TimelineGroup> sameTarget = groupsByTarget.get(targetKeyByFirstCall.get(group.firstCallNumber()));
        if (sameTarget == null || sameTarget.size() < 2
                || sameTarget.get(0).firstCallNumber() != group.firstCallNumber()) {
            return List.of();
        }
        List<String> callNumbers = new ArrayList<>();
        for (TimelineGroup laterGroup : sameTarget.subList(1, sameTarget.size())) {
            if (!laterGroup.content().equals(group.content())) {
                callNumbers.add(String.valueOf(laterGroup.firstCallNumber()));
            }
        }
        return callNumbers;
    }

    private static String renderFirstOccurrence(
            TimelineGroup group, List<TimelineGroup> sameContent, List<String> revisitedCallNumbers) {
        StringBuilder line = new StringBuilder(callNumberPrefix(group)).append(group.content());
        if (group.callCount() > 1) {
            line.append(" × ").append(group.callCount());
        }
        if (sameContent.size() > 1) {
            List<String> laterCallNumbers = new ArrayList<>();
            for (TimelineGroup laterGroup : sameContent.subList(1, sameContent.size())) {
                laterCallNumbers.add(String.valueOf(laterGroup.firstCallNumber()));
            }
            line.append("   [identical call repeats at ").append(abbreviateCallNumbers(laterCallNumbers)).append(']');
        }
        if (!revisitedCallNumbers.isEmpty()) {
            line.append("   [same file, different input, at ")
                    .append(abbreviateCallNumbers(revisitedCallNumbers)).append(']');
        }
        return line.toString();
    }

    // Real traces do this at a scale that would otherwise swamp the line: one measured trace read a
    // single file 69 times with 53 distinct inputs, which would put 50+ call numbers on one marker.
    // The count is what carries the finding once the list is that long, so cap the numbers and
    // report the remainder.
    private static String abbreviateCallNumbers(List<String> callNumbers) {
        if (callNumbers.size() <= MAX_LISTED_CALL_NUMBERS) {
            return String.join(", ", callNumbers);
        }
        return String.join(", ", callNumbers.subList(0, MAX_LISTED_CALL_NUMBERS))
                + " and " + (callNumbers.size() - MAX_LISTED_CALL_NUMBERS) + " more";
    }

    private static String renderLaterOccurrence(TimelineGroup group, TimelineGroup firstOccurrence) {
        StringBuilder line = new StringBuilder(callNumberPrefix(group)).append(group.shortLabel());
        if (group.callCount() > 1) {
            line.append(" × ").append(group.callCount());
        }
        return line.append(" (repeat of call ").append(firstOccurrence.firstCallNumber()).append(')').toString();
    }

    private static String callNumberPrefix(TimelineGroup group) {
        if (group.callCount() > 1) {
            return group.firstCallNumber() + "-" + (group.firstCallNumber() + group.callCount() - 1) + ". ";
        }
        return group.firstCallNumber() + ". ";
    }

    /**
     * Facts computed here rather than left for the model to find. Both small local models this
     * feature targets (llama3.1 8B, qwen2.5-coder 7B, measured on a real 60-call trace) reliably
     * MIS-CITE call numbers when asked to scan the timeline themselves — llama3.1 reported "calls 21
     * and 26 are identical TodoWrite" when 21 and 26 are model calls and the TodoWrites are 20 and
     * 25; qwen said "call 7 is identical to call 1" when call 1 is a model call. Both, however,
     * explained a fact correctly once it was handed to them (each nailed the outlier model call at
     * call 11 with its real duration). So detection belongs in code, where it is exact and testable,
     * and the model is left the job it can actually do: judging whether a verified fact matters.
     * Every line here is derived from the same span/log data the timeline is, so it cannot disagree
     * with it.
     *
     * <p><b>Observations whose remedy is mechanical carry their own {@code Suggested rule:} line</b>,
     * pre-written here and copied verbatim by the model into the answer's "Apply this" section. The
     * feature's whole point is a reader who can act on the review, and a 7B model asked to invent
     * project-instruction wording writes vague wording; asked instead to decide whether a
     * ready-made rule applies, it decides well. Only the mechanical shapes get one — a shell
     * command with a dedicated-tool equivalent, a file worked over more than once, and a request
     * begun on top of an already-loaded context — because those are the ones where the fix is
     * knowable without reading the trace. A failed call's fix depends on why it failed, and a
     * dominant subagent may have been exactly the right call to make, so those carry none.
     *
     * <p><b>A few observations say what the trace got RIGHT</b>, marked with the {@code Went well:}
     * prefix and surfaced by the template as its own answer section. The reason is not balance: it
     * is that this review's documented failure mode is <i>manufacturing</i> a fault on a trace that
     * has none — trace {@code 5d6c9ca05d7c6ce12e41a84980693f10}, a 31-second {@code /ship} with no
     * failures and no revisits, produced two separate fabricated findings before the slash-command
     * and preceding-turn fences went in — and an answer contract that only has somewhere to put
     * criticism will produce criticism. A positive gives that output somewhere true to go.
     *
     * <p>They are therefore gated at least as hard as the faults, for exactly the reason the
     * outlier model call is gated: praise is <i>easier</i> to fabricate than criticism, because
     * nothing constrains it, and an ungated "what went right" line fires on every trace and says
     * "the agent used Read appropriately". So there is no positive for healthy cache reuse (the
     * median trace already reuses 96.8%, and the overview says so) and none for a clean trace (that
     * is already the answer contract's one-sentence empty case). Only two shapes qualify, both
     * carrying information no other page or report gives the reader: a request that named its
     * target well enough that the agent went straight there, and a dominant subagent that was
     * dominant <i>because</i> it kept work out of an already-loaded main context.
     */
    private VerifiedObservations buildObservations(
            List<Span> spans,
            Map<String, LogRecord> toolResultsByUseId,
            CallAttribution callAttribution,
            boolean isContinuation,
            boolean answersPrecedingQuestion,
            String directedStartLine,
            Map<String, Long> blockedMsByToolSpanId,
            long traceDurationMs) {
        List<String> observations = new ArrayList<>();
        int callNumber = 0;
        int modelCallCount = 0;
        Map<ShellAntipattern, List<String>> shellAntipatternCalls = new LinkedHashMap<>();
        List<String> failedCalls = new ArrayList<>();
        List<UnrecoveredFailure> unrecoveredFailures = new ArrayList<>();
        Map<ModelCallFailure, List<String>> modelCallFailureCalls = new LinkedHashMap<>();
        List<String> longBlockedCalls = new ArrayList<>();
        long totalBlockedMs = 0L;
        long maxSingleBlockedMs = 0L;
        String longestBlockedCallNumber = null;

        for (Span span : spans) {
            boolean isToolCall = tuningProperties.getToolSpanName().equals(span.getName());
            boolean isModelCall = tuningProperties.getLlmRequestSpanName().equals(span.getName());
            if (!isToolCall && !isModelCall) {
                continue;
            }
            callNumber++;
            if (isModelCall) {
                modelCallCount++;
                ModelCallFailure modelCallFailure = modelCallFailureFor(span);
                if (modelCallFailure != null) {
                    modelCallFailureCalls.computeIfAbsent(modelCallFailure, key -> new ArrayList<>())
                            .add(callNumber + branchSuffix(span, callAttribution));
                }
                continue;
            }
            // Which branch a call sat on doesn't change whether it failed or whether a shell
            // command had a dedicated-tool equivalent -- both are true wherever they happened, so
            // these stay grouped across branches. It does change who has to act on it, so the
            // call number carries the subagent when it wasn't the main loop, and nothing extra
            // when it was (the common case stays as terse as it was).
            String callReference = callNumber + branchSuffix(span, callAttribution);
            ShellAntipattern shellAntipattern = shellAntipatternFor(span);
            if (shellAntipattern != null) {
                shellAntipatternCalls.computeIfAbsent(shellAntipattern, key -> new ArrayList<>())
                        .add(callReference);
            }
            if (isFailedToolCall(span, toolResultsByUseId)) {
                // The error text comes along rather than living only in the Errors section further
                // down. On adae1753270dd3088520435ae7f8af94 the sole failure rendered here as a bare
                // "40 (Bash)" and the review never mentioned it at all: a call number and a tool
                // name say that something failed but nothing about what, so there is no thread for
                // the recovery question to pull on without cross-referencing another section.
                String failureMessage = toolFailureMessage(span, toolResultsByUseId);
                failedCalls.add(callReference + " (" + toolNameOf(span) + ")"
                        + (failureMessage == null ? "" : ": " + failureMessage));
                unrecoveredFailures.add(
                        new UnrecoveredFailure(callNumber, callReference, toolNameOf(span), failureMessage));
            }
            Long blockedMs = blockedMsByToolSpanId.get(span.getSpanId());
            if (blockedMs != null) {
                totalBlockedMs += blockedMs;
                if (blockedMs > maxSingleBlockedMs) {
                    maxSingleBlockedMs = blockedMs;
                    longestBlockedCallNumber = callReference;
                }
                if (blockedMs >= BLOCKED_ON_USER_CALL_FLOOR_MS) {
                    longBlockedCalls.add(callReference);
                }
            }
        }

        for (Map.Entry<RevisitedTarget, List<String>> entry : revisitedFiles(spans, callAttribution).entrySet()) {
            RevisitedTarget target = entry.getKey();
            observations.add("- " + target.label() + " touched " + entry.getValue().size()
                    + " times, at call(s) " + abbreviateCallNumbers(entry.getValue()));
            observations.add(SUGGESTED_RULE_PREFIX + target.suggestedRule());
        }
        for (Map.Entry<ShellAntipattern, List<String>> entry : shellAntipatternCalls.entrySet()) {
            ShellAntipattern shellAntipattern = entry.getKey();
            observations.add("- Shell command used where a dedicated tool does the same thing: "
                    + shellAntipattern.label() + " — at call(s) " + abbreviateCallNumbers(entry.getValue()));
            observations.add(SUGGESTED_RULE_PREFIX + shellAntipattern.suggestedRule());
        }
        if (!failedCalls.isEmpty()) {
            observations.add("- Tool calls that failed: " + abbreviateCallNumbers(failedCalls));
        }
        for (Map.Entry<ModelCallFailure, List<String>> entry : modelCallFailureCalls.entrySet()) {
            ModelCallFailure modelCallFailure = entry.getKey();
            List<String> calls = entry.getValue();
            observations.add("- Model calls that failed: " + calls.size() + " of " + modelCallCount
                    + ", at call(s) " + abbreviateCallNumbers(calls) + " — " + modelCallFailure.describe());
            if (modelCallFailure.isEnvironmental()) {
                observations.add(NOT_A_FINDING_PREFIX + ENVIRONMENTAL_FAILURE_NOTE);
            }
        }
        String blockedOnUser = blockedOnUserObservation(
                longBlockedCalls, totalBlockedMs, maxSingleBlockedMs, longestBlockedCallNumber, traceDurationMs);
        // Null when one pause IS the waiting -- see singlePauseDominates, which now suppresses the
        // observation outright rather than marking it. That also gates PERMISSIONS_TARGET, so such a
        // trace is offered no observation, no pre-written rule and no file to put one in. The
        // per-call "[blocked Ns on user approval]" markers stay on the timeline, so the fact is
        // still visible where it belongs -- on the call it happened to.
        boolean permissionsRuleApplies = blockedOnUser != null;
        if (permissionsRuleApplies) {
            observations.add(blockedOnUser);
            observations.add(SUGGESTED_RULE_PREFIX
                    + "Pre-authorize or batch the permission prompts this kind of call needs so the agent "
                    + "isn't left waiting on user approval mid-run.");
        }
        observations.addAll(modelCallOutlierObservation(spans));
        VerifiedObservations costDrivers = costDriverObservations(callAttribution);
        observations.addAll(costDrivers.lines());
        observations.addAll(cacheReuseObservations(spans));
        observations.addAll(contextSizeObservations(callAttribution, isContinuation));

        // Listed last so the review reads the faults first and the section stays anchored on what
        // it is for; the template decides where each one lands in the answer, not this order.
        // Computed by the caller (build()), not here, because the wording-settled verdict has to be
        // known before the template renders section B -- see directedStartObservation's javadoc.
        if (directedStartLine != null) {
            observations.add(directedStartLine);
        }
        // Stated as a verified fact rather than left to the two prose fences that lost to "yes fix
        // it" -- see answersPrecedingQuestion. It is a positive for the same reason directedStartLine
        // is: it says the reader's wording did its job, and it gives the answer contract somewhere
        // true to put the request half instead of manufacturing a vagueness finding.
        if (answersPrecedingQuestion) {
            observations.add(RESOLVED_FOLLOW_UP_OBSERVATION);
        }
        return new VerifiedObservations(
                observations,
                costDrivers.hasPositives() || directedStartLine != null || answersPrecedingQuestion,
                permissionsRuleApplies,
                settledToolSwapFor(shellAntipatternCalls),
                unrecoveredFailures);
    }

    /**
     * The {@code Tool swap} line, decided here instead of asked of the second model call.
     *
     * <p><b>Why this one line is settled in code.</b> The swap is mechanical: the shell-antipattern
     * observation already knows the command and the tool that replaces it, so there is no judgment
     * left for a model to add — only a chance to lose the fact. On trace
     * {@code 9ab1feeebdd15a449bbc4c9983dcb79d} it lost it: the observation fired for `sed` at five
     * calls and carried its pre-written rule, the ready-made rule list repeated that rule to the
     * second call, and the answer still came back {@code Tool swap: None}. Nothing was wrong with
     * the second call's own contract — it is told to write a swap "only when the review actually
     * reported a tool being used where a dedicated one exists", and the first call's prose had
     * dropped the observation, so {@code None} followed correctly from what it was shown. The fix is
     * therefore not a sterner instruction to either call but removing the line from the model's
     * reach, the same move {@code answersPrecedingQuestion} and {@code wordingSettledReason} already
     * make for the wording line.
     *
     * <p><b>One line, so one antipattern.</b> The command with the most calls behind it wins, and
     * {@code shellAntipatternCalls}' insertion order (first appearance in the trace) breaks ties, so
     * the choice is deterministic and the winner is the one carrying the most evidence. The others
     * are not lost — each still has its own observation line and its own {@code Suggested rule:},
     * which is where a reader sees the full set.
     */
    private static String settledToolSwapFor(Map<ShellAntipattern, List<String>> shellAntipatternCalls) {
        Map.Entry<ShellAntipattern, List<String>> dominant = null;
        for (Map.Entry<ShellAntipattern, List<String>> entry : shellAntipatternCalls.entrySet()) {
            if (dominant == null || entry.getValue().size() > dominant.getValue().size()) {
                dominant = entry;
            }
        }
        return dominant == null ? null : dominant.getKey().toolSwapLine(dominant.getValue());
    }

    /**
     * Why {@code Better wording} may only be {@code None} on this trace — rendered into the prompt so
     * a small model is told the fact rather than asked to notice it, the same move
     * {@link #answersPrecedingQuestion} already makes for its own half of this gate.
     */
    private static String wordingSettledReason(
            boolean answersPrecedingQuestion, boolean requestWellAimed, boolean requestIsQuestion) {
        if (answersPrecedingQuestion) {
            return "It is the answer to the question the preceding message ends with, so \"unnamed target\" "
                    + "and \"ambiguity\" do not apply here: the target is named in that message.";
        }
        if (requestWellAimed) {
            return "It named its target explicitly and the agent's very first tool call went straight to it "
                    + "with no searching or exploring beforehand (see the positive observation above), so "
                    + "\"unnamed target\" and \"ambiguity\" do not apply here.";
        }
        if (requestIsQuestion) {
            return "It is a question, not an instruction: it asks for an answer this trace was run to "
                    + "produce, so it has no target to name and is not underspecified for being open. "
                    + "Whether the agent actually answered it belongs to section A, not to its wording.";
        }
        return "";
    }

    /**
     * Observation lines, whether any of them is a {@code Went well:} positive, whether the
     * approval-wait observation fired, and the code-composed {@code Tool swap} line when a shell
     * antipattern did.
     *
     * @param permissionsRuleApplies gates {@link #PERMISSIONS_TARGET} onto the rule-target list —
     *     true when the approval-wait observation fired AND the waiting was a run of prompts rather
     *     than {@link #singlePauseDominates one long pause}, since only the former has a remedy that
     *     file could carry. It is
     *     threaded out of {@code buildObservations} rather than read back off the rendered lines
     *     (the way {@code hasNotAFindingObservations} is) because it decides what goes into a closed
     *     <b>enum</b> the model's answer is validated against — a substring match on prose is a fine
     *     definition for a paragraph that explains a prefix, and much too loose for that.
     * @param settledToolSwap the {@code Tool swap} line composed from the shell-antipattern
     *     observation, or null when none fired — see {@link #settledToolSwapFor}. Threaded out for
     *     the same reason {@code permissionsRuleApplies} is: it becomes an answer field this
     *     application enforces, so it has to carry {@link ShellAntipattern}'s own {@code command}
     *     and {@code replacement} rather than a substring scraped back off a rendered line.
     * @param failedToolCalls every tool call this trace's own spans/logs show failed, threaded out
     *     for the identical reason {@code settledToolSwap} is: {@code TraceAnalysisService} enforces
     *     that each one is cited somewhere in the model's own "What went wrong" findings, injecting
     *     a fallback fault when it isn't — see {@code ensureFailedToolCallsReported}. Empty rather
     *     than null on a clean trace, matching {@code lines}' own empty-not-null convention.
     */
    private record VerifiedObservations(
            List<String> lines,
            boolean hasPositives,
            boolean permissionsRuleApplies,
            String settledToolSwap,
            List<UnrecoveredFailure> failedToolCalls) {}

    /**
     * The one request-quality positive: the agent's <b>first</b> tool call targeted something the
     * request itself names, so the trace spent nothing finding its target.
     *
     * <p>This is the honest inverse of section B's "unnamed target" fault — same evidence, opposite
     * sign — and it is the only thing in this whole feature that tells a reader their <i>wording</i>
     * worked. Nothing else does: no dashboard page and no section of the markdown report reports on
     * a request that was well aimed.
     *
     * <p>Three constraints keep it from becoming the ungated praise line this class exists to avoid.
     * It reads the <b>first tool call only</b> — a match on a later call proves nothing, since
     * whatever ran before it is exactly the searching this observation claims did not happen, so a
     * first call carrying neither {@link #FILE_PATH_ATTRIBUTE} nor {@link #FULL_COMMAND_ATTRIBUTE}
     * (a {@code Grep}/{@code Glob} pattern, a search) ends the check rather than continuing down the
     * trace. It matches the target's <b>last path segment</b>, because a reader names
     * {@code TraceService.java} while the span carries the repo-absolute path — and for a Bash call
     * that segment is normally the command's own trailing path argument ({@code ls}/{@code cat}/
     * {@code cd <path>}), which {@link #lastPathSegment} resolves the same way. And that segment must
     * clear {@link #MINIMUM_NAMED_TARGET_LENGTH}, so a short name cannot match inside an unrelated
     * word of the prompt.
     *
     * <p><b>Falling back to {@code full_command} is not a cosmetic widening.</b> On trace
     * {@code 73590130fdbec1b4f2c89217103fb3db} the request named an absolute directory path and the
     * very first call was {@code Bash ls -la <that path>} — as directed a start as this feature can
     * see — and the file-path-only check reported nothing, because {@code file_path} is a Read/Edit/
     * Write attribute and a Bash span never carries one (0% coverage, same as {@code shellAntipatternFor}
     * documents for {@code full_command}'s own 100%). The gate had conflated "carries no file_path"
     * with "was a search", which is only true for Read/Edit/Write tools; for Bash it just meant "was
     * a shell command", true of every {@code ls}/{@code cat}/{@code cd} arrival alongside every real
     * {@code grep}/{@code find} search.
     *
     * <p>{@code promptText} is null unless the trace carries a genuine human-written request — never
     * for a slash command, a {@code <task-notification>} envelope, or a subagent run — which is what
     * keeps this from crediting request wording that nobody wrote, the same trap
     * {@link #slashCommandName} and {@link #isMachineAuthoredPrompt} close for the fault half.
     */
    private String directedStartObservation(
            List<Span> spans, String promptText, CallAttribution callAttribution) {
        if (promptText == null || promptText.isBlank()) {
            return null;
        }
        String lowerCasePromptText = promptText.toLowerCase(Locale.ROOT);
        int callNumber = 0;
        for (Span span : spans) {
            boolean isToolCall = tuningProperties.getToolSpanName().equals(span.getName());
            if (!isToolCall && !tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                continue;
            }
            callNumber++;
            if (!isToolCall) {
                continue;
            }
            String target = directedStartTarget(span);
            if (target == null) {
                return null;
            }
            String fileName = lastPathSegment(target);
            if (fileName.length() < MINIMUM_NAMED_TARGET_LENGTH
                    || !lowerCasePromptText.contains(fileName.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return POSITIVE_PREFIX + "the request named its target and the agent went straight to it — the "
                    + "very first tool call of the trace (call " + callNumber + branchSuffix(span, callAttribution)
                    + ", " + toolNameOf(span) + " " + fileName + ") targeted exactly what the request names, "
                    + "with no searching or exploring beforehand";
        }
        return null;
    }

    // file_path covers Read/Edit/Write (44% span coverage per backend/CLAUDE.md); full_command
    // covers Bash (48%), whose own target is normally its trailing path argument. Neither present
    // means the call genuinely carries no addressable target -- a Grep/Glob pattern -- which is the
    // real "this was a search" case directedStartObservation must not credit.
    private static String directedStartTarget(Span span) {
        Object filePath = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
        if (filePath != null) {
            return String.valueOf(filePath);
        }
        Object fullCommand = JsonAttributeReaders.attribute(span.getAttributes(), FULL_COMMAND_ATTRIBUTE);
        return fullCommand == null ? null : String.valueOf(fullCommand);
    }

    // Identity for "this branch searched this file", the key focusedResolutionHolds tracks the most
    // recent SEARCH_TOOL_NAMES call against. Branch-scoped for the identical reason RevisitedTarget is
    // -- a subagent's own Read of a file says nothing about whether the MAIN loop's later Edit of a
    // same-named file in ITS OWN branch followed a look, since the two run in unrelated contexts.
    private record SearchedFileKey(String branchKey, String filePath) {}

    /**
     * Whether this trace's own execution proves an unnamed, non-question request was never actually a
     * problem — the code-side verdict {@code wordingAlreadySettled} does NOT read, on purpose (see the
     * end of this javadoc), that suppresses only the "Ambiguity that cost work" bullet in section B.
     *
     * <p><b>Trace {@code 24e1af7c5582529fe62dcacabe2d0a23} is what this fixes.</b> The request —
     * "when toggling to disabled the save button is disabled too" — names no file and asks no
     * question, so neither {@link #requestWellAimed} nor {@link #requestIsQuestion} could ever settle
     * it, and the review it actually produced faulted the wording anyway: <i>"leading the agent to
     * perform multiple, sequential, and overlapping tasks ... instead of focusing on a single,
     * actionable fix."</i> The trace itself is one coherent thread — {@code Bash grep} the bug,
     * {@code Read} the file, two {@code Edit}s fixing it and its own adjacent doc comment, two more
     * {@code Bash grep}s and a {@code Read} checking whether the same stale claim exists elsewhere,
     * one more {@code Edit} fixing the doc it found, and a {@code Bash} verification run — 9 calls,
     * every one of them either the fix or a documented consistency check the fix itself triggered, no
     * failure, no revisit, no call touching a file nothing before it had looked at. "Ambiguity that
     * cost work" asks whether the vagueness the reader left in the prompt is visible in the calls that
     * followed; on this shape it plainly is not, and the code can say so without asking a 7B model to
     * read nine calls and reach the same conclusion the outlier/blocked-on-user gates exist to avoid
     * asking it to reach unaided.
     *
     * <p><b>What is actually checked, and why each piece is there.</b> Every {@link
     * #MUTATING_TOOL_NAMES} call in the trace must be preceded, within the same branch and within
     * {@link #FOCUSED_RESOLUTION_CHAIN_WINDOW_CALLS} calls, by a {@link #SEARCH_TOOL_NAMES} call
     * against the identical file — the "unbroken chain" the trace above shows (grep → read → edit,
     * grep → grep → read → edit). No tool call anywhere in the trace may have failed ({@link
     * #isFailedToolCall}) or be a failed model call ({@link #modelCallFailureFor}) — a search that
     * needed a retry after an error is not evidence the agent knew where to go the first time. And
     * {@link #revisitedFiles} must report nothing at all — a re-read or re-search with a different
     * input is the "different intent" this verdict has to exclude, since a trace that circled back to
     * reconsider a file is not the shape being credited here.
     *
     * <p><b>Three constraints keep this conservative, measured against 30 days of this database's own
     * traces.</b> The population is every human-written, non-slash, non-notification request that
     * {@link #requestWellAimed} does NOT already clear (1,497 traces, using the identical
     * last-path-segment test that gate applies to the first tool call). Of those, 1,276 (85.2%) show
     * no failed tool call anywhere. Of THOSE, 597 made at least one file-addressed mutating call — a
     * trace with nothing to write cannot have this verdict say anything, so it is required to hold at
     * all ({@code hasMutatingFileCall} below). Of those 597, only 222 (37.2%) are also free of a
     * {@link #revisitedFiles} group. And of those 222, only 113 — 18.9% of the 597, 7.5% of the full
     * 1,497 — have every mutating call reachable within the 15-call window: a rate in the same range
     * {@link #requestWellAimed} (4.9%) and {@link #requestIsQuestion} (21.5%) already clear before
     * they are allowed to remove a fault from the model's reach. A trace that fails any one of these
     * checks is left exactly as it was — the ambiguity bullet stays open, the same fail-closed default
     * every other gate here uses.
     *
     * <p><b>What this deliberately does NOT prove, and why {@code Drift} does not read it.</b> This
     * verdict is entirely internal to the trace's own execution — it shows the agent never wandered,
     * never failed, never doubled back — and says nothing about whether the file it landed on was the
     * one the request was actually about. A tight, unbroken, revisit-free chain of searches and edits
     * on the WRONG file is exactly as "focused" by this test as one on the right file, and that
     * mismatch is precisely what {@code Drift} exists to catch — "going somewhere the exchange never
     * invited is a real fault whose fix is a standing rule, not a rewording" (see backend/CLAUDE.md's
     * own note on this). Gating {@code Drift} on this verdict would suppress the one fault the
     * ambiguity bullet was never responsible for catching in the first place. Nor does it suppress
     * "Unnamed target" — that bullet states a true fact about this trace's wording (it really did not
     * name a file, a page, or any other surface) and this verdict has nothing to say about whether
     * that fact is a fault, only about whether it cost anything; the two bullets ask different
     * questions and only one of them has an answer here.
     */
    private boolean focusedResolutionHolds(
            List<Span> spans, Map<String, LogRecord> toolResultsByUseId, CallAttribution callAttribution) {
        Map<SearchedFileKey, Integer> lastSearchCallNumberByFile = new HashMap<>();
        boolean hasMutatingFileCall = false;
        int callNumber = 0;
        for (Span span : spans) {
            boolean isToolCall = tuningProperties.getToolSpanName().equals(span.getName());
            boolean isModelCall = tuningProperties.getLlmRequestSpanName().equals(span.getName());
            if (!isToolCall && !isModelCall) {
                continue;
            }
            callNumber++;
            if (isModelCall) {
                if (modelCallFailureFor(span) != null) {
                    return false;
                }
                continue;
            }
            if (isFailedToolCall(span, toolResultsByUseId)) {
                return false;
            }
            Object fileAttribute = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
            if (fileAttribute == null) {
                continue;
            }
            String toolName = toolNameOf(span);
            SearchedFileKey fileKey = new SearchedFileKey(
                    callAttribution.branchKeyOf(span.getSpanId()), String.valueOf(fileAttribute));
            if (SEARCH_TOOL_NAMES.contains(toolName)) {
                lastSearchCallNumberByFile.put(fileKey, callNumber);
                continue;
            }
            if (!MUTATING_TOOL_NAMES.contains(toolName)) {
                continue;
            }
            hasMutatingFileCall = true;
            Integer lastSearchCallNumber = lastSearchCallNumberByFile.get(fileKey);
            if (lastSearchCallNumber == null
                    || callNumber - lastSearchCallNumber > FOCUSED_RESOLUTION_CHAIN_WINDOW_CALLS) {
                return false;
            }
        }
        return hasMutatingFileCall && revisitedFiles(spans, callAttribution).isEmpty();
    }

    /**
     * What drove this trace's spend, when one line item actually did. Both shapes are gated for the
     * same reason the outlier model call is: every trace that dispatched a subagent spent something
     * on it, and every trace has a most expensive call, so an ungated line reports a tautology and
     * invites the model to hedge about it.
     *
     * <p><b>A dominant subagent gets a {@code Went well:} qualifier when the main loop's own context
     * was already large.</b> The template has always said that a dispatch keeping a long search out
     * of the main context is money well spent, but only as a hedge attached to what still reads as a
     * criticism — a big number with a caveat. When the main loop was carrying
     * {@link #LARGE_STARTING_CONTEXT_TOKENS} or more, that caveat is a verified fact rather than a
     * possibility, and stating it as one is what lets the answer report the dispatch as the right
     * call instead of a cost to apologise for. The qualifier rides on the observation it qualifies
     * rather than becoming a second line, so one dispatch can never be reported twice.
     */
    private static VerifiedObservations costDriverObservations(CallAttribution callAttribution) {
        List<String> observations = new ArrayList<>();
        boolean hasPositives = false;
        double measuredCostUsd = callAttribution.measuredCostUsd();
        if (measuredCostUsd <= 0) {
            return new VerifiedObservations(observations, false, false, null, List.of());
        }

        long peakMainLoopContextTokens = maximum(callAttribution.mainLoopContextTokens());
        for (SubagentDispatch dispatch : callAttribution.dispatches()) {
            double share = shareOf(dispatch.costUsd, measuredCostUsd);
            if (share >= DOMINANT_SUBAGENT_COST_SHARE) {
                observations.add("- Most of this trace's model spend went to one subagent: "
                        + dispatch.label() + " cost $" + String.format(COST_FORMAT, dispatch.costUsd)
                        + " of the $" + String.format(COST_FORMAT, measuredCostUsd)
                        + " its model calls account for ("
                        + String.format(PERCENT_FORMAT, share * PERCENT_SCALE) + "%), across "
                        + dispatch.modelCallCount + " model calls it made on its own");
                if (peakMainLoopContextTokens >= LARGE_STARTING_CONTEXT_TOKENS) {
                    observations.add(POSITIVE_NOTE_PREFIX + "the main loop was already carrying "
                            + peakMainLoopContextTokens + " prompt tokens at its largest, so those "
                            + dispatch.modelCallCount + " calls ran in the subagent's own fresh context "
                            + "instead of being added to that one and re-sent on every call afterwards. "
                            + "The dominant share here is the dispatch doing its job, not a fault.");
                    hasPositives = true;
                }
            }
        }

        int modelCallCount = callAttribution.mainLoopModelCallCount()
                + callAttribution.auxiliaryModelCallCount()
                + subagentModelCallCount(callAttribution);
        double costliestShare = shareOf(callAttribution.costliestCallCostUsd(), measuredCostUsd);
        if (modelCallCount >= MINIMUM_MODEL_CALLS_FOR_COST_CONCENTRATION
                && costliestShare >= DOMINANT_CALL_COST_SHARE) {
            observations.add("- One model call carried the cost: call " + callAttribution.costliestCallNumber()
                    + " cost $" + String.format(COST_FORMAT, callAttribution.costliestCallCostUsd())
                    + ", " + String.format(PERCENT_FORMAT, costliestShare * PERCENT_SCALE)
                    + "% of the $" + String.format(COST_FORMAT, measuredCostUsd)
                    + " this trace's " + modelCallCount + " model calls account for");
        }
        return new VerifiedObservations(observations, hasPositives, false, null, List.of());
    }

    private static int subagentModelCallCount(CallAttribution callAttribution) {
        int modelCallCount = 0;
        for (SubagentDispatch dispatch : callAttribution.dispatches()) {
            modelCallCount += dispatch.modelCallCount;
        }
        return modelCallCount;
    }

    /**
     * Cache reuse, reported only when it was genuinely bad. Cache reads are billed at a tenth of
     * fresh input, so a trace whose prompt tokens were mostly re-reads is cheap by construction and
     * saying so is not a finding — measured here, the median trace already reuses 96.8%. What is
     * worth a reader's attention is the opposite case: a long conversation that kept re-writing its
     * cache instead of reading it, which is what a low ratio across many calls means.
     */
    private List<String> cacheReuseObservations(List<Span> spans) {
        long cacheReadTokens = 0L;
        long promptTokens = 0L;
        int modelCallCount = 0;
        for (Span span : spans) {
            if (!tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                continue;
            }
            modelCallCount++;
            cacheReadTokens += JsonAttributeReaders.longAttribute(span.getAttributes(), JsonAttributeReaders.CACHE_READ_TOKENS_ATTRIBUTE);
            promptTokens += JsonAttributeReaders.promptTokensOf(span);
        }
        if (modelCallCount < MINIMUM_MODEL_CALLS_FOR_CACHE_JUDGMENT || promptTokens <= 0) {
            return List.of();
        }
        double cacheReadShare = shareOf(cacheReadTokens, promptTokens);
        if (cacheReadShare >= POOR_CACHE_REUSE_RATIO) {
            return List.of();
        }
        return List.of("- Prompt cache was barely reused: only "
                + String.format(PERCENT_FORMAT, cacheReadShare * PERCENT_SCALE) + "% of this trace's "
                + promptTokens + " prompt tokens were cache reads, against a median of 96.8% for traces "
                + "in this database — the rest was paid for at full input price across "
                + modelCallCount + " model calls");
    }

    /**
     * How loaded the main loop's context was, at the two moments that decide whether this request
     * belonged in this session at all: when it started, and at its largest.
     *
     * <p>A request that opens on 300k tokens of unrelated history pays for that history on every
     * single call it then makes, and the fix is not a better prompt — it is a new session. That is
     * why the starting-context line is one of only three observations to carry a pre-written rule.
     *
     * <p><b>The starting-context half is suppressed for a continuation trace</b>, because its rule
     * cannot be followed there. A {@code <task-notification>} trace exists <i>because</i> work
     * started earlier in this session finished; it is not "a task unrelated to the one before it",
     * it is the same task resumed, and there is no fresh session in which to have handled it. On
     * trace {@code 1635329e1e7db7f934b007d90aba7d61} — a 6-span wake-up that read a test report and
     * reported the result — the observation fired at 204,326 tokens and the answer copied the rule
     * out verbatim, telling the reader to start unrelated work in a fresh session. Measured over 30
     * days the line fires on 14 of 96 notification traces (14.6%), against 112 of 685 (16.4%) for
     * ordinary ones, so this is not about how often it fires but about the remedy being impossible:
     * a false pre-written rule is the most confident way for this review to be wrong. Their first
     * call is genuinely bigger (p50 124k against 78k) — that is what a continuation looks like, not
     * a fault. The <b>growth</b> half is deliberately kept: a continuation can still balloon while
     * it runs, and that line carries no pre-written rule for the model to copy.
     */
    private static List<String> contextSizeObservations(
            CallAttribution callAttribution, boolean isContinuation) {
        List<Long> mainLoopContextTokens = callAttribution.mainLoopContextTokens();
        if (mainLoopContextTokens.isEmpty()) {
            return List.of();
        }
        long startingContextTokens = mainLoopContextTokens.get(0);
        long peakContextTokens = maximum(mainLoopContextTokens);

        List<String> observations = new ArrayList<>();
        if (startingContextTokens >= LARGE_STARTING_CONTEXT_TOKENS && !isContinuation) {
            observations.add("- This request began on an already-loaded context: its first model call sent "
                    + startingContextTokens + " prompt tokens, before doing any work of its own "
                    + "(three quarters of traces here never reach that size at all)");
            observations.add(SUGGESTED_RULE_PREFIX + "Start a task that is unrelated to the one before it in a "
                    + "fresh session rather than continuing an existing one — everything already in the "
                    + "context is re-sent on every call the new task makes.");
        }
        if (peakContextTokens >= LARGE_PEAK_CONTEXT_TOKENS
                && peakContextTokens >= startingContextTokens * LARGE_CONTEXT_GROWTH_MULTIPLE) {
            observations.add("- The context grew steeply while this request ran: from " + startingContextTokens
                    + " prompt tokens on the first model call to " + peakContextTokens + " at its largest, across "
                    + mainLoopContextTokens.size() + " main-loop model calls");
        }
        return observations;
    }

    /**
     * Named calls the agent sat waiting on user approval for, reported only when the wait pattern
     * is genuinely worth a standing rule rather than a routine pause.
     *
     * <p>Measured over 30 days: a {@code claude_code.tool.blocked_on_user} span has a p50 wait of
     * 0.0s and a p90 of 1.6s, so most waits are trivial and reporting them all would be the same
     * non-finding {@link #modelCallOutlierObservation} exists to avoid. This fires only when the
     * trace has at least one wait past {@link #BLOCKED_ON_USER_CALL_FLOOR_MS} (10s) AND either that
     * single wait or the trace's total blocked time clears {@link #BLOCKED_ON_USER_TRACE_SHARE_FLOOR}
     * (25%) of the trace's own duration, OR a single wait clears 30s outright — the population
     * measured at 132 and 160 respectively out of 677 traces that ever blocked at all, not the
     * routine short pause every trace has some of.
     */
    private static String blockedOnUserObservation(
            List<String> longBlockedCalls, long totalBlockedMs, long maxSingleBlockedMs,
            String longestBlockedCallNumber, long traceDurationMs) {
        if (longBlockedCalls.isEmpty() || traceDurationMs <= 0) {
            return null;
        }
        double blockedShare = shareOf(totalBlockedMs, traceDurationMs);
        if (blockedShare < BLOCKED_ON_USER_TRACE_SHARE_FLOOR
                && maxSingleBlockedMs < BLOCKED_ON_USER_SINGLE_WAIT_FLOOR_MS) {
            return null;
        }
        // One pause IS the waiting: not a pattern, not the agent's, and no observation at all. The
        // reader still sees it as a "[blocked ... on user approval]" marker on the call itself.
        if (singlePauseDominates(maxSingleBlockedMs, totalBlockedMs)) {
            return null;
        }
        // Naming the longest wait and the call it sat on is what stops the list of call numbers
        // reading as an even spread. Given only the total and six call numbers, phi4 wrote "a long
        // pause of 23m 23s at calls 4, 6, 30, 46, 48, 50" -- attributing one pause to all six.
        return "- This trace spent " + formatDuration(totalBlockedMs) + " ("
                + String.format(PERCENT_FORMAT, blockedShare * PERCENT_SCALE)
                + "% of its duration) waiting on user approval, including a wait of at least "
                + formatDuration(BLOCKED_ON_USER_CALL_FLOOR_MS) + " at call(s) "
                + abbreviateCallNumbers(longBlockedCalls)
                + "; the longest single wait was " + formatDuration(maxSingleBlockedMs)
                + " at call " + longestBlockedCallNumber;
    }

    /**
     * Whether one pause accounts for essentially all of a trace's blocked time — see
     * {@link #BLOCKED_ON_USER_SINGLE_PAUSE_DOMINANCE} for the measurements behind the floor.
     *
     * <p>This decides whether the approval wait is the agent's to answer for at all, so it gates
     * three things together: the pre-written {@code Suggested rule:} about batching prompts, the
     * {@code Not a finding:} note that replaces it, and {@link #PERMISSIONS_TARGET} — a trace whose
     * waiting was one human pause is offered no ready-made rule and no permissions file to put one
     * in, rather than being talked out of a rule it has already been handed.
     */
    private static boolean singlePauseDominates(long maxSingleBlockedMs, long totalBlockedMs) {
        return totalBlockedMs > 0
                && (double) maxSingleBlockedMs / totalBlockedMs >= BLOCKED_ON_USER_SINGLE_PAUSE_DOMINANCE;
    }

    /**
     * The longest model call, reported <b>only when it is a genuine outlier against this trace's own
     * median</b> — and with the output tokens that make it judgeable.
     *
     * <p>An earlier revision emitted a bare "Longest model call: call N at M ms" on every trace.
     * Every trace has a longest model call, so that line fired unconditionally with nothing to
     * compare against, and what came back was the exact non-finding this review exists to avoid:
     * <i>"call 130 at 52333 ms was the longest model call ... it's unclear whether this was
     * necessary"</i>. Duration alone cannot settle it. Output tokens can: a long call that produced
     * a lot of output was writing (earned), a long call that produced little was deciding (the
     * request left it too much to decide at once), which is a fix the reader can actually apply. So
     * the line carries the output tokens, the median it is being measured against, and how many
     * calls that median covers — and is dropped entirely on traces where no call stands out, rather
     * than manufacturing a finding.
     *
     * <p><b>Which of those two it is, is now decided here rather than by the model.</b> Handing over
     * the numbers and a two-clause rule in the template ("many means writing, which is earned and
     * not worth reporting; few means deciding") is precisely the shape {@link #buildObservations}
     * exists to refuse, and it failed the same way everything else left to prose has: on trace
     * {@code adae1753270dd3088520435ae7f8af94} the outlier produced <b>4,676 output tokens — the
     * most of any call in that trace</b>, 1.8x the next highest, and the review reported it as
     * <i>"excessive reasoning for the task at hand"</i>, reading the rule exactly backwards and
     * spending a bullet on the trace's single largest piece of writing.
     *
     * <p><b>The discriminator is the rate, not the volume</b>, because volume cannot separate them:
     * measured over 30 days, the slowest call's output against its trace's median output is p10
     * 3.37x — the slowest call is nearly always also a big-output call, so "produced a lot" is true
     * of essentially every outlier and decides nothing. Milliseconds per output token does separate
     * them, and sharply: against its own trace's median rate the outlier sits at p50 0.88, p75 1.00,
     * p90 1.05 — normally as efficient per token as the typical call, just longer because it wrote
     * more — while 10 of 351 (2.8%) sit at or past {@link #MODEL_CALL_DECIDING_RATE_MULTIPLE} with
     * nothing between 1.2x and 2.0x. Below the floor the line carries a {@code Not a finding:} note;
     * at or above it, it stays an ordinary fault. <b>Zero output tokens is the extreme deciding
     * case</b>, not an unmeasurable one: a long call that wrote nothing spent all of its time
     * deciding, so it is a finding rather than being excused for having no rate.
     */
    private List<String> modelCallOutlierObservation(List<Span> spans) {
        List<Long> modelCallDurationsMs = new ArrayList<>();
        List<Double> modelCallRatesMsPerToken = new ArrayList<>();
        int callNumber = 0;
        int slowestCallNumber = 0;
        long slowestDurationMs = 0;
        long slowestOutputTokens = 0;
        String slowestModel = null;
        String slowestEffort = null;
        for (Span span : spans) {
            boolean isModelCall = tuningProperties.getLlmRequestSpanName().equals(span.getName());
            if (!isModelCall && !tuningProperties.getToolSpanName().equals(span.getName())) {
                continue;
            }
            callNumber++;
            if (!isModelCall) {
                continue;
            }
            long durationMs = spanDurationMs(span);
            long outputTokens = JsonAttributeReaders.longAttribute(span.getAttributes(), OUTPUT_TOKENS_ATTRIBUTE);
            modelCallDurationsMs.add(durationMs);
            if (outputTokens > 0) {
                modelCallRatesMsPerToken.add((double) durationMs / outputTokens);
            }
            if (durationMs > slowestDurationMs) {
                slowestDurationMs = durationMs;
                slowestCallNumber = callNumber;
                slowestOutputTokens = outputTokens;
                Object model = JsonAttributeReaders.attribute(span.getAttributes(), MODEL_ATTRIBUTE);
                slowestModel = model == null ? null : String.valueOf(model);
                slowestEffort = span.getEffort();
            }
        }

        if (modelCallDurationsMs.size() < MINIMUM_MODEL_CALLS_FOR_OUTLIER
                || slowestDurationMs < MODEL_CALL_OUTLIER_FLOOR_MS) {
            return List.of();
        }
        long medianDurationMs = median(modelCallDurationsMs);
        if (medianDurationMs <= 0 || slowestDurationMs < medianDurationMs * MODEL_CALL_OUTLIER_MEDIAN_MULTIPLE) {
            return List.of();
        }
        // Named so the reader can act on it directly -- "use a different model" or "increase effort"
        // is only a usable fix once the call names which model and effort actually produced the
        // duration being judged. Both are absent-safe: effort is unrecorded on ~2% of calls (see the
        // span_efforts note in backend/CLAUDE.md) and a model can in principle be missing too.
        String line = "- Outlier model call: call " + slowestCallNumber + " took " + formatDuration(slowestDurationMs)
                + " and produced " + slowestOutputTokens + " output tokens on "
                + (slowestModel == null ? "an unrecorded model" : slowestModel)
                + (slowestEffort == null ? "" : " at " + slowestEffort + " effort") + " — "
                + String.format(PERCENT_FORMAT, (double) slowestDurationMs / medianDurationMs)
                + "x the median model call in this trace (" + formatDuration(medianDurationMs) + " across "
                + modelCallDurationsMs.size() + " model calls)";
        // Dropped, not annotated. An earlier revision emitted the line with a "Not a finding:" note
        // saying its length was earned, and phi4 opened a fault about it anyway on two consecutive
        // runs of adae1753270dd3088520435ae7f8af94 -- the same lesson the schema gate taught: what
        // the model cannot see, it cannot misreport, while what it can see it will argue with. This
        // is also what this method already does when no call stands out at all, so the excused case
        // now behaves the same way as the absent one rather than being a third state.
        if (spentItsTimeWriting(slowestDurationMs, slowestOutputTokens, modelCallRatesMsPerToken)) {
            return List.of();
        }
        return List.of(line);
    }

    /**
     * Whether the outlier's length is accounted for by what it wrote — see
     * {@link #modelCallOutlierObservation} for the measurements behind the floor.
     *
     * <p>A call that produced no output at all wrote nothing to explain its duration and is never
     * excused here. A trace with no usable median rate (every model call produced zero output) is
     * left as an ordinary finding rather than excused on missing evidence: this decides whether to
     * suppress a fault, so "cannot tell" must not be the branch that suppresses one.
     */
    private static boolean spentItsTimeWriting(
            long durationMs, long outputTokens, List<Double> modelCallRatesMsPerToken) {
        if (outputTokens <= 0 || modelCallRatesMsPerToken.isEmpty()) {
            return false;
        }
        double medianRate = medianRate(modelCallRatesMsPerToken);
        if (medianRate <= 0) {
            return false;
        }
        return (double) durationMs / outputTokens < medianRate * MODEL_CALL_DECIDING_RATE_MULTIPLE;
    }

    // Lower median, matching median(List<Long>) -- same reasoning: this is a comparison baseline for
    // one call, not a statistic anyone reads on its own.
    private static double medianRate(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get((sorted.size() - 1) / 2);
    }

    // Lower median on an even count -- the figure is a comparison baseline for one outlier, not a
    // statistic anyone reads on its own, so interpolating between the two middle values would add
    // arithmetic without changing any judgment it feeds.
    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get((sorted.size() - 1) / 2);
    }

    private static long maximum(List<Long> values) {
        long maximum = 0L;
        for (Long value : values) {
            maximum = Math.max(maximum, value);
        }
        return maximum;
    }

    /**
     * One tool repeatedly worked over one file, with the paste-ready rule that shape implies.
     *
     * <p>{@code fileName} carries the <b>full path</b> despite its name — it is the record's
     * equality/grouping key, and grouping on the bare basename is exactly the bug fixed on trace
     * {@code df8c757de3bfbfe9c1da2b29f431a192} (see {@link #fileUsageLine}'s javadoc for the full
     * evidence). {@link #label} renders it through {@link #disambiguatedFileLabel} rather than the
     * full path, for the same "cheap enough to disambiguate, short enough to read" reasoning.
     */
    private record RevisitedTarget(
            String branchKey, String branchLabel, String toolName, String fileName, int generation) {

        // branchLabel is a pure function of branchKey, so carrying both keeps record equality
        // consistent while saving a second lookup at render time.
        String label() {
            String target = toolName + " " + disambiguatedFileLabel(fileName);
            return branchLabel == null ? target : target + ", inside subagent " + branchLabel + ",";
        }

        // Read is the tool this shape is normally about (a file re-read at another offset), and it
        // has a fix specific enough to be worth stating: ask for the length you need the first
        // time. Every other tool gets the general form rather than a rule that would read as
        // nonsense on, say, a repeated Grep.
        String suggestedRule() {
            if (READ_TOOL_NAME.equals(toolName)) {
                return "Read a file once at the length you actually need — do not come back for "
                        + "another slice of a file you have already read unless an edit changed it.";
            }
            return "Before running " + toolName + " against something you have already run it "
                    + "against, re-read the result you already have instead of running it again.";
        }
    }

    /**
     * A shell command with a dedicated-tool equivalent, and the paste-ready rule that says so.
     *
     * <p><b>The rule says "default to", not "never run".</b> One template serves every entry of
     * {@code bashAntipatternReplacements}, and the entries are not alike: {@code cat -> Read} and
     * {@code echo -> Write} are total substitutions, while {@code find -> Glob} and
     * {@code sed -> Edit} cover only the common case and leave real residue ({@code find -exec} /
     * {@code -delete} / {@code -mtime}, an in-place stream edit). An absolute rule is therefore
     * false for half the map, and this one is copied verbatim into the reader's instruction file —
     * where a rule that has to be broken to get work done erodes the accurate rules around it. A
     * reader who wants the absolute form for a specific command can write it; the review should not
     * overclaim on their behalf. If a per-command wording is ever wanted, widen the property to
     * carry it rather than hardening this template.
     */
    private record ShellAntipattern(String command, String replacement) {

        String label() {
            return "`" + command + "` -> " + replacement;
        }

        String suggestedRule() {
            return "Default to " + replacement + " instead of `" + command + "` in Bash.";
        }

        /**
         * The {@code Tool swap} line for this antipattern, composed in code rather than asked of the
         * model — see {@link #settledToolSwapFor}. Worded off the same two fields
         * {@link #suggestedRule} uses so the rule and the swap cannot describe different tools, and
         * it names the calls because every other fault line in this prompt does.
         *
         * <p>Deliberately does not fill the template's "for &lt;what it was doing&gt;" clause. What
         * the command was doing is not on the span — only its first token is — and a purpose
         * invented to fill a slot is the exact failure this whole class is built against.
         */
        String toolSwapLine(List<String> callNumbers) {
            return "use " + replacement + " instead of `" + command + "` in Bash — at call(s) "
                    + abbreviateCallNumbers(callNumbers);
        }
    }

    /**
     * Files one tool touched more than once <b>on the same execution path</b>, keyed the same way the
     * inline revisit marker is ({@code (tool, file_path)}, so Read-then-Edit is not counted) plus the
     * branch the calls ran on. The inline marker already flags these on the line itself, but a small
     * model has to scan for those; pulling the same fact up here means it never has to.
     *
     * <p><b>The grouping key is the full path, not the last path segment — this is a correctness fix,
     * not a rendering one.</b> An earlier revision keyed {@link RevisitedTarget} on the bare basename,
     * which silently merges two different files whenever they share one, and this repository
     * guarantees that collision by construction (one {@code CLAUDE.md} per {@code pages/<Name>Page/}
     * folder). On trace {@code df8c757de3bfbfe9c1da2b29f431a192} that merge produced a false
     * "re-read {@code CLAUDE.md} at calls 52,53,55,190,196,204" observation carrying a {@code
     * Suggested rule:} line the answer contract tells the model to copy verbatim — one of those six
     * call numbers (52) actually names {@code frontend/CLAUDE.md}, a file read exactly once, while the
     * other five are the genuinely repeated {@code TraceDetailPage/CLAUDE.md}. Grouping on the full
     * path fixes the fact; the rendered label still shows only {@link #disambiguatedFileLabel}'s last
     * two segments, which is what keeps {@link RevisitedTarget#label} short without reintroducing the
     * collision it was just fixed to avoid.
     *
     * <p><b>A write to the file ends its window, and both halves of that are correctness fixes.</b>
     * The key carries a {@code generation} that increments each time a {@link #MUTATING_TOOL_NAMES}
     * call touches that path, so calls only group with other calls that saw the <i>same</i> content.
     * Without it the observation contradicted the very rule it carries. On trace
     * {@code adae1753270dd3088520435ae7f8af94} it reported "`LogRecordRepository.java` touched 3
     * times, at calls 16, 18, 26" while its own pre-written remedy reads "do not come back for
     * another slice of a file you have already read <i>unless an edit changed it</i>" — and call 26
     * followed the Edit at call 20 on exactly that file, so the one call the rule excuses was being
     * counted as evidence for it. The second half is that a mutating tool now ends its own window
     * too, which is what stops two sequential {@code Edit}s to one file — how an edit normally
     * happens — reading as redundancy. That was not a marginal case: measured over 30 days, 676 of
     * 1,203 revisit groups (56%) were on a mutating tool, and applying the window leaves <b>zero</b>
     * of them, taking the whole population from 1,203 groups over 297 traces to 429 over 165. Every
     * one of those 676 carried a {@code Suggested rule:} line the answer contract tells the model to
     * copy verbatim into the reader's own instructions — and the general (non-Read) form of that
     * rule reads as nonsense for an {@code Edit}: "re-read the result you already have instead of
     * running it again" is advice not to make the second edit.
     *
     * <p><b>The branch is part of the key, and that is a correctness fix rather than a refinement.</b>
     * Grouping across branches reports the main loop reading a file and a subagent later reading the
     * same file as one "touched 3 times" finding — but a subagent runs in its own fresh context, and
     * keeping a long search out of the main context is the whole reason to dispatch one. Measured over
     * 90 days, 277 of 1,812 revisit groups (15.3%, across 59 traces) span more than one branch, so
     * this was firing on real traces. It matters more than the raw rate suggests: this observation
     * carries a pre-written {@code Suggested rule:} line that the answer contract tells the model to
     * copy verbatim, so a false grouping here became a false standing rule in the reader's
     * CLAUDE.md — the most confident possible way for the review to be wrong.
     *
     * <p>Two <i>different</i> subagents each reading the same file is likewise not counted: it is a
     * question about how the dispatches were scoped, not about re-reading, and the rule this
     * observation carries would be the wrong fix for it.
     */
    private Map<RevisitedTarget, List<String>> revisitedFiles(
            List<Span> spans, CallAttribution callAttribution) {
        Map<RevisitedTarget, List<String>> callNumbersByFile = new LinkedHashMap<>();
        Map<String, Integer> generationByFile = new HashMap<>();
        int callNumber = 0;
        for (Span span : spans) {
            boolean isToolCall = tuningProperties.getToolSpanName().equals(span.getName());
            if (!isToolCall && !tuningProperties.getLlmRequestSpanName().equals(span.getName())) {
                continue;
            }
            callNumber++;
            Object filePath = isToolCall ? JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE) : null;
            if (filePath == null) {
                continue;
            }
            String toolName = toolNameOf(span);
            String path = String.valueOf(filePath);
            RevisitedTarget target = new RevisitedTarget(
                    callAttribution.branchKeyOf(span.getSpanId()),
                    callAttribution.branchLabelOf(span.getSpanId()),
                    toolName,
                    path,
                    generationByFile.getOrDefault(path, 0));
            callNumbersByFile.computeIfAbsent(target, file -> new ArrayList<>()).add(String.valueOf(callNumber));
            if (MUTATING_TOOL_NAMES.contains(toolName)) {
                generationByFile.merge(path, 1, Integer::sum);
            }
        }
        callNumbersByFile.values().removeIf(callNumbers -> callNumbers.size() < 2);
        return callNumbersByFile;
    }

    // " in subagent Explore (dispatched at call 12)" for a call inside a dispatch, empty for the
    // main loop -- so a main-loop call number reads exactly as it did before branches existed.
    private static String branchSuffix(Span span, CallAttribution callAttribution) {
        String branchLabel = callAttribution.branchLabelOf(span.getSpanId());
        return branchLabel == null ? "" : " in subagent " + branchLabel;
    }

    private static String lastPathSegment(String filePath) {
        int lastSeparator = filePath.lastIndexOf('/');
        return lastSeparator < 0 ? filePath : filePath.substring(lastSeparator + 1);
    }

    /**
     * The last <b>two</b> path segments — immediate parent directory plus basename, e.g.
     * {@code TraceDetailPage/CLAUDE.md} rather than the bare {@code CLAUDE.md} {@link
     * #lastPathSegment} would give. Used everywhere a file-touching finding needs to name its file
     * cheaply but unambiguously: {@link #fileUsageLine} and {@link #revisitedFiles} both grew this
     * after trace {@code df8c757de3bfbfe9c1da2b29f431a192} showed the bare basename silently merging
     * {@code frontend/CLAUDE.md} and {@code frontend/src/pages/TraceDetailPage/CLAUDE.md} into one
     * false fact.
     *
     * <p>Two segments were chosen over the full repo-absolute path (this file's own reasoning
     * elsewhere: ~80 characters per line of pure path noise the reader does not need to resolve, only
     * recognise) and over a two-pass "only widen the names that actually collide" scheme (cheap,
     * deterministic, no second traversal, and enough to disambiguate this repo's actual collision
     * shape — a per-page {@code CLAUDE.md}, an {@code index.ts} barrel — at the cost of ~15-20 extra
     * characters per line). Falls back to the bare basename when there is no parent segment to add —
     * a file at repo root, or a caller passing an already-bare name.
     */
    private static String disambiguatedFileLabel(String filePath) {
        int lastSeparator = filePath.lastIndexOf('/');
        if (lastSeparator < 0) {
            return filePath;
        }
        int parentSeparator = filePath.lastIndexOf('/', lastSeparator - 1);
        return filePath.substring(parentSeparator + 1);
    }

    // Reuses TuningProperties' configured bash-antipattern map (cat -> Read, find -> Glob, ...),
    // keyed the same way ReportService keys it: on the command's first token. The span's
    // full_command attribute carries the raw command on 100% of measured Bash spans, so this needs
    // no JSON parsing of tool_input.
    private ShellAntipattern shellAntipatternFor(Span span) {
        Object fullCommand = JsonAttributeReaders.attribute(span.getAttributes(), FULL_COMMAND_ATTRIBUTE);
        if (fullCommand == null) {
            return null;
        }
        String command = String.valueOf(fullCommand).strip();
        String firstToken = command.split("\\s+", 2)[0];
        String replacement = tuningProperties.getBashAntipatternReplacements().get(firstToken);
        return replacement == null ? null : new ShellAntipattern(firstToken, replacement);
    }

    private String toolNameOf(Span span) {
        return JsonAttributeReaders.toolNameOf(span, tuningProperties.getToolAttribute());
    }

    private boolean isFailedToolCall(Span span, Map<String, LogRecord> toolResultsByUseId) {
        Object toolUseId = JsonAttributeReaders.attribute(span.getAttributes(), TOOL_USE_ID_ATTRIBUTE);
        LogRecord toolResult = toolUseId == null ? null : toolResultsByUseId.get(String.valueOf(toolUseId));
        if (toolResult == null) {
            return false;
        }
        Object success = JsonAttributeReaders.attribute(toolResult.getAttributes(), SUCCESS_ATTRIBUTE);
        return success != null && !Boolean.parseBoolean(String.valueOf(success));
    }

    /**
     * The failed call's own error text, read off the same {@code tool_result} log
     * {@link #isFailedToolCall} judged it by, truncated to the length the Errors section already
     * uses. Null when the log carries no {@code error} — a call can report {@code success:false}
     * without one, and an empty {@code ": "} on the observation line would read as a missing value
     * rather than an absent one.
     */
    private String toolFailureMessage(Span span, Map<String, LogRecord> toolResultsByUseId) {
        Object toolUseId = JsonAttributeReaders.attribute(span.getAttributes(), TOOL_USE_ID_ATTRIBUTE);
        LogRecord toolResult = toolUseId == null ? null : toolResultsByUseId.get(String.valueOf(toolUseId));
        if (toolResult == null) {
            return null;
        }
        Object error = JsonAttributeReaders.attribute(toolResult.getAttributes(), ERROR_ATTRIBUTE);
        return error == null ? null : truncate(collapseWhitespace(String.valueOf(error)), ERROR_TRUNCATION_LENGTH);
    }

    /**
     * A failed tool call, carrying enough for {@code TraceAnalysisService} to check whether the
     * model's own findings actually cite it and, when they don't, to compose a fallback finding
     * without re-deriving anything from the spans a second time.
     *
     * <p>{@code callNumber} is the bare integer {@code buildObservations} counts calls by — what a
     * citation check has to compare against — while {@code callReference} is the same number with
     * its subagent-branch suffix already applied ({@link #branchSuffix}), the form a reader should
     * see quoted back at them. Package-visible so {@code TraceAnalysisService} can act on it — see
     * {@code ensureFailedToolCallsReported}.
     */
    record UnrecoveredFailure(int callNumber, String callReference, String toolName, String message) {
    }

    /**
     * One distinct way model calls failed in this trace, used as a map key so eight identical
     * failures collapse into one observation naming eight call numbers rather than eight lines.
     *
     * <p>The status code is what makes this worth computing at all. Without it the reviewing model
     * sees an English sentence and has to guess whether the failure was the agent's doing; with it,
     * {@link #isEnvironmental()} decides in code and the observation says so outright.
     */
    private record ModelCallFailure(Integer statusCode, String message) {

        /**
         * Whether nothing the reader could write in an instruction file would have prevented this.
         * A missing status code is deliberately NOT treated as environmental: unclassified is not
         * the same as excused, and the reviewing model is left to judge it on the message.
         */
        boolean isEnvironmental() {
            return statusCode != null
                    && (statusCode == RATE_LIMIT_STATUS
                            || statusCode == REQUEST_TIMEOUT_STATUS
                            || statusCode >= FIRST_SERVER_ERROR_STATUS);
        }

        String describe() {
            return (statusCode == null ? "" : "HTTP " + statusCode + ": ") + message;
        }
    }

    // A model call's own failure, which -- unlike a tool call's -- is entirely on the span: the
    // llm_request span carries error, status_code and success:false directly, so there is no
    // tool_use_id join to make. Both signals are checked because a span can carry an error message
    // without an error status and vice versa.
    private ModelCallFailure modelCallFailureFor(Span span) {
        Map<String, Object> attributes = span.getAttributes();
        String error = JsonAttributeReaders.stringAttribute(attributes, ERROR_ATTRIBUTE);
        if (error == null && !ERROR_STATUS_CODE.equals(span.getStatusCode())) {
            return null;
        }
        String message = error != null ? error : nullToUnknown(span.getStatusMessage());
        return new ModelCallFailure(
                statusCodeOf(attributes), truncate(message, ERROR_TRUNCATION_LENGTH));
    }

    // Null rather than 0 when absent: "no status code was reported" and "the status code was zero"
    // have to stay distinguishable, since isEnvironmental turns on exactly that difference.
    private static Integer statusCodeOf(Map<String, Object> attributes) {
        Object value = JsonAttributeReaders.attribute(attributes, STATUS_CODE_ATTRIBUTE);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Span status messages plus the tool_result logs' own error text — the two do not fully overlap
     * (a tool can report failure on its log while its span still carries an ok status).
     *
     * <p>Identical failures are collapsed to one line with a {@code ×N} count. A trace that hits a
     * quota wall does so on every call it has left, and the eight-times-repeated sentence that
     * produced read as noise: the reviewing model skipped the section outright and reported nothing
     * about the errors that dominated the trace.
     *
     * <p>Each line names its source and HTTP status where there is one, because "what failed" and
     * "whose fault was that" are the two things the bare message does not say. A status code is the
     * single most decision-relevant fact about a model-call failure — see
     * {@link ModelCallFailure#isEnvironmental()} — and it was previously dropped everywhere.
     */
    private List<String> buildErrorLines(List<Span> spans, List<LogRecord> logRecords) {
        Map<ErrorLine, Integer> occurrences = new LinkedHashMap<>();
        for (Span span : spans) {
            if (!ERROR_STATUS_CODE.equals(span.getStatusCode()) || span.getStatusMessage() == null) {
                continue;
            }
            // A tool failure reaches this method TWICE -- once here on the tool.execution span and
            // once below on the tool_result log that describes the same failure -- and the two
            // ErrorLine keys differ only in `source` (the span's label against the tool's name), so
            // they never collapsed. On trace adae1753270dd3088520435ae7f8af94 that turned one Bash
            // failure into two lines and an outcome summary reading "1 error (2 distinct)", where
            // "distinct" exceeded the total it was qualifying. The log is the richer of the pair
            // (it carries the tool name and the full error) and the execution span is derivative,
            // so the span leg skips it and keeps only failures no log reports -- llm_request spans
            // above all, which have no tool_result counterpart to be duplicated by.
            if (tuningProperties.getToolExecutionSpanName().equals(span.getName())) {
                continue;
            }
            occurrences.merge(
                    new ErrorLine(
                            errorSourceLabel(span),
                            truncate(span.getStatusMessage(), ERROR_TRUNCATION_LENGTH)),
                    1,
                    Integer::sum);
        }
        for (LogRecord logRecord : logRecords) {
            if (!JsonAttributeReaders.isEvent(logRecord, tuningProperties.getToolEventName())) {
                continue;
            }
            Object error = JsonAttributeReaders.attribute(logRecord.getAttributes(), ERROR_ATTRIBUTE);
            if (error == null) {
                continue;
            }
            Object toolName = JsonAttributeReaders.attribute(logRecord.getAttributes(), tuningProperties.getToolAttribute());
            occurrences.merge(
                    new ErrorLine(
                            nullToUnknown(toolName == null ? null : String.valueOf(toolName)),
                            truncate(String.valueOf(error), ERROR_TRUNCATION_LENGTH)),
                    1,
                    Integer::sum);
        }
        return occurrences.entrySet().stream()
                .map(entry -> "- " + entry.getKey().source()
                        + (entry.getValue() == 1 ? "" : " ×" + entry.getValue())
                        + ": " + entry.getKey().message())
                .toList();
    }

    /** One distinct error, used as a map key so repeats of it collapse into a single counted line. */
    private record ErrorLine(String source, String message) {}

    // "llm_request (claude-fable-5, HTTP 429)", "Bash", "claude_code.some_other_span". The model
    // name and status are qualifiers rather than part of the name so the same rendering serves a
    // tool span, which has neither.
    private String errorSourceLabel(Span span) {
        StringBuilder label = new StringBuilder();
        boolean isModelCall = tuningProperties.getLlmRequestSpanName().equals(span.getName());
        if (isModelCall) {
            label.append("llm_request");
        } else if (tuningProperties.getToolSpanName().equals(span.getName())) {
            label.append(toolNameOf(span));
        } else {
            label.append(span.getName());
        }
        List<String> qualifiers = new ArrayList<>();
        Object model = JsonAttributeReaders.attribute(span.getAttributes(), MODEL_ATTRIBUTE);
        if (isModelCall && model != null) {
            qualifiers.add(String.valueOf(model));
        }
        Integer statusCode = statusCodeOf(span.getAttributes());
        if (statusCode != null) {
            qualifiers.add("HTTP " + statusCode);
        }
        if (!qualifiers.isEmpty()) {
            label.append(" (").append(String.join(", ", qualifiers)).append(')');
        }
        return label.toString();
    }

    /**
     * Fits the trace to {@code ollama.max-prompt-chars} by splitting an oversized timeline into
     * consecutive, NON-overlapping windows rather than eliding a middle stretch of it — replacing the
     * old {@code truncateToBudget}/{@code trimMiddleOut} elision, which made calls in the elided
     * middle uncitable. Every window is a complete, independent review prompt (its own header/carry
     * -over, the whole overview/observations/errors, and its own slice of the timeline); {@code
     * TraceAnalysisFindingsMerge} joins what each window's model call comes back with. {@code
     * windows()} is never empty, and no call number is ever dropped: {@link #packWindows} only thins
     * the per-call detail level and, when even that is not enough, hard-truncates one oversized line
     * (see {@link #fitLine}) — it never removes a call.
     *
     * <p><b>Detail level is chosen GLOBALLY, once, not per window.</b> The repeat/back-reference
     * markers {@link #renderGroups} writes are facts over the WHOLE call list ("identical call
     * repeats at 12, 40, 90"), and {@code TimelineDetail}'s own contract is that call numbers and
     * these markers are unaffected by the level — a per-window level would let the same call render
     * two contradictory ways in different windows. A trace fitting whole at {@link
     * TimelineDetail#FULL} stays one window either way, rendered byte-identical to the pre-windowing
     * prompt.
     *
     * <p><b>WHICH level is chosen belongs to the operator, because the two remedies trade against
     * each other and the right trade depends on the model.</b> {@code
     * ollama.timeline-detail-preference} decides it: {@code FEWEST_REVIEW_WINDOWS} (the default)
     * tries every level loosest-first and takes whichever needs the fewest windows, so a trace pays
     * in per-call detail before it pays in model calls; {@code MOST_TIMELINE_DETAIL} inverts that,
     * holding the timeline at {@link TimelineDetail#FULL} and partitioning as far as the budget
     * requires. The default is the cheaper one, not the better one — thinning detail is what the
     * ladder's own contract warns is only safe for calls where nothing went wrong, and a small model
     * reading two short prompts is the case this feature actually targets. Which wins on a given
     * model is a measurement; {@code TraceAnalysisRegressionHarness} is what settles it, the same way
     * it settles {@code ollama.structured-output}.
     *
     * <p><b>The non-timeline frame length is measured ONCE, not once per candidate level.</b> Because
     * {@code MustacheConfig} runs with {@code escapeHTML(false)}, {@code {{timeline}}} renders
     * verbatim and the rendered length is exactly additive over the frame plus the timeline text — so
     * probing the frame with an empty timeline (see {@link #frameLength}) gives an exact per-window
     * budget by arithmetic, with no fixed-point re-rendering. The header and carry-over block widths
     * are reserved as fixed constants instead of measured, for the same reason: both vary per window
     * (call range, files touched so far) and re-measuring them would reintroduce the fixed-point
     * problem this method exists to avoid. Total template renders per build: one probe, plus one per
     * window actually emitted.
     */
    private PromptResult partitionToBudget(
            Map<String, Object> context, String renderedPrompt, List<TimelineLine> fullDetailTimelineLines,
            Function<TimelineDetail, List<TimelineLine>> timelineRenderer, CarryOverState carryOverState,
            boolean wordingAlreadySettled, boolean hasPositiveObservations, ApplyThisInputs applyThisInputs,
            String summary, List<UnrecoveredFailure> failedToolCalls) {
        int budget = ollamaProperties.getMaxPromptChars();
        int timelineCallCount = lastCallNumberOf(fullDetailTimelineLines);
        if (renderedPrompt.length() <= budget) {
            PromptWindow singleWindow = new PromptWindow(
                    renderedPrompt, 1, 1, firstCallNumberOf(fullDetailTimelineLines), timelineCallCount, true);
            return new PromptResult(
                    List.of(singleWindow), timelineCallCount, wordingAlreadySettled, hasPositiveObservations,
                    applyThisInputs, summary, failedToolCalls);
        }

        int frame = frameLength(context);
        LevelWindows chosen = chooseLevel(timelineRenderer, frame, budget);
        if (chosen.windows().size() == 1) {
            // A tighter level than FULL fits the whole trace in one window -- still no header, no
            // carry-over, and still one review call, exactly as a trace that fit at FULL would be.
            context.put("hasWindows", false);
            context.put("hasCarryOver", false);
            context.put("timeline", renderText(chosen.windows().get(0)));
            String text = template.execute(context);
            PromptWindow singleWindow = new PromptWindow(
                    text, 1, 1, firstCallNumberOf(fullDetailTimelineLines), timelineCallCount, true);
            return new PromptResult(
                    List.of(singleWindow), timelineCallCount, wordingAlreadySettled, hasPositiveObservations,
                    applyThisInputs, summary, failedToolCalls);
        }

        List<PromptWindow> windows = new ArrayList<>();
        int windowCount = chosen.windows().size();
        for (int index = 0; index < windowCount; index++) {
            List<TimelineLine> windowLines = chosen.windows().get(index);
            // Sections B and C are re-asked once each across the whole partitioned trace rather than
            // once per window -- see the javadoc on the judgesRequest/judgesCost context keys this
            // sets. B goes to the first window (mergedRequestKind already breaks a tie towards it,
            // and the earliest calls are where a target-finding search would show up); C goes to the
            // last, whose carry-over already summarises spend and files across every prior window.
            boolean judgesRequest = index == 0;
            boolean judgesCost = index == windowCount - 1;
            context.put("judgesRequest", judgesRequest);
            context.put("judgesCost", judgesCost);
            String text = renderWindow(context, windowLines, index, windowCount, timelineCallCount, carryOverState);
            windows.add(new PromptWindow(
                    text, index + 1, windowCount, firstCallNumberOf(windowLines), lastCallNumberOf(windowLines),
                    judgesRequest));
        }
        return new PromptResult(
                windows, timelineCallCount, wordingAlreadySettled, hasPositiveObservations, applyThisInputs,
                summary, failedToolCalls);
    }

    /** One {@link TimelineDetail} level's lines, and how {@link #packWindows} partitions them. */
    private record LevelWindows(TimelineDetail detail, List<List<TimelineLine>> windows) {
    }

    // Picks the level the whole timeline renders at -- see partitionToBudget's javadoc for why the
    // level is a single, global decision rather than one made per window, and
    // OllamaProperties.TimelineDetailPreference for why which level wins is the operator's call.
    // FEWEST_REVIEW_WINDOWS tries every level (loosest first) and keeps whichever needs the fewest
    // windows, preferring the loosest on a tie; MOST_TIMELINE_DETAIL renders at the loosest level
    // whatever that costs in windows. Both leave every call its own line and its own number: the
    // levels differ only in how much of a call's detail survives, and packWindows drops nothing.
    private LevelWindows chooseLevel(
            Function<TimelineDetail, List<TimelineLine>> timelineRenderer, int frame, int budget) {
        TimelineDetail loosestDetail = TimelineDetail.values()[0];
        if (ollamaProperties.getTimelineDetailPreference()
                == OllamaProperties.TimelineDetailPreference.MOST_TIMELINE_DETAIL) {
            return new LevelWindows(loosestDetail, windowsForLevel(timelineRenderer, loosestDetail, frame, budget));
        }
        LevelWindows best = null;
        for (TimelineDetail detail : TimelineDetail.values()) {
            List<List<TimelineLine>> windows = windowsForLevel(timelineRenderer, detail, frame, budget);
            if (best == null || windows.size() < best.windows().size()) {
                best = new LevelWindows(detail, windows);
            }
        }
        return best;
    }

    // One level's timeline, whole when it fits the budget and bin-packed into windows when it does
    // not. The per-window budget subtracts the frame every window re-pays plus the header/carry-over
    // reservations, so a window's own slice is what is left over -- see packWindows.
    private List<List<TimelineLine>> windowsForLevel(
            Function<TimelineDetail, List<TimelineLine>> timelineRenderer, TimelineDetail detail, int frame,
            int budget) {
        List<TimelineLine> lines = timelineRenderer.apply(detail);
        if (frame + renderText(lines).length() <= budget) {
            return List.of(lines);
        }
        return packWindows(lines, budget - frame - WINDOW_HEADER_RESERVE_CHARS - CARRY_OVER_BUDGET_CHARS);
    }

    // The non-timeline length of the rendered prompt, measured once by rendering with an empty
    // timeline -- see partitionToBudget's javadoc for why escapeHTML(false) makes this exact rather
    // than approximate.
    private int frameLength(Map<String, Object> context) {
        String timeline = String.valueOf(context.get("timeline"));
        Object hasWindows = context.get("hasWindows");
        Object hasCarryOver = context.get("hasCarryOver");
        Object carryOver = context.get("carryOver");
        context.put("timeline", "");
        context.put("hasWindows", true);
        context.put("hasCarryOver", true);
        context.put("carryOver", "");
        int length = template.execute(context).length();
        context.put("timeline", timeline);
        context.put("hasWindows", hasWindows == null ? false : hasWindows);
        context.put("hasCarryOver", hasCarryOver == null ? false : hasCarryOver);
        context.put("carryOver", carryOver == null ? "" : carryOver);
        return length;
    }

    /**
     * Bin-packs rendered lines into consecutive, non-overlapping windows, none exceeding {@code
     * timelineBudget}. PURE and STATIC — no template involved, so it is directly table-testable. A
     * single line wider than the whole budget is fitted with {@link #fitLine} rather than dropped, so
     * no call is ever lost to packing.
     *
     * @throws IllegalStateException when {@code timelineBudget} is below {@link
     *     #MINIMUM_TIMELINE_CHARS_PER_WINDOW} — a {@code max-prompt-chars} this tight cannot hold a
     *     usable window regardless of how the lines are packed, which is operator misconfiguration
     *     rather than something to silently work around with hundreds of one-line windows.
     */
    static List<List<TimelineLine>> packWindows(List<TimelineLine> lines, int timelineBudget) {
        if (timelineBudget < MINIMUM_TIMELINE_CHARS_PER_WINDOW) {
            throw new IllegalStateException(
                    "ollama.max-prompt-chars leaves only " + timelineBudget + " characters per timeline window, "
                            + "below the " + MINIMUM_TIMELINE_CHARS_PER_WINDOW + "-character floor a window needs "
                            + "to be useful -- increase ollama.max-prompt-chars.");
        }
        List<List<TimelineLine>> windows = new ArrayList<>();
        if (lines.isEmpty()) {
            return windows;
        }
        List<TimelineLine> current = new ArrayList<>();
        int currentLength = 0;
        for (TimelineLine line : lines) {
            TimelineLine fitted = line.text().length() > timelineBudget ? fitLine(line, timelineBudget) : line;
            int lineLength = fitted.text().length() + 1;
            if (!current.isEmpty() && currentLength + lineLength > timelineBudget) {
                windows.add(current);
                current = new ArrayList<>();
                currentLength = 0;
            }
            current.add(fitted);
            currentLength += lineLength;
        }
        windows.add(current);
        return windows;
    }

    // Shrinks one oversized line to fit, keeping its call-number prefix and tool name / llm_request
    // token intact and middle-out-truncating the remainder -- see toolInputFor's own note on why
    // middle-out is the safe direction for a chained shell command. Never drops the line.
    static TimelineLine fitLine(TimelineLine line, int maxChars) {
        String text = line.text();
        Matcher prefixMatcher = TimelineCitations.TIMELINE_LINE.matcher(text);
        int prefixEnd = prefixMatcher.lookingAt() ? prefixMatcher.end() : 0;
        String prefix = text.substring(0, prefixEnd);
        String remainder = text.substring(prefixEnd);
        int remainderBudget = Math.max(0, maxChars - prefix.length());
        return new TimelineLine(
                prefix + truncateMiddleOut(remainder, remainderBudget), line.firstCallNumber(), line.lastCallNumber());
    }

    // One window's complete prompt: the shared overview/observations/errors context, this window's
    // own header and carry-over block, and its slice of the timeline.
    private String renderWindow(
            Map<String, Object> context, List<TimelineLine> window, int index, int windowCount, int totalCalls,
            CarryOverState carryOverState) {
        boolean hasCarryOver = index > 0;
        context.put("hasWindows", true);
        context.put("windowNumber", index + 1);
        context.put("windowCount", windowCount);
        context.put("windowFirstCall", firstCallNumberOf(window));
        context.put("windowLastCall", lastCallNumberOf(window));
        context.put("timelineCallCount", totalCalls);
        context.put("hasCarryOver", hasCarryOver);
        context.put("carryOver", hasCarryOver ? carryOverState.render(firstCallNumberOf(window)) : "");
        context.put("timeline", renderText(window));
        return template.execute(context);
    }

    /**
     * Positional facts about the trace, computed ONCE in {@link #build} from the same span list the
     * timeline is built from, and sliced per window by {@link #render}. Only what is genuinely
     * POSITIONAL travels here — the overview, verified observations and errors are computed over the
     * WHOLE trace already and render identically in every window, so they need no carry-over copy.
     *
     * <p>A plain class rather than a record: {@link #render} keeps a monotonic cursor into {@link
     * #callFacts} across calls (see its own javadoc), which needs a mutable field a record's
     * canonical-constructor-only shape cannot hold.
     */
    private static final class CarryOverState {

        private final List<CallFact> callFacts;
        private final List<UnrecoveredFailure> failures;

        // The calls already accumulated for windowFirstCallNumbers seen so far, and how far into
        // callFacts that accumulation has scanned -- see render's own javadoc for why this can grow
        // incrementally instead of being rebuilt from callFacts.get(0) on every call.
        private final List<CallFact> accumulatedBefore = new ArrayList<>();
        private int nextUnscannedIndex;
        private int lastWindowFirstCallNumber = Integer.MIN_VALUE;

        private CarryOverState(List<CallFact> callFacts, List<UnrecoveredFailure> failures) {
            this.callFacts = callFacts;
            this.failures = failures;
        }

        /** One numbered call's carry-over-relevant facts. */
        private record CallFact(
                int callNumber, boolean isToolCall, boolean isModelCall, String target, boolean mutatesTarget,
                double cumulativeMeasuredCostUsd) {
        }

        // Truncated to CARRY_OVER_BUDGET_CHARS in priority order: the counts line and the failures
        // are kept first (a failure is what a finding hangs on), files are dropped first if the
        // budget runs out.
        //
        // partitionToBudget's window loop calls this once per window, strictly left to right, so
        // windowFirstCallNumber is guaranteed to strictly increase from one call to the next --
        // windows are non-overlapping, call-number-ordered slices of the same timeline. That lets
        // accumulatedBefore grow incrementally: each call only scans the calls newly exposed since
        // nextUnscannedIndex rather than re-filtering the whole callFacts list from the start, which
        // made every window pay for every earlier window's calls again (O(windowCount * totalCalls)
        // in the worst case). The guard below turns a violated invariant into a loud failure instead
        // of a silently wrong (too-short) carry-over block.
        String render(int windowFirstCallNumber) {
            if (windowFirstCallNumber < lastWindowFirstCallNumber) {
                throw new IllegalStateException(
                        "CarryOverState.render called out of order: " + windowFirstCallNumber
                                + " after " + lastWindowFirstCallNumber
                                + " -- render requires strictly increasing windowFirstCallNumber.");
            }
            lastWindowFirstCallNumber = windowFirstCallNumber;
            while (nextUnscannedIndex < callFacts.size()
                    && callFacts.get(nextUnscannedIndex).callNumber() < windowFirstCallNumber) {
                accumulatedBefore.add(callFacts.get(nextUnscannedIndex));
                nextUnscannedIndex++;
            }
            if (accumulatedBefore.isEmpty()) {
                return "";
            }
            String countsLine = countsLine(accumulatedBefore, windowFirstCallNumber);
            List<String> failureLines = failureLines(windowFirstCallNumber);
            List<String> fileLines = fileLines(accumulatedBefore);

            List<String> sections = new ArrayList<>();
            int remaining = CARRY_OVER_BUDGET_CHARS - countsLine.length();
            sections.add(countsLine);
            remaining = appendWithinBudget(sections, "Failures already seen:", failureLines, remaining);
            appendWithinBudget(sections, "Files already touched, most recent first:", fileLines, remaining);
            return String.join("\n", sections);
        }

        private String countsLine(List<CallFact> before, int windowFirstCallNumber) {
            long toolCalls = before.stream().filter(CallFact::isToolCall).count();
            long modelCalls = before.stream().filter(CallFact::isModelCall).count();
            long failedCount = failures.stream()
                    .filter(failure -> failure.callNumber() < windowFirstCallNumber)
                    .count();
            double cost = before.get(before.size() - 1).cumulativeMeasuredCostUsd();
            return "Before this window: " + before.size() + " calls (" + toolCalls + " tool, " + modelCalls
                    + " model), " + failedCount + " failed, $" + String.format(COST_FORMAT, cost) + " spent.";
        }

        private List<String> failureLines(int windowFirstCallNumber) {
            List<String> lines = new ArrayList<>();
            for (UnrecoveredFailure failure : failures) {
                if (failure.callNumber() >= windowFirstCallNumber) {
                    continue;
                }
                if (lines.size() == MAX_CARRY_OVER_FAILURES) {
                    break;
                }
                lines.add("call " + failure.callReference() + " (" + failure.toolName() + "): " + failure.message());
            }
            return lines;
        }

        // Most-recent-first, deduplicated on target -- the LAST touch of a file is the one that
        // matters for "has this already been read/edited", so a LinkedHashMap keyed on target and
        // filled in call order keeps exactly that touch before the list is reversed.
        private List<String> fileLines(List<CallFact> before) {
            Map<String, CallFact> latestTouchByTarget = new LinkedHashMap<>();
            for (CallFact fact : before) {
                if (fact.target() != null) {
                    latestTouchByTarget.remove(fact.target());
                    latestTouchByTarget.put(fact.target(), fact);
                }
            }
            List<CallFact> touches = new ArrayList<>(latestTouchByTarget.values());
            Collections.reverse(touches);
            List<String> lines = new ArrayList<>();
            for (CallFact fact : touches) {
                if (lines.size() == MAX_CARRY_OVER_FILES) {
                    lines.add("…and " + (touches.size() - MAX_CARRY_OVER_FILES) + " more files");
                    break;
                }
                lines.add("call " + fact.callNumber() + " (" + (fact.mutatesTarget() ? "edited" : "read") + ") "
                        + fact.target());
            }
            return lines;
        }

        // Appends a labelled block only if at least its label and first line fit; returns the
        // remaining budget for the next, lower-priority block.
        private static int appendWithinBudget(
                List<String> sections, String label, List<String> lines, int remainingBudget) {
            if (lines.isEmpty() || remainingBudget <= label.length()) {
                return remainingBudget;
            }
            List<String> kept = new ArrayList<>();
            int used = label.length();
            for (String line : lines) {
                int lineLength = line.length() + 1;
                if (used + lineLength > remainingBudget) {
                    break;
                }
                kept.add(line);
                used += lineLength;
            }
            if (kept.isEmpty()) {
                return remainingBudget;
            }
            sections.add(label + "\n" + String.join("\n", kept));
            return remainingBudget - used;
        }
    }

    // Walks the same span list the timeline is built from, once, to compute the positional facts
    // partitionToBudget's carry-over block needs -- see CarryOverState. Kept separate from
    // attributeCalls/buildTimelineLines rather than folded into either, since this is the one place
    // that needs a running (call-number-ordered) view rather than a per-span or per-group one.
    private CarryOverState buildCarryOverState(
            List<Span> spans, CallAttribution callAttribution, List<UnrecoveredFailure> failedToolCalls) {
        List<CarryOverState.CallFact> facts = new ArrayList<>();
        int callNumber = 0;
        double runningMeasuredCostUsd = 0.0;
        for (Span span : spans) {
            if (!isCall(span)) {
                continue;
            }
            callNumber++;
            boolean isToolCall = tuningProperties.getToolSpanName().equals(span.getName());
            boolean isModelCall = tuningProperties.getLlmRequestSpanName().equals(span.getName());
            String target = null;
            boolean mutatesTarget = false;
            if (isToolCall) {
                Object filePath = JsonAttributeReaders.attribute(span.getAttributes(), FILE_PATH_ATTRIBUTE);
                target = filePath == null ? null : String.valueOf(filePath);
                mutatesTarget = MUTATING_TOOL_NAMES.contains(toolNameOf(span));
            }
            if (isModelCall) {
                Double costUsd = callAttribution.costUsdBySpanId().get(span.getSpanId());
                if (costUsd != null) {
                    runningMeasuredCostUsd += costUsd;
                }
            }
            facts.add(new CarryOverState.CallFact(
                    callNumber, isToolCall, isModelCall, target, mutatesTarget, runningMeasuredCostUsd));
        }
        return new CarryOverState(facts, failedToolCalls);
    }

    private static long spanDurationMs(Span span) {
        return span.getDurationNanos() == null ? 0L : Math.round(span.getDurationNanos() / NANOS_PER_MILLI);
    }

    private static String nullToUnknown(String value) {
        return value == null ? "unknown" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATION_SUFFIX;
    }

    private static String truncateToTail(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return TRUNCATION_PREFIX + value.substring(value.length() - maxLength);
    }

    /**
     * Keeps both ends and drops the middle, for values whose meaning is not front-loaded — see
     * {@link #toolInputFor} for the shell command this exists for. Tail-weighted because the head
     * only has to identify the operation ({@code git add …}, {@code ./mvnw verify …}) while the tail
     * carries the chained steps that decide whether a finding about the call is true.
     */
    private static String truncateMiddleOut(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        int tailLength = (int) Math.round(maxLength * MIDDLE_OUT_TAIL_SHARE);
        int headLength = maxLength - tailLength;
        return value.substring(0, headLength)
                + OMITTED_CHARS_NOTE.formatted(value.length() - maxLength)
                + value.substring(value.length() - tailLength);
    }
}
