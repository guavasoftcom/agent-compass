package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.LogRecord;
import com.guavasoft.agentcompass.service.LogQueryService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LogsController.class)
class LogsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LogQueryService logQueryService;

    @Test
    void logsReturnsAllLogRecordsWhenNoFilterApplied() throws Exception {
        when(logQueryService.recentLogs(List.of(), null, null)).thenReturn(List.of(
                LogRecord.builder()
                        .id(7L)
                        .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                        .severityNumber(17)
                        .severityText("ERROR")
                        .body("Edit failed: old_string not unique")
                        .scopeName("claude-code.events")
                        .build()));

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].severityText").value("ERROR"))
                .andExpect(jsonPath("$[0].body").value("Edit failed: old_string not unique"));

        verify(logQueryService).recentLogs(List.of(), null, null);
    }

    @Test
    void logsNarrowsBySuppliedFiltersAndReturnsFilteredCount() throws Exception {
        when(logQueryService.recentLogs(
                List.of("event.name=tool_result", "tool_name=Read"), null, null))
                .thenReturn(List.of(
                        LogRecord.builder()
                                .id(11L)
                                .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                                .body("tool_result")
                                .scopeName("claude-code.events")
                                .build()));

        mockMvc.perform(get("/api/logs")
                .param("filter", "event.name=tool_result", "tool_name=Read"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].body").value("tool_result"));

        verify(logQueryService).recentLogs(
                List.of("event.name=tool_result", "tool_name=Read"), null, null);
    }

    @Test
    void logsNarrowsByTimeWindow() throws Exception {
        Instant windowStart = Instant.parse("2026-05-21T12:00:00Z");
        Instant windowEnd = Instant.parse("2026-05-21T12:05:00Z");
        when(logQueryService.recentLogs(List.of(), windowStart, windowEnd))
                .thenReturn(List.of(
                        LogRecord.builder()
                                .id(21L)
                                .timestamp(Instant.parse("2026-05-21T12:02:30Z"))
                                .body("tool_result")
                                .scopeName("claude-code.events")
                                .build()));

        mockMvc.perform(get("/api/logs")
                .param("startTimestamp", windowStart.toString())
                .param("endTimestamp", windowEnd.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)));

        verify(logQueryService).recentLogs(List.of(), windowStart, windowEnd);
    }

    @Test
    void logAttributesReturnsDistinctKeyValuePairsWhenNoFilterApplied() throws Exception {
        when(logQueryService.availableAttributePairs(List.of(), null, null)).thenReturn(List.of(
                "event.name=tool_result",
                "tool_name=Bash",
                "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0]").value("event.name=tool_result"));

        verify(logQueryService).availableAttributePairs(List.of(), null, null);
    }

    @Test
    void logAttributesNarrowsBySuppliedFilters() throws Exception {
        when(logQueryService.availableAttributePairs(
                List.of("event.name=tool_result"), null, null))
                .thenReturn(List.of("event.name=tool_result", "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes")
                .param("filter", "event.name=tool_result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        verify(logQueryService).availableAttributePairs(
                List.of("event.name=tool_result"), null, null);
    }

    @Test
    void logAttributesNarrowsByTimeWindow() throws Exception {
        Instant windowStart = Instant.parse("2026-05-21T12:00:00Z");
        Instant windowEnd = Instant.parse("2026-05-21T12:05:00Z");
        when(logQueryService.availableAttributePairs(List.of(), windowStart, windowEnd))
                .thenReturn(List.of("event.name=tool_result", "tool_name=Read"));

        mockMvc.perform(get("/api/logs/attributes")
                .param("startTimestamp", windowStart.toString())
                .param("endTimestamp", windowEnd.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        verify(logQueryService).availableAttributePairs(List.of(), windowStart, windowEnd);
    }
}
