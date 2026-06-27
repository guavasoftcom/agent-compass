package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.FacetValue;
import com.guavasoft.agentcompass.model.TraceCursor;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceFacets;
import com.guavasoft.agentcompass.model.TraceHistogram;
import com.guavasoft.agentcompass.model.TraceHistogramBucket;
import com.guavasoft.agentcompass.model.TracePage;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceExplorerService;
import com.guavasoft.agentcompass.service.TraceService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TracesController.class)
class TracesExplorerControllerTest {

    private static final String WINDOW_START = "2026-06-11T00:00:00Z";
    private static final String WINDOW_END = "2026-06-12T00:00:00Z";
    private static final String TRACE_ID = "aabbccddeeff00112233445566778899";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TraceService traceService;

    @MockitoBean
    TraceExplorerService traceExplorerService;

    @MockitoBean
    LogService logService;

    // -------------------------------------------------------------------------
    // GET /api/traces/histogram
    // -------------------------------------------------------------------------

    @Test
    void histogramReturnsTraceHistogramWithDefaultBuckets() throws Exception {
        Instant bucketT0 = Instant.parse(WINDOW_START);
        Instant bucketT1 = bucketT0.plusSeconds(1800);
        TraceHistogram histogram = new TraceHistogram(
                1_800_000L,
                List.of(new TraceHistogramBucket(bucketT0, bucketT1, 41L, 3L, 8200.0)),
                1840.0, 11200.0, 540L, 28L);

        when(traceExplorerService.histogram(any(TraceQueryCriteria.class), eq(48)))
                .thenReturn(histogram);

        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketMs").value(1_800_000))
                .andExpect(jsonPath("$.total").value(540))
                .andExpect(jsonPath("$.errorCount").value(28))
                .andExpect(jsonPath("$.p50Ms").value(1840.0))
                .andExpect(jsonPath("$.p95Ms").value(11200.0))
                .andExpect(jsonPath("$.buckets", hasSize(1)))
                .andExpect(jsonPath("$.buckets[0].ok").value(41))
                .andExpect(jsonPath("$.buckets[0].error").value(3))
                .andExpect(jsonPath("$.buckets[0].p95Ms").value(8200.0));

        verify(traceExplorerService).histogram(any(TraceQueryCriteria.class), eq(48));
    }

    @Test
    void histogramForwardsCustomBucketCount() throws Exception {
        when(traceExplorerService.histogram(any(TraceQueryCriteria.class), eq(24)))
                .thenReturn(new TraceHistogram(3_600_000L, List.of(), 0.0, 0.0, 0L, 0L));

        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("buckets", "24"))
                .andExpect(status().isOk());

