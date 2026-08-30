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

@Schema(description = "One row of flyway_schema_history. Hibernate runs with ddl-auto=validate, so this "
        + "is the whole story of how the schema reached its current shape.")
public record SchemaMigration(
        @Schema(description = "Flyway's apply-order rank, highest last applied", example = "19")
        int installedRank,

        @Schema(description = "Migration version, or null for a repeatable migration", example = "19")
        String version,

        @Schema(description = "Human-readable description from the filename",
                example = "event name column everywhere") String description,

        @Schema(description = "Migration type", example = "SQL") String type,

        @Schema(description = "Migration script filename",
                example = "V19__event_name_column_everywhere.sql") String script,

        @Schema(description = "Whether the migration applied cleanly. A false here means the schema is "
                + "in a failed state and the application would not have started.", example = "true")
        boolean success,

        @Schema(description = "How long the migration took to run, in milliseconds", example = "412")
        int executionTimeMillis,

        @Schema(description = "When the migration was applied. Flyway stores this as a timestamp without "
                + "time zone in the server's local zone; it is read AT TIME ZONE 'UTC', which is correct "
                + "for the bundled compose Postgres and any server running UTC.",
                example = "2026-08-14T09:02:11Z") Instant installedOn) {
}
