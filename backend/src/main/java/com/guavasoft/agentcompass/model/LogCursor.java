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
 * Keyset cursor for cursor-paged log queries. Serialized as a JSON object
 * {@code {"ts":"…","id":…}} matching the {@code LogCursor} TypeScript interface
 * in {@code logsApi.ts}. The frontend encodes it as {@code "ts,id"} in the
 * {@code before}/{@code after} query params; the service parses that string —
 * it is never surfaced in this serialized form as a raw string.
 */
@Schema(description = "Keyset cursor for deterministic (timestamp, id) paging")
public record LogCursor(
        @Schema(description = "Timestamp of the boundary row", example = "2026-06-07T18:42:11.004Z")
        Instant ts,

        @Schema(description = "Database id of the boundary row", example = "84213")
        long id) {
}
