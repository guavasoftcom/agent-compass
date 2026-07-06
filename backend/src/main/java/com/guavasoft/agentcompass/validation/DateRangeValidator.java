package com.guavasoft.agentcompass.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, DateRangeBounds> {

    private static final long SECONDS_PER_DAY = 86_400L;

    private int maxDays;

    @Override
    public void initialize(ValidDateRange constraint) {
        maxDays = constraint.maxDays();
    }

    @Override
    public boolean isValid(DateRangeBounds params, ConstraintValidatorContext context) {
        if (params.startTimestamp() == null || params.endTimestamp() == null) {
            return true;
        }
        long rangeSeconds = Duration.between(params.startTimestamp(), params.endTimestamp()).getSeconds();
        return rangeSeconds <= (long) maxDays * SECONDS_PER_DAY;
    }
}
