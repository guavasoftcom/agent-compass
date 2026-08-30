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
import java.util.List;

@Schema(description = "The outcome of a completed purge. This is the response to the only endpoint in "
        + "the application that deletes data; it ran, and it cannot be undone. Note that "
        + "databaseTotalBytesAfter will usually be close to databaseTotalBytesBefore: a DELETE marks "
        + "space reusable by Postgres but does not hand it back to the operating system, which needs "
        + "VACUUM FULL — that cannot run inside this transaction and is left to the operator.")
public record PurgeResult(
        @Schema(description = "Retention window that was applied, in days", example = "30")
        int retentionDays,

        @Schema(description = "Sessions whose last activity, across all three signals, was older "
                + "than this instant were deleted in full", example = "2026-07-24T11:47:31Z")
        Instant cutoff,

        @Schema(description = "Per-table outcome") List<PurgeTableResult> tables,

        @Schema(description = "Total rows deleted across all three tables", example = "2380480")
        long totalRowsDeleted,

        @Schema(description = "Rows older than the cutoff that were deliberately kept rather than "
                + "deleted. Almost entirely rows belonging to a session still active in some other "
                + "signal — the whole-session gating that stops a session from being split, part "
                + "purged and part kept. A small residual (metric_points only) covers a metric point "
                + "with no session id, whose stream's newest row is kept regardless so a still-live "
                + "counter's next emission does not record its entire cumulative value as one "
                + "increment.", example = "585473") long preservedRows,

        @Schema(description = "pg_database_size before the purge", example = "7822851095")
        long databaseTotalBytesBefore,

        @Schema(description = "pg_database_size after the purge and ANALYZE. Expect little movement "
                + "— see the note on this schema.", example = "7822851095")
        long databaseTotalBytesAfter,

        @Schema(description = "How long the delete plus ANALYZE took", example = "48213")
        long durationMillis,

        @Schema(description = "SQL the operator can run to actually shrink the files on disk. Not "
                + "executed here: VACUUM FULL takes an ACCESS EXCLUSIVE lock and needs room for a "
                + "full table rewrite, so when and whether to run it is the operator's call.",
                example = "VACUUM FULL log_records, metric_points, spans;") String reclaimSpaceSql,

        @Schema(description = "When the purge completed", example = "2026-08-23T11:48:19Z")
        Instant completedAt) {
}
