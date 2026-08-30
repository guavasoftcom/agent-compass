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

import java.util.List;

/**
 * Response DTO for {@code GET /api/logs/facets}. Each dimension is counted with
 * all other active filters applied but its own excluded (standard faceted
 * search). {@code severity} is always zero-filled to four values in
 * ERROR→WARN→INFO→DEBUG order.
 *
 * <p>The {@code scope} dimension is intentionally absent: Claude Code emits
 * exactly one instrumentation scope name for all rows, so the dimension carries
 * no signal and clutters the filter rail.
 */
@Schema(description = "Per-dimension facet counts for the Logs filter rail")
public record LogFacets(
        @Schema(description = "Severity counts — always four entries in ERROR→WARN→INFO→DEBUG order, zero-filled")
        List<FacetValue> severity,

        @Schema(description = "Event-name (attributes['event.name']) counts, descending by count, capped at 50")
        List<FacetValue> event,

        @Schema(description = "Tool name (attributes['tool_name']) counts, descending by count, capped at 50")
        List<FacetValue> tool) {
}
