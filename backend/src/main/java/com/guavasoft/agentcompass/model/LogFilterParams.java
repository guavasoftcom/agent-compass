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

import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Parameter object for the shared log-filter query params consumed by the histogram, facets,
 * and logs endpoints. Bound via {@code @ModelAttribute} from repeated query-string entries.
 *
 * <p>Intentionally a Lombok {@code @Getter}/{@code @Setter} class rather than a record. Spring's
 * constructor-binding path for records does not reliably collect repeated query params
 * ({@code ?filter=a&filter=b}) into a {@code List<String>} constructor argument — the
 * {@code WebDataBinder} setter path is the only mechanism guaranteed to work for that case.
 * Records are the codebase default for DTOs, but query-param List binding needs setter binding.
 *
 * <p>{@code severity} is intentionally excluded. The histogram endpoint always renders all
 * four severity series and explicitly passes an empty severity list to the repository; folding
 * severity here would make histogram advertise a param it silently ignores.
 */
@Getter
@Setter
public class LogFilterParams {

    @Parameter(
            description = "Active key=value attribute filters; rows must contain all of them",
            example = "event.name=tool_result")
    private List<String> filter;

    @Parameter(
            description = "event.name values to include; rows must match at least one",
            example = "tool_result")
    private List<String> event;

    @Parameter(
            description = "Tool or server names to include; rows must match at least one",
            example = "Bash")
    private List<String> tool;

    /**
     * Full-text search term. Bound as {@code q} — the wire name the frontend's
     * {@code buildLogsQuery} emits and the name {@link TraceFilterParams} already uses for the
     * same concept. Spring binds by field name, so this one deliberately breaks the
     * spelled-out-names rule: renaming it re-breaks the wire contract, and a mismatch here
     * fails silently (the criteria coerce null to {@code ""}, which every query treats as
     * "no full-text filter").
     */
    @Parameter(
            description = "Full-text search over log body and serialised attributes",
            example = "old_string not unique")
    private String q;
}
