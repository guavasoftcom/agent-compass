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
 * Response DTO for cursor-paged {@code GET /api/logs} (Stream and live-tail
 * modes). {@code nextCursor} is an object {@code {ts, id}} matching the
 * TypeScript {@code LogCursor} interface in {@code logsApi.ts} — it is
 * {@code null} when the page is empty or there are no more rows.
 */
@Schema(description = "One page of cursor-paged log records, newest first")
public record LogCursorPage(
        @Schema(description = "Log records for this page, newest first")
        List<LogRecord> items,

        @Schema(description = "Cursor for the next (older) page, or null when there are no more rows",
                nullable = true)
        LogCursor nextCursor,

        @Schema(description = "True when the page filled to the requested limit and more rows exist beyond")
        boolean hasMore,

        @Schema(description = "Total matching records for the full window (ignoring the cursor). "
                + "Only computed on the initial page (no before/after cursor); continuation and "
                + "live-tail pages return 0 and the client keeps its own running total.",
                example = "3443")
        long totalCount) {
}
