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

import com.guavasoft.agentcompass.model.CostBreakdown;
import com.guavasoft.agentcompass.service.CostService;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispatch tests for {@code GET /api/cost/breakdown}, including {@code repositoryUrl} binding off
 * {@link com.guavasoft.agentcompass.model.TimeWindowParams} -- see backend/CLAUDE.md's dual-window
 * dispatch shape.
 */
@WebMvcTest(CostController.class)
class CostControllerTest {

    private static final int DEFAULT_MINUTES = 1440;
    private static final int EXPLICIT_MINUTES = 60;
    private static final Instant CUSTOM_RANGE_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant CUSTOM_RANGE_END = Instant.parse("2026-04-08T00:00:00Z");
    private static final String REPOSITORY_URL = "https://github.com/guavasoftcom/coding-agent-tuning";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CostService costService;

    private static CostBreakdown sampleBreakdown() {
        return new CostBreakdown(
                408.16, 331.90, 22.98, 1.21, 873.6,
                5814L, 100L, 50L, 10L, 20L,
                List.of(), List.of(), List.of(), List.of(), 86400L);
    }

    @Test
    void breakdownDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(costService.breakdown(DEFAULT_MINUTES, null)).thenReturn(sampleBreakdown());

        mockMvc.perform(get("/api/cost/breakdown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCostUsd").value(408.16))
                .andExpect(jsonPath("$.totalRequests").value(5814));

        verify(costService).breakdown(DEFAULT_MINUTES, null);
    }

    @Test
    void breakdownDispatchesSuppliedMinutesToService() throws Exception {
        when(costService.breakdown(EXPLICIT_MINUTES, null)).thenReturn(sampleBreakdown());

        mockMvc.perform(get("/api/cost/breakdown").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(costService).breakdown(EXPLICIT_MINUTES, null);
    }

    @Test
    void breakdownUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(costService.breakdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, null)).thenReturn(sampleBreakdown());

        mockMvc.perform(get("/api/cost/breakdown")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(costService).breakdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, null);
    }

    @Test
    void breakdownDispatchesSuppliedRepositoryUrlToServiceOnTheMinutesForm() throws Exception {
        when(costService.breakdown(DEFAULT_MINUTES, REPOSITORY_URL)).thenReturn(sampleBreakdown());

        mockMvc.perform(get("/api/cost/breakdown").param("repositoryUrl", REPOSITORY_URL))
                .andExpect(status().isOk());

        verify(costService).breakdown(DEFAULT_MINUTES, REPOSITORY_URL);
    }

    @Test
    void breakdownDispatchesSuppliedRepositoryUrlToServiceOnTheCustomRangeForm() throws Exception {
        when(costService.breakdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, REPOSITORY_URL))
                .thenReturn(sampleBreakdown());

        mockMvc.perform(get("/api/cost/breakdown")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString())
                .param("repositoryUrl", REPOSITORY_URL))
                .andExpect(status().isOk());

        verify(costService).breakdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, REPOSITORY_URL);
    }
}
