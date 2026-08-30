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
 * Parameter object for the pagination query params of the traces explorer endpoint,
 * bound via {@code @ModelAttribute}. Follows the same pattern as {@link LogPaginationParams}.
 *
 * <p>The params encode two mutually exclusive paging modes. The presence of {@code page}
 * switches to offset mode ({@code size} and {@code sort} apply); otherwise the endpoint
 * performs cursor keyset paging ({@code before}, {@code after}, {@code limit}, and
 * {@code sort} apply).
 */
@Getter
@Setter
public class TracePaginationParams {

    private static final int DEFAULT_CURSOR_PAGE_SIZE = 60;
    private static final int DEFAULT_OFFSET_PAGE_SIZE = 25;

    @Parameter(
            description = "Cursor: rows after this ts,traceId in sort order (scroll-back for sort=new)",
            example = "2026-06-12T18:42:11.004Z,7ed9599a86cafe01beefcafe01234567")
    private String before;

    @Parameter(
            description = "Cursor: rows before this ts,traceId in sort order (live tail for sort=new)",
            example = "2026-06-12T18:42:11.004Z,7ed9599a86cafe01beefcafe01234567")
    private String after;

    @Parameter(description = "Cursor mode page size", example = "60")
    private int limit = DEFAULT_CURSOR_PAGE_SIZE;

    @Parameter(
            description = "Offset mode: 0-based page number. Presence switches to offset mode.",
            example = "0")
    private Integer page;

    @Parameter(description = "Offset mode: page size (25, 50, or 100)", example = "25")
    private int size = DEFAULT_OFFSET_PAGE_SIZE;

    @Parameter(
            description = "Sort order: new (start desc), old (start asc), slow (duration desc), "
                    + "fast (duration asc), spans (spanCount desc), err (errorCount desc then start desc), "
                    + "tokens (totalTokens desc), cost (totalCostUsd desc)",
            example = "new")
    private String sort = "new";

    public boolean isOffsetMode() {
        return page != null;
    }

    /**
     * The 0-based offset page, with a null {@code page} resolved to the first page.
     *
     * <p>{@code page} has to stay boxed so {@link #isOffsetMode()} can distinguish "absent" from
     * "0", but the traces endpoint selects its offset handler with
     * {@code @GetMapping(params = "page")}, which matches on param *presence* — so a bare
     * {@code ?page=} routes to offset mode while binding null (Spring converts an empty string to
     * null for boxed types). Unboxing that directly is an NPE, i.e. a 500 on a malformed request.
     */
    public int resolvedPage() {
        return page == null ? 0 : page;
    }
}
