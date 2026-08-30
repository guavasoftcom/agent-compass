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

import java.time.Instant;
import java.util.List;

/**
 * Bundles all shared WHERE-clause inputs for the three Traces-page endpoints
 * (histogram, facets, row list). Building the criteria once in the service keeps
 * all three repository queries on the same contract — the "one WHERE clause"
 * invariant that makes totalCount == histogram sum == facet status totals.
 */
public record TraceQueryCriteria(
        Instant startTimestamp,
        Instant endTimestamp,
        String[] statuses,
        String[] operations,
        String[] services,
        String[] durations,
        String[] sessions,
        String fullTextQuery) {

    /**
     * Normalizes the window to Postgres's microsecond resolution — see
     * {@link QueryWindowPrecision}, without which the histogram zero-fill cannot
     * match the buckets {@code date_bin} returns.
     */
    public TraceQueryCriteria {
        startTimestamp = QueryWindowPrecision.toDatabasePrecision(startTimestamp);
        endTimestamp = QueryWindowPrecision.toDatabasePrecision(endTimestamp);
    }

    /** Convenience factory that coerces null lists to empty arrays. */
    public static TraceQueryCriteria of(
            Instant startTimestamp,
            Instant endTimestamp,
            List<String> statuses,
            List<String> operations,
            List<String> services,
            List<String> durations,
            List<String> sessions,
            String fullTextQuery) {
        return new TraceQueryCriteria(
                startTimestamp,
                endTimestamp,
                toArray(statuses),
                toArray(operations),
                toArray(services),
                toArray(durations),
                toArray(sessions),
                fullTextQuery == null ? "" : fullTextQuery);
    }

    private static String[] toArray(List<String> list) {
        return list == null ? new String[0] : list.toArray(new String[0]);
    }
}
