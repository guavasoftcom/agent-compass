package com.guavasoft.agentcompass.validation;

import java.time.Instant;

/**
 * Contract for any parameter type carrying an optional custom {@code startTimestamp}/
 * {@code endTimestamp} pair that {@link DateRangeValidator} can bound. Implemented by
 * {@link com.guavasoft.agentcompass.model.TimeWindowParams} (a record, whose component
 * accessors satisfy this interface automatically) and by
 * {@link com.guavasoft.agentcompass.model.TraceFilterParams} (a Lombok {@code @Getter}/
 * {@code @Setter} class, which implements these methods explicitly since its Lombok-generated
 * accessors are named {@code getStartTimestamp()}/{@code getEndTimestamp()} instead).
 */
public interface DateRangeBounds {

    Instant startTimestamp();

    Instant endTimestamp();
}
