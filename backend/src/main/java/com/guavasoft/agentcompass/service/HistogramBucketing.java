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

import java.time.Duration;
import java.time.Instant;

/**
 * Shared bucket-width picker for the explorer histograms. Extracted from
 * identical copies in {@link LogService} and {@link TraceExplorerService}.
 * Each page keeps its own "nice" step ladder — mirroring its frontend NICE
 * array, which is why the ladders deliberately differ — and passes it in, so
 * only the ladder walk lives here.
 */
public final class HistogramBucketing {

  private HistogramBucketing() {
  }

  /**
   * Picks the smallest ladder step wide enough to cover the window in at most
   * {@code targetBuckets} buckets, falling back to the largest step for
   * windows wider than the ladder covers. {@code ladderSeconds} must be sorted
   * ascending and non-empty.
   */
  public static long pickBucketSeconds(
      Instant windowStart, Instant windowEnd, int targetBuckets, long[] ladderSeconds) {
    long windowSeconds = Math.max(1L, Duration.between(windowStart, windowEnd).getSeconds());
    long rawSeconds = windowSeconds / Math.max(1, targetBuckets);
    for (long stepSeconds : ladderSeconds) {
      if (stepSeconds >= rawSeconds) {
        return stepSeconds;
      }
    }
    return ladderSeconds[ladderSeconds.length - 1];
  }
}
