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
package com.guavasoft.agentcompass.service;

/**
 * Shared clamping helpers for every offset-paged and cursor-paged dashboard
 * list endpoint (Logs, Traces, Sessions, Metrics). Extracted from three
 * near-identical copies in {@link LogService}, {@link TraceExplorerService},
 * and {@link MetricService} so the page-size ceiling and offset arithmetic
 * live in exactly one place.
 */
public final class PageBounds {

  /**
   * Ceiling on every clamped cursor {@code limit} and offset {@code size}.
   * Without this, an attacker-chosen {@code limit=1000000000} fetches a
   * billion-row {@code List} (OOM), and {@code limit=Integer.MAX_VALUE}
   * overflows the "+1 probe" ({@code resolvedLimit + 1}) some callers use into
   * a negative SQL {@code LIMIT} (500 error).
   */
  public static final int MAXIMUM_PAGE_SIZE = 500;

  /**
   * Default cursor {@code limit} when the caller omits it or sends a
   * non-positive value, shared by the Logs and Traces cursor endpoints.
   */
  public static final int DEFAULT_CURSOR_LIMIT = 60;

  /**
   * Default offset-page {@code size} when the caller omits it or sends a
   * non-positive value. Matches the frontend DataGrids' default rows-per-page
   * (Logs, Traces, Sessions, Metrics).
   */
  public static final int DEFAULT_OFFSET_PAGE_SIZE = 25;

  /**
   * totalCount placeholder for cursor continuation (before=) and live-tail
   * (after=) pages. The client reads the total only from the initial page and
   * maintains its own running total from there, so continuation pages skip the
   * expensive filtered COUNT entirely.
   */
  public static final long CONTINUATION_PAGE_TOTAL_COUNT = 0L;

  private PageBounds() {
  }

  /**
   * Floors a caller-supplied page/cursor size to {@code defaultSize} when it's
   * non-positive, and ceiling-clamps it to {@link #MAXIMUM_PAGE_SIZE}
   * otherwise. Callers pass their own endpoint-specific default (e.g. 60 for a
   * cursor limit, 25 for an offset page size).
   */
  public static int clampPageSize(int requestedSize, int defaultSize) {
    if (requestedSize <= 0) {
      return defaultSize;
    }
    return Math.min(requestedSize, MAXIMUM_PAGE_SIZE);
  }

  /**
   * Computes {@code page * pageSize} as a {@code long} before narrowing back
   * to {@code int}, clamping to {@link Integer#MAX_VALUE} rather than letting
   * the multiplication wrap around into a negative SQL {@code OFFSET}.
   * {@code pageSize} is expected to already be capped at
   * {@link #MAXIMUM_PAGE_SIZE} by {@link #clampPageSize}, so only an extreme
   * {@code page} value can trigger the clamp here. Negative {@code page}
   * values are floored to 0 first.
   */
  public static int computeOffset(int page, int pageSize) {
    long rawOffset = (long) Math.max(0, page) * pageSize;
    return rawOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rawOffset;
  }
}
