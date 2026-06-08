package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;

import com.guavasoft.agentcompass.validation.ValidDateRange;

@ValidDateRange
public record TimeWindowParams(
        @Parameter(description = "Inclusive lower bound on the custom range (ISO-8601). "
                + "Overrides minutes when paired with endTimestamp.", example = "2026-04-01T00:00:00Z") Instant startTimestamp,
        @Parameter(description = "Inclusive upper bound on the custom range (ISO-8601). "
                + "Overrides minutes when paired with startTimestamp. "
                + "Must be within 30 days of startTimestamp.", example = "2026-04-30T23:59:59Z") Instant endTimestamp) {
}
