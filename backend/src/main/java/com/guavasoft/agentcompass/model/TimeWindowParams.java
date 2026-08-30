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
import java.time.Instant;

import com.guavasoft.agentcompass.validation.DateRangeBounds;
import com.guavasoft.agentcompass.validation.ValidDateRange;

@ValidDateRange
public record TimeWindowParams(
        @Parameter(description = "Inclusive lower bound on the custom range (ISO-8601). "
                + "Overrides minutes when paired with endTimestamp.", example = "2026-04-01T00:00:00Z") Instant startTimestamp,
        @Parameter(description = "Inclusive upper bound on the custom range (ISO-8601). "
                + "Overrides minutes when paired with startTimestamp. "
                + "Must be within 30 days of startTimestamp.", example = "2026-04-30T23:59:59Z") Instant endTimestamp)
        implements DateRangeBounds {
}
