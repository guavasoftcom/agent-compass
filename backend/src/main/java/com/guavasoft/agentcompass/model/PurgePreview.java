package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "A dry run of a retention cutoff. This endpoint never deletes anything — "
        + "DELETE /api/system/telemetry does, behind a confirmation phrase — it only measures what "
        + "that would remove and hands back the SQL to run yourself instead. The purge is gated on "
        + "whole sessions, not row age: a row belonging to a session that is still active in any "
        + "signal survives however old it is, so a session is always purged entirely or not at all. "
        + "Two more things worth knowing: a DELETE makes space reusable by Postgres but does not "
        + "return it to the operating system without VACUUM FULL; and metric_points.value_delta is "
        + "precomputed at ingest, so removing old points does not corrupt the increments on the ones "
        + "that remain.")
public record PurgePreview(
        @Schema(description = "Retention window that was previewed, in days", example = "30")
        int retentionDays,

        @Schema(description = "Rows strictly older than this instant would be deleted",
                example = "2026-07-24T11:47:31Z") Instant cutoff,

        @Schema(description = "Per-table estimates, largest reclaim first")
        List<PurgeTableEstimate> tables,

        @Schema(description = "Total exact rows the cutoff would delete across all three tables",
                example = "1204118") long totalRowsToDelete,

        @Schema(description = "Sum of the per-table byte estimates", example = "2438765432")
        long estimatedReclaimableBytes,

        @Schema(description = "The full script — one DELETE per table plus a trailing VACUUM note — "
                + "ready to paste into psql") String sql) {
}
