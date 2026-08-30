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
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "EventRow", description = "One persisted OTLP metric data point, shaped for the dashboard grid")
public class EventRow {

    @Schema(description = "Database primary key", example = "42")
    private Long id;

    @Schema(description = "OTLP metric name", example = "claude_code.code_edit_tool.decision")
    private String metricName;

    @Schema(description = "Human-readable metric description as emitted by the instrumentation, if any",
            example = "Tracks code-edit tool acceptance decisions")
    private String description;

    @Schema(description = "OTLP metric unit (UCUM-style), if the instrumentation set one", example = "tokens")
    private String unit;

    @Schema(description = "Instrumentation scope name from the OTLP InstrumentationScope envelope", example = "io.opentelemetry.metrics")
    private String scopeName;

    @Schema(description = "Wall-clock timestamp of the data point (UTC)", example = "2026-05-12T17:30:00Z")
    private Instant timestamp;

    @Schema(description = "Numeric value when the data point carries an OTLP AS_DOUBLE", example = "1.5")
    private Double valueDouble;

    @Schema(description = "Numeric value when the data point carries an OTLP AS_INT", example = "1")
    private Long valueLong;

    @Schema(description = "Which value field is populated for this row", example = "long", allowableValues = { "long",
            "double", "unknown" })
    private String valueKind;

    @Schema(description = "Data-point attributes (free-form key/value bag, stored as jsonb)",
            example = "{\"tool\":\"Read\",\"decision\":\"accept\"}")
    private Map<String, Object> attributes;

    @Schema(description = "Resource attributes inherited from the OTLP ResourceMetrics envelope",
            example = "{\"service.name\":\"claude-code\"}")
    private Map<String, Object> resourceAttributes;
}
