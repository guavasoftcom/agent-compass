package com.guavasoft.agentcompass.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;

import com.guavasoft.agentcompass.model.TimeWindowParams;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, TimeWindowParams> {

    private static final long SECONDS_PER_DAY = 86_400L;

    private int maxDays;

    @Override
    public void initialize(ValidDateRange constraint) {
        maxDays = constraint.maxDays();
    }

    @Override
    public boolean isValid(TimeWindowParams params, ConstraintValidatorContext context) {
        if (params.startTimestamp() == null || params.endTimestamp() == null) {
            return true;
        }
        long rangeSeconds = Duration.between(params.startTimestamp(), params.endTimestamp()).getSeconds();
        return rangeSeconds <= (long) maxDays * SECONDS_PER_DAY;
    }
}
