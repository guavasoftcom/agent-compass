package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceCursorPage;
import com.guavasoft.agentcompass.model.TraceQueryCriteria;
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
class TracesControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TraceService traceService;

    @MockitoBean
    TraceExplorerService traceExplorerService;

    @MockitoBean
    LogService logService;

    @Test
    void tracesListReturnsCursorPageWithTotalCount() throws Exception {
        when(traceExplorerService.cursorPage(
                any(TraceQueryCriteria.class), eq("new"), isNull(), isNull(), anyInt()))
                .thenReturn(new TraceCursorPage(List.of(), null, false, 0L));

        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", "2026-06-11T00:00:00Z")
                        .param("endTimestamp", "2026-06-12T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(traceExplorerService)
                .cursorPage(any(TraceQueryCriteria.class), eq("new"), isNull(), isNull(), anyInt());
    }

    @Test
    void traceLogRecordsReturnsAllLogRecordsForTheGivenTraceId() throws Exception {
        when(logService.logsForTrace("0102030405060708090a0b0c0d0e0f10")).thenReturn(List.of(
                LogRecord.builder()
                        .id(3L)
                        .traceId("0102030405060708090a0b0c0d0e0f10")
                        .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                        .severityNumber(9)
                        .severityText("INFO")
                        .body("tool_result")
                        .scopeName("claude-code.events")
                        .build(),
                LogRecord.builder()
                        .id(4L)
                        .traceId("0102030405060708090a0b0c0d0e0f10")
                        .timestamp(Instant.parse("2026-05-21T12:00:01Z"))
                        .severityNumber(9)
                        .severityText("INFO")
                        .body("agent_turn_end")
                        .scopeName("claude-code.events")
                        .build()));

        mockMvc.perform(get("/api/traces/0102030405060708090a0b0c0d0e0f10/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].traceId").value("0102030405060708090a0b0c0d0e0f10"))
                .andExpect(jsonPath("$[0].body").value("tool_result"))
                .andExpect(jsonPath("$[1].body").value("agent_turn_end"));

        verify(logService).logsForTrace("0102030405060708090a0b0c0d0e0f10");
    }

    // -------------------------------------------------------------------------
    // HIGH-1 regression — unauthenticated DoS via unbounded date range / buckets
    // -------------------------------------------------------------------------

    @Test
    void histogramRejectsDateRangeOverThirtyDays() throws Exception {
        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", "1970-01-01T00:00:00Z")
                        .param("endTimestamp", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void histogramRejectsBucketsAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", "2026-06-11T00:00:00Z")
                        .param("endTimestamp", "2026-06-12T00:00:00Z")
                        .param("buckets", "100000000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void histogramRejectsBucketsBelowMinimum() throws Exception {
        mockMvc.perform(get("/api/traces/histogram")
                        .param("startTimestamp", "2026-06-11T00:00:00Z")
                        .param("endTimestamp", "2026-06-12T00:00:00Z")
                        .param("buckets", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void facetsRejectsDateRangeOverThirtyDays() throws Exception {
        mockMvc.perform(get("/api/traces/facets")
                        .param("startTimestamp", "1970-01-01T00:00:00Z")
                        .param("endTimestamp", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tracesCursorPageRejectsDateRangeOverThirtyDays() throws Exception {
        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", "1970-01-01T00:00:00Z")
                        .param("endTimestamp", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tracesOffsetPageRejectsDateRangeOverThirtyDays() throws Exception {
        mockMvc.perform(get("/api/traces")
                        .param("startTimestamp", "1970-01-01T00:00:00Z")
                        .param("endTimestamp", "2026-01-01T00:00:00Z")
                        .param("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void traceSpansReturnsAllSpansForTheGivenTraceId() throws Exception {
        when(traceService.spansForTrace("0102030405060708090a0b0c0d0e0f10")).thenReturn(List.of(
                Span.builder()
                        .id(1L)
                        .traceId("0102030405060708090a0b0c0d0e0f10")
                        .spanId("1112131415161718")
                        .name("Bash")
                        .kind("internal")
                        .statusCode("ok")
                        .durationNanos(25_000_000L)
                        .build(),
                Span.builder()
                        .id(2L)
                        .traceId("0102030405060708090a0b0c0d0e0f10")
                        .spanId("2122232425262728")
                        .parentSpanId("1112131415161718")
                        .name("exec")
                        .build()));

        mockMvc.perform(get("/api/traces/0102030405060708090a0b0c0d0e0f10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].spanId").value("1112131415161718"))
                .andExpect(jsonPath("$[1].parentSpanId").value("1112131415161718"));

        verify(traceService).spansForTrace("0102030405060708090a0b0c0d0e0f10");
    }
}
