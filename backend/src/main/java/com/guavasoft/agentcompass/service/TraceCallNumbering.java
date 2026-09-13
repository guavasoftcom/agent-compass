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

import com.guavasoft.agentcompass.model.Span;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single definition of what a "call" is, and what number it carries.
 *
 * <p>{@link TraceAnalysisPromptBuilder} numbers the timeline it hands the model, and the answer that
 * comes back cites those numbers ("call 20 was an outlier"). Nothing on the trace detail page used
 * to carry that number: the waterfall's own index badge counts <b>every</b> span in DFS order —
 * the {@code claude_code.interaction} root, each tool call's {@code tool.execution} and
 * {@code blocked_on_user} children — while a call number counts only the two span names below, in
 * trace order. On a real trace the two diverge immediately (call 20 was waterfall row 47), so a
 * reader had no way to walk from a citation to the row it is about.
 *
 * <p>Both sides therefore read the rule from here rather than each keeping their own copy: the
 * prompt builder's {@code isCall} delegates to {@link #isCall}, and {@code TraceService} fills
 * {@link Span#getCallNumber()} from {@link #callNumbersBySpanId} over the same
 * {@code start_timestamp}-ordered span list the builder is handed. A second implementation would be
 * free to drift, and a citation pointing at the wrong row is worse than one pointing nowhere.
 *
 * <p>Numbering is positional over the whole list, so a span carrying no span id still consumes its
 * number — dropping it would renumber every call after it and silently invalidate the model's
 * citations. It simply gets no entry in the map.
 *
 * <p>Public (this class was originally package-private) so {@code service.detector}'s ported
 * detectors are a third reader of the same rule, for the identical reason the first two are: a
 * detector citing a call number must mean the exact row the waterfall and the trace-analysis
 * timeline already agree on.
 */
public final class TraceCallNumbering {

    private TraceCallNumbering() {
    }

    /**
     * Whether this span is one of the numbered calls: a tool call or a model request. Everything
     * else in a trace is structure around those (the turn root, the execution and approval-wait
     * children of a tool call) and is deliberately unnumbered.
     */
    public static boolean isCall(Span span, String toolSpanName, String llmRequestSpanName) {
        return toolSpanName.equals(span.getName()) || llmRequestSpanName.equals(span.getName());
    }

    /**
     * Call number per span id, 1-based in the order the spans are given — which must be the
     * {@code start_timestamp} ascending order both the prompt builder and the trace detail page
     * already receive.
     */
    public static Map<String, Integer> callNumbersBySpanId(
            List<Span> spans, String toolSpanName, String llmRequestSpanName) {
        Map<String, Integer> callNumberBySpanId = new HashMap<>();
        int callNumber = 0;
        for (Span span : spans) {
            if (!isCall(span, toolSpanName, llmRequestSpanName)) {
                continue;
            }
            callNumber++;
            if (span.getSpanId() != null) {
                callNumberBySpanId.put(span.getSpanId(), callNumber);
            }
        }
        return callNumberBySpanId;
    }
}
