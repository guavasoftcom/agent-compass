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

@Schema(description = "On-disk footprint and time span of one table. Sizes come from pg_class and "
        + "decompose exactly: heapBytes + indexBytes + toastBytes == totalBytes. Row counts are exact "
        + "COUNT(*) rather than pg_class.reltuples, which is unreliable on this schema (a freshly "
        + "migrated database reports -1, and n_live_tup was measured reporting 6 for a 19-row table).")
public record TableStorage(
        @Schema(description = "Table name", example = "metric_points") String tableName,

        @Schema(description = "Exact row count", example = "4590123") long rowCount,

        @Schema(description = "Main-fork (heap) bytes, excluding indexes and TOAST", example = "3221225472")
        long heapBytes,

        @Schema(description = "Total bytes across every index on the table", example = "1073741824")
        long indexBytes,

        @Schema(description = "Bytes in the table's TOAST relation and its index. Dominates log_records, "
                + "whose attributes payload averages ~11 KB per row.", example = "1546188226")
        long toastBytes,

        @Schema(description = "pg_total_relation_size — heap + indexes + TOAST", example = "5841378122")
        long totalBytes,

        @Schema(description = "Timestamp of the oldest row, or null when the table is empty. Reads the "
                + "table's own time column (spans use start_timestamp).", example = "2026-05-22T09:14:02Z")
        Instant oldestTimestamp,

        @Schema(description = "Timestamp of the newest row, or null when the table is empty",
                example = "2026-08-23T11:47:31Z") Instant newestTimestamp,

        @Schema(description = "Rows whose timestamp falls in the last seven days — the numerator of the "
                + "growth estimate", example = "331520") long rowsLastSevenDays,

        @Schema(description = "Estimated bytes added per day: average on-disk bytes per row "
                + "(totalBytes / rowCount) multiplied by the last seven days' insert rate. Assumes row "
                + "size is stationary, and is zero for an empty table. An estimate, not a measurement — "
                + "no historical size samples are retained.", example = "201326592")
        long estimatedBytesPerDay) {
}
