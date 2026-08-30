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

@Schema(name = "TokenDistribution", description = "Token-band heatmap data for the Metrics distribution panel. "
        + "bands is the ordered list of Y-axis labels (top → bottom). exemplars are sampled "
        + "high-token requests placed on the col × row grid.")
public record TokenDistribution(
        @Schema(description = "Y-axis band labels ordered top-to-bottom",
                example = "[\"256K\",\"128K\",\"64K\",\"32K\",\"16K\",\"8K\",\"4K\",\"0\"]") List<String> bands,
        @Schema(description = "Sampled exemplar requests, up to 8") List<ExemplarPoint> exemplars) {
}
