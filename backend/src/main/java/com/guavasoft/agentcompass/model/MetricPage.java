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
 * Response DTO for offset-paged {@code GET /api/metrics} (Metrics DataGrid).
 * Mirrors {@link LogPage}, which follows the same {@code {items, totalCount}}
 * envelope for the Logs Table view.
 */
@Schema(description = "One page of offset-paged metric data points")
public record MetricPage(
        @Schema(description = "Metric data points for this page")
        List<EventRow> items,

        @Schema(description = "Total matching records across all pages", example = "3443")
        long totalCount) {
}
