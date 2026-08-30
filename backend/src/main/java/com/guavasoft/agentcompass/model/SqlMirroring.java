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

/**
 * Whether a {@code tuning.*} property's value also appears as a literal inside a Flyway migration,
 * and therefore whether overriding it silently breaks the pages that read those objects.
 *
 * <p>Native {@code @Query} SQL and migration DDL cannot read Spring properties at parse time, so
 * several defaults are duplicated into generated columns, views, functions, and partial-index
 * predicates. The duplication is deliberate and documented on {@code TuningProperties} and in
 * {@code AGENTS.md}; this enum is what surfaces it at runtime.
 */
@Schema(description = "Whether overriding a property also requires a Flyway migration")
public enum SqlMirroring {

    /** Read only through bind parameters. Safe to override in {@code application.yml} alone. */
    @Schema(description = "Read only through bind parameters — safe to override on its own")
    NOT_MIRRORED,

    /**
     * The property's value is duplicated as a literal in migration SQL. Overriding it means a new
     * migration redefining the affected generated column, view, function, or index predicate —
     * otherwise the pages reading them silently resolve the wrong rows.
     */
    @Schema(description = "Duplicated as a literal in migration SQL — overriding requires a new "
            + "migration redefining the affected object")
    MIRRORED,

    /**
     * The property's default happens to equal a literal that migration SQL hardcodes for its own
     * reasons, without the property being that literal's source. Overriding the property does not
     * invalidate the SQL, but the two then describe different things — worth knowing before
     * assuming a change took effect everywhere.
     */
    @Schema(description = "Shares a literal that migration SQL hardcodes independently — overriding "
            + "does not invalidate the SQL, but the two then diverge")
    SHARED_LITERAL
}
