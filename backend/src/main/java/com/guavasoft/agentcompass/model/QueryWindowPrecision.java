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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Normalizes explorer query windows to the precision Postgres actually stores.
 *
 * <p>{@code timestamptz} keeps microseconds, so any finer part of a window bound
 * is silently dropped by the driver. That truncation used to break the Logs and
 * Traces histograms: the bucket origin handed to {@code date_bin} arrived
 * truncated, so Postgres returned buckets at {@code trunc(windowStart) + k *
 * width}, while the Java-side zero-fill walked {@code windowStart + k * width}
 * from the untruncated value. No key ever matched and every bucket came back
 * zero. It only reproduced where the clock is finer than a microsecond —
 * {@code Instant.now()} is microsecond-precision on macOS but nanosecond-precision
 * on Linux, so local runs passed while CI and the deployed backend did not.
 *
 * <p>Truncating on the way in fixes it at the source and keeps every endpoint on
 * one window: the histogram, facet, and row queries share the same bounds, so
 * the {@code totalCount == histogram sum == facet total} invariant still holds.
 */
final class QueryWindowPrecision {

    private QueryWindowPrecision() {
    }

    /** Truncates to microseconds, the resolution of a Postgres {@code timestamptz}. */
    static Instant toDatabasePrecision(Instant windowBound) {
        return windowBound == null ? null : windowBound.truncatedTo(ChronoUnit.MICROS);
    }
}
