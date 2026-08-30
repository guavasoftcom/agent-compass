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

@Schema(description = "Four-way claude_code.token.usage split by type (reset-aware SUM(value_delta) per kind). "
        + "Every field is non-null; a kind with no points in scope reads 0.")
public record SessionTokenBreakdown(
        @Schema(description = "Sum of value_delta for type='input'", example = "61200") long input,

        @Schema(description = "Sum of value_delta for type='output'", example = "148500") long output,

        @Schema(description = "Sum of value_delta for type='cacheCreation'", example = "402300") long cacheCreation,

        @Schema(description = "Sum of value_delta for type='cacheRead'", example = "4788000") long cacheRead) {

    /** Sum of the four kinds -- the single source of truth for any "total tokens" figure. */
    public long total() {
        return input + output + cacheCreation + cacheRead;
    }
}