        verify(traceExplorerService).histogram(any(TraceQueryCriteria.class), eq(24));
    }

    // -------------------------------------------------------------------------
    // GET /api/traces/facets
    // -------------------------------------------------------------------------

    @Test
    void facetsReturnsBothStatusValuesAlwaysZeroFilled() throws Exception {
        TraceFacets facets = new TraceFacets(
                List.of(new FacetValue("ok", 512L), new FacetValue("error", 28L)),
                List.of(new FacetValue("session.turn", 160L)),
                List.of(new FacetValue("claude_code.session", 165L)),
                List.of(
                        new FacetValue("d0", 60L),
                        new FacetValue("d1", 210L),
                        new FacetValue("d2", 180L),
                        new FacetValue("d3", 90L)),
                List.of(new FacetValue("sess_1a2b3c4d", 44L)));

        when(traceExplorerService.facets(any(TraceQueryCriteria.class))).thenReturn(facets);

        mockMvc.perform(get("/api/traces/facets")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", hasSize(2)))
                .andExpect(jsonPath("$.status[0].value").value("ok"))
                .andExpect(jsonPath("$.status[0].count").value(512))
                .andExpect(jsonPath("$.status[1].value").value("error"))
                .andExpect(jsonPath("$.status[1].count").value(28))
                .andExpect(jsonPath("$.operation[0].value").value("session.turn"))
                .andExpect(jsonPath("$.service[0].value").value("claude_code.session"))
                .andExpect(jsonPath("$.duration", hasSize(4)))
                .andExpect(jsonPath("$.duration[0].value").value("d0"))
                .andExpect(jsonPath("$.session[0].value").value("sess_1a2b3c4d"));

        verify(traceExplorerService).facets(any(TraceQueryCriteria.class));
    }

    // -------------------------------------------------------------------------
    // GET /api/traces — cursor mode (no 'page' param)
    // -------------------------------------------------------------------------

    @Test
    void tracesCursorInitialPageReturnsCursorPageWithTotalCount() throws Exception {
        TraceSummary traceSummary = TraceSummary.builder()
                .traceId(TRACE_ID)
                .rootSpanName("session.turn")
                .spanCount(8L)
                .startTimestamp(Instant.parse("2026-06-11T12:00:00Z"))
                .endTimestamp(Instant.parse("2026-06-11T12:00:05Z"))
                .durationNanos(5_000_000_000L)
                .errorCount(0L)
                .build();
        TraceCursor nextCursor = new TraceCursor(Instant.parse("2026-06-11T12:00:00Z"), TRACE_ID);
        TraceCursorPage cursorPage = new TraceCursorPage(
                List.of(traceSummary), nextCursor, false, 540L);

        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("new"), isNull(), isNull(), anyInt()))
                .thenReturn(cursorPage);

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.items[0].rootSpanName").value("session.turn"))
                .andExpect(jsonPath("$.totalCount").value(540))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor.id").value(TRACE_ID));

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("new"), isNull(), isNull(), anyInt());
    }

    @Test
    void tracesCursorPageForwardsBeforeCursorParam() throws Exception {
        String beforeCursor = "2026-06-11T12:00:00Z," + TRACE_ID;

        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("new"), eq(beforeCursor), isNull(), anyInt()))
                .thenReturn(new TraceCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("before", beforeCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("new"), eq(beforeCursor), isNull(), anyInt());
    }

    @Test
    void tracesCursorPageForwardsAfterCursorParamForLiveTail() throws Exception {
        String afterCursor = "2026-06-11T12:00:00Z," + TRACE_ID;

        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("new"), isNull(), eq(afterCursor), anyInt()))
                .thenReturn(new TraceCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("after", afterCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("new"), isNull(), eq(afterCursor), anyInt());
    }

    @Test
    void tracesCursorPageForwardsSortParam() throws Exception {
        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("slow"), isNull(), isNull(), anyInt()))
                .thenReturn(new TraceCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("sort", "slow"))
                .andExpect(status().isOk());

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("slow"), isNull(), isNull(), anyInt());
    }

    @Test
    void tracesCursorPageForwardsSortTokensParam() throws Exception {
        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("tokens"), isNull(), isNull(), anyInt()))
                .thenReturn(new TraceCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("sort", "tokens"))
                .andExpect(status().isOk());

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("tokens"), isNull(), isNull(), anyInt());
    }

    // -------------------------------------------------------------------------
    // GET /api/traces?page= — offset mode
    // -------------------------------------------------------------------------

    @Test
    void tracesOffsetPageReturnsPaginatedResult() throws Exception {
        TraceSummary traceSummary = TraceSummary.builder()
                .traceId(TRACE_ID)
                .rootSpanName("tool.execute")
                .spanCount(4L)
                .startTimestamp(Instant.parse("2026-06-11T10:00:00Z"))
                .endTimestamp(Instant.parse("2026-06-11T10:00:01Z"))
                .durationNanos(1_000_000_000L)
                .errorCount(0L)
                .build();
        TracePage tracePage = new TracePage(List.of(traceSummary), 100L);

        when(traceExplorerService.offsetPage(
                any(TraceQueryCriteria.class), eq("new"), eq(0), eq(25)))
                .thenReturn(tracePage);

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.totalCount").value(100));

        verify(traceExplorerService).offsetPage(any(TraceQueryCriteria.class), eq("new"), eq(0), eq(25));
    }

    @Test
    void tracesOffsetPageForwardsSortParam() throws Exception {
        when(traceExplorerService.offsetPage(
                any(TraceQueryCriteria.class), eq("err"), eq(0), anyInt()))
                .thenReturn(new TracePage(List.of(), 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("page", "0")
                        .param("sort", "err"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0));

        verify(traceExplorerService)
                .offsetPage(any(TraceQueryCriteria.class), eq("err"), eq(0), anyInt());
    }

    // -------------------------------------------------------------------------
    // Filter params forwarding
    // -------------------------------------------------------------------------

    @Test
    void histogramForwardsStatusFilterToService() throws Exception {
        when(traceExplorerService.histogram(any(TraceQueryCriteria.class), anyInt()))
                .thenReturn(new TraceHistogram(1_800_000L, List.of(), 0.0, 0.0, 0L, 0L));

        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", WINDOW_START)
                        .param("endTimestamp", WINDOW_END)
                        .param("status", "error"))
                .andExpect(status().isOk());

        verify(traceExplorerService).histogram(any(TraceQueryCriteria.class), anyInt());
    }
}
