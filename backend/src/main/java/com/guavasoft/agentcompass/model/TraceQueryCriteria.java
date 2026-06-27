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
