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
package com.guavasoft.agentcompass.validation;

import java.time.Instant;

/**
 * Contract for any parameter type carrying an optional custom {@code startTimestamp}/
 * {@code endTimestamp} pair that {@link DateRangeValidator} can bound. Implemented by
 * {@link com.guavasoft.agentcompass.model.TimeWindowParams} (a record, whose component
 * accessors satisfy this interface automatically) and by
 * {@link com.guavasoft.agentcompass.model.TraceFilterParams} (a Lombok {@code @Getter}/
 * {@code @Setter} class, which implements these methods explicitly since its Lombok-generated
 * accessors are named {@code getStartTimestamp()}/{@code getEndTimestamp()} instead).
 */
public interface DateRangeBounds {

    Instant startTimestamp();

    Instant endTimestamp();
}
