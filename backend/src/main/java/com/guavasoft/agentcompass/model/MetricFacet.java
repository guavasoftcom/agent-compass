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

@Schema(name = "MetricFacet",
        description = "One attribute key carried by the selected metric's data points in the window, with "
                + "its distinct values. Powers the Metrics page filter picker. Keys with too many "
                + "distinct values to be useful in a picker (session ids and similar) are omitted.")
public record MetricFacet(
        @Schema(description = "Attribute key", example = "model") String key,
        @Schema(description = "Distinct values for this key, most common first (ties alphabetical)")
        List<MetricFacetValue> values) {
}
