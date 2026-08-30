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

@Schema(name = "MetricSplitRow",
        description = "One attribute-value row within a metric's split breakdown on the Metrics page")
public record MetricSplitRow(
        @Schema(description = "Attribute value label, e.g. the model or token type",
                example = "claude-sonnet-4") String label,
        @Schema(description = "Pre-formatted total for this split value in the window",
                example = "7.8M") String value,
        @Schema(description = "Share of the metric total (0–100)", example = "60") int pct,
        @Schema(description = "Chart palette index, 0 = largest", example = "0") int colorIndex) {
}
