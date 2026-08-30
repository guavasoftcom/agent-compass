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

@Schema(description = "What deleting one table's rows would remove. The purge is gated on whole "
        + "sessions, not row age alone — a row older than the cutoff still survives if its session.id "
        + "has activity anywhere more recently than that, so a session is always purged entirely or "
        + "not at all, never partially. Row counts are exact; byte figures are proportional estimates, "
        + "because the only exact answer requires summing pg_column_size per row, which detoasts every "
        + "row it touches.")
public record PurgeTableEstimate(
        @Schema(description = "Table name", example = "log_records") String tableName,

        @Schema(description = "Column the cutoff is applied to. spans has no timestamp column — it uses "
                + "start_timestamp.", example = "timestamp") String timestampColumn,

        @Schema(description = "Exact count of rows the purge would delete — rows older than the "
                + "cutoff, less any this table preserves", example = "88412") long rowsToDelete,

        @Schema(description = "Rows older than the cutoff that a purge deliberately keeps. Almost "
                + "entirely rows belonging to a session that is still active in some other signal — "
                + "protecting them is what stops a session from being split, part purged and part kept. "
                + "A small residual (metric_points only) covers a metric point with no session.id at "
                + "all: the newest row of its stream survives regardless of session state, because "
                + "value_delta is computed against a row's predecessor and a stream left without one "
                + "would book its whole cumulative counter as one increment on its next emission.",
                example = "585473") long preservedRows,

        @Schema(description = "Exact total row count, for context", example = "132254") long totalRows,

        @Schema(description = "rowsToDelete as a percentage of totalRows, zero for an empty table",
                example = "66.8") double sharePercent,

        @Schema(description = "Estimated bytes the deletion would free, as "
                + "pg_total_relation_size * rowsToDelete / totalRows. Assumes deleted rows are of "
                + "average size, which understates a purge of older log_records if attribute payloads "
                + "have grown over time.", example = "1198765432") long estimatedReclaimableBytes,

        @Schema(description = "The single DELETE statement for this table",
                example = "DELETE FROM log_records WHERE timestamp < TIMESTAMPTZ '2026-07-24T11:47:31Z';")
        String statement) {
}
