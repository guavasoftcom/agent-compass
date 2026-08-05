package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

import com.guavasoft.agentcompass.validation.DateRangeBounds;
import com.guavasoft.agentcompass.validation.ValidDateRange;

/**
 * {@link TimeWindowParams} with both bounds required.
 *
 * <p>Most dashboard endpoints accept a one-sided or absent window and fall back to the
 * {@code ?minutes=} form, which is why {@code TimeWindowParams} leaves both components nullable
 * and {@link com.guavasoft.agentcompass.validation.DateRangeValidator} passes a partial range.
 * The histogram endpoints can't: they bucket with {@code date_bin} anchored on the window start
 * and zero-fill across {@code [start, end]}, so a null bound NPEs in the bucket-width picker
 * before any SQL runs. Binding them to this type turns that 500 into the 400 it always was.
 */
@ValidDateRange
public record RequiredTimeWindowParams(
        @NotNull
        @Parameter(description = "Inclusive lower bound on the histogram window (ISO-8601, required).",
                example = "2026-04-01T00:00:00Z") Instant startTimestamp,
        @NotNull
        @Parameter(description = "Inclusive upper bound on the histogram window (ISO-8601, required). "
                + "Must be within 30 days of startTimestamp.",
                example = "2026-04-30T23:59:59Z") Instant endTimestamp)
        implements DateRangeBounds {
}
