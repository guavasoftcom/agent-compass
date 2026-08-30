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

/**
 * One default-minutes/explicit-minutes/custom-range trio per {@code GET /api/trends/*}
 * section endpoint, mirroring the dual-window dispatch shape every one of the four
 * shares (see backend/CLAUDE.md's "Thin controllers" section).
 */
@WebMvcTest(TrendsController.class)
class TrendsControllerTest {

    private static final int DEFAULT_MINUTES = 1440;
    private static final int EXPLICIT_MINUTES = 60;
    private static final Instant CUSTOM_RANGE_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant CUSTOM_RANGE_END = Instant.parse("2026-04-08T00:00:00Z");
    private static final String METRIC_KEY = "sample_metric";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TrendService trendService;

    private static TrendsResponse sampleResponse() {
        TrendsResponse.MetricTrend sampleMetric = new TrendsResponse.MetricTrend(
                128.40, 154.10,
                List.of(10.0, 12.0, 15.0, 18.0, 20.0, 25.0, 28.40),
                List.of(15.0, 18.0, 20.0, 22.0, 25.0, 27.0, 27.10),
                "down");
        return new TrendsResponse(
                new TrendsResponse.Window(
                        Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-29T00:00:00Z")),
                new TrendsResponse.Window(
                        Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-22T00:00:00Z")),
                Map.of(METRIC_KEY, sampleMetric));
    }

    private void assertSampleResponseBody(String url) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.start").value("2026-08-22T00:00:00Z"))
                .andExpect(jsonPath("$.current.end").value("2026-08-29T00:00:00Z"))
                .andExpect(jsonPath("$.previous.start").value("2026-08-15T00:00:00Z"))
                .andExpect(jsonPath("$.previous.end").value("2026-08-22T00:00:00Z"))
                .andExpect(jsonPath("$.metrics." + METRIC_KEY + ".before").value(128.40))
                .andExpect(jsonPath("$.metrics." + METRIC_KEY + ".after").value(154.10))
                .andExpect(jsonPath("$.metrics." + METRIC_KEY + ".beforeSeries.length()").value(7))
                .andExpect(jsonPath("$.metrics." + METRIC_KEY + ".afterSeries.length()").value(7))
                .andExpect(jsonPath("$.metrics." + METRIC_KEY + ".directionIsGoodWhen").value("down"));
    }

    // -------------------------------------------------------------------------
    // /api/trends/cost
    // -------------------------------------------------------------------------

    @Test
    void costTrendsDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(trendService.costTrends(DEFAULT_MINUTES)).thenReturn(sampleResponse());

        assertSampleResponseBody("/api/trends/cost");

        verify(trendService).costTrends(DEFAULT_MINUTES);
    }

    @Test
    void costTrendsDispatchesSuppliedMinutesToService() throws Exception {
        when(trendService.costTrends(EXPLICIT_MINUTES)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/cost").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(trendService).costTrends(EXPLICIT_MINUTES);
    }

    @Test
    void costTrendsUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(trendService.costTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/cost")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(trendService).costTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END);
    }

    // -------------------------------------------------------------------------
    // /api/trends/token-efficiency
    // -------------------------------------------------------------------------

    @Test
    void tokenEfficiencyTrendsDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(trendService.tokenEfficiencyTrends(DEFAULT_MINUTES)).thenReturn(sampleResponse());

        assertSampleResponseBody("/api/trends/token-efficiency");

        verify(trendService).tokenEfficiencyTrends(DEFAULT_MINUTES);
    }

    @Test
    void tokenEfficiencyTrendsDispatchesSuppliedMinutesToService() throws Exception {
        when(trendService.tokenEfficiencyTrends(EXPLICIT_MINUTES)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/token-efficiency").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(trendService).tokenEfficiencyTrends(EXPLICIT_MINUTES);
    }

    @Test
    void tokenEfficiencyTrendsUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(trendService.tokenEfficiencyTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/token-efficiency")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(trendService).tokenEfficiencyTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END);
    }

    // -------------------------------------------------------------------------
    // /api/trends/reliability
    // -------------------------------------------------------------------------

    @Test
    void reliabilityTrendsDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(trendService.reliabilityTrends(DEFAULT_MINUTES)).thenReturn(sampleResponse());

        assertSampleResponseBody("/api/trends/reliability");

        verify(trendService).reliabilityTrends(DEFAULT_MINUTES);
    }

    @Test
    void reliabilityTrendsDispatchesSuppliedMinutesToService() throws Exception {
        when(trendService.reliabilityTrends(EXPLICIT_MINUTES)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/reliability").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(trendService).reliabilityTrends(EXPLICIT_MINUTES);
    }

    @Test
    void reliabilityTrendsUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(trendService.reliabilityTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/reliability")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(trendService).reliabilityTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END);
    }

    // -------------------------------------------------------------------------
    // /api/trends/activity
    // -------------------------------------------------------------------------

    @Test
    void activityTrendsDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(trendService.activityTrends(DEFAULT_MINUTES)).thenReturn(sampleResponse());

        assertSampleResponseBody("/api/trends/activity");

        verify(trendService).activityTrends(DEFAULT_MINUTES);
    }

    @Test
    void activityTrendsDispatchesSuppliedMinutesToService() throws Exception {
        when(trendService.activityTrends(EXPLICIT_MINUTES)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/activity").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(trendService).activityTrends(EXPLICIT_MINUTES);
    }

    @Test
    void activityTrendsUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(trendService.activityTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/trends/activity")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(trendService).activityTrendsInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END);
    }
}
