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

@Schema(name = "MetricAttributes",
        description = "The filterable attributes of one metric over a window, for the Metrics filter "
                + "picker. Empty when the metric has no qualifying attribute or was never seen.")
public record MetricAttributes(
        @Schema(description = "Attribute keys (alphabetical), each with its distinct values (most common "
                + "first, ties alphabetical). Keys with more than 25 distinct values are omitted.")
        List<MetricFacet> attributes) {
}
