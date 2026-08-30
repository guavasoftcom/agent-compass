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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, DateRangeBounds> {

    private static final long SECONDS_PER_DAY = 86_400L;

    private int maxDays;

    @Override
    public void initialize(ValidDateRange constraint) {
        maxDays = constraint.maxDays();
    }

    @Override
    public boolean isValid(DateRangeBounds params, ConstraintValidatorContext context) {
        if (params.startTimestamp() == null || params.endTimestamp() == null) {
            return true;
        }
        long rangeSeconds = Duration.between(params.startTimestamp(), params.endTimestamp()).getSeconds();
        return rangeSeconds <= (long) maxDays * SECONDS_PER_DAY;
    }
}
