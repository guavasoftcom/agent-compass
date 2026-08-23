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
