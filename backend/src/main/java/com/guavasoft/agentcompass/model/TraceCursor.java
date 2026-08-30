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

import java.time.Instant;

/**
 * Keyset cursor for cursor-paged trace queries. Serialized as a JSON object
 * {@code {"ts":"…","id":"…"}} matching the {@code TraceCursor} TypeScript interface
 * in {@code tracesApi.ts}. The frontend encodes it as {@code "ts,id"} in the
 * {@code before}/{@code after} query params; the service parses that string — it is
 * never surfaced in this serialized form as a raw string.
 *
 * <p>Note: unlike {@link LogCursor} where {@code id} is a long (database PK),
 * the trace cursor {@code id} is the {@code traceId} hex string because the
 * traces list is aggregated and has no single-row PK. The sort-key value for
 * non-time sorts is resolved server-side from the traceId on each paging call.
 */
@Schema(description = "Keyset cursor for deterministic trace paging")
public record TraceCursor(
        @Schema(description = "Start timestamp of the boundary trace row",
                example = "2026-06-12T18:42:11.004Z")
        Instant ts,

        @Schema(description = "traceId hex string of the boundary row",
                example = "7ed9599a86cafe01beefcafe01234567")
        String id) {
}
