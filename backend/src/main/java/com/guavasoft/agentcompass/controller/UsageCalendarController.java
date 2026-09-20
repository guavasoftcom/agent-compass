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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.UsageCalendarDaily;
import com.guavasoft.agentcompass.model.UsageCalendarParams;
import com.guavasoft.agentcompass.service.UsageCalendarService;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/usage/calendar")
@Tag(name = "Usage Calendar",
        description = "Day-by-day usage — cost, tokens, skill and subagent calls, sessions, active time — "
                + "bucketed by the caller's local calendar day. Powers the Usage Calendar page.")
public class UsageCalendarController {

    private final UsageCalendarService usageCalendarService;

    @GetMapping("/daily")
    @Operation(
            summary = "Daily usage rollup for the Usage Calendar page",
            description = "Returns exactly one row per local calendar day in the half-open range [from, to), "
                    + "oldest first, zero-filled where nothing happened. Days are the local days of the "
                    + "supplied IANA timeZone (default UTC); from and to must be the full UTC instants of "
                    + "the first and one-past-last local midnight, not bare dates. The range may span at "
                    + "most 42 days. Cost and tokens are counter-derived (the Tokens and Sessions "
                    + "pipeline), so cost reads slightly off the Cost page for the same span. Pass "
                    + "granularity=hourly to also fill each day's 24-bucket hourly array (the week view's "
                    + "sparklines); without it hourly is null.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per local calendar day in the requested range",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = UsageCalendarDaily.class))))
    public UsageCalendarDaily daily(@Valid @ModelAttribute UsageCalendarParams usageCalendarParams) {
        return usageCalendarService.daily(
                usageCalendarParams.from(),
                usageCalendarParams.to(),
                usageCalendarParams.timeZone(),
                usageCalendarParams.repositoryUrl(),
                usageCalendarParams.includesHourly());
    }
}
