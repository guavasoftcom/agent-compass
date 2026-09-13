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

import java.util.List;
import java.util.Optional;

/**
 * Prints how many review windows each corpus trace needs at a range of {@code ollama.max-prompt-chars}
 * budgets, under both {@code ollama.timeline-detail-preference} settings. Calls {@code preparePrompt}
 * only -- no Ollama, no inference -- so the whole sweep runs in seconds and can be used to CHOOSE a
 * budget before paying for a run of {@code TraceAnalysisRegressionHarness} at it.
 *
 * <p>Needs the operator's own database, so it is gated the same way the harness is.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TRACE_ANALYSIS_HARNESS", matches = "true")
class TraceAnalysisBudgetProbe {

    private static final List<String> TRACE_IDS = List.of(
            "5d6c9ca05d7c6ce12e41a84980693f10",
            "83b37f35664d88848e00d20be9f737ae",
            "1635329e1e7db7f934b007d90aba7d61",
            "80e62a90dc49cec593af52f698d8dd6b",
            "73590130fdbec1b4f2c89217103fb3db",
            "dfe4ea1da356f008ae46b2790736e223",
            "299f2704e7161e2271a5c3749cdf3551");

    private static final List<Integer> BUDGETS = List.of(60_000, 48_000, 40_000, 36_000, 30_000, 26_000, 22_000);

    @Autowired
    private TraceAnalysisService traceAnalysisService;

    @Autowired
    private OllamaProperties ollamaProperties;

    @Test
    void printsWindowCountsAcrossBudgetsAndPreferences() {
        int originalBudget = ollamaProperties.getMaxPromptChars();
        OllamaProperties.TimelineDetailPreference originalPreference =
                ollamaProperties.getTimelineDetailPreference();
        StringBuilder table = new StringBuilder("\n=== Window counts by budget (fewest / most-detail) ===\n");
        table.append(String.format("%-34s", "trace"));
        for (int budget : BUDGETS) {
            table.append(String.format("%12d", budget));
        }
        table.append("\n");

        try {
            for (String traceId : TRACE_IDS) {
                table.append(String.format("%-34s", traceId.substring(0, 12)));
                for (int budget : BUDGETS) {
                    ollamaProperties.setMaxPromptChars(budget);
                    table.append(String.format("%12s", windowCountsAt(traceId)));
                }
                table.append("\n");
            }
        } finally {
            ollamaProperties.setMaxPromptChars(originalBudget);
            ollamaProperties.setTimelineDetailPreference(originalPreference);
        }
        System.out.println(table);
    }

    private String windowCountsAt(String traceId) {
        return windowCountFor(traceId, OllamaProperties.TimelineDetailPreference.FEWEST_REVIEW_WINDOWS)
                + "/" + windowCountFor(traceId, OllamaProperties.TimelineDetailPreference.MOST_TIMELINE_DETAIL);
    }

    private String windowCountFor(String traceId, OllamaProperties.TimelineDetailPreference preference) {
        ollamaProperties.setTimelineDetailPreference(preference);
        try {
            Optional<TraceAnalysisService.PreparedPrompt> prepared = traceAnalysisService.preparePrompt(traceId);
            return prepared.map(value -> String.valueOf(value.windows().size())).orElse("-");
        } catch (IllegalStateException e) {
            // packWindows' own floor: a budget this tight cannot hold a usable window for this trace.
            return "X";
        }
    }
}
