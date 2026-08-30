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
import java.util.List;

@Schema(description = "Every tuning.* property the running instance resolved, with the SQL-mirroring "
        + "warning attached. Defaults match Claude Code's emission shape; an override that is flagged "
        + "MIRRORED but has no accompanying migration is the failure mode this endpoint exists to "
        + "make visible, because it produces empty pages rather than errors.")
public record EffectiveConfiguration(
        @Schema(description = "Properties grouped by what they drive") List<ConfigurationGroup> groups,

        @Schema(description = "Total properties across every group. A completeness test asserts this "
                + "equals the field count on TuningProperties, so a newly added property fails the "
                + "build until it is classified.", example = "47") int propertyCount,

        @Schema(description = "How many properties differ from their compiled-in default", example = "2")
        int overriddenCount) {
}
