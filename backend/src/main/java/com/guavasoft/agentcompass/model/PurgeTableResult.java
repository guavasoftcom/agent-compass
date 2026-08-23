package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What one table actually gave up. Counts are what Postgres reported deleting, "
        + "not what the preview predicted — live ingest between the two moves the boundary slightly.")
public record PurgeTableResult(
        @Schema(description = "Table name", example = "log_records") String tableName,

        @Schema(description = "Rows actually deleted", example = "85267") long rowsDeleted,

        @Schema(description = "Rows remaining afterwards", example = "50861") long rowsRemaining) {
}
