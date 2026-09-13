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

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;

import java.util.Map;

/**
 * Stateless, low-level jsonb-attribute readers shared by {@link SubagentCostAttributor} and
 * {@link TraceAnalysisPromptBuilder} — extracted from two near-identical copies of {@code
 * attribute}/{@code stringAttribute}/{@code longAttribute}/{@code doubleAttribute}/{@code
 * isEvent}/{@code plural}/{@code toolNameOf}/{@code promptTokensOf}, the smallest, most
 * general-purpose readers either class had. Reuse these rather than re-extracting a third copy,
 * the same rule {@link PageBounds} and {@link HistogramBucketing} follow for their own callers.
 */
final class JsonAttributeReaders {

    private static final String EVENT_NAME_ATTRIBUTE = "event.name";
    private static final String UNNAMED_TOOL = "tool";

    // What one model call had to read: everything sent up the wire, cached or not. Output tokens
    // are not part of it -- this is the size of the conversation at that moment, not the size of
    // the answer.
    static final String INPUT_TOKENS_ATTRIBUTE = "input_tokens";
    static final String CACHE_READ_TOKENS_ATTRIBUTE = "cache_read_tokens";
    static final String CACHE_CREATION_TOKENS_ATTRIBUTE = "cache_creation_tokens";

    private JsonAttributeReaders() {
    }

    static Object attribute(Map<String, Object> attributes, String key) {
        return attributes == null ? null : attributes.get(key);
    }

    static String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attribute(attributes, key);
        return value == null ? null : String.valueOf(value);
    }

    static long longAttribute(Map<String, Object> attributes, String key) {
        Object value = attribute(attributes, key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    static double doubleAttribute(Map<String, Object> attributes, String key) {
        Object value = attribute(attributes, key);
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }

    static boolean isEvent(LogRecord logRecord, String eventName) {
        Object recordedEventName = attribute(logRecord.getAttributes(), EVENT_NAME_ATTRIBUTE);
        return eventName.equals(recordedEventName);
    }

    static String plural(long count) {
        return count == 1 ? "" : "s";
    }

    /**
     * A span's tool name, or {@code UNNAMED_TOOL} when the span carries none. {@code toolAttribute}
     * is the caller's {@code TuningProperties.getToolAttribute()} — this class stays free of any
     * dependency on {@code TuningProperties} so it can be reused by any future caller regardless of
     * which configuration bean it happens to hold.
     */
    static String toolNameOf(Span span, String toolAttribute) {
        Object toolName = attribute(span.getAttributes(), toolAttribute);
        return toolName == null ? UNNAMED_TOOL : String.valueOf(toolName);
    }

    static long promptTokensOf(Span span) {
        Map<String, Object> attributes = span.getAttributes();
        return longAttribute(attributes, INPUT_TOKENS_ATTRIBUTE)
                + longAttribute(attributes, CACHE_READ_TOKENS_ATTRIBUTE)
                + longAttribute(attributes, CACHE_CREATION_TOKENS_ATTRIBUTE);
    }
}
