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
package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.Parameter;
import lombok.Getter;
import lombok.Setter;

/**
 * Parameter object for the pagination query params of the logs endpoint, bound via
 * {@code @ModelAttribute}. Follows the Lombok {@code @Getter}/{@code @Setter} shape of
 * {@link LogFilterParams} rather than a record so field initializers can supply the
 * defaults that {@code @RequestParam(defaultValue = ...)} provided before.
 *
 * <p>The params encode two mutually exclusive paging modes. The presence of {@code page}
 * switches to offset mode ({@code size} applies); otherwise the endpoint performs cursor
 * keyset paging ({@code before}, {@code after}, and {@code limit} apply). A request carries
 * either cursor or offset params, not both.
 */
@Getter
@Setter
public class LogPaginationParams {

    private static final int DEFAULT_CURSOR_PAGE_SIZE = 60;
    private static final int DEFAULT_OFFSET_PAGE_SIZE = 25;

    @Parameter(
            description = "Cursor: rows strictly older than this ts,id (scroll-back)",
            example = "2026-06-07T18:42:11.004Z,84213")
    private String before;

    @Parameter(
            description = "Cursor: rows strictly newer than this ts,id (live tail)",
            example = "2026-06-07T18:42:11.004Z,84213")
    private String after;

    @Parameter(description = "Cursor mode page size", example = "60")
    private int limit = DEFAULT_CURSOR_PAGE_SIZE;

    @Parameter(
            description = "Offset mode: 0-based page number. Presence switches to offset mode.",
            example = "0")
    private Integer page;

    @Parameter(description = "Offset mode: page size (25, 50, or 100)", example = "25")
    private int size = DEFAULT_OFFSET_PAGE_SIZE;

    public boolean isOffsetMode() {
        return page != null;
    }
}
