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

import com.guavasoft.agentcompass.service.ReportService;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dispatch tests for {@code GET /api/report}, including {@code repositoryUrl} binding -- unlike
 * every other controller's dual-window params, {@link ReportController} takes {@code minutes} /
 * {@code startTimestamp} / {@code endTimestamp} / {@code repositoryUrl} as raw {@code @RequestParam}s
 * rather than a {@code @ModelAttribute TimeWindowParams}, so the binding has to be proven here
 * rather than inherited from a shared param object's own test.
 */
@WebMvcTest(ReportController.class)
class ReportControllerTest {

    private static final int DEFAULT_MINUTES = 1440;
    private static final int EXPLICIT_MINUTES = 60;
    private static final Instant CUSTOM_RANGE_START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant CUSTOM_RANGE_END = Instant.parse("2026-04-08T00:00:00Z");
    private static final String REPOSITORY_URL = "https://github.com/guavasoftcom/agent-compass";
    private static final String SAMPLE_MARKDOWN = "# Agent Compass Report\n\nNo data.\n";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReportService reportService;

    @Test
    void reportDefaultsToTheStandardOneDayWindowWhenNoParamsSupplied() throws Exception {
        when(reportService.renderMarkdown(DEFAULT_MINUTES, null)).thenReturn(SAMPLE_MARKDOWN);

        mockMvc.perform(get("/api/report"))
                .andExpect(status().isOk())
                .andExpect(content().string(SAMPLE_MARKDOWN));

        verify(reportService).renderMarkdown(DEFAULT_MINUTES, null);
    }

    @Test
    void reportDispatchesSuppliedMinutesToService() throws Exception {
        when(reportService.renderMarkdown(EXPLICIT_MINUTES, null)).thenReturn(SAMPLE_MARKDOWN);

        mockMvc.perform(get("/api/report").param("minutes", String.valueOf(EXPLICIT_MINUTES)))
                .andExpect(status().isOk());

        verify(reportService).renderMarkdown(EXPLICIT_MINUTES, null);
    }

    @Test
    void reportUsesCustomRangeWhenBothTimestampsSupplied() throws Exception {
        when(reportService.renderMarkdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, null))
                .thenReturn(SAMPLE_MARKDOWN);

        mockMvc.perform(get("/api/report")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString()))
                .andExpect(status().isOk());

        verify(reportService).renderMarkdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, null);
    }

    @Test
    void reportDispatchesSuppliedRepositoryUrlToServiceOnTheMinutesForm() throws Exception {
        when(reportService.renderMarkdown(DEFAULT_MINUTES, REPOSITORY_URL)).thenReturn(SAMPLE_MARKDOWN);

        mockMvc.perform(get("/api/report").param("repositoryUrl", REPOSITORY_URL))
                .andExpect(status().isOk());

        verify(reportService).renderMarkdown(DEFAULT_MINUTES, REPOSITORY_URL);
    }

    @Test
    void reportDispatchesSuppliedRepositoryUrlToServiceOnTheCustomRangeForm() throws Exception {
        when(reportService.renderMarkdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, REPOSITORY_URL))
                .thenReturn(SAMPLE_MARKDOWN);

        mockMvc.perform(get("/api/report")
                .param("startTimestamp", CUSTOM_RANGE_START.toString())
                .param("endTimestamp", CUSTOM_RANGE_END.toString())
                .param("repositoryUrl", REPOSITORY_URL))
                .andExpect(status().isOk());

        verify(reportService).renderMarkdownInRange(CUSTOM_RANGE_START, CUSTOM_RANGE_END, REPOSITORY_URL);
    }
}
