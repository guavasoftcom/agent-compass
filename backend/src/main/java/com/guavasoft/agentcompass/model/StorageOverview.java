package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Database footprint for the Settings page. Nothing prunes old telemetry — there is "
        + "no retention window, TTL, or cleanup job — so this grows without bound until an operator "
        + "deletes rows by hand. /api/system/purge-preview estimates what such a deletion would reclaim.")
public record StorageOverview(
        @Schema(description = "Every table in the public schema, largest first. Includes the three "
                + "telemetry tables plus flyway_schema_history.") List<TableStorage> tables,

        @Schema(description = "pg_database_size for the connected database — larger than the sum of the "
                + "listed tables, since it also covers system catalogs and free space",
                example = "7699898368") long databaseTotalBytes,

        @Schema(description = "Sum of every table's estimatedBytesPerDay", example = "245366784")
        long estimatedTotalBytesPerDay,

        @Schema(description = "When these figures were read. They are computed live on each request, not "
                + "cached.", example = "2026-08-23T11:47:31Z") Instant measuredAt) {
}
