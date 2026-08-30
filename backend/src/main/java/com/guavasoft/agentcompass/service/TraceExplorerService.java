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
package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.FacetValue;
import com.guavasoft.agentcompass.model.TraceCursor;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceFacets;
import com.guavasoft.agentcompass.model.TraceHistogram;
import com.guavasoft.agentcompass.model.TraceHistogramBucket;
import com.guavasoft.agentcompass.model.TracePage;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregation service for the Trace Explorer page: histogram, facets, cursor-paged
 * stream, and offset-paged table. All three endpoints share the same CTE-based WHERE
 * clause so totalCount == histogram sum == facet status totals for identical filters.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TraceExplorerService {

    /**
     * Bucket-width ladder in seconds — "nice" steps matching the TracesPage
     * frontend's NICE array (which tops out at 6 hours, unlike the Logs one).
     */
    private static final long[] HISTOGRAM_LADDER_SECONDS = {
        60L, 120L, 300L, 600L, 900L, 1800L, 3600L, 7200L, 10800L, 21600L
    };

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
    private static final String SORT_COST = "cost";

    private static final String STATUS_OK = "ok";
    private static final String STATUS_ERROR = "error";
    private static final String DURATION_D0 = "d0";
    private static final String DURATION_D1 = "d1";
    private static final String DURATION_D2 = "d2";
    private static final String DURATION_D3 = "d3";

    /**
     * Resolved sort-key values for a cursor trace row. All six values are populated
     * from the single {@code traceCursorSortKey} lookup; each keyset branch uses only
     * the fields it needs. {@code totalCostUsd} stays a {@link BigDecimal} so the
     * cost keyset predicate compares against the numeric column without a
     * double round-trip that could drop a tie-break.
     */
    private record CursorSortKey(
            double durationMs,
            long spanCount,
            long errorCount,
            Instant minStart,
            long totalTokens,
            BigDecimal totalCostUsd) {
    }

    /**
     * Preview length for {@link TraceSummary#getFirstUserPrompt()}, matching the
     * Sessions grid's prompt preview so the two columns truncate identically.
     */
    private static final int PROMPT_PREVIEW_LENGTH = 200;

    // Matches a trace's root claude_code.interaction span for the own-vs-background
    // cost split in backgroundCostUsdForTrace. Mirrors LogService's identically-named
    // constant of the same purpose (own-vs-background split via the same repository
    // query); not shared as a single cross-class constant because this is a
    // structural root-span-name pattern, not a deployment-tunable value -- the same
    // duplication-over-sharing precedent SpanRepository already sets for this exact
    // literal (~40 inline occurrences, no shared constant).
    private static final String INTERACTION_ROOT_SPAN_NAME_PATTERN = "claude_code.interaction%";

    private final SpanRepository spanRepository;
    private final LogRecordRepository logRecordRepository;
    private final TuningProperties tuningProperties;

    // =========================================================================
    // Histogram
    // =========================================================================

    /** Builds the throughput histogram with per-bucket p95 and window-wide aggregates. */
    public TraceHistogram histogram(TraceQueryCriteria criteria, int targetBuckets) {
        Instant windowStart = criteria.startTimestamp();
        Instant windowEnd = criteria.endTimestamp();
        long bucketSeconds = HistogramBucketing.pickBucketSeconds(
                windowStart, windowEnd, targetBuckets, HISTOGRAM_LADDER_SECONDS);
        long bucketMs = Duration.ofSeconds(bucketSeconds).toMillis();

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

    // =========================================================================
    // Facets
    // =========================================================================

    private static final String FACET_KIND_STATUS = "status";
    private static final String FACET_KIND_OPERATION = "operation";
    private static final String FACET_KIND_SERVICE = "service";
    private static final String FACET_KIND_DURATION_BUCKET = "duration_bucket";
    private static final String FACET_KIND_SESSION = "session";

    /**
     * Returns per-dimension facet counts with standard self-exclusion, in a single
     * repository round trip. {@link SpanRepository#facetTraceAll} computes the shared
     * {@code traces} CTE once and cross-joins it against all five facet dimensions;
     * this method demultiplexes the flat {@code (facetKind, facetKey, rowCount)} rows
     * back into the five facet lists the response DTO exposes.
     */
    public TraceFacets facets(TraceQueryCriteria criteria) {
        List<Object[]> rows = spanRepository.facetTraceAll(
                criteria.startTimestamp(), criteria.endTimestamp(),
                criteria.statuses(), criteria.operations(), criteria.services(),
                criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                FACET_OPERATION_CAP, FACET_SESSION_CAP);

        Map<String, List<Object[]>> rowsByFacetKind = new HashMap<>();
        for (Object[] row : rows) {
            rowsByFacetKind
                    .computeIfAbsent((String) row[0], key -> new ArrayList<>())
                    .add(row);
        }

        List<FacetValue> statusFacets = buildStatusFacets(rowsByFacetKind.get(FACET_KIND_STATUS));
        List<FacetValue> operationFacets = toFacetValues(rowsByFacetKind.get(FACET_KIND_OPERATION));
        List<FacetValue> serviceFacets = toFacetValues(rowsByFacetKind.get(FACET_KIND_SERVICE));
        List<FacetValue> durationFacets = buildDurationFacets(rowsByFacetKind.get(FACET_KIND_DURATION_BUCKET));
        List<FacetValue> sessionFacets = toFacetValues(rowsByFacetKind.get(FACET_KIND_SESSION));
        return new TraceFacets(statusFacets, operationFacets, serviceFacets, durationFacets, sessionFacets);
    }

    /** Column order within a facet row: facet_kind(0), facet_key(1), row_count(2). */
    private static List<FacetValue> buildStatusFacets(List<Object[]> rows) {
        Map<String, Long> countsByStatus = countsByFacetKey(rows);
        return List.of(
                new FacetValue(STATUS_OK, countsByStatus.getOrDefault(STATUS_OK, 0L)),
                new FacetValue(STATUS_ERROR, countsByStatus.getOrDefault(STATUS_ERROR, 0L)));
    }

    private static List<FacetValue> buildDurationFacets(List<Object[]> rows) {
        Map<String, Long> countsByBucket = countsByFacetKey(rows);
        return List.of(
                new FacetValue(DURATION_D0, countsByBucket.getOrDefault(DURATION_D0, 0L)),
                new FacetValue(DURATION_D1, countsByBucket.getOrDefault(DURATION_D1, 0L)),
                new FacetValue(DURATION_D2, countsByBucket.getOrDefault(DURATION_D2, 0L)),
                new FacetValue(DURATION_D3, countsByBucket.getOrDefault(DURATION_D3, 0L)));
    }

    private static Map<String, Long> countsByFacetKey(List<Object[]> rows) {
        Map<String, Long> countsByKey = new HashMap<>();
        if (rows == null) {
            return countsByKey;
        }
        for (Object[] row : rows) {
            countsByKey.put((String) row[1], ((Number) row[2]).longValue());
        }
        return countsByKey;
    }

    private static List<FacetValue> toFacetValues(List<Object[]> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> new FacetValue((String) row[1], ((Number) row[2]).longValue()))
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
        int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);
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
        int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);
        List<TraceSummary> probeItems = fetchSortedCursorRowsBefore(
                criteria, sort, cursor, resolvedLimit + 1);
        return buildCursorPage(probeItems, resolvedLimit, PageBounds.CONTINUATION_PAGE_TOTAL_COUNT);
    }

    private TraceCursorPage cursorPageAfter(
            TraceQueryCriteria criteria, String sort, TraceCursor cursor, int limit) {
        int resolvedLimit = PageBounds.clampPageSize(limit, PageBounds.DEFAULT_CURSOR_LIMIT);
        List<TraceSummary> probeItems = fetchSortedCursorRowsAfter(
                criteria, sort, cursor, resolvedLimit + 1);
        return buildCursorPage(probeItems, resolvedLimit, PageBounds.CONTINUATION_PAGE_TOTAL_COUNT);
    }

    private List<TraceSummary> fetchSortedCursorRows(
            TraceQueryCriteria criteria, String sort, int pageLimit) {
        List<Object[]> rows;
        switch (sort) {
            case SORT_OLD:
                rows = spanRepository.traceListSortOldCursor(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
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
            case SORT_COST:
                rows = spanRepository.traceListSortCostCursor(
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
            case SORT_OLD:
                rows = spanRepository.traceListSortOldBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        cursor.ts(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
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
            case SORT_COST:
                rows = spanRepository.traceListSortCostBefore(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.totalCostUsd(), cursor.id(),
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
            case SORT_OLD:
                rows = spanRepository.traceListSortOldAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        cursor.ts(), cursor.id(),
                        criteria.statuses(), criteria.operations(), criteria.services(),
                        criteria.durations(), criteria.sessions(), criteria.fullTextQuery(),
                        pageLimit);
                break;
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
            case SORT_COST:
                rows = spanRepository.traceListSortCostAfter(
                        criteria.startTimestamp(), criteria.endTimestamp(),
                        sortKey.totalCostUsd(), cursor.id(),
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
     * error_count(2), min_start(3), total_tokens(4), total_cost_usd(5).
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
        BigDecimal totalCostUsd = keyRow[5] == null ? BigDecimal.ZERO : (BigDecimal) keyRow[5];
        return new CursorSortKey(durationMs, spanCount, errorCount, minStart, totalTokens, totalCostUsd);
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

        int resolvedSize = PageBounds.clampPageSize(size, PageBounds.DEFAULT_OFFSET_PAGE_SIZE);
        int resolvedPage = Math.max(0, page);
        int pageOffset = PageBounds.computeOffset(resolvedPage, resolvedSize);
        List<TraceSummary> items = fetchSortedOffsetRows(criteria, sort, resolvedSize, pageOffset);
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
            case SORT_COST:
                rows = spanRepository.traceListSortCost(
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
    // Single trace
    // =========================================================================

    /**
     * Aggregate summary for one trace, for the trace detail page's header. Same
     * {@link TraceSummary} shape the list endpoints return — including
     * {@code firstUserPrompt} — so both surfaces read one field name.
     *
     * <p>Not window-scoped: a permalinked trace resolves regardless of the window
     * currently selected in the UI. Empty when no spans carry the trace id.
     */
    public Optional<TraceSummary> traceSummary(String traceId) {
        List<Object[]> rows = spanRepository.traceSummaryById(traceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TraceSummary summary = toTraceSummaries(rows).get(0);
        summary.setBackgroundCostUsd(backgroundCostUsdForTrace(traceId));
        return Optional.of(summary);
    }

    /**
     * Portion of this one trace's cost billed after its own root span closed. A
     * single-element call to the same trace-correlated split query the Sessions
     * prompt timeline uses ({@link LogRecordRepository#findCostSplitByTraceIds})
     * rather than a new column on {@code traceSummaryById} -- that query's row
     * mapper ({@link #toTraceSummary}) is shared by 20 other list/sort/histogram
     * queries with an identical column order, so adding a column there for a
     * field only this single-trace endpoint needs would mean touching all of them.
     */
    private double backgroundCostUsdForTrace(String traceId) {
        List<Object[]> rows = logRecordRepository.findCostSplitByTraceIds(
                List.of(traceId),
                tuningProperties.getApiRequestEventName(),
                tuningProperties.getApiRequestCostAttribute(),
                INTERACTION_ROOT_SPAN_NAME_PATTERN);
        if (rows.isEmpty() || rows.get(0)[2] == null) {
            return 0.0;
        }
        return ((Number) rows.get(0)[2]).doubleValue();
    }

    // =========================================================================
    // Row mapping
    // =========================================================================

    /**
     * Maps a raw Object[] row from the trace list queries into a {@link TraceSummary}.
     * Column order: trace_id(0), min_start(1), max_end(2), error_count(3),
     * span_count(4), root_span_name(5), session_id(6), root_span_id(7), duration_ms(8),
     * total_tokens(9), total_cost_usd(10).
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
        double totalCostUsd = row[10] == null ? 0.0 : ((Number) row[10]).doubleValue();

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
                .totalCostUsd(totalCostUsd)
                .build();
    }

    /**
     * Maps the raw list rows and stamps each one's {@code firstUserPrompt} in a single
     * follow-up query for the whole page — the same batch-enrich shape the Sessions grid
     * uses for its prompt context, rather than a per-row lookup or a tenth copy of the
     * prompt join across all twenty trace-list queries.
     */
    private List<TraceSummary> toTraceSummaries(List<Object[]> rows) {
        List<TraceSummary> summaries = rows.stream().map(TraceExplorerService::toTraceSummary).toList();
        return withFirstUserPrompts(summaries);
    }

    /**
     * Fills {@link TraceSummary#setFirstUserPrompt(String)} on every summary whose trace has
     * an initiating user prompt. Traces missing from the lookup keep a null prompt: either
     * they are not rooted in a conversational turn, or prompt-body capture was off when they
     * were recorded.
     */
    private List<TraceSummary> withFirstUserPrompts(List<TraceSummary> summaries) {
        if (summaries.isEmpty()) {
            return summaries;
        }
        List<String> traceIds = summaries.stream().map(TraceSummary::getTraceId).toList();
        Map<String, String> promptByTraceId = findFirstUserPrompts(traceIds);
        for (TraceSummary summary : summaries) {
            summary.setFirstUserPrompt(promptByTraceId.get(summary.getTraceId()));
        }
        return summaries;
    }

    /** Column order from {@code findFirstUserPromptByTraceIds}: trace_id(0), first_user_prompt(1). */
    private Map<String, String> findFirstUserPrompts(List<String> traceIds) {
        List<Object[]> promptRows = logRecordRepository.findFirstUserPromptByTraceIds(
                traceIds,
                tuningProperties.getUserPromptEventName(),
                tuningProperties.getPromptAttribute(),
                PROMPT_PREVIEW_LENGTH);
        Map<String, String> promptByTraceId = new HashMap<>();
        for (Object[] promptRow : promptRows) {
            promptByTraceId.put((String) promptRow[0], blankToNull((String) promptRow[1]));
        }
        return promptByTraceId;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
