package com.guavasoft.agentcompass.model;

import java.time.Instant;
import java.util.List;

/**
 * Bundles all shared WHERE-clause inputs for the three Logs-page endpoints
 * (histogram, facets, row list). Building the criteria once in the service
 * keeps all three repository queries on the same contract.
 *
 * <p>The {@code scopes} dimension has been removed: Claude Code emits exactly
 * one instrumentation scope name for all rows, so it carries no filter signal.
 * The {@code scope_name} column is still mapped on {@code LogRecordEntity} and
 * shown in the row DTO; only the facet and filter are gone.
 */
public record LogQueryCriteria(
        Instant startTimestamp,
        Instant endTimestamp,
        String[] filters,
        String[] severities,
        String[] events,
        String[] tools,
        String fullTextQuery) {

    /**
     * Normalizes the window to Postgres's microsecond resolution — see
     * {@link QueryWindowPrecision}, without which the histogram zero-fill cannot
     * match the buckets {@code date_bin} returns.
     */
    public LogQueryCriteria {
        startTimestamp = QueryWindowPrecision.toDatabasePrecision(startTimestamp);
        endTimestamp = QueryWindowPrecision.toDatabasePrecision(endTimestamp);
    }

    /** Convenience factory that coerces null lists to empty arrays. */
    public static LogQueryCriteria of(
            Instant startTimestamp,
            Instant endTimestamp,
            List<String> filters,
            List<String> severities,
            List<String> events,
            List<String> tools,
            String fullTextQuery) {
        return new LogQueryCriteria(
                startTimestamp,
                endTimestamp,
                toArray(filters),
                toArray(severities),
                toArray(events),
                toArray(tools),
                fullTextQuery == null ? "" : fullTextQuery);
    }

    private static String[] toArray(List<String> list) {
        return list == null ? new String[0] : list.toArray(new String[0]);
    }
}
