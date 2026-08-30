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
 * Response DTO for offset-paged {@code GET /api/traces} (Table mode).
 * Matches the {@code TracesListResult} TypeScript interface in {@code tracesApi.ts}.
 */
@Schema(description = "One page of offset-paged trace summaries")
public record TracePage(
        @Schema(description = "Trace summaries for this page, in requested sort order")
        List<TraceSummary> items,

        @Schema(description = "Total matching traces across all pages", example = "540")
        long totalCount) {
}
