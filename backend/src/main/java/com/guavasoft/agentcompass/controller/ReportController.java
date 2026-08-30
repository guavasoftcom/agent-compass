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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.service.ReportService;

import java.time.Instant;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/report")
@Tag(name = "Report", description = "Markdown tuning report for paste-into-agent self-tuning")
public class ReportController {

    private static final MediaType MARKDOWN = MediaType.valueOf("text/markdown;charset=UTF-8");

    private final ReportService reportService;

    @GetMapping(produces = "text/markdown")
    @Operation(
            summary = "Render the tuning report as markdown for the given window",
            description = "The body is markdown intended to be pasted directly into a coding-agent chat "
                    + "so the agent can revise its own AGENTS.md / skills / prompts.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Markdown report body",
            content = @Content(
                    mediaType = "text/markdown",
                    schema = @Schema(type = "string", example = "# Agent Compass Report\n\n..."))))
    public ResponseEntity<String> report(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Parameter(description = "Optional explicit start of the custom range (ISO-8601). Overrides "
                    + "`minutes` when paired with endTimestamp.") @RequestParam(required = false) Instant startTimestamp,
            @Parameter(description = "Optional explicit end of the custom range (ISO-8601). Overrides "
                    + "`minutes` when paired with startTimestamp.") @RequestParam(required = false) Instant endTimestamp) {
        String body = startTimestamp != null && endTimestamp != null
                ? reportService.renderMarkdownInRange(startTimestamp, endTimestamp)
                : reportService.renderMarkdown(minutes);
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .body(body);
    }
}
