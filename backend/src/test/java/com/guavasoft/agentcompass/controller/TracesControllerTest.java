package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.model.Span;
import com.guavasoft.agentcompass.model.TraceSummary;
import com.guavasoft.agentcompass.service.LogQueryService;
import com.guavasoft.agentcompass.service.TraceQueryService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TracesController.class)
class TracesControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TraceQueryService traceQueryService;

    @MockitoBean
    LogQueryService logQueryService;

    @Test
    void tracesReturnsTraceSummariesAndExposesTotalCountHeader() throws Exception {
        when(traceQueryService.recentTraces(null)).thenReturn(List.of(
                TraceSummary.builder()
                        .traceId("0102030405060708090a0b0c0d0e0f10")
                        .rootSpanName("Bash")
                        .spanCount(3L)
                        .startTimestamp(Instant.parse("2026-05-21T12:00:00Z"))
                        .endTimestamp(Instant.parse("2026-05-21T12:00:00.250Z"))
                        .durationNanos(250_000_000L)
                        .errorCount(0L)
                        .build()));
        when(traceQueryService.countTraces(null)).thenReturn(42L);

        mockMvc.perform(get("/api/traces"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].traceId").value("0102030405060708090a0b0c0d0e0f10"))
                .andExpect(jsonPath("$[0].rootSpanName").value("Bash"))
                .andExpect(jsonPath("$[0].spanCount").value(3))
                .andExpect(jsonPath("$[0].errorCount").value(0));

        verify(traceQueryService).recentTraces(null);
        verify(traceQueryService).countTraces(null);
    }

    @Test
    void traceLogRecordsReturnsAllLogRecordsForTheGivenTraceId() throws Exception {
        when(logQueryService.logsForTrace("0102030405060708090a0b0c0d0e0f10")).thenReturn(List.of(
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

        verify(logQueryService).logsForTrace("0102030405060708090a0b0c0d0e0f10");
    }

    @Test
    void traceSpansReturnsAllSpansForTheGivenTraceId() throws Exception {
        when(traceQueryService.spansForTrace("0102030405060708090a0b0c0d0e0f10")).thenReturn(List.of(
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

        verify(traceQueryService).spansForTrace("0102030405060708090a0b0c0d0e0f10");
    }
}
