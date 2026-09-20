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
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

import com.guavasoft.agentcompass.validation.DateRangeBounds;
import com.guavasoft.agentcompass.validation.ValidDateRange;

/**
 * Query parameters of {@code GET /api/usage/calendar/daily}: a half-open {@code [from, to)} range of
 * whole local days, the IANA zone that defines "a day", and the optional repository scope.
 *
 * <p>Both bounds are required because the rollup zero-fills one row per calendar day across the range,
 * which needs a concrete start and end. The span cap is 42 days rather than the {@code TimeWindowParams}
 * 30: a calendar month can run 31 days, and a six-row month grid spans 42.
 */
@ValidDateRange(maxDays = UsageCalendarParams.MAXIMUM_RANGE_DAYS)
public record UsageCalendarParams(
        @NotNull
        @Parameter(description = "Inclusive start of the range: the first instant of the first local day "
                + "(ISO-8601, a full UTC instant rather than a bare date).",
                example = "2026-08-31T05:00:00Z") Instant from,
        @NotNull
        @Parameter(description = "Exclusive end of the range: the first instant of the local day AFTER the last "
                + "one wanted. Must be within 42 days of from.",
                example = "2026-10-01T05:00:00Z") Instant to,
        @Parameter(description = "IANA time zone that defines the day boundaries (e.g. America/Chicago). "
                + "Defaults to UTC.", example = "America/Chicago") String timeZone,
        @Parameter(description = "Repository URL to scope the result to. Omitted or null means show every "
                + "repository, including telemetry with no repository attribution.",
                example = "https://github.com/guavasoftcom/coding-agent-tuning") String repositoryUrl,
        @Pattern(regexp = "(?i)daily|hourly", message = "granularity must be daily or hourly")
        @Parameter(description = "daily (the default) returns one row per day; hourly also fills each day's "
                + "24-bucket hourly array. Ask for hourly only over the range that needs it: the extra "
                + "queries read the same counters again, split by hour.",
                example = "hourly") String granularity)
        implements DateRangeBounds {

    /** Widest accepted range: six calendar weeks, the tallest month grid. */
    public static final int MAXIMUM_RANGE_DAYS = 42;

    private static final String DEFAULT_TIME_ZONE = "UTC";
    private static final String HOURLY_GRANULARITY = "hourly";

    /**
     * Defaults a missing zone to UTC and normalizes the frontend's "Unattributed" sentinel to
     * {@code null} — see {@link RepositoryUrlFilter}.
     */
    public UsageCalendarParams {
        timeZone = timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone;
        repositoryUrl = RepositoryUrlFilter.normalize(repositoryUrl);
    }

    /** Whether each day should carry its 24 hourly buckets. */
    public boolean includesHourly() {
        return HOURLY_GRANULARITY.equalsIgnoreCase(granularity);
    }

    @Override
    public Instant startTimestamp() {
        return from;
    }

    @Override
    public Instant endTimestamp() {
        return to;
    }
}
