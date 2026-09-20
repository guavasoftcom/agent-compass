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

import com.guavasoft.agentcompass.model.UsageCalendarDaily;
import com.guavasoft.agentcompass.model.UsageCalendarDay;
import com.guavasoft.agentcompass.model.UsageCalendarHour;
import com.guavasoft.agentcompass.model.UsageCalendarModelCost;
import com.guavasoft.agentcompass.service.UsageCalendarService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispatch and validation tests for {@code GET /api/usage/calendar/daily}: parameter binding, the
 * UTC default, the "Unattributed" sentinel normalization, and the 42-day span cap.
 */
@WebMvcTest(UsageCalendarController.class)
class UsageCalendarControllerTest {

    private static final String FROM = "2026-09-01T05:00:00Z";
    private static final String TO = "2026-10-01T05:00:00Z";
    private static final String TIME_ZONE = "America/Chicago";
    private static final String REPOSITORY_URL = "https://github.com/guavasoftcom/coding-agent-tuning";
    private static final String UNATTRIBUTED_SENTINEL = "__unattributed__";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UsageCalendarService usageCalendarService;

    private static UsageCalendarDaily sampleDaily() {
        return new UsageCalendarDaily(List.of(new UsageCalendarDay(
                LocalDate.of(2026, 9, 1), 73.2, 369_400L, 5L, 2L, 4L, 7_860L, 284L, 99L, 0L, 1L, 11L, 1L,
                List.of(new UsageCalendarModelCost("claude-sonnet-4", 41.5)),
                List.of(new UsageCalendarHour(14, 4.1, 28_100L, 1L, 1_260L)))));
    }

    @Test
    void dailyDispatchesTheRangeZoneAndRepositoryToTheService() throws Exception {
        when(usageCalendarService.daily(Instant.parse(FROM), Instant.parse(TO), TIME_ZONE, REPOSITORY_URL, false))
                .thenReturn(sampleDaily());

        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", FROM)
                .param("to", TO)
                .param("timeZone", TIME_ZONE)
                .param("repositoryUrl", REPOSITORY_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].date").value("2026-09-01"))
                .andExpect(jsonPath("$.days[0].costUsd").value(73.2))
                .andExpect(jsonPath("$.days[0].tokens").value(369_400))
                .andExpect(jsonPath("$.days[0].skillCalls").value(5))
                .andExpect(jsonPath("$.days[0].decisionsRejected").value(1))
                .andExpect(jsonPath("$.days[0].costByModel[0].model").value("claude-sonnet-4"))
                .andExpect(jsonPath("$.days[0].costByModel[0].costUsd").value(41.5));

        verify(usageCalendarService).daily(Instant.parse(FROM), Instant.parse(TO), TIME_ZONE, REPOSITORY_URL, false);
    }

    @Test
    void dailyDefaultsTheTimeZoneToUtcWhenOmitted() throws Exception {
        when(usageCalendarService.daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false))
                .thenReturn(sampleDaily());

        mockMvc.perform(get("/api/usage/calendar/daily").param("from", FROM).param("to", TO))
                .andExpect(status().isOk());

        verify(usageCalendarService).daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false);
    }

    @Test
    void dailyMapsTheUnattributedSentinelToAllRepositories() throws Exception {
        when(usageCalendarService.daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false))
                .thenReturn(sampleDaily());

        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", FROM)
                .param("to", TO)
                .param("repositoryUrl", UNATTRIBUTED_SENTINEL))
                .andExpect(status().isOk());

        verify(usageCalendarService).daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false);
    }

    @Test
    void dailyAsksTheServiceForHourlyBucketsOnlyWhenGranularityIsHourly() throws Exception {
        when(usageCalendarService.daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, true))
                .thenReturn(sampleDaily());

        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", FROM)
                .param("to", TO)
                .param("granularity", "HOURLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].hourly[0].hour").value(14))
                .andExpect(jsonPath("$.days[0].hourly[0].costUsd").value(4.1))
                .andExpect(jsonPath("$.days[0].hourly[0].tokens").value(28_100))
                .andExpect(jsonPath("$.days[0].hourly[0].skillCalls").value(1))
                .andExpect(jsonPath("$.days[0].hourly[0].activeSeconds").value(1_260));

        verify(usageCalendarService).daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, true);
    }

    @Test
    void dailyTreatsAnExplicitDailyGranularityAsNoHourlyBuckets() throws Exception {
        when(usageCalendarService.daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false))
                .thenReturn(sampleDaily());

        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", FROM)
                .param("to", TO)
                .param("granularity", "daily"))
                .andExpect(status().isOk());

        verify(usageCalendarService).daily(Instant.parse(FROM), Instant.parse(TO), "UTC", null, false);
    }

    @Test
    void dailyRejectsAnUnknownGranularity() throws Exception {
        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", FROM)
                .param("to", TO)
                .param("granularity", "minutely"))
                .andExpect(status().isBadRequest());

        verify(usageCalendarService, never()).daily(any(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    void dailyRejectsARangeWiderThanFortyTwoDays() throws Exception {
        mockMvc.perform(get("/api/usage/calendar/daily")
                .param("from", "2026-08-01T00:00:00Z")
                .param("to", "2026-09-30T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(usageCalendarService, never()).daily(any(), any(), anyString(), any(), anyBoolean());
    }

    @Test
    void dailyRejectsARequestMissingEitherBound() throws Exception {
        mockMvc.perform(get("/api/usage/calendar/daily").param("from", FROM))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/usage/calendar/daily").param("to", TO))
                .andExpect(status().isBadRequest());

        verify(usageCalendarService, never()).daily(any(), any(), anyString(), any(), anyBoolean());
    }
}
