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
