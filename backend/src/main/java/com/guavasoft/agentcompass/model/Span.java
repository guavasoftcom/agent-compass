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
package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "Span", description = "One persisted OTLP span, shaped for the dashboard grid")
public class Span {

    @Schema(description = "Database primary key", example = "42")
    private Long id;

    @Schema(description = "Hex-encoded OTLP trace ID (16 bytes / 32 hex chars)", example = "0102030405060708090a0b0c0d0e0f10")
    private String traceId;

    @Schema(description = "Hex-encoded OTLP span ID (8 bytes / 16 hex chars)", example = "1112131415161718")
    private String spanId;

    @Schema(description = "Parent span ID, hex-encoded, when this span is part of a longer chain", example = "0a0b0c0d0e0f1011")
    private String parentSpanId;

    @Schema(description = "Span name as emitted by the agent (e.g. tool name or operation)", example = "Bash")
    private String name;

    @Schema(description = "Span kind", example = "internal", allowableValues = { "internal", "server", "client",
            "producer", "consumer" })
    private String kind;

    @Schema(description = "Start of the span (UTC)", example = "2026-05-12T17:30:00Z")
    private Instant startTimestamp;

    @Schema(description = "End of the span (UTC)", example = "2026-05-12T17:30:00.025Z")
    private Instant endTimestamp;

    @Schema(description = "Duration in nanoseconds (end_timestamp - start_timestamp)", example = "25000000")
    private Long durationNanos;

    @Schema(description = "Span status code as emitted by the agent", example = "ok", allowableValues = { "ok",
            "error" })
    private String statusCode;

    @Schema(description = "Free-form status message (typically populated when status_code is \"error\")")
    private String statusMessage;

    @Schema(description = "Instrumentation scope name", example = "claude-code.tools")
    private String scopeName;

    @Schema(description = "Span attributes (free-form key/value bag, stored as jsonb)", example = "{\"command\":\"ls -la\"}")
    private Map<String, Object> attributes;

    @Schema(description = "Span events as [{name, timestamp, attributes}, ...]")
    private List<Map<String, Object>> events;

    @Schema(description = "Resource attributes inherited from the OTLP ResourceSpans envelope",
            example = "{\"service.name\":\"claude-code\"}")
    private Map<String, Object> resourceAttributes;

    @Schema(description = "Cost attributed to this span in USD (the span_costs view): the summed cost_usd of the "
            + "api_request logs Claude Code stamped with this span id — the span that was active when the request "
            + "was issued, which is the interaction root or the tool.execution span, not the llm_request child. "
            + "0 for spans no request was logged against. Summed client-side into the trace detail page's Cost "
            + "KPI, but that sum can come in BELOW TraceSummary.totalCostUsd for the same trace: a request log "
            + "can carry a trace id without a span id (OTLP permits it), and TraceSummary.totalCostUsd counts "
            + "those requests too while no span here can claim them.",
            example = "0.0182")
    private Double costUsd;

    @Schema(description = "Reasoning effort this request ran at (the span_efforts view). Claude Code emits it "
            + "only on the api_request log, never as a span attribute, so it is correlated back onto the span by "
            + "request_id — a key that is globally unique on both sides, making the pairing exact rather than "
            + "heuristic. Null on spans that are not llm_request calls, and on the minority of calls whose log "
            + "recorded no effort (~2% of recent traffic, higher in older data). Null means NOT RECORDED, never "
            + "a default level — don't render it as one.",
            example = "high")
    private String effort;
}
