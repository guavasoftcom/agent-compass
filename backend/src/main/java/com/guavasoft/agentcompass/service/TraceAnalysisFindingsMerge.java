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

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Joins the per-window {@link TraceAnalysisAnswer.Findings} that {@code
 * TraceAnalysisService#generateAndSave} collects from a partitioned trace's DRAFTING calls into the
 * one {@code Findings} document the APPLYING call and the stored review are built from — see {@code
 * TraceAnalysisPromptBuilder#partitionToBudget}'s own javadoc for why a trace is windowed at all.
 *
 * <p><b>A single window is returned unchanged.</b> The common case — a trace whose timeline fits in
 * one window — pays nothing for this class existing: no dedup pass, no citation extraction, no
 * request-kind vote, since there is only one vote to take.
 *
 * <p><b>Dedup is keyed on label AND citations, not either alone.</b> Two windows drafting findings
 * from disjoint slices of one trace can both notice the same real problem — the redundant file read
 * that started in one window and continued into the next — and label it the same thing without
 * either one being wrong. Treating a shared label as sufficient would collapse two genuinely
 * different findings about different calls into one; treating shared citations as sufficient (with
 * no label check) would collapse two different observations that happen to mention an overlapping
 * call range. So a candidate is folded into an already-kept finding only when its label matches
 * (case-insensitively, stripped) <b>and</b> either both are uncited or their cited call numbers (read
 * through {@link TimelineCitations#citedCallNumbers}, the same oracle {@code
 * TraceAnalysisService#citedCallNumbers} and the scorer both use, so "cited" never means two things
 * in this codebase) actually intersect. An uncited finding — a general observation with no call
 * number in it — has no citations to compare, so two of those dedup on label alone; that is the
 * common shape for a finding like "the agent used Bash where Grep would have worked" repeated near-
 * identically by two windows that each saw one instance of it.
 *
 * <p><b>Survivors are ordered round-robin across windows, not window-by-window.</b> Concatenating
 * window 1's findings before window 2's would let an early window fill the whole five-fault cap
 * before a later window's own findings are even considered, which is exactly backwards for a review
 * whose whole point is covering the trace evenly. Taking one finding from each window in turn (then
 * the second finding from each, and so on) means every window gets a fair turn before the cap —
 * {@code MAXIMUM_MERGED_POSITIVE_FINDINGS} / {@code MAXIMUM_MERGED_FAULT_FINDINGS}, matching {@link
 * TraceAnalysisAnswer}'s own two/five caps — is reached.
 *
 * <p><b>The request kind is decided by majority, ties broken by the first window.</b> Every window
 * classifies the request independently (it is handed the same overview and prompt text each time),
 * so windows should usually agree; when they don't, majority vote is the least arbitrary way to pick
 * one, and a genuine tie falls back to the first window's verdict since that window carries the
 * "wording already settled" gate this decision ultimately feeds — see {@code
 * TraceAnalysisService#requestWasQuestion}. A disagreement of either shape is logged at INFO with the
 * full vote count, because a silently-resolved 2-1 split or a silently broken tie is otherwise
 * unobservable from the stored answer alone.
 */
@Slf4j
final class TraceAnalysisFindingsMerge {

    private static final int MAXIMUM_MERGED_POSITIVE_FINDINGS = 2;
    private static final int MAXIMUM_MERGED_FAULT_FINDINGS = 5;

    private TraceAnalysisFindingsMerge() {
    }

    static TraceAnalysisAnswer.Findings merge(List<TraceAnalysisAnswer.Findings> perWindowFindings) {
        if (perWindowFindings == null || perWindowFindings.isEmpty()) {
            return new TraceAnalysisAnswer.Findings(List.of(), List.of(), null);
        }
        if (perWindowFindings.size() == 1) {
            return perWindowFindings.get(0);
        }

        List<List<TraceAnalysisAnswer.Finding>> perWindowPositives = new ArrayList<>();
        List<List<TraceAnalysisAnswer.Finding>> perWindowFaults = new ArrayList<>();
        List<String> perWindowRequestKinds = new ArrayList<>();
        for (TraceAnalysisAnswer.Findings windowFindings : perWindowFindings) {
            perWindowPositives.add(windowFindings.wentWell());
            perWindowFaults.add(windowFindings.wentWrong());
            perWindowRequestKinds.add(windowFindings.requestKind());
        }

        List<TraceAnalysisAnswer.Finding> wentWell =
                dedupedRoundRobin(perWindowPositives, MAXIMUM_MERGED_POSITIVE_FINDINGS);
        List<TraceAnalysisAnswer.Finding> wentWrong =
                dedupedRoundRobin(perWindowFaults, MAXIMUM_MERGED_FAULT_FINDINGS);
        String requestKind = mergedRequestKind(perWindowRequestKinds);
        return new TraceAnalysisAnswer.Findings(wentWell, wentWrong, requestKind);
    }

    /**
     * One list (positives, or faults) taken round-robin across every window's own list, skipping a
     * candidate whose label and citations both match one already kept, and stopping once {@code cap}
     * survivors have been collected.
     */
    private static List<TraceAnalysisAnswer.Finding> dedupedRoundRobin(
            List<List<TraceAnalysisAnswer.Finding>> perWindowLists, int cap) {
        List<List<TraceAnalysisAnswer.Finding>> nonNullLists = new ArrayList<>();
        int maximumWindowSize = 0;
        for (List<TraceAnalysisAnswer.Finding> windowFindings : perWindowLists) {
            List<TraceAnalysisAnswer.Finding> safeWindowFindings =
                    windowFindings == null ? List.of() : windowFindings;
            nonNullLists.add(safeWindowFindings);
            maximumWindowSize = Math.max(maximumWindowSize, safeWindowFindings.size());
        }

        List<TraceAnalysisAnswer.Finding> merged = new ArrayList<>();
        List<String> keptLabels = new ArrayList<>();
        List<Set<Integer>> keptCitations = new ArrayList<>();
        for (int index = 0; index < maximumWindowSize && merged.size() < cap; index++) {
            for (List<TraceAnalysisAnswer.Finding> windowFindings : nonNullLists) {
                if (index >= windowFindings.size()) {
                    continue;
                }
                TraceAnalysisAnswer.Finding candidate = windowFindings.get(index);
                if (candidate == null || isDuplicate(candidate, keptLabels, keptCitations)) {
                    continue;
                }
                merged.add(candidate);
                keptLabels.add(normalizedLabel(candidate.label()));
                keptCitations.add(citationsOf(candidate));
                if (merged.size() >= cap) {
                    break;
                }
            }
        }
        return merged;
    }

    private static boolean isDuplicate(
            TraceAnalysisAnswer.Finding candidate, List<String> keptLabels, List<Set<Integer>> keptCitations) {
        String candidateLabel = normalizedLabel(candidate.label());
        Set<Integer> candidateCitations = citationsOf(candidate);
        for (int index = 0; index < keptLabels.size(); index++) {
            if (!keptLabels.get(index).equals(candidateLabel)) {
                continue;
            }
            Set<Integer> existingCitations = keptCitations.get(index);
            boolean bothUncited = candidateCitations.isEmpty() && existingCitations.isEmpty();
            boolean citationsIntersect = !candidateCitations.isEmpty() && !existingCitations.isEmpty()
                    && !Collections.disjoint(candidateCitations, existingCitations);
            if (bothUncited || citationsIntersect) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> citationsOf(TraceAnalysisAnswer.Finding finding) {
        Set<Integer> citations = new LinkedHashSet<>(TimelineCitations.citedCallNumbers(finding.detail()));
        citations.addAll(TimelineCitations.citedCallNumbers(finding.fix()));
        return citations;
    }

    private static String normalizedLabel(String label) {
        return label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
    }

    private static String mergedRequestKind(List<String> perWindowRequestKinds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String requestKind : perWindowRequestKinds) {
            if (StringUtils.isBlank(requestKind)) {
                continue;
            }
            counts.merge(requestKind, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return null;
        }
        if (counts.size() == 1) {
            return counts.keySet().iterator().next();
        }

        int maximumCount = Collections.max(counts.values());
        List<String> topRequestKinds = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == maximumCount) {
                topRequestKinds.add(entry.getKey());
            }
        }
        String picked = topRequestKinds.size() == 1 ? topRequestKinds.get(0) : firstNonBlank(perWindowRequestKinds);
        log.info("Request kind disagreement across windows: {} -- picked {}", counts, picked);
        return picked;
    }

    private static String firstNonBlank(List<String> values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
