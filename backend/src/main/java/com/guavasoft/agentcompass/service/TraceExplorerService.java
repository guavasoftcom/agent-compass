package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.model.FacetValue;
import com.guavasoft.agentcompass.model.TraceCursor;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceFacets;
import com.guavasoft.agentcompass.model.TraceHistogram;
import com.guavasoft.agentcompass.model.TraceHistogramBucket;
import com.guavasoft.agentcompass.model.TracePage;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregation service for the Trace Explorer page: histogram, facets, cursor-paged
 * stream, and offset-paged table. All three endpoints share the same CTE-based WHERE
 * clause so totalCount == histogram sum == facet status totals for identical filters.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraceExplorerService {

    private static final long[] HISTOGRAM_LADDER_SECONDS = {
        60L, 120L, 300L, 600L, 900L, 1800L, 3600L, 7200L, 10800L, 21600L
    };
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final int DEFAULT_HISTOGRAM_BUCKETS = 48;
    private static final int DEFAULT_CURSOR_LIMIT = 60;
    private static final long CONTINUATION_PAGE_TOTAL_COUNT = 0L;
    private static final int FACET_OPERATION_CAP = 50;
    private static final int FACET_SERVICE_CAP = 50;
    private static final int FACET_SESSION_CAP = 8;

    private static final String SORT_NEW = "new";
    private static final String SORT_OLD = "old";
    private static final String SORT_SLOW = "slow";
    private static final String SORT_FAST = "fast";
    private static final String SORT_SPANS = "spans";
    private static final String SORT_ERR = "err";
    private static final String SORT_TOKENS = "tokens";

    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";
    private static final String DURATION_D0 = "d0";
    private static final String DURATION_D1 = "d1";
    private static final String DURATION_D2 = "d2";
    private static final String DURATION_D3 = "d3";

    /**
     * Resolved sort-key values for a cursor trace row. All five values are populated
     * from the single {@code traceCursorSortKey} lookup; each keyset branch uses only
     * the fields it needs.
     */
    private record CursorSortKey(
            double durationMs,
            long spanCount,
            long errorCount,
            Instant minStart,
            long totalTokens) {
    }

    private final SpanRepository spanRepository;

    // =========================================================================
    // Histogram
    // =========================================================================

    /** Builds the throughput histogram with per-bucket p95 and window-wide aggregates. */
    public TraceHistogram histogram(TraceQueryCriteria criteria, int targetBuckets) {
        int resolvedTargetBuckets = targetBuckets <= 0 ? DEFAULT_HISTOGRAM_BUCKETS : targetBuckets;
        Instant windowStart = criteria.startTimestamp();
        Instant windowEnd = criteria.endTimestamp();
        long bucketSeconds = pickBucketSeconds(windowStart, windowEnd, resolvedTargetBuckets);
        long bucketMs = bucketSeconds * MILLIS_PER_SECOND;

        List<Object[]> bucketRows = spanRepository.traceHistogramBuckets(
                windowStart, windowEnd, bucketSeconds,
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());

        List<Object[]> globalRows = spanRepository.traceHistogramGlobals(
                windowStart, windowEnd,
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());

        Map<Instant, Object[]> rowByBucket = new HashMap<>();
        for (Object[] row : bucketRows) {
            rowByBucket.put((Instant) row[0], row);
        }

        List<TraceHistogramBucket> buckets = buildZeroFilledBuckets(
                windowStart, windowEnd, bucketSeconds, rowByBucket);

        Object[] globals = globalRows.isEmpty() ? new Object[]{0L, 0L, 0.0, 0.0} : globalRows.get(0);
        long total = globals[0] == null ? 0L : ((Number) globals[0]).longValue();
        long errorCount = globals[1] == null ? 0L : ((Number) globals[1]).longValue();
        double p50Ms = globals[2] == null ? 0.0 : ((Number) globals[2]).doubleValue();
        double p95Ms = globals[3] == null ? 0.0 : ((Number) globals[3]).doubleValue();

        return new TraceHistogram(bucketMs, buckets, p50Ms, p95Ms, total, errorCount);
    }

    private List<TraceHistogramBucket> buildZeroFilledBuckets(
            Instant windowStart,
            Instant windowEnd,
            long bucketSeconds,
            Map<Instant, Object[]> rowByBucket) {

        List<TraceHistogramBucket> buckets = new ArrayList<>();
        Instant bucketStart = windowStart;
        while (!bucketStart.isAfter(windowEnd)) {
            Instant bucketEnd = bucketStart.plusSeconds(bucketSeconds);
            Object[] row = rowByBucket.get(bucketStart);
            long okCount = row != null && row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long errorBucketCount = row != null && row[2] != null ? ((Number) row[2]).longValue() : 0L;
            double p95BucketMs = row != null && row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
            buckets.add(new TraceHistogramBucket(bucketStart, bucketEnd, okCount, errorBucketCount, p95BucketMs));
            bucketStart = bucketEnd;
        }
        return buckets;
    }

    private static long pickBucketSeconds(Instant windowStart, Instant windowEnd, int targetBuckets) {
        long windowSeconds = Math.max(1L, Duration.between(windowStart, windowEnd).getSeconds());
        long rawSeconds = windowSeconds / Math.max(1, targetBuckets);
        for (long stepSeconds : HISTOGRAM_LADDER_SECONDS) {
            if (stepSeconds >= rawSeconds) {
                return stepSeconds;
            }
        }
        return HISTOGRAM_LADDER_SECONDS[HISTOGRAM_LADDER_SECONDS.length - 1];
    }

    // =========================================================================
    // Facets
    // =========================================================================

    /** Returns per-dimension facet counts with standard self-exclusion. */
    public TraceFacets facets(TraceQueryCriteria criteria) {
        List<FacetValue> statusFacets = buildStatusFacets(criteria);
        List<FacetValue> operationFacets = buildOperationFacets(criteria);
        List<FacetValue> serviceFacets = buildServiceFacets(criteria);
        List<FacetValue> durationFacets = buildDurationFacets(criteria);
        List<FacetValue> sessionFacets = buildSessionFacets(criteria);
        return new TraceFacets(statusFacets, operationFacets, serviceFacets, durationFacets, sessionFacets);
    }

    private List<FacetValue> buildStatusFacets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceStatus(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());

        Map<String, Long> countsByStatus = new HashMap<>();
        for (Object[] row : rows) {
            countsByStatus.put((String) row[0], ((Number) row[1]).longValue());
        }
        return List.of(
                new FacetValue(STATUS_OK, countsByStatus.getOrDefault(STATUS_OK, 0L)),
                new FacetValue(STATUS_ERROR, countsByStatus.getOrDefault(STATUS_ERROR, 0L)));
    }

    private List<FacetValue> buildOperationFacets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceOperation(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                FACET_OPERATION_CAP);
        return toFacetValues(rows);
    }

    private List<FacetValue> buildServiceFacets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceService(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                FACET_SERVICE_CAP);
        return toFacetValues(rows);
    }

    private List<FacetValue> buildDurationFacets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceDuration(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(),
                criteria.services(), criteria.sessions(), criteria.fullTextQuery());

        Map<String, Long> countsByBucket = new HashMap<>();
        for (Object[] row : rows) {
            countsByBucket.put((String) row[0], ((Number) row[1]).longValue());
        }
        return List.of(
                new FacetValue(DURATION_D0, countsByBucket.getOrDefault(DURATION_D0, 0L)),
                new FacetValue(DURATION_D1, countsByBucket.getOrDefault(DURATION_D1, 0L)),
                new FacetValue(DURATION_D2, countsByBucket.getOrDefault(DURATION_D2, 0L)),
                new FacetValue(DURATION_D3, countsByBucket.getOrDefault(DURATION_D3, 0L)));
    }

    private List<FacetValue> buildSessionFacets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceSession(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(),
                criteria.services(), criteria.durations(), criteria.fullTextQuery(),
                FACET_SESSION_CAP);
        return toFacetValues(rows);
    }

    private static List<FacetValue> toFacetValues(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new FacetValue((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    // =========================================================================
    // Cursor paging
    // =========================================================================

    /**
     * Entry point for cursor-paged requests. Dispatches to before/after/initial
     * variants based on which cursor param is present. Cursor parsing lives here,
     * not in the controller.
     */
    public TraceCursorPage cursorPage(
            TraceQueryCriteria criteria, String sort, String before, String after, int limit) {
        if (before != null) {
            return cursorPageBefore(criteria, sort, parseCursor(before), limit);
        }
        if (after != null) {
            return cursorPageAfter(criteria, sort, parseCursor(after), limit);
        }
        return cursorPageFirst(criteria, sort, limit);
    }

    private TraceCursorPage cursorPageFirst(TraceQueryCriteria criteria, String sort, int limit) {
        int resolvedLimit = limit <= 0 ? DEFAULT_CURSOR_LIMIT : limit;
        long totalCount = spanRepository.countFilteredTraces(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());

        List<TraceSummary> probeItems = fetchSortedCursorRows(
                criteria, sort, resolvedLimit + 1);
        return buildCursorPage(probeItems, resolvedLimit, totalCount);
    }

    private TraceCursorPage cursorPageBefore(
            TraceQueryCriteria criteria, String sort, TraceCursor cursor, int limit) {
        int resolvedLimit = limit <= 0 ? DEFAULT_CURSOR_LIMIT : limit;
        List<TraceSummary> probeItems = fetchSortedCursorRowsBefore(
                criteria, sort, cursor, resolvedLimit + 1);
        return buildCursorPage(probeItems, resolvedLimit, CONTINUATION_PAGE_TOTAL_COUNT);
    }

    private TraceCursorPage cursorPageAfter(
            TraceQueryCriteria criteria, String sort, TraceCursor cursor, int limit) {
        int resolvedLimit = limit <= 0 ? DEFAULT_CURSOR_LIMIT : limit;
        List<TraceSummary> probeItems = fetchSortedCursorRowsAfter(
                criteria, sort, cursor, resolvedLimit + 1);
        return buildCursorPage(probeItems, resolvedLimit, CONTINUATION_PAGE_TOTAL_COUNT);
    }

    private List<TraceSummary> fetchSortedCursorRows(
            TraceQueryCriteria criteria, String sort, int pageLimit) {
        List<Object[]> rows;
        switch (sort) {
            case SORT_SLOW:
                rows = spanRepository.traceListSortSlowCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_FAST:
                rows = spanRepository.traceListSortFastCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_SPANS:
                rows = spanRepository.traceListSortSpansCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_ERR:
                rows = spanRepository.traceListSortErrCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_TOKENS:
                rows = spanRepository.traceListSortTokensCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            default:
                rows = spanRepository.traceListSortNew(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
        }
        return toTraceSummaries(rows);
    }

    private List<TraceSummary> fetchSortedCursorRowsBefore(
            TraceQueryCriteria criteria, String sort, TraceCursor cursor, int pageLimit) {
        CursorSortKey sortKey = resolveCursorSortKey(criteria, cursor);
        if (sortKey == null) {
            return fetchSortedCursorRows(criteria, sort, pageLimit);
        }
        List<Object[]> rows;
        switch (sort) {
            case SORT_SLOW:
                rows = spanRepository.traceListSortSlowBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.durationMs(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_FAST:
                rows = spanRepository.traceListSortFastBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.durationMs(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_SPANS:
                rows = spanRepository.traceListSortSpansBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.spanCount(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_ERR:
                rows = spanRepository.traceListSortErrBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.errorCount(), sortKey.minStart(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_TOKENS:
                rows = spanRepository.traceListSortTokensBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.totalTokens(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            default:
                rows = spanRepository.traceListSortNewBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        cursor.ts(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
        }
        return toTraceSummaries(rows);
    }

    private List<TraceSummary> fetchSortedCursorRowsAfter(
            TraceQueryCriteria criteria, String sort, TraceCursor cursor, int pageLimit) {
        CursorSortKey sortKey = resolveCursorSortKey(criteria, cursor);
        if (sortKey == null) {
            return fetchSortedCursorRows(criteria, sort, pageLimit);
        }
        List<Object[]> rows;
        switch (sort) {
            case SORT_SLOW:
                rows = spanRepository.traceListSortSlowAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.durationMs(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_FAST:
                rows = spanRepository.traceListSortFastAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.durationMs(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_SPANS:
                rows = spanRepository.traceListSortSpansAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.spanCount(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_ERR:
                rows = spanRepository.traceListSortErrAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.errorCount(), sortKey.minStart(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            case SORT_TOKENS:
                rows = spanRepository.traceListSortTokensAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.totalTokens(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
            default:
                rows = spanRepository.traceListSortNewAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        cursor.ts(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
        }
        return toTraceSummaries(rows);
    }

    /**
     * Looks up the sort-key values for the cursor trace within the active window+filters.
     * Returns {@code null} when the traceId is not present in the filtered window, which
     * is the signal for the caller to fall back to the first-page query.
     *
     * <p>Column order from {@code traceCursorSortKey}: duration_ms(0), span_count(1),
     * error_count(2), min_start(3), total_tokens(4).
     */
    private CursorSortKey resolveCursorSortKey(TraceQueryCriteria criteria, TraceCursor cursor) {
        List<Object[]> keyRows = spanRepository.traceCursorSortKey(
                criteria.startTimestamp(), criteria.endTimestamp(),
                cursor.id(),
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());
        if (keyRows.isEmpty()) {
            return null;
        }
        Object[] keyRow = keyRows.get(0);
        double durationMs = keyRow[0] == null ? 0.0 : ((Number) keyRow[0]).doubleValue();
        long spanCount = keyRow[1] == null ? 0L : ((Number) keyRow[1]).longValue();
        long errorCount = keyRow[2] == null ? 0L : ((Number) keyRow[2]).longValue();
        Instant minStart = (Instant) keyRow[3];
        long totalTokens = keyRow[4] == null ? 0L : ((Number) keyRow[4]).longValue();
        return new CursorSortKey(durationMs, spanCount, errorCount, minStart, totalTokens);
    }

    private static TraceCursorPage buildCursorPage(
            List<TraceSummary> probeItems, int limit, long totalCount) {
        boolean hasMore = probeItems.size() > limit;
        List<TraceSummary> items = hasMore ? probeItems.subList(0, limit) : probeItems;
        TraceSummary lastItem = items.isEmpty() ? null : items.get(items.size() - 1);
        TraceCursor nextCursor = lastItem != null
                ? new TraceCursor(lastItem.getStartTimestamp(), lastItem.getTraceId())
                : null;
        return new TraceCursorPage(items, nextCursor, hasMore, totalCount);
    }

    /**
     * Parses a "ts,traceId" cursor string into a {@link TraceCursor}. Splitting happens
     * on the first comma only so ISO-8601 timestamps with sub-second precision are not
     * truncated.
     */
    public TraceCursor parseCursor(String cursorString) {
        int commaIndex = cursorString.indexOf(',');
        if (commaIndex < 0) {
            throw new IllegalArgumentException("Cursor must be in ts,traceId format: " + cursorString);
        }
        Instant cursorTs = Instant.parse(cursorString.substring(0, commaIndex));
        String cursorTraceId = cursorString.substring(commaIndex + 1);
        return new TraceCursor(cursorTs, cursorTraceId);
    }

    // =========================================================================
    // Offset paging
    // =========================================================================

    /** Offset-paged table query with configurable sort. */
    public TracePage offsetPage(TraceQueryCriteria criteria, String sort, int page, int size) {
        long totalCount = spanRepository.countFilteredTraces(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery());

        int pageOffset = page * size;
        List<TraceSummary> items = fetchSortedOffsetRows(criteria, sort, size, pageOffset);
        return new TracePage(items, totalCount);
    }

    private List<TraceSummary> fetchSortedOffsetRows(
            TraceQueryCriteria criteria, String sort, int pageSize, int pageOffset) {
        List<Object[]> rows;
        switch (sort) {
            case SORT_OLD:
                rows = spanRepository.traceListSortOld(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            case SORT_SLOW:
                rows = spanRepository.traceListSortSlow(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            case SORT_FAST:
                rows = spanRepository.traceListSortFast(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            case SORT_SPANS:
                rows = spanRepository.traceListSortSpans(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            case SORT_ERR:
                rows = spanRepository.traceListSortErr(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            case SORT_TOKENS:
                rows = spanRepository.traceListSortTokens(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
            default:
                rows = spanRepository.traceListSortNewOffset(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageSize, pageOffset);
                break;
        }
        return toTraceSummaries(rows);
    }

    // =========================================================================
    // Row mapping
    // =========================================================================

    /**
     * Maps a raw Object[] row from the trace list queries into a {@link TraceSummary}.
     * Column order: trace_id(0), min_start(1), max_end(2), error_count(3),
     * span_count(4), root_span_name(5), session_id(6), root_span_id(7), duration_ms(8),
     * total_tokens(9).
     */
    private static TraceSummary toTraceSummary(Object[] row) {
        String traceId = (String) row[0];
        Instant minStart = (Instant) row[1];
        Instant maxEnd = (Instant) row[2];
        long errorCount = row[3] == null ? 0L : ((Number) row[3]).longValue();
        long spanCount = row[4] == null ? 0L : ((Number) row[4]).longValue();
        String rootSpanName = blankToNull((String) row[5]);
        String sessionId = blankToNull((String) row[6]);
        String rootSpanId = blankToNull((String) row[7]);
        long durationNanos = Duration.between(minStart, maxEnd).toNanos();
        long totalTokens = row[9] == null ? 0L : ((Number) row[9]).longValue();

        return TraceSummary.builder()
                .traceId(traceId)
                .startTimestamp(minStart)
                .endTimestamp(maxEnd)
                .durationNanos(durationNanos)
                .errorCount(errorCount)
                .spanCount(spanCount)
                .rootSpanName(rootSpanName)
                .sessionId(sessionId)
                .rootSpanId(rootSpanId)
                .totalTokens(totalTokens)
                .build();
    }

    private static List<TraceSummary> toTraceSummaries(List<Object[]> rows) {
        return rows.stream().map(TraceExplorerService::toTraceSummary).toList();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
