package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.TrendsResponse;
import com.guavasoft.agentcompass.service.TrendService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrendsController.class)
class TrendsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TrendService trendService;

    private static TrendsResponse sampleResponse() {
        TrendsResponse.MetricTrend totalCost = new TrendsResponse.MetricTrend(
                128.40, 154.10,
                List.of(10.0, 12.0, 15.0, 18.0, 20.0, 25.0, 28.40),
                List.of(15.0, 18.0, 20.0, 22.0, 25.0, 27.0, 27.10),
                "down");
        return new TrendsResponse(
                new TrendsResponse.Window(
                        Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-29T00:00:00Z")),
                new TrendsResponse.Window(
                        Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-22T00:00:00Z")),
                Map.of("total_cost", totalCost));
    }

    @Test
    void trendsDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(trendService.trends(1440)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.start").value("2026-08-22T00:00:00Z"))
                .andExpect(jsonPath("$.current.end").value("2026-08-29T00:00:00Z"))
                .andExpect(jsonPath("$.previous.start").value("2026-08-15T00:00:00Z"))
                .andExpect(jsonPath("$.previous.end").value("2026-08-22T00:00:00Z"))
                .andExpect(jsonPath("$.metrics.total_cost.before").value(128.40))
                .andExpect(jsonPath("$.metrics.total_cost.after").value(154.10))
                .andExpect(jsonPath("$.metrics.total_cost.beforeSeries.length()").value(7))
                .andExpect(jsonPath("$.metrics.total_cost.afterSeries.length()").value(7))
                .andExpect(jsonPath("$.metrics.total_cost.directionIsGoodWhen").value("down"));

        verify(trendService).trends(1440);
    }

    @Test
    void trendsDispatchesSuppliedMinutesToService() throws Exception {
        when(trendService.trends(60)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends").param("minutes", "60"))
                .andExpect(status().isOk());

        verify(trendService).trends(60);
    }

    @Test
    void trendsUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        Instant startTimestamp = Instant.parse("2026-04-01T00:00:00Z");
        Instant endTimestamp = Instant.parse("2026-04-08T00:00:00Z");
        when(trendService.trendsInRange(startTimestamp, endTimestamp)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends")
                .param("startTimestamp", startTimestamp.toString())
                .param("endTimestamp", endTimestamp.toString()))
                .andExpect(status().isOk());

        verify(trendService).trendsInRange(startTimestamp, endTimestamp);
    }
}
