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
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

import com.guavasoft.agentcompass.validation.DateRangeBounds;
import com.guavasoft.agentcompass.validation.ValidDateRange;

/**
 * {@link TimeWindowParams} with both bounds required.
 *
 * <p>Most dashboard endpoints accept a one-sided or absent window and fall back to the
 * {@code ?minutes=} form, which is why {@code TimeWindowParams} leaves both components nullable
 * and {@link com.guavasoft.agentcompass.validation.DateRangeValidator} passes a partial range.
 * The histogram endpoints can't: they bucket with {@code date_bin} anchored on the window start
 * and zero-fill across {@code [start, end]}, so a null bound NPEs in the bucket-width picker
 * before any SQL runs. Binding them to this type turns that 500 into the 400 it always was.
 */
@ValidDateRange
public record RequiredTimeWindowParams(
        @NotNull
        @Parameter(description = "Inclusive lower bound on the histogram window (ISO-8601, required).",
                example = "2026-04-01T00:00:00Z") Instant startTimestamp,
        @NotNull
        @Parameter(description = "Inclusive upper bound on the histogram window (ISO-8601, required). "
                + "Must be within 30 days of startTimestamp.",
                example = "2026-04-30T23:59:59Z") Instant endTimestamp)
        implements DateRangeBounds {
}
