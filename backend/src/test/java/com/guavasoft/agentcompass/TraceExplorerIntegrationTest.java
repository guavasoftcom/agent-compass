package com.guavasoft.agentcompass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
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
import com.guavasoft.agentcompass.service.TraceExplorerService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TraceExplorerService} against a real Postgres
 * instance (Testcontainers). Validates:
 * - histogram zero-fill and bucket assignment
 * - window-wide p50/p95 and total/errorCount aggregates
 * - facet self-exclusion (status/duration always zero-filled to full set)
 * - cursor paging (sort=new initial, before=, after= live-tail)
 * - offset paging (sort=new, sort=slow)
 * - counts-agree invariant: totalCount == histogram total == facet status sum
 */
@SpringBootTest
@Testcontainers
class TraceExplorerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    SpanRepository spanRepository;

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    TraceExplorerService service;

    private Instant windowStart;
    private Instant windowEnd;

    // Trace identifiers — each trace has a root span + one child span.
    private static final String TRACE_SESSION_OK   = "aaaa000000000000aaaa000000000001";
    private static final String TRACE_TOOL_OK      = "bbbb000000000000bbbb000000000001";
    private static final String TRACE_MODEL_ERROR  = "cccc000000000000cccc000000000001";
    private static final String TRACE_OUTSIDE_WIN  = "dddd000000000000dddd000000000001";
    // In-flight trace: both spans have a non-null parent_span_id pointing at an unexported span.
    private static final String TRACE_IN_FLIGHT    = "eeee000000000000eeee000000000001";
    private static final String IN_FLIGHT_ORPHAN_PARENT_ID = "ffff000000000000";

    private static final String SESSION_ID_ONE = "sess_integration_01";
    private static final String ATTR_SESSION_ID = "session.id";

    // Token attributes seeded on TRACE_SESSION_OK's root span.
    private static final String ATTR_INPUT_TOKENS  = "gen_ai.usage.input_tokens";
    private static final String ATTR_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";
    private static final long   SEED_INPUT_TOKENS  = 1200L;
    private static final long   SEED_OUTPUT_TOKENS = 800L;
    private static final long   EXPECTED_TOTAL_TOKENS = SEED_INPUT_TOKENS + SEED_OUTPUT_TOKENS;

    /**
     * Sub-microsecond offset used to build a window finer than a Postgres
     * {@code timestamptz} can store — the precision the histogram bucket origin has
     * to be normalized to.
     */
    private static final int SUB_MICROSECOND_NANOS = 37;

    // Prompt-context fixtures. TRACE_SESSION_OK's prompt is multi-line and longer
    // than the preview length, so it covers whitespace collapse (the newline/tab
    // run becomes one space) and 200-char truncation: 50 A's + 1 space + 149 B's.
    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_PROMPT = "prompt";
    private static final String USER_PROMPT_EVENT_NAME = "user_prompt";
    private static final int PROMPT_PREVIEW_LENGTH = 200;
    private static final String MULTI_LINE_PROMPT = "A".repeat(50) + "\n \t \n" + "B".repeat(250);
    private static final String EXPECTED_COLLAPSED_TRUNCATED_PROMPT = "A".repeat(50) + " " + "B".repeat(149);
    private static final String SLASH_COMMAND_PROMPT = "/ship";

    @BeforeEach
    void seedSpans() {
        spanRepository.deleteAll();
        logRecordRepository.deleteAll();

        // Window: 60 minutes
        windowStart = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600);
        windowEnd = windowStart.plusSeconds(3600);

        // Trace 1: session.turn, 5 s duration, ok, starts at windowStart + 5 min.
        // Carries token attributes so totalTokens = SEED_INPUT_TOKENS + SEED_OUTPUT_TOKENS.
        Instant t1Start = windowStart.plusSeconds(300);
        Map<String, Object> sessionTokenAttributes = new HashMap<>();
        sessionTokenAttributes.put(ATTR_SESSION_ID, SESSION_ID_ONE);
        sessionTokenAttributes.put(ATTR_INPUT_TOKENS, SEED_INPUT_TOKENS);
        sessionTokenAttributes.put(ATTR_OUTPUT_TOKENS, SEED_OUTPUT_TOKENS);
        addTraceWithRootAttributes(TRACE_SESSION_OK, "session.turn", t1Start,
                t1Start.plusSeconds(5), false, sessionTokenAttributes);

        // Trace 2: tool.execute, 50 ms duration, ok, starts at windowStart + 20 min
        Instant t2Start = windowStart.plusSeconds(1200);
        addTrace(TRACE_TOOL_OK, "tool.execute", SESSION_ID_ONE, t2Start,
                t2Start.plusMillis(50), false);

        // Trace 3: model.completion, 2 s duration, ERROR, starts at windowStart + 40 min
        Instant t3Start = windowStart.plusSeconds(2400);
        addTrace(TRACE_MODEL_ERROR, "model.completion", null, t3Start,
                t3Start.plusSeconds(2), true);

        // Trace 4: outside window (start = windowEnd + 10 min) — must never appear
        Instant t4Start = windowEnd.plusSeconds(600);
        addTrace(TRACE_OUTSIDE_WIN, "session.turn", null, t4Start,
                t4Start.plusSeconds(1), false);

        // Trace 5: in-flight — both spans have a non-null parent_span_id pointing at an
        // unexported span id (simulates child spans exported before their root).
        // Earliest span name is "tool.partial"; that span should be used as the stand-in root.
        Instant t5Start = windowStart.plusSeconds(1800);
        addInFlightTrace(TRACE_IN_FLIGHT, t5Start, t5Start.plusSeconds(3));

        // Prompt context, correlated to traces by log_records.trace_id:
        // - TRACE_SESSION_OK: one multi-line prompt (collapse + truncation coverage).
        // - TRACE_IN_FLIGHT: a bare slash command followed by a later prompt; the
        //   earliest wins, with no slash-command deprioritization (unlike Sessions).
        // - TRACE_MODEL_ERROR: a user_prompt event with no prompt attribute, i.e.
        //   prompt-body capture was disabled -> null.
        // - TRACE_TOOL_OK: no user_prompt event at all -> null.
        addUserPromptLog(TRACE_SESSION_OK, MULTI_LINE_PROMPT, t1Start);
        addUserPromptLog(TRACE_IN_FLIGHT, SLASH_COMMAND_PROMPT, t5Start);
        addUserPromptLog(TRACE_IN_FLIGHT, "Follow-up in the same trace", t5Start.plusSeconds(1));
        addUserPromptLog(TRACE_MODEL_ERROR, null, t3Start);
    }

    // -------------------------------------------------------------------------
    // Histogram
    // -------------------------------------------------------------------------

    @Test
    void histogramContainsExactlyFourTracesInWindow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceHistogram histogram = service.histogram(criteria, 48);

        assertThat(histogram.total()).isEqualTo(4);
        assertThat(histogram.errorCount()).isEqualTo(1);
        assertThat(histogram.p50Ms()).isGreaterThan(0);
        assertThat(histogram.p95Ms()).isGreaterThan(0);
    }

    @Test
    void histogramBucketsZeroFilledCoverEntireWindow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceHistogram histogram = service.histogram(criteria, 48);

        List<TraceHistogramBucket> buckets = histogram.buckets();
        assertThat(buckets).isNotEmpty();

        // First bucket starts at or before windowStart
        assertThat(buckets.get(0).t0()).isEqualTo(windowStart);

        // Bucket sum equals total (4 in-window traces: session.turn, tool.execute, model.completion, in-flight)
        long bucketOkSum = buckets.stream().mapToLong(TraceHistogramBucket::ok).sum();
        long bucketErrorSum = buckets.stream().mapToLong(TraceHistogramBucket::error).sum();
        assertThat(bucketOkSum + bucketErrorSum).isEqualTo(histogram.total());
        assertThat(bucketErrorSum).isEqualTo(histogram.errorCount());
    }

    @Test
    void histogramPopulatesBucketsWhenWindowIsFinerThanPostgresPrecision() {
        // The seeded window is hour-aligned, which is why the other histogram tests
        // never caught this: a window carrying sub-microsecond nanos is truncated by
        // Postgres, so the date_bin origin and the Java-side zero-fill walk have to be
        // normalized to the same precision or every bucket comes back zero.
        TraceQueryCriteria subMicrosecondWindow = TraceQueryCriteria.of(
                windowStart.plusNanos(SUB_MICROSECOND_NANOS), windowEnd,
                null, null, null, null, null, null);
        TraceHistogram histogram = service.histogram(subMicrosecondWindow, 48);

        long bucketSum = histogram.buckets().stream()
                .mapToLong(bucket -> bucket.ok() + bucket.error())
                .sum();
        assertThat(bucketSum).isEqualTo(histogram.total()).isEqualTo(4L);
    }

    @Test
    void histogramObeysStatusFilter() {
        TraceQueryCriteria criteria = criteriaWithStatuses(List.of("error"));
        TraceHistogram histogram = service.histogram(criteria, 48);

        assertThat(histogram.total()).isEqualTo(1);
        assertThat(histogram.errorCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Facets
    // -------------------------------------------------------------------------

    @Test
    void facetsStatusAlwaysReturnsBothValuesZeroFilled() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        List<FacetValue> status = facets.status();
        assertThat(status).hasSize(2);
        assertThat(status.stream().map(FacetValue::value).toList())
                .containsExactlyInAnyOrder("ok", "error");
        long totalFromFacets = status.stream().mapToLong(FacetValue::count).sum();
        // 4 in-window traces: session.turn (ok), tool.execute (ok), model.completion (error), in-flight (ok)
        assertThat(totalFromFacets).isEqualTo(4);
    }

    @Test
    void facetsDurationAlwaysReturnsFourBucketsZeroFilled() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        List<FacetValue> duration = facets.duration();
        assertThat(duration).hasSize(4);
        assertThat(duration.stream().map(FacetValue::value).toList())
                .containsExactly("d0", "d1", "d2", "d3");
        // tool.execute is 50ms → d0, model.completion is 2s → d2, session.turn is 5s → d3,
        // in-flight is 3s → d2 (so d2 count becomes 2)
        long d0Count = durationCount(duration, "d0");
        long d2Count = durationCount(duration, "d2");
        long d3Count = durationCount(duration, "d3");
        assertThat(d0Count).isEqualTo(1);
        assertThat(d2Count).isEqualTo(2);
        assertThat(d3Count).isEqualTo(1);
    }

    @Test
    void facetsStatusSelfExclusionExcludesStatusFilterFromOwnCount() {
        // Apply status=error filter — status facet should still count both values
        // (self-excluded), so ok count should reflect unfiltered ok traces.
        TraceQueryCriteria criteria = criteriaWithStatuses(List.of("error"));
        TraceFacets facets = service.facets(criteria);

        List<FacetValue> status = facets.status();
        long okCount = status.stream()
                .filter(f -> "ok".equals(f.value()))
                .mapToLong(FacetValue::count)
                .sum();
        // ok traces exist — self-exclusion means status filter is removed for status facet
        assertThat(okCount).isGreaterThan(0);
    }

    @Test
    void facetsOperationListsObservedOperations() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        List<String> operations = facets.operation().stream()
                .map(FacetValue::value).toList();
        assertThat(operations).contains("session.turn", "tool.execute", "model.completion");
    }

    @Test
    void facetsServiceDerivationGroupsCorrectly() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        Map<String, Long> serviceCountByName = new HashMap<>();
        for (FacetValue serviceValue : facets.service()) {
            serviceCountByName.put(serviceValue.value(), serviceValue.count());
        }
        // session.turn → claude_code.session
        assertThat(serviceCountByName).containsKey("claude_code.session");
        // tool.execute → claude_code.tools
        assertThat(serviceCountByName).containsKey("claude_code.tools");
        // model.completion → claude_code.models
        assertThat(serviceCountByName).containsKey("claude_code.models");
    }

    @Test
    void facetsSessionListsOnlyInWindowSessions() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        List<String> sessions = facets.session().stream().map(FacetValue::value).toList();
        assertThat(sessions).contains(SESSION_ID_ONE);
        // outside-window trace should not appear (no session either way in this case)
    }

    // -------------------------------------------------------------------------
    // Counts-agree invariant
    // -------------------------------------------------------------------------

    @Test
    void totalCountHistogramTotalAndFacetStatusSumAgree() {
        TraceQueryCriteria criteria = fullWindowCriteria();

        TraceHistogram histogram = service.histogram(criteria, 48);
        TraceFacets facets = service.facets(criteria);
        TraceCursorPage initialPage = service.cursorPage(criteria, "new", null, null, 60);

        long facetStatusTotal = facets.status().stream().mapToLong(FacetValue::count).sum();
        long histogramTotal = histogram.total();
        long cursorTotal = initialPage.totalCount();

        // 4 in-window traces: session.turn, tool.execute, model.completion, in-flight
        assertThat(histogramTotal).isEqualTo(4);
        assertThat(facetStatusTotal).isEqualTo(histogramTotal);
        assertThat(cursorTotal).isEqualTo(histogramTotal);
    }

    // -------------------------------------------------------------------------
    // Cursor paging — sort=new
    // -------------------------------------------------------------------------

    @Test
    void cursorInitialPageReturnsAllFourTracesNewestFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "new", null, null, 60);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasMore()).isFalse();
        // newest first: model.completion (t3Start=+40min) > in-flight (+30min) >
        //               tool.execute (+20min) > session.turn (+5min)
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
        assertThat(page.items().get(3).getTraceId()).isEqualTo(TRACE_SESSION_OK);
    }

    @Test
    void cursorScrollBackBeforeReturnsOlderRows() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // Load first page of size 1 to get cursor pointing at the newest trace
        TraceCursorPage firstPage = service.cursorPage(criteria, "new", null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();

        TraceCursor cursor = firstPage.nextCursor();
        String beforeParam = cursor.ts().toString() + "," + cursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "new", beforeParam, null, 60);
        // 3 older traces remain: in-flight (+30min), tool.execute (+20min), session.turn (+5min)
        assertThat(nextPage.items()).hasSize(3);
        // Should not contain the newest trace
        List<String> nextPageIds = nextPage.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(nextPageIds).doesNotContain(TRACE_MODEL_ERROR);
    }

    @Test
    void cursorLiveTailAfterReturnsEmptyWhenNothingNew() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // Get the newest trace's cursor
        TraceCursorPage firstPage = service.cursorPage(criteria, "new", null, null, 1);
        TraceCursor cursor = firstPage.nextCursor();
        String afterParam = cursor.ts().toString() + "," + cursor.id();

        // Poll for newer rows — there are none
        TraceCursorPage tailPage = service.cursorPage(criteria, "new", null, afterParam, 60);
        assertThat(tailPage.items()).isEmpty();
        assertThat(tailPage.totalCount()).isEqualTo(0);
    }

    @Test
    void cursorHasMoreIsTrueWhenMoreRowsExist() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "new", null, null, 1);

        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Cursor paging — sort=old
    //
    // sort=old had a case in the offset dispatch only; all three cursor dispatches fell
    // through to the sort=new default, so Stream mode silently served newest-first.
    // -------------------------------------------------------------------------

    @Test
    void cursorInitialPageSortOldReturnsAllFourTracesOldestFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "old", null, null, 60);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasMore()).isFalse();
        // oldest first — the exact reverse of the sort=new ordering above
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(page.items().get(3).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
    }

    @Test
    void cursorScrollBackSortOldReturnsNewerRows() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // First page of size 1 leaves the cursor on the OLDEST trace under sort=old
        TraceCursorPage firstPage = service.cursorPage(criteria, "old", null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(firstPage.hasMore()).isTrue();

        TraceCursor cursor = firstPage.nextCursor();
        String beforeParam = cursor.ts().toString() + "," + cursor.id();

        // "Scroll back" means further along the sort order, which for an ascending list is
        // later in time — the 3 newer traces, still oldest-first.
        TraceCursorPage nextPage = service.cursorPage(criteria, "old", beforeParam, null, 60);
        assertThat(nextPage.items()).hasSize(3);
        assertThat(nextPage.items().get(2).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
        List<String> nextPageIds = nextPage.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(nextPageIds).doesNotContain(TRACE_SESSION_OK);
    }

    @Test
    void cursorLiveTailSortOldReturnsEmptyWhenNothingOlder() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "old", null, null, 1);
        TraceCursor cursor = firstPage.nextCursor();
        String afterParam = cursor.ts().toString() + "," + cursor.id();

        // Nothing sorts above the head of an ascending list here
        TraceCursorPage tailPage = service.cursorPage(criteria, "old", null, afterParam, 60);
        assertThat(tailPage.items()).isEmpty();
    }

    // NOTE: a tail poll whose limit is smaller than the number of waiting rows still drops the
    // rows adjacent to the head. traceListSortOldAfter selects the right SET, but
    // buildCursorPage's "+1 probe then subList(0, limit)" trims from the wrong end. That trim is
    // shared with sort=new and with the logs tail, so it is not fixed here.

    // -------------------------------------------------------------------------
    // Offset paging
    // -------------------------------------------------------------------------

    @Test
    void offsetPageSortNewReturnsNewestFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage page = service.offsetPage(criteria, "new", 0, 25);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
    }

    @Test
    void offsetPageSortSlowReturnsSlowestFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage page = service.offsetPage(criteria, "slow", 0, 25);

        assertThat(page.items()).hasSize(4);
        // session.turn is 5000ms, model.completion+in-flight are 2000ms/3000ms, tool.execute is 50ms
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(page.items().get(3).getTraceId()).isEqualTo(TRACE_TOOL_OK);
    }

    @Test
    void offsetPageRespectsPageSizeAndNumber() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage firstPage = service.offsetPage(criteria, "new", 0, 2);
        TracePage secondPage = service.offsetPage(criteria, "new", 1, 2);

        assertThat(firstPage.items()).hasSize(2);
        assertThat(secondPage.items()).hasSize(2);
        assertThat(firstPage.totalCount()).isEqualTo(4);
        assertThat(secondPage.totalCount()).isEqualTo(4);
        // No overlap between pages
        String firstPageLastId = firstPage.items().get(1).getTraceId();
        String secondPageFirstId = secondPage.items().get(0).getTraceId();
        assertThat(firstPageLastId).isNotEqualTo(secondPageFirstId);
    }

    // -------------------------------------------------------------------------
    // HIGH-2 regression — unclamped limit/size/page resource exhaustion
    // -------------------------------------------------------------------------

    @Test
    void offsetPageClampsOversizedSizeInsteadOfFetchingUnboundedRows() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // size is attacker-chosen and huge; must be clamped, not passed straight to SQL LIMIT.
        TracePage page = service.offsetPage(criteria, "new", 0, 2_000_000_000);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
    }

    @Test
    void offsetPageGuardsAgainstPageTimesSizeIntOverflow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // page * size would overflow int and wrap to a negative OFFSET pre-fix, causing a 500.
        TracePage page = service.offsetPage(criteria, "new", Integer.MAX_VALUE, 25);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).isEmpty();
    }

    @Test
    void offsetPageFloorsNegativePageToZero() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage page = service.offsetPage(criteria, "new", -5, 2);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(2);
    }

    @Test
    void cursorPageFirstClampsOversizedLimitInsteadOfOverflowingTheProbeFetch() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // limit=Integer.MAX_VALUE previously overflowed resolvedLimit + 1 into a negative
        // SQL LIMIT (500 error). Clamped, this must behave like any oversized-but-valid limit.
        TraceCursorPage page = service.cursorPage(criteria, "new", null, null, Integer.MAX_VALUE);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasMore()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Outside-window exclusion
    // -------------------------------------------------------------------------

    @Test
    void tracesOutsideWindowNeverAppearInAnyEndpoint() {
        TraceQueryCriteria criteria = fullWindowCriteria();

        TracePage page = service.offsetPage(criteria, "new", 0, 100);
        List<String> traceIds = page.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(traceIds).doesNotContain(TRACE_OUTSIDE_WIN);
        assertThat(page.totalCount()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // Cursor paging — sort=slow
    // -------------------------------------------------------------------------

    @Test
    void cursorSlowInitialPageReturnsSlowestFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "slow", null, null, 60);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasMore()).isFalse();
        // session.turn 5000ms > in-flight 3000ms > model.completion 2000ms > tool.execute 50ms
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(page.items().get(3).getTraceId()).isEqualTo(TRACE_TOOL_OK);
    }

    @Test
    void cursorSlowHasMoreIsTrueAndNextCursorSetWhenPageSmall() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "slow", null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
        // first item should be the slowest trace
        assertThat(firstPage.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
    }

    @Test
    void cursorSlowScrollBackBeforeExcludesCursorRow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "slow", null, null, 1);

        TraceCursor cursor = firstPage.nextCursor();
        String beforeParam = cursor.ts().toString() + "," + cursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "slow", beforeParam, null, 60);

        List<String> nextPageIds = nextPage.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(nextPageIds).doesNotContain(TRACE_SESSION_OK);
        // in-flight (3000ms), model.completion (2000ms), tool.execute (50ms)
        assertThat(nextPageIds).hasSize(3);
    }

    @Test
    void cursorSlowScrollBackNextCursorPointsToLastReturnedItem() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "slow", null, null, 1);
        TraceCursor firstCursor = firstPage.nextCursor();
        String beforeParam = firstCursor.ts().toString() + "," + firstCursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "slow", beforeParam, null, 1);

        assertThat(nextPage.items()).hasSize(1);
        assertThat(nextPage.hasMore()).isTrue();
        // second-slowest trace is in-flight (3000 ms); model.completion is 2000 ms
        assertThat(nextPage.items().get(0).getTraceId()).isEqualTo(TRACE_IN_FLIGHT);
        assertThat(nextPage.nextCursor()).isNotNull();
    }

    @Test
    void cursorSlowCursorNotFoundFallsBackToFirstPage() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // Use a traceId that does not exist in the window
        String nonExistentCursor = windowStart.toString() + ",deadbeefdeadbeefdeadbeefdeadbeef";

        TraceCursorPage page = service.cursorPage(criteria, "slow", nonExistentCursor, null, 60);

        // Fallback to first page: all four traces returned slowest-first
        assertThat(page.items()).hasSize(4);
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
    }

    // -------------------------------------------------------------------------
    // Cursor paging — sort=err
    // -------------------------------------------------------------------------

    @Test
    void cursorErrInitialPagePutsErrorTracesFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "err", null, null, 60);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        assertThat(page.hasMore()).isFalse();
        // model.completion has error_count=1; the three ok traces have error_count=0
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
    }

    @Test
    void cursorErrHasMoreIsTrueAndNextCursorSetWhenPageSmall() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "err", null, null, 1);

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(firstPage.items().get(0).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
    }

    @Test
    void cursorErrScrollBackBeforeExcludesCursorRow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "err", null, null, 1);

        TraceCursor cursor = firstPage.nextCursor();
        String beforeParam = cursor.ts().toString() + "," + cursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "err", beforeParam, null, 60);

        List<String> nextPageIds = nextPage.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(nextPageIds).doesNotContain(TRACE_MODEL_ERROR);
        // 3 ok traces remain: tool.execute, session.turn, in-flight
        assertThat(nextPageIds).hasSize(3);
    }

    @Test
    void cursorErrScrollBackFullSecondPageOrderedByStartDesc() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        // Skip the error trace by scrolling past it
        TraceCursorPage firstPage = service.cursorPage(criteria, "err", null, null, 1);
        TraceCursor firstCursor = firstPage.nextCursor();
        String beforeParam = firstCursor.ts().toString() + "," + firstCursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "err", beforeParam, null, 60);

        // Remaining three traces all have error_count=0; tiebreak is min_start DESC then trace_id DESC.
        // in-flight: +30min, tool.execute: +20min, session.turn: +5min
        assertThat(nextPage.items()).hasSize(3);
        assertThat(nextPage.items().get(0).getTraceId()).isEqualTo(TRACE_IN_FLIGHT);
        assertThat(nextPage.items().get(1).getTraceId()).isEqualTo(TRACE_TOOL_OK);
        assertThat(nextPage.items().get(2).getTraceId()).isEqualTo(TRACE_SESSION_OK);
    }

    @Test
    void cursorErrCursorNotFoundFallsBackToFirstPage() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        String nonExistentCursor = windowStart.toString() + ",deadbeefdeadbeefdeadbeefdeadbeef";

        TraceCursorPage page = service.cursorPage(criteria, "err", nonExistentCursor, null, 60);

        assertThat(page.items()).hasSize(4);
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_MODEL_ERROR);
    }

    // -------------------------------------------------------------------------
    // Cursor paging — sort=spans
    // -------------------------------------------------------------------------

    @Test
    void cursorSpansInitialPageReturnsMostSpansFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "spans", null, null, 60);

        assertThat(page.items()).hasSize(4);
        // Each trace has 2 spans (root + child); tiebreak is trace_id DESC
        // All have equal span_count=2, so trace with largest trace_id hex comes first
        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void cursorSpansScrollBackBeforeExcludesCursorRow() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage firstPage = service.cursorPage(criteria, "spans", null, null, 1);

        assertThat(firstPage.hasMore()).isTrue();
        TraceCursor cursor = firstPage.nextCursor();
        String firstTraceId = firstPage.items().get(0).getTraceId();
        String beforeParam = cursor.ts().toString() + "," + cursor.id();

        TraceCursorPage nextPage = service.cursorPage(criteria, "spans", beforeParam, null, 60);

        List<String> nextPageIds = nextPage.items().stream()
                .map(TraceSummary::getTraceId).toList();
        assertThat(nextPageIds).doesNotContain(firstTraceId);
        assertThat(nextPageIds).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // totalTokens field and sort=tokens
    // -------------------------------------------------------------------------

    @Test
    void totalTokensReflectsTokenAttributesOnRootSpan() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage page = service.offsetPage(criteria, "new", 0, 25);

        TraceSummary sessionTrace = page.items().stream()
                .filter(item -> TRACE_SESSION_OK.equals(item.getTraceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TRACE_SESSION_OK not found in page"));

        assertThat(sessionTrace.getTotalTokens()).isEqualTo(EXPECTED_TOTAL_TOKENS);
    }

    @Test
    void sortTokensPlacesHighestTokenTraceFirst() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "tokens", null, null, 60);

        assertThat(page.totalCount()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
        // TRACE_SESSION_OK has EXPECTED_TOTAL_TOKENS; all others have 0 tokens.
        assertThat(page.items().get(0).getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(page.items().get(0).getTotalTokens()).isEqualTo(EXPECTED_TOTAL_TOKENS);
        // The remaining three traces have totalTokens=0.
        assertThat(page.items().get(1).getTotalTokens()).isEqualTo(0L);
        assertThat(page.items().get(2).getTotalTokens()).isEqualTo(0L);
        assertThat(page.items().get(3).getTotalTokens()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // In-flight trace — no parentless span
    // -------------------------------------------------------------------------

    @Test
    void inFlightTraceAppearsInListWithEarliestSpanNameAsRootSpanName() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TracePage page = service.offsetPage(criteria, "new", 0, 100);

        TraceSummary inFlightSummary = page.items().stream()
                .filter(item -> TRACE_IN_FLIGHT.equals(item.getTraceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("in-flight trace not found in list"));

        assertThat(inFlightSummary.getRootSpanName()).isNotNull();
        assertThat(inFlightSummary.getRootSpanName()).isEqualTo("tool.partial");
    }

    @Test
    void inFlightTraceRootSpanNameIsNeverNull() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceCursorPage page = service.cursorPage(criteria, "new", null, null, 100);

        for (TraceSummary traceSummary : page.items()) {
            assertThat(traceSummary.getRootSpanName())
                    .as("rootSpanName must not be null for traceId %s", traceSummary.getTraceId())
                    .isNotNull();
        }
    }

    @Test
    void inFlightTraceCountedUnderDerivedServiceFromEarliestSpanName() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        // "tool.partial" maps to the 'tool.%' CASE branch → claude_code.tools
        Map<String, Long> serviceCountByName = new HashMap<>();
        for (FacetValue serviceValue : facets.service()) {
            serviceCountByName.put(serviceValue.value(), serviceValue.count());
        }
        assertThat(serviceCountByName).containsKey("claude_code.tools");
        // tool.execute (seeded in addTrace) + in-flight (tool.partial) = 2 traces under claude_code.tools
        assertThat(serviceCountByName.get("claude_code.tools")).isEqualTo(2L);
    }

    @Test
    void inFlightTraceAppearsInOperationFacet() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceFacets facets = service.facets(criteria);

        List<String> operations = facets.operation().stream()
                .map(FacetValue::value).toList();
        assertThat(operations).contains("tool.partial");
    }

    @Test
    void inFlightTraceIncludedInHistogramBucketTotal() {
        TraceQueryCriteria criteria = fullWindowCriteria();
        TraceHistogram histogram = service.histogram(criteria, 48);

        long bucketSum = histogram.buckets().stream()
                .mapToLong(b -> b.ok() + b.error())
                .sum();
        assertThat(bucketSum).isEqualTo(4);
        assertThat(histogram.total()).isEqualTo(4);
    }

    // -------------------------------------------------------------------------
    // firstUserPrompt
    // -------------------------------------------------------------------------

    @Test
    void firstUserPromptCollapsesWhitespaceAndTruncatesToPreviewLength() {
        TracePage page = service.offsetPage(fullWindowCriteria(), "new", 0, 25);

        TraceSummary sessionTrace = traceFrom(page, TRACE_SESSION_OK);
        assertThat(sessionTrace.getFirstUserPrompt()).isEqualTo(EXPECTED_COLLAPSED_TRUNCATED_PROMPT);
        assertThat(sessionTrace.getFirstUserPrompt()).hasSize(PROMPT_PREVIEW_LENGTH);
    }

    @Test
    void firstUserPromptTakesEarliestPromptWhenTraceCarriesSeveral() {
        TracePage page = service.offsetPage(fullWindowCriteria(), "new", 0, 25);

        // The in-flight trace carries a bare slash command first, then a follow-up.
        // Unlike the Sessions grid there is no slash-command deprioritization: a
        // trace has one initiating turn, so the earliest prompt wins as-is.
        assertThat(traceFrom(page, TRACE_IN_FLIGHT).getFirstUserPrompt()).isEqualTo(SLASH_COMMAND_PROMPT);
    }

    @Test
    void firstUserPromptNullWhenTraceIsNotRootedInAConversationalTurn() {
        TracePage page = service.offsetPage(fullWindowCriteria(), "new", 0, 25);

        // Tool-rooted: no user_prompt log carries this trace id at all.
        assertThat(traceFrom(page, TRACE_TOOL_OK).getFirstUserPrompt()).isNull();
    }

    @Test
    void firstUserPromptNullWhenPromptBodyCaptureWasDisabled() {
        TracePage page = service.offsetPage(fullWindowCriteria(), "new", 0, 25);

        // The user_prompt log exists but carries no prompt text (capture off).
        assertThat(traceFrom(page, TRACE_MODEL_ERROR).getFirstUserPrompt()).isNull();
    }

    @Test
    void cursorPageCarriesTheSameFirstUserPromptAsTheOffsetPage() {
        TraceCursorPage cursorPage = service.cursorPage(fullWindowCriteria(), "new", null, null, 60);

        TraceSummary sessionTrace = cursorPage.items().stream()
                .filter(item -> TRACE_SESSION_OK.equals(item.getTraceId()))
                .findFirst().orElseThrow();
        assertThat(sessionTrace.getFirstUserPrompt()).isEqualTo(EXPECTED_COLLAPSED_TRUNCATED_PROMPT);
    }

    // -------------------------------------------------------------------------
    // Single-trace summary (trace detail header)
    // -------------------------------------------------------------------------

    @Test
    void traceSummaryReturnsTheSameShapeAsAListRowIncludingThePrompt() {
        TraceSummary summary = service.traceSummary(TRACE_SESSION_OK).orElseThrow();

        assertThat(summary.getTraceId()).isEqualTo(TRACE_SESSION_OK);
        assertThat(summary.getRootSpanName()).isEqualTo("session.turn");
        assertThat(summary.getSessionId()).isEqualTo(SESSION_ID_ONE);
        assertThat(summary.getSpanCount()).isEqualTo(2L);
        assertThat(summary.getErrorCount()).isZero();
        assertThat(summary.getTotalTokens()).isEqualTo(EXPECTED_TOTAL_TOKENS);
        assertThat(summary.getFirstUserPrompt()).isEqualTo(EXPECTED_COLLAPSED_TRUNCATED_PROMPT);
    }

    @Test
    void traceSummaryResolvesTracesOutsideTheSelectedWindow() {
        // Not window-scoped — a permalinked trace must resolve regardless of window.
        assertThat(service.traceSummary(TRACE_OUTSIDE_WIN)).isPresent();
    }

    @Test
    void traceSummaryEmptyForUnknownTraceId() {
        assertThat(service.traceSummary("99999999999999999999999999999999")).isEmpty();
    }

    @Test
    void traceSummaryFallsBackToEarliestSpanAsRootForInFlightTraces() {
        TraceSummary summary = service.traceSummary(TRACE_IN_FLIGHT).orElseThrow();

        assertThat(summary.getRootSpanName()).isEqualTo("tool.partial");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static TraceSummary traceFrom(TracePage page, String traceId) {
        return page.items().stream()
                .filter(item -> traceId.equals(item.getTraceId()))
                .findFirst().orElseThrow();
    }

    /**
     * Seeds one {@code user_prompt} log record correlated to a trace the way Claude
     * Code emits it: the prompt text lives in the {@code prompt} attribute and the
     * owning trace id in the top-level {@code trace_id} column. A null
     * {@code promptText} models prompt-body capture being disabled — the event is
     * still emitted, it just carries no prompt.
     */
    private void addUserPromptLog(String traceId, String promptText, Instant timestamp) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, USER_PROMPT_EVENT_NAME);
        attributes.put(ATTR_SESSION_ID, SESSION_ID_ONE);
        if (promptText != null) {
            attributes.put(ATTR_PROMPT, promptText);
        }

        LogRecordEntity logRecord = new LogRecordEntity();
        logRecord.setTimestamp(timestamp);
        logRecord.setObservedTimestamp(timestamp);
        logRecord.setBody("");
        logRecord.setScopeName("com.anthropic.claude_code.events");
        logRecord.setTraceId(traceId);
        logRecord.setSpanId(traceId.substring(0, 16));
        logRecord.setAttributes(attributes);
        logRecord.setResourceAttributes(null);
        logRecord.setScopeAttributes(null);
        logRecord.setReceivedAt(Instant.now());
        logRecordRepository.save(logRecord);
    }

    private TraceQueryCriteria fullWindowCriteria() {
        return TraceQueryCriteria.of(
                windowStart, windowEnd,
                null, null, null, null, null, null);
    }

    private TraceQueryCriteria criteriaWithStatuses(List<String> statuses) {
        return TraceQueryCriteria.of(
                windowStart, windowEnd,
                statuses, null, null, null, null, null);
    }

    private static long durationCount(List<FacetValue> duration, String bucketId) {
        return duration.stream()
                .filter(f -> bucketId.equals(f.value()))
                .mapToLong(FacetValue::count)
                .sum();
    }

    /**
     * Seeds one in-flight trace: two spans that both have a non-null parent_span_id
     * pointing at {@code IN_FLIGHT_ORPHAN_PARENT_ID}, an unexported span. The earliest
     * span is named "tool.partial"; the later span is named "tool.partial.child".
     * Neither has {@code parent_span_id = null}, so the fallback root-resolution logic
     * must pick "tool.partial" (the earliest span) as the stand-in root.
     */
    private void addInFlightTrace(String traceId, Instant start, Instant end) {
        SpanEntity earlierSpan = new SpanEntity();
        earlierSpan.setTraceId(traceId);
        earlierSpan.setSpanId(traceId.substring(0, 16));
        earlierSpan.setParentSpanId(IN_FLIGHT_ORPHAN_PARENT_ID);
        earlierSpan.setName("tool.partial");
        earlierSpan.setKind("internal");
        earlierSpan.setStartTimestamp(start);
        earlierSpan.setEndTimestamp(end);
        earlierSpan.setDurationNanos((end.toEpochMilli() - start.toEpochMilli()) * 1_000_000L);
        earlierSpan.setStatusCode("ok");
        earlierSpan.setAttributes(null);
        earlierSpan.setScopeAttributes(null);
        earlierSpan.setResourceAttributes(null);
        earlierSpan.setEvents(null);
        earlierSpan.setReceivedAt(Instant.now());

        SpanEntity laterSpan = new SpanEntity();
        laterSpan.setTraceId(traceId);
        laterSpan.setSpanId(traceId.substring(16, 32));
        laterSpan.setParentSpanId(IN_FLIGHT_ORPHAN_PARENT_ID);
        laterSpan.setName("tool.partial.child");
        laterSpan.setKind("internal");
        laterSpan.setStartTimestamp(start.plusMillis(500));
        laterSpan.setEndTimestamp(end.minusMillis(100));
        laterSpan.setDurationNanos(1_000_000L);
        laterSpan.setStatusCode("ok");
        laterSpan.setAttributes(null);
        laterSpan.setScopeAttributes(null);
        laterSpan.setResourceAttributes(null);
        laterSpan.setEvents(null);
        laterSpan.setReceivedAt(Instant.now());

        spanRepository.save(earlierSpan);
        spanRepository.save(laterSpan);
    }

    /**
     * Seeds one trace with a fully-specified root-span attribute map. Useful when the
     * caller needs to inject token attributes or other custom keys alongside session.id.
     * The child span gets null attributes as usual.
     */
    private void addTraceWithRootAttributes(
            String traceId,
            String rootSpanName,
            Instant start,
            Instant end,
            boolean hasError,
            Map<String, Object> rootAttributes) {

        String statusCode = hasError ? "error" : "ok";

        SpanEntity rootSpan = new SpanEntity();
        rootSpan.setTraceId(traceId);
        rootSpan.setSpanId(traceId.substring(0, 16));
        rootSpan.setParentSpanId(null);
        rootSpan.setName(rootSpanName);
        rootSpan.setKind("server");
        rootSpan.setStartTimestamp(start);
        rootSpan.setEndTimestamp(end);
        rootSpan.setDurationNanos(end.toEpochMilli() - start.toEpochMilli() > 0
                ? (end.toEpochMilli() - start.toEpochMilli()) * 1_000_000L : 1_000_000L);
        rootSpan.setStatusCode(statusCode);
        rootSpan.setAttributes(rootAttributes.isEmpty() ? null : rootAttributes);
        rootSpan.setScopeAttributes(null);
        rootSpan.setResourceAttributes(null);
        rootSpan.setEvents(null);
        rootSpan.setReceivedAt(Instant.now());

        SpanEntity childSpan = new SpanEntity();
        childSpan.setTraceId(traceId);
        childSpan.setSpanId(traceId.substring(16, 32));
        childSpan.setParentSpanId(rootSpan.getSpanId());
        childSpan.setName("child." + rootSpanName);
        childSpan.setKind("internal");
        childSpan.setStartTimestamp(start.plusMillis(10));
        childSpan.setEndTimestamp(end.minusMillis(10));
        childSpan.setDurationNanos(1_000_000L);
        childSpan.setStatusCode(statusCode);
        childSpan.setAttributes(null);
        childSpan.setScopeAttributes(null);
        childSpan.setResourceAttributes(null);
        childSpan.setEvents(null);
        childSpan.setReceivedAt(Instant.now());

        spanRepository.save(rootSpan);
        spanRepository.save(childSpan);
    }

    /**
     * Seeds one trace: a root span + one child span sharing the same trace_id.
     * The root span carries {@code parent_span_id = null} and the session.id attribute.
     * Status is set on both spans when {@code hasError} is true.
     */
    private void addTrace(
            String traceId,
            String rootSpanName,
            String sessionId,
            Instant start,
            Instant end,
            boolean hasError) {

        String statusCode = hasError ? "error" : "ok";

        Map<String, Object> rootAttributes = new HashMap<>();
        if (sessionId != null) {
            rootAttributes.put(ATTR_SESSION_ID, sessionId);
        }

        SpanEntity rootSpan = new SpanEntity();
        rootSpan.setTraceId(traceId);
        rootSpan.setSpanId(traceId.substring(0, 16));
        rootSpan.setParentSpanId(null);
        rootSpan.setName(rootSpanName);
        rootSpan.setKind("server");
        rootSpan.setStartTimestamp(start);
        rootSpan.setEndTimestamp(end);
        rootSpan.setDurationNanos(end.toEpochMilli() - start.toEpochMilli() > 0
                ? (end.toEpochMilli() - start.toEpochMilli()) * 1_000_000L : 1_000_000L);
        rootSpan.setStatusCode(statusCode);
        rootSpan.setAttributes(rootAttributes.isEmpty() ? null : rootAttributes);
        rootSpan.setScopeAttributes(null);
        rootSpan.setResourceAttributes(null);
        rootSpan.setEvents(null);
        rootSpan.setReceivedAt(Instant.now());

        SpanEntity childSpan = new SpanEntity();
        childSpan.setTraceId(traceId);
        childSpan.setSpanId(traceId.substring(16, 32));
        childSpan.setParentSpanId(rootSpan.getSpanId());
        childSpan.setName("child." + rootSpanName);
        childSpan.setKind("internal");
        childSpan.setStartTimestamp(start.plusMillis(10));
        childSpan.setEndTimestamp(end.minusMillis(10));
        childSpan.setDurationNanos(1_000_000L);
        childSpan.setStatusCode(statusCode);
        childSpan.setAttributes(null);
        childSpan.setScopeAttributes(null);
        childSpan.setResourceAttributes(null);
        childSpan.setEvents(null);
        childSpan.setReceivedAt(Instant.now());

        spanRepository.save(rootSpan);
        spanRepository.save(childSpan);
    }
}
