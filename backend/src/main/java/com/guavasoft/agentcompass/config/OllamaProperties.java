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
package com.guavasoft.agentcompass.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to reach the local Ollama server for the "Analyze trace" feature.
 *
 * <p><b>Deliberately NOT part of {@link TuningProperties} / {@link TuningPropertyCatalog}.</b> That
 * catalog exists specifically to classify OTLP event/attribute-<i>name</i> overrides — its own
 * javadoc scopes it there, and {@code TuningPropertyCatalogTest} reflects over every
 * {@code TuningProperties} field and fails the build if one is left unclassified. An Ollama
 * endpoint/model/timeout is a "how do I reach a dependency" setting, the same shape as
 * {@code spring.datasource.*}: it mirrors into no SQL and names no OTLP attribute key, so it gets
 * its own sibling properties class instead of being forced into a catalog built for something else.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {

    /** Base URL of the Ollama server. */
    private String baseUrl = "http://localhost:11434";

    /** Model to run analysis with. Must already be pulled by the operator (`ollama pull <model>`). */
    private String model = "llama3.1";

    /**
     * Whether "Analyze trace" is available at all. Defaults off — this feature calls out to a
     * local Ollama server that most installs won't have running, and enabling it unconditionally
     * would make every fresh install's first "Analyze trace" click fail with a 503. An operator
     * opts in from the Settings page's Ollama tab, which {@code TraceAnalysisService} enforces
     * server-side (see its {@code regenerate} gate) rather than relying on the UI toggle alone to
     * keep the "Analyze trace" button from calling out.
     */
    private boolean enabled = false;

    /** Connect timeout — fail fast if Ollama is not running at all. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Read timeout — cold local inference on a large model can take a while. */
    private Duration readTimeout = Duration.ofSeconds(120);

    /**
     * Budget for one rendered timeline WINDOW, in characters. Sized against real traces in this
     * database: a p50 trace renders ~1.4k characters of tool input, a p95 trace ~15.9k with each
     * input capped at 200 characters, plus ~4.6k of final response and ~4.2k of prompt text. Must
     * stay comfortably inside {@link #contextTokens} once converted to tokens (roughly 3 characters
     * per token, deliberately over-counted — see {@code OllamaClient.contextTokensFor}), or Ollama
     * truncates what this budget carefully assembled.
     *
     * <p><b>This budget applies to the findings prompt</b> — the first of the review's two-or-more
     * calls. The apply-this call, which turns findings into the three "Apply this" lines, carries no
     * timeline or overview and is a few thousand characters whatever the trace looks like, so it is
     * not budgeted separately.
     *
     * <p><b>The review instructions are a fixed ~10.5k of this</b> — the template's own legend, rules
     * and findings contract, which render on every trace regardless of size, and are re-paid on
     * every window of a partitioned trace. The budget was raised repeatedly as those grew (24k when
     * the answer contract did, 26k for the cost/context section, 33k then 36k for further contract
     * growth), each time so the instruction growth came out of headroom rather than out of the
     * trace's own timeline, which is the section with nothing to spare.
     *
     * <p><b>No trace is ever truncated any more — an oversized timeline is PARTITIONED, not cut.</b>
     * {@code TraceAnalysisPromptBuilder.TimelineDetail} first re-renders the timeline at successively
     * tighter per-line detail (fewer characters per call, same call count); if even the tightest
     * level does not fit one window, the timeline is split into consecutive, non-overlapping windows
     * — each a full review call, each carrying a code-composed "what already happened" carry-over —
     * so every call number still reaches the model, in exactly one window. See
     * {@code TraceAnalysisPromptBuilder.PromptWindow} and {@code TraceAnalysisFindingsMerge}, which
     * merges the per-window findings before the apply-this call ever runs.
     *
     * <p><b>60,000 was chosen so partitioning is rare, not so it is unnecessary.</b> Measured against
     * this database (2,177 traces, 2026-09-08): at the OLD 36,000 cap, 15 traces (0.7%) overflowed
     * even the tightest {@code TimelineDetail} level — under the old design those 15 lost calls to
     * middle-out elision; under this one they now partition into 2+ windows. At 60,000, zero of
     * 2,177 traces need more than one window. The corpus's largest recorded trace (595 timeline
     * calls) still fits one window at the tightest detail level. A future trace bigger than anything
     * measured here is handled correctly regardless — partitioning, not this number, is the
     * guarantee.
     *
     * <p>Raising it further means raising {@link #contextTokens} with it, which costs KV-cache RAM
     * on the Ollama host: see that field's javadoc for the exact arithmetic.
     */
    private int maxPromptChars = 60_000;

    /**
     * Context window sent to Ollama as {@code options.num_ctx}. NOT a passive default: Ollama
     * silently left-truncates any prompt exceeding the model's context window, so leaving this
     * unset (Ollama's own default is 4096 tokens, 2048 on older releases) would quietly chop the
     * instructions off the top of every non-trivial trace-analysis prompt.
     *
     * <p>24,576 holds {@link #maxPromptChars} (60,000) with room for the model's own answer:
     * {@code OllamaClient.contextTokensFor} sizes each request's {@code num_ctx} as
     * {@code promptChars / 3 + 1 + 4096} clamped to this ceiling, and that estimate deliberately
     * over-counts at 3 characters per token — 60,000 chars gives 24,097 tokens against this 24,576
     * ceiling, where 60,861 would be the first value to clamp, and a clamp is exactly the silent
     * left-truncation this setting exists to prevent. The two must always move together; raising
     * either without the other reintroduces silent truncation. Raising it costs KV-cache RAM on the
     * machine running Ollama — this is +50% on the single largest allocation a run makes (paid only
     * on the findings call; the apply-this call already sizes its own window down) — lowering it
     * starts truncating real traces well before {@link #maxPromptChars} does.
     */
    private int contextTokens = 24_576;

    /**
     * Whether to ask Ollama for a JSON answer matching a schema ({@code format} on
     * {@code /api/generate}) and render the markdown from it in code, instead of asking the model to
     * write the markdown itself.
     *
     * <p><b>Off by default, and that default is a measurement gate rather than caution about the
     * mechanism.</b> Constrained decoding makes the answer's <i>shape</i> unfalsifiable — the model
     * cannot write its review twice ({@code TraceAnalysisService#collapseDuplicatedBlock} exists
     * only to repair that), cannot skip an "Apply this" line, and cannot name a rule target off the
     * closed list, which becomes a schema {@code enum} instead of a sentence asking it to copy one
     * verbatim. What it can also do on a 7B model is degrade the answer's <i>content</i>, since
     * every token is now drawn from a grammar-restricted distribution. Which effect dominates is a
     * property of the operator's model, not something this project can assert, so both paths stay
     * runnable and {@code TraceAnalysisRegressionHarness} is what decides between them.
     *
     * <p>Two operational notes. Older Ollama releases <b>silently ignore an unknown {@code format}
     * field</b> — the same failure shape {@link #contextTokens} documents for {@code num_ctx} — so
     * the answer comes back as ordinary prose and parsing it as JSON fails loudly rather than
     * quietly producing a wrong review; that is deliberate. And stored analyses are markdown either
     * way: this changes how the text is produced, never what is persisted or what the frontend
     * parses, so it can be flipped on and off without migrating {@code trace_analyses}.
     */
    private boolean structuredOutput = false;

    /**
     * How {@code TraceAnalysisPromptBuilder.partitionToBudget} resolves a timeline that does not fit
     * {@link #maxPromptChars}, when the two available remedies conflict. Both remedies are lossless
     * in the one way that matters — every call keeps its line and its number under either — so this
     * chooses only what a trace pays with.
     *
     * <p><b>The two are opposites, and the right answer depends on the model.</b> Tightening the
     * detail level buys room by shortening every call's line (the tool-input cap drops 200 → 80 → 30,
     * the {@code -> ok} and duration come off calls that succeeded, a model call's token/cost
     * breakdown comes off); partitioning buys it by splitting the timeline across more review calls,
     * each carrying full-detail lines. A model that reads a long prompt well should take the tighter
     * lines and the single call; a small one, which is what this feature targets, generally reads two
     * short prompts better than one long one, and the evidence a finding rests on is exactly what the
     * tighter levels thin.
     */
    private TimelineDetailPreference timelineDetailPreference = TimelineDetailPreference.FEWEST_REVIEW_WINDOWS;

    /** Which way an oversized timeline gives way — see {@link #timelineDetailPreference}. */
    public enum TimelineDetailPreference {

        /**
         * Pay in per-call detail before paying in review calls: every detail level is tried and
         * whichever needs the fewest windows wins, ties going to the loosest. The original behaviour
         * and still the default, since it makes the fewest local inference calls.
         */
        FEWEST_REVIEW_WINDOWS,

        /**
         * Pay in review calls before paying in per-call detail: the timeline always renders at the
         * loosest detail level and is partitioned into as many windows as that needs. Costs one full
         * model call per extra window — each re-paying the fixed review instructions, ~10.5k
         * characters of every window — in exchange for never thinning a call's evidence.
         */
        MOST_TIMELINE_DETAIL
    }
}
