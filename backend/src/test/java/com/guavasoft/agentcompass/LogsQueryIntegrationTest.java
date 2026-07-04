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
import com.guavasoft.agentcompass.model.LogCursor;
import com.guavasoft.agentcompass.model.LogCursorPage;
import com.guavasoft.agentcompass.model.LogFacets;
import com.guavasoft.agentcompass.model.LogHistogram;
import com.guavasoft.agentcompass.model.LogPage;
import com.guavasoft.agentcompass.model.LogQueryCriteria;
import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.LogService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link LogService} against a real Postgres instance so the
 * histogram zero-fill, faceted-search exclusion, cursor keyset paging,
 * event-based severity derivation, and tool-attribute SQL are validated
 * end-to-end. The controller-layer {@code LogsControllerTest} mocks the service
 * and cannot catch SQL-level bugs.
 *
 * <p>All seeded rows have NULL severity_text and NULL severity_number, mirroring
 * the real Claude Code telemetry (37k rows, all NULL). Severity is derived
 * entirely from the event-based fallback in {@code derive_log_severity()}.
 */
@SpringBootTest
@Testcontainers
class LogsQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository repository;

    @Autowired
    LogService service;

    @Autowired
    SpanRepository spanRepository;

    /** Window spanning 60 minutes; rows are seeded at 5-minute intervals inside it. */
    private Instant windowStart;
    private Instant windowEnd;

    // Row-offset constants (seconds from windowStart) for the eight seed rows.
    private static final int OFFSET_REJECT_DECISION     = 0;
    private static final int OFFSET_SUCCESS_FALSE       = 300;
    private static final int OFFSET_API_ERROR           = 600;
    private static final int OFFSET_COMPACTION          = 900;
    private static final int OFFSET_DISCONNECTED_STATUS = 1200;
    private static final int OFFSET_HOOK_EXECUTION      = 1500;
    private static final int OFFSET_TOOL_DECISION_ACCEPT = 1800;
    private static final int OFFSET_PLAIN_API_REQUEST   = 2100;

    // Expected severity labels for each row.
    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_WARN  = "WARN";
    private static final String SEVERITY_DEBUG = "DEBUG";
    private static final String SEVERITY_INFO  = "INFO";

    // Attribute keys used in seed rows and assertions.
    private static final String ATTR_EVENT_NAME             = "event.name";
    private static final String ATTR_DECISION               = "decision";
    private static final String ATTR_SUCCESS                = "success";
    private static final String ATTR_STATUS                 = "status";
    private static final String ATTR_TOOL_NAME              = "tool_name";
    private static final String ATTR_NUM_NON_BLOCKING_ERROR = "num_non_blocking_error";

    // Event-name values used in seed rows.
    private static final String EVENT_TOOL_DECISION           = "tool_decision";
    private static final String EVENT_TOOL_RESULT             = "tool_result";
    private static final String EVENT_API_ERROR               = "api_error";
    private static final String EVENT_COMPACTION              = "compaction";
    private static final String EVENT_MCP_SERVER_CONNECTION   = "mcp_server_connection";
    private static final String EVENT_HOOK_EXECUTION_START    = "hook_execution_start";
    private static final String EVENT_API_REQUEST             = "api_request";

    // Tool values used in seed rows.
    private static final String TOOL_READ  = "Read";
    private static final String TOOL_BASH  = "Bash";
    private static final String TOOL_WRITE = "Write";

    @BeforeEach
    void seed() {
        repository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        // Row 1 — ERROR via decision='reject' (tool_decision event).
        saveLog(windowStart.plusSeconds(OFFSET_REJECT_DECISION),
                null, null,
                "claude_code.tools", EVENT_TOOL_DECISION,
                TOOL_READ,
                Map.of(ATTR_EVENT_NAME, EVENT_TOOL_DECISION,
                        ATTR_DECISION, "reject",
                        ATTR_TOOL_NAME, TOOL_READ),
                "tool decision rejected");

        // Row 2 — ERROR via success='false' (tool_result event).
        saveLog(windowStart.plusSeconds(OFFSET_SUCCESS_FALSE),
                null, null,
                "claude_code.tools", EVENT_TOOL_RESULT,
                TOOL_BASH,
                Map.of(ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                        ATTR_SUCCESS, "false",
                        ATTR_TOOL_NAME, TOOL_BASH),
                "tool result failed");

        // Row 3 — ERROR via event.name='api_error'.
        saveLog(windowStart.plusSeconds(OFFSET_API_ERROR),
                null, null,
                "anthropic.api", EVENT_API_ERROR,
                null,
                Map.of(ATTR_EVENT_NAME, EVENT_API_ERROR),
                "api error occurred");

        // Row 4 — WARN via event.name='compaction'.
        saveLog(windowStart.plusSeconds(OFFSET_COMPACTION),
                null, null,
                "anthropic.api", EVENT_COMPACTION,
                null,
                Map.of(ATTR_EVENT_NAME, EVENT_COMPACTION),
                "context compaction triggered");

        // Row 5 — WARN via status='disconnected' (mcp_server_connection event).
        saveLog(windowStart.plusSeconds(OFFSET_DISCONNECTED_STATUS),
                null, null,
                "claude_code.mcp", EVENT_MCP_SERVER_CONNECTION,
                null,
                Map.of(ATTR_EVENT_NAME, EVENT_MCP_SERVER_CONNECTION,
                        ATTR_STATUS, "disconnected"),
                "mcp server disconnected");

        // Row 6 — DEBUG via event.name='hook_execution_start'.
        saveLog(windowStart.plusSeconds(OFFSET_HOOK_EXECUTION),
                null, null,
                "claude_code.hooks", EVENT_HOOK_EXECUTION_START,
                null,
                Map.of(ATTR_EVENT_NAME, EVENT_HOOK_EXECUTION_START,
                        ATTR_NUM_NON_BLOCKING_ERROR, "0"),
                "hook started");

        // Row 7 — DEBUG via tool_decision + decision='accept'.
        saveLog(windowStart.plusSeconds(OFFSET_TOOL_DECISION_ACCEPT),
                null, null,
                "claude_code.tools", EVENT_TOOL_DECISION,
                TOOL_WRITE,
                Map.of(ATTR_EVENT_NAME, EVENT_TOOL_DECISION,
                        ATTR_DECISION, "accept",
                        ATTR_TOOL_NAME, TOOL_WRITE),
                "tool decision accepted");

        // Row 8 — INFO via plain api_request (no error signal, not a debug event).
        saveLog(windowStart.plusSeconds(OFFSET_PLAIN_API_REQUEST),
                null, null,
                "anthropic.api", EVENT_API_REQUEST,
                null,
                Map.of(ATTR_EVENT_NAME, EVENT_API_REQUEST),
                "api request sent");
    }

    // -------------------------------------------------------------------------
    // Histogram tests
    // -------------------------------------------------------------------------

    @Test
    void histogramBucketSumEqualsFilteredTotalCount() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogHistogram histogram = service.histogram(allRows, 50);

        long bucketSum = histogram.buckets().stream()
                .mapToLong(bucket -> bucket.error() + bucket.warn() + bucket.info() + bucket.debug())
                .sum();

        long totalCount = service.cursorPageFirst(allRows, 1000).totalCount();
        assertThat(bucketSum).isEqualTo(totalCount).isEqualTo(8L);
    }

    @Test
    void histogramZeroFillsEmptyBuckets() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogHistogram histogram = service.histogram(allRows, 50);

        // Over a 60-min window with target=50, bucket width = 1 min → ~60 buckets,
        // far more than 8 seeded rows.
        assertThat(histogram.buckets().size()).isGreaterThan(8);

        boolean anyNonZero = histogram.buckets().stream()
                .anyMatch(bucket -> bucket.error() + bucket.warn() + bucket.info() + bucket.debug() > 0);
        assertThat(anyNonZero).isTrue();

        assertThat(histogram.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.t0()).isNotNull();
            assertThat(bucket.t1()).isNotNull();
        });
    }

    @Test
    void histogramExcludesSeverityFilterSoAllFourSeriesAlwaysPresent() {
        // Histogram intentionally ignores severity filter — all 8 rows still counted.
        LogQueryCriteria errorOnly = criteria(List.of(), List.of(SEVERITY_ERROR), List.of(), List.of(), "");
        LogHistogram histogram = service.histogram(errorOnly, 50);

        long totalBucketSum = histogram.buckets().stream()
                .mapToLong(bucket -> bucket.error() + bucket.warn() + bucket.info() + bucket.debug())
                .sum();
        assertThat(totalBucketSum).isEqualTo(8L);
    }

    // -------------------------------------------------------------------------
    // Derived-severity tests (all rows have NULL severity_text + severity_number)
    // -------------------------------------------------------------------------

    @Test
    void derivedSeverityFacetProducesExpectedErrorWarnInfoDebugCounts() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogFacets facets = service.facets(allRows);

        // Severity facet always has 4 entries in ERROR→WARN→INFO→DEBUG order.
        assertThat(facets.severity()).hasSize(4);
        List<String> severityOrder = facets.severity().stream().map(FacetValue::value).toList();
        assertThat(severityOrder).containsExactly(SEVERITY_ERROR, SEVERITY_WARN, SEVERITY_INFO, SEVERITY_DEBUG);

        // Rows 1 (reject), 2 (success=false), 3 (api_error) → 3 ERROR.
        long errorCount = countForSeverity(facets, SEVERITY_ERROR);
        assertThat(errorCount).isEqualTo(3L);

        // Rows 4 (compaction), 5 (status=disconnected) → 2 WARN.
        long warnCount = countForSeverity(facets, SEVERITY_WARN);
        assertThat(warnCount).isEqualTo(2L);

        // Row 8 (api_request, no error/warn/debug signal) → 1 INFO.
        long infoCount = countForSeverity(facets, SEVERITY_INFO);
        assertThat(infoCount).isEqualTo(1L);

        // Rows 6 (hook_execution_start), 7 (tool_decision accept) → 2 DEBUG.
        long debugCount = countForSeverity(facets, SEVERITY_DEBUG);
        assertThat(debugCount).isEqualTo(2L);
    }

    @Test
    void facetSeveritySumReconcilesTotalCount() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogFacets facets = service.facets(allRows);
        long totalCount = service.cursorPageFirst(allRows, 1000).totalCount();

        long severitySum = facets.severity().stream().mapToLong(FacetValue::count).sum();
        assertThat(severitySum).isEqualTo(totalCount).isEqualTo(8L);
    }

    @Test
    void histogramSumReconcilesTotalCount() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogHistogram histogram = service.histogram(allRows, 50);
        long totalCount = service.cursorPageFirst(allRows, 1000).totalCount();

        long bucketSum = histogram.buckets().stream()
                .mapToLong(bucket -> bucket.error() + bucket.warn() + bucket.info() + bucket.debug())
                .sum();
        assertThat(bucketSum).isEqualTo(totalCount).isEqualTo(8L);
    }

    @Test
    void severityFilterErrorReturnsOnlyThreeErrorRows() {
        LogQueryCriteria errorFilter = criteria(List.of(), List.of(SEVERITY_ERROR), List.of(), List.of(), "");
        LogCursorPage page = service.cursorPageFirst(errorFilter, 100);
        assertThat(page.totalCount()).isEqualTo(3L);
    }

    @Test
    void severityFilterWarnReturnsOnlyTwoWarnRows() {
        LogQueryCriteria warnFilter = criteria(List.of(), List.of(SEVERITY_WARN), List.of(), List.of(), "");
        LogCursorPage page = service.cursorPageFirst(warnFilter, 100);
        assertThat(page.totalCount()).isEqualTo(2L);
    }

    @Test
    void severityFilterDebugReturnsOnlyTwoDebugRows() {
        LogQueryCriteria debugFilter = criteria(List.of(), List.of(SEVERITY_DEBUG), List.of(), List.of(), "");
        LogCursorPage page = service.cursorPageFirst(debugFilter, 100);
        assertThat(page.totalCount()).isEqualTo(2L);
    }

    @Test
    void severityFilterInfoReturnsOnlyOneInfoRow() {
        LogQueryCriteria infoFilter = criteria(List.of(), List.of(SEVERITY_INFO), List.of(), List.of(), "");
        LogCursorPage page = service.cursorPageFirst(infoFilter, 100);
        assertThat(page.totalCount()).isEqualTo(1L);
        assertThat(page.items().get(0).getBody()).isEqualTo("api request sent");
    }

    // -------------------------------------------------------------------------
    // Tool facet — asserts tool_name attribute is used (not tool.name / mcp.server)
    // -------------------------------------------------------------------------

    @Test
    void toolFacetPicksUpToolNameAttributeValues() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogFacets facets = service.facets(allRows);

        List<String> toolValues = facets.tool().stream().map(FacetValue::value).toList();
        // Rows 1 (Read), 2 (Bash), 7 (Write) have tool_name set.
        assertThat(toolValues).containsExactlyInAnyOrder(TOOL_READ, TOOL_BASH, TOOL_WRITE);
    }

    @Test
    void toolFilterNarrowsRowsUsingToolNameKey() {
        // Only rows with tool_name=Read should match.
        LogQueryCriteria readFilter = criteria(List.of(), List.of(), List.of(TOOL_READ), List.of(), "");
        LogCursorPage page = service.cursorPageFirst(readFilter, 100);
        assertThat(page.totalCount()).isEqualTo(1L);
        assertThat(page.items().get(0).getBody()).isEqualTo("tool decision rejected");
    }

    // -------------------------------------------------------------------------
    // Scope is absent from facets response
    // -------------------------------------------------------------------------

    @Test
    void facetsResponseHasNoScopeField() {
        // LogFacets record has no scope field — this is a compile-time guarantee,
        // but we also verify the service returns the correct three-field shape.
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogFacets facets = service.facets(allRows);

        assertThat(facets.severity()).isNotNull();
        assertThat(facets.event()).isNotNull();
        assertThat(facets.tool()).isNotNull();
        // No scope() accessor exists on LogFacets — verified at compile time.
    }

    @Test
    void facetEventExcludesEventFilterFromItsOwnCounting() {
        // With event=tool_result active, event facet shows all events.
        LogQueryCriteria eventFilter = criteria(List.of(), List.of(), List.of(), List.of(EVENT_TOOL_RESULT), "");
        LogFacets facets = service.facets(eventFilter);

        List<String> eventValues = facets.event().stream().map(FacetValue::value).toList();
        // Should also include api_error, compaction, etc. — not just tool_result.
        assertThat(eventValues).containsAnyOf(EVENT_API_ERROR, EVENT_COMPACTION, EVENT_API_REQUEST);
    }

    @Test
    void facetToolExcludesToolFilterFromItsOwnCounting() {
        // With tool=Read active, tool facet shows all tools (own dim excluded).
        LogQueryCriteria toolFilter = criteria(List.of(), List.of(), List.of(TOOL_READ), List.of(), "");
        LogFacets facets = service.facets(toolFilter);

        List<String> toolValues = facets.tool().stream().map(FacetValue::value).toList();
        // Should include Bash and Write too.
        assertThat(toolValues).containsAnyOf(TOOL_BASH, TOOL_WRITE);
    }

    // -------------------------------------------------------------------------
    // Cursor paging tests
    // -------------------------------------------------------------------------

    @Test
    void cursorPageFirstReturnsNewestRowsFirst() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogCursorPage firstPage = service.cursorPageFirst(allRows, 3);

        assertThat(firstPage.items()).hasSize(3);
        assertThat(firstPage.totalCount()).isEqualTo(8L);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotNull();

        List<Instant> timestamps = firstPage.items().stream()
                .map(LogRecord::getTimestamp)
                .toList();
        for (int i = 0; i < timestamps.size() - 1; i++) {
            assertThat(timestamps.get(i)).isAfterOrEqualTo(timestamps.get(i + 1));
        }
    }

    @Test
    void cursorPageBeforeReturnsDeterministicallyOlderRowsWithNoOverlap() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogCursorPage firstPage = service.cursorPageFirst(allRows, 4);
        LogCursor cursor = firstPage.nextCursor();
        assertThat(cursor).isNotNull();

        LogCursorPage secondPage = service.cursorPageBefore(allRows, cursor, 4);
        assertThat(secondPage.items()).hasSize(4);

        List<Long> firstIds = firstPage.items().stream().map(LogRecord::getId).toList();
        List<Long> secondIds = secondPage.items().stream().map(LogRecord::getId).toList();
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

        Instant oldestFirst = firstPage.items().stream()
                .map(LogRecord::getTimestamp)
                .min(Instant::compareTo)
                .orElseThrow();
        secondPage.items().forEach(item ->
                assertThat(item.getTimestamp()).isBeforeOrEqualTo(oldestFirst));
    }

    @Test
    void cursorPagesCollectivelyReturnAllRows() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogCursorPage firstPage = service.cursorPageFirst(allRows, 4);
        LogCursorPage secondPage = service.cursorPageBefore(allRows, firstPage.nextCursor(), 4);

        List<Long> allIds = new java.util.ArrayList<>();
        firstPage.items().forEach(item -> allIds.add(item.getId()));
        secondPage.items().forEach(item -> allIds.add(item.getId()));

        assertThat(allIds).hasSize(8);
        assertThat(allIds).doesNotHaveDuplicates();
        assertThat(secondPage.hasMore()).isFalse();
    }

    @Test
    void cursorAfterReturnsOnlyRowsNewerThanCursor() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogRecord anchorRow = service.cursorPageFirst(allRows, 8).items().stream()
                .filter(row -> "api error occurred".equals(row.getBody()))
                .findFirst()
                .orElseThrow();
        LogCursor afterCursor = new LogCursor(anchorRow.getTimestamp(), anchorRow.getId());
        LogCursorPage tailPage = service.cursorPageAfter(allRows, afterCursor, 10);

        tailPage.items().forEach(item ->
                assertThat(item.getTimestamp()).isAfterOrEqualTo(anchorRow.getTimestamp()));
        assertThat(tailPage.items()).noneMatch(item -> item.getId().equals(anchorRow.getId()));
    }

    @Test
    void cursorAfterReturnsEmptyWhenNothingNew() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogRecord newestRow = service.cursorPageFirst(allRows, 1).items().get(0);
        LogCursor newestCursor = new LogCursor(newestRow.getTimestamp(), newestRow.getId());

        LogCursorPage tailPage = service.cursorPageAfter(allRows, newestCursor, 10);
        assertThat(tailPage.items()).isEmpty();
        assertThat(tailPage.hasMore()).isFalse();
    }

    @Test
    void parseCursorRoundTrips() {
        Instant timestamp = Instant.parse("2026-06-07T18:42:11.004Z");
        long rowId = 84213L;
        String cursorString = timestamp + "," + rowId;

        LogCursor parsed = service.parseCursor(cursorString);
        assertThat(parsed.ts()).isEqualTo(timestamp);
        assertThat(parsed.id()).isEqualTo(rowId);
    }

    @Test
    void cursorPageDispatchesOnRawCursorStrings() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogCursorPage firstPage = service.cursorPageFirst(allRows, 4);
        LogCursor cursor = firstPage.nextCursor();
        String cursorString = cursor.ts() + "," + cursor.id();

        LogCursorPage noCursorPage = service.cursorPage(allRows, null, null, 4);
        assertThat(idsOf(noCursorPage)).isEqualTo(idsOf(firstPage));

        LogCursorPage beforePage = service.cursorPage(allRows, cursorString, null, 4);
        assertThat(idsOf(beforePage)).isEqualTo(idsOf(service.cursorPageBefore(allRows, cursor, 4)));

        LogCursorPage afterPage = service.cursorPage(allRows, null, cursorString, 10);
        assertThat(idsOf(afterPage)).isEqualTo(idsOf(service.cursorPageAfter(allRows, cursor, 10)));
    }

    private static List<Long> idsOf(LogCursorPage page) {
        return page.items().stream().map(LogRecord::getId).toList();
    }

    // -------------------------------------------------------------------------
    // Offset paging tests
    // -------------------------------------------------------------------------

    @Test
    void offsetPageReturnsCorrectPageAndTotalCount() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogPage firstOffsetPage = service.offsetPage(allRows, 0, 5);

        assertThat(firstOffsetPage.totalCount()).isEqualTo(8L);
        assertThat(firstOffsetPage.items()).hasSize(5);

        LogPage secondOffsetPage = service.offsetPage(allRows, 1, 5);
        assertThat(secondOffsetPage.totalCount()).isEqualTo(8L);
        assertThat(secondOffsetPage.items()).hasSize(3);
    }

    @Test
    void offsetPageTotalCountMatchesCursorTotalCount() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        long offsetTotal = service.offsetPage(allRows, 0, 100).totalCount();
        long cursorTotal = service.cursorPageFirst(allRows, 100).totalCount();
        assertThat(offsetTotal).isEqualTo(cursorTotal).isEqualTo(8L);
    }

    @Test
    void offsetPageIsDisjointAcrossPages() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogPage page0 = service.offsetPage(allRows, 0, 4);
        LogPage page1 = service.offsetPage(allRows, 1, 4);

        List<Long> ids0 = page0.items().stream().map(LogRecord::getId).toList();
        List<Long> ids1 = page1.items().stream().map(LogRecord::getId).toList();
        assertThat(ids0).doesNotContainAnyElementsOf(ids1);
    }

    // -------------------------------------------------------------------------
    // HIGH-2 regression — unclamped limit/size/page resource exhaustion
    // -------------------------------------------------------------------------

    @Test
    void offsetPageClampsOversizedSizeInsteadOfFetchingUnboundedRows() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        // size is attacker-chosen and huge; must be clamped, not passed straight to SQL LIMIT.
        LogPage page = service.offsetPage(allRows, 0, 2_000_000_000);

        assertThat(page.totalCount()).isEqualTo(8L);
        assertThat(page.items()).hasSize(8);
    }

    @Test
    void offsetPageGuardsAgainstPageTimesSizeIntOverflow() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        // page * size would overflow int and wrap to a negative OFFSET pre-fix, causing a 500.
        LogPage page = service.offsetPage(allRows, Integer.MAX_VALUE, 25);

        assertThat(page.totalCount()).isEqualTo(8L);
        assertThat(page.items()).isEmpty();
    }

    @Test
    void offsetPageFloorsNegativePageToZero() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        LogPage page = service.offsetPage(allRows, -5, 5);

        assertThat(page.totalCount()).isEqualTo(8L);
        assertThat(page.items()).hasSize(5);
    }

    @Test
    void cursorPageFirstClampsOversizedLimitInsteadOfOverflowingTheProbeFetch() {
        LogQueryCriteria allRows = criteria(List.of(), List.of(), List.of(), List.of(), "");
        // limit=Integer.MAX_VALUE previously overflowed resolvedLimit + 1 into a negative
        // SQL LIMIT (500 error). Clamped, this must behave like any oversized-but-valid limit.
        LogCursorPage page = service.cursorPageFirst(allRows, Integer.MAX_VALUE);

        assertThat(page.totalCount()).isEqualTo(8L);
        assertThat(page.items()).hasSize(8);
        assertThat(page.hasMore()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Full-text query test
    // -------------------------------------------------------------------------

    @Test
    void fullTextQueryNarrowsResults() {
        LogQueryCriteria queryFilter = criteria(List.of(), List.of(), List.of(), List.of(), "api request sent");
        LogCursorPage page = service.cursorPageFirst(queryFilter, 10);

        assertThat(page.totalCount()).isEqualTo(1L);
        assertThat(page.items().get(0).getBody()).isEqualTo("api request sent");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LogQueryCriteria criteria(
            List<String> filters,
            List<String> severities,
            List<String> tools,
            List<String> events,
            String fullTextQuery) {
        return LogQueryCriteria.of(
                windowStart, windowEnd,
                filters, severities, events, tools, fullTextQuery);
    }

    private static long countForSeverity(LogFacets facets, String severityLabel) {
        return facets.severity().stream()
                .filter(facetValue -> severityLabel.equals(facetValue.value()))
                .mapToLong(FacetValue::count)
                .sum();
    }

    private void saveLog(
            Instant timestamp,
            String severityText,
            Integer severityNumber,
            String scopeName,
            String eventName,
            String toolName,
            Map<String, Object> extraAttributes,
            String body) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setSeverityText(severityText);
        entity.setSeverityNumber(severityNumber);
        entity.setScopeName(scopeName);
        entity.setBody(body);

        Map<String, Object> attributes = new HashMap<>(extraAttributes);
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        repository.save(entity);
    }

    // -------------------------------------------------------------------------
    // logsForTrace — leaf-span re-pointing
    // -------------------------------------------------------------------------

    private static final String TRACE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SPAN_ROOT = "r000000000000000";
    private static final String SPAN_TOOL_WRAPPER = "t000000000000000";
    private static final String SPAN_TOOL_EXEC = "e000000000000000";
    private static final String SPAN_BLOCKED = "b000000000000000";
    private static final String SPAN_LLM = "l000000000000000";
    private static final String TOOL_USE_ID = "toolu_abc";
    private static final String REQUEST_ID = "req_xyz";
    private static final String PROMPT_ID = "prompt_1";
    private static final String EVENT_API_REQUEST_BODY = "api_request_body";

    @Test
    void logsForTraceRepointsCoarseLogsToTheirLeafSpans() {
        spanRepository.deleteAll();
        saveSpan(SPAN_ROOT, null, "claude_code.interaction", Map.of());
        saveSpan(SPAN_TOOL_WRAPPER, SPAN_ROOT, "claude_code.tool",
                Map.of("tool_use_id", TOOL_USE_ID));
        saveSpan(SPAN_TOOL_EXEC, SPAN_TOOL_WRAPPER, "claude_code.tool.execution",
                Map.of("tool_use_id", TOOL_USE_ID));
        saveSpan(SPAN_BLOCKED, SPAN_TOOL_WRAPPER, "claude_code.tool.blocked_on_user", Map.of());
        saveSpan(SPAN_LLM, SPAN_ROOT, "claude_code.llm_request",
                Map.of("request_id", REQUEST_ID));

        // tool_result stamped on the coarse root → expect re-point to the execution leaf
        // (not the tool wrapper, which shares the same tool_use_id).
        saveTraceLog(SPAN_ROOT, EVENT_TOOL_RESULT, Map.of("tool_use_id", TOOL_USE_ID));
        // api_request stamped on the coarse root → expect re-point to the llm_request span.
        // Carries prompt.id + event.sequence so it also anchors the api_request_body pairing.
        saveTraceLog(SPAN_ROOT, EVENT_API_REQUEST,
                Map.of("request_id", REQUEST_ID, "prompt.id", PROMPT_ID, "event.sequence", 20));
        // api_request_body has no request_id; it precedes its api_request in sequence within
        // the same prompt → expect re-point to the same llm_request span via the pairing.
        saveTraceLog(SPAN_ROOT, EVENT_API_REQUEST_BODY,
                Map.of("prompt.id", PROMPT_ID, "event.sequence", 19));
        // tool_decision already on its blocked_on_user leaf → expect preserved, even
        // though it also carries a tool_use_id.
        saveTraceLog(SPAN_BLOCKED, EVENT_TOOL_DECISION, Map.of("tool_use_id", TOOL_USE_ID));
        // hook on the coarse root with no correlation key → expect preserved on the root.
        saveTraceLog(SPAN_ROOT, EVENT_HOOK_EXECUTION_START, Map.of());

        Map<String, String> spanIdByEvent = service.logsForTrace(TRACE_ID).stream()
                .collect(java.util.stream.Collectors.toMap(
                        logRecord -> (String) logRecord.getAttributes().get(ATTR_EVENT_NAME),
                        LogRecord::getSpanId));

        assertThat(spanIdByEvent.get(EVENT_TOOL_RESULT)).isEqualTo(SPAN_TOOL_EXEC);
        assertThat(spanIdByEvent.get(EVENT_API_REQUEST)).isEqualTo(SPAN_LLM);
        assertThat(spanIdByEvent.get(EVENT_API_REQUEST_BODY)).isEqualTo(SPAN_LLM);
        assertThat(spanIdByEvent.get(EVENT_TOOL_DECISION)).isEqualTo(SPAN_BLOCKED);
        assertThat(spanIdByEvent.get(EVENT_HOOK_EXECUTION_START)).isEqualTo(SPAN_ROOT);
    }

    private void saveSpan(String spanId, String parentSpanId, String name, Map<String, Object> attributes) {
        SpanEntity entity = new SpanEntity();
        entity.setTraceId(TRACE_ID);
        entity.setSpanId(spanId);
        entity.setParentSpanId(parentSpanId);
        entity.setName(name);
        entity.setStartTimestamp(windowStart);
        entity.setEndTimestamp(windowStart.plusSeconds(1));
        entity.setReceivedAt(Instant.now());
        entity.setAttributes(new HashMap<>(attributes));
        spanRepository.save(entity);
    }

    private void saveTraceLog(String spanId, String eventName, Map<String, Object> extraAttributes) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(windowStart);
        entity.setReceivedAt(Instant.now());
        entity.setTraceId(TRACE_ID);
        entity.setSpanId(spanId);
        Map<String, Object> attributes = new HashMap<>(extraAttributes);
        attributes.put(ATTR_EVENT_NAME, eventName);
        entity.setAttributes(attributes);
        repository.save(entity);
    }
}
