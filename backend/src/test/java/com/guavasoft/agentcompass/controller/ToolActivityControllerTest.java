package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.HookExecutionSummary;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ToolActivityController.class)
class ToolActivityControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LogService logService;

    @MockitoBean
    TraceService traceService;

    @Test
    void toolCallsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolCalls(anyInt())).thenReturn(List.of(
                ToolCallCount.builder().tool("Read").calls(8L).build(),
                ToolCallCount.builder().tool("Bash").calls(1L).build()));

        mockMvc.perform(get("/api/tool-activity/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Read"))
                .andExpect(jsonPath("$[0].calls").value(8))
                .andExpect(jsonPath("$[1].tool").value("Bash"));

        verify(logService).aggregateToolCalls(1440);
    }

    @Test
    void skillUsageReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateSkillUsage(anyInt())).thenReturn(List.of(
                ToolCallCount.builder().tool("verify").calls(4L).build(),
                ToolCallCount.builder().tool("ship").calls(1L).build()));

        mockMvc.perform(get("/api/tool-activity/skill-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("verify"))
                .andExpect(jsonPath("$[0].calls").value(4))
                .andExpect(jsonPath("$[1].tool").value("ship"));

        verify(logService).aggregateSkillUsage(1440);
    }

    @Test
    void subagentUsageReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateSubagentUsage(anyInt())).thenReturn(List.of(
                ToolCallCount.builder().tool("Explore").calls(7L).build()));

        mockMvc.perform(get("/api/tool-activity/subagent-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tool").value("Explore"))
                .andExpect(jsonPath("$[0].calls").value(7));

        verify(logService).aggregateSubagentUsage(1440);
    }

    @Test
    void toolFailureRatesReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolFailureRates(anyInt())).thenReturn(List.of(
                new ToolFailureRate("Bash", 20L, 5L, 0.25),
                new ToolFailureRate("Read", 100L, 1L, 0.01)));

        mockMvc.perform(get("/api/tool-activity/failure-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Bash"))
                .andExpect(jsonPath("$[0].calls").value(20))
                .andExpect(jsonPath("$[0].failures").value(5))
                .andExpect(jsonPath("$[0].failureRate").value(0.25))
                .andExpect(jsonPath("$[1].tool").value("Read"));

        verify(logService).aggregateToolFailureRates(1440);
    }

    @Test
    void toolRepeatsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolRepeats(anyInt())).thenReturn(List.of(
                new ToolRepeatStat("Edit", "/repo/src/foo.ts", 4L, 8L, 3L),
                new ToolRepeatStat("Bash", "grep", 2L, 3L, 1L)));

        mockMvc.perform(get("/api/tool-activity/repeats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Edit"))
                .andExpect(jsonPath("$[0].scope").value("/repo/src/foo.ts"))
                .andExpect(jsonPath("$[0].medianRunLength").value(4))
                .andExpect(jsonPath("$[0].maxRunLength").value(8))
                .andExpect(jsonPath("$[0].sessions").value(3))
                .andExpect(jsonPath("$[1].tool").value("Bash"));

        verify(logService).aggregateToolRepeats(1440);
    }

    @Test
    void toolCallsPropagatesExplicitMinutesParam() throws Exception {
        when(logService.aggregateToolCalls(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/calls").param("minutes", "4320"))
                .andExpect(status().isOk());

        verify(logService).aggregateToolCalls(4320);
    }

    @Test
    void toolCallsRejectsDateRangeExceedingThirtyDays() throws Exception {
        mockMvc.perform(get("/api/tool-activity/calls")
                .param("startTimestamp", "2026-01-01T00:00:00Z")
                .param("endTimestamp", "2026-02-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void toolCallsAcceptsDateRangeOfExactlyThirtyDays() throws Exception {
        Instant rangeStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant rangeEnd = Instant.parse("2026-01-31T00:00:00Z");
        when(logService.aggregateToolCallsInRange(rangeStart, rangeEnd)).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/calls")
                .param("startTimestamp", rangeStart.toString())
                .param("endTimestamp", rangeEnd.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void toolDenialsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolDenials(anyInt())).thenReturn(List.of(
                new ToolDenialCount("Bash", "config", 12L),
                new ToolDenialCount("Edit", "hook", 3L)));

        mockMvc.perform(get("/api/tool-activity/denials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Bash"))
                .andExpect(jsonPath("$[0].source").value("config"))
                .andExpect(jsonPath("$[0].count").value(12))
                .andExpect(jsonPath("$[1].tool").value("Edit"));

        verify(logService).aggregateToolDenials(1440);
    }

    @Test
    void hookExecutionsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateHookExecutions(anyInt())).thenReturn(List.of(
                new HookExecutionSummary("PreToolUse", "PreToolUse:Write", 45L, 40L, 3L, 2L, 0L)));

        mockMvc.perform(get("/api/tool-activity/hook-executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].hookEvent").value("PreToolUse"))
                .andExpect(jsonPath("$[0].hookName").value("PreToolUse:Write"))
                .andExpect(jsonPath("$[0].blockingErrors").value(3));

        verify(logService).aggregateHookExecutions(1440);
    }
}
