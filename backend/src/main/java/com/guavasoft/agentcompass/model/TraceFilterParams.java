package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

import com.guavasoft.agentcompass.validation.DateRangeBounds;
import com.guavasoft.agentcompass.validation.ValidDateRange;

/**
 * Parameter object for the shared trace-filter query params consumed by the histogram, facets,
 * and traces list endpoints. Bound via {@code @ModelAttribute} from repeated query-string entries.
 *
 * <p>Uses Lombok {@code @Getter}/{@code @Setter} rather than a record because Spring's
 * WebDataBinder setter path is the only mechanism guaranteed to collect repeated query params
 * ({@code ?status=ok&status=error}) into a {@code List<String>}.
 *
 * <p>Implements {@link DateRangeBounds} (rather than exposing only the Lombok-generated
 * {@code getStartTimestamp()}/{@code getEndTimestamp()}) so the same {@link ValidDateRange}
 * constraint that caps {@link TimeWindowParams} at 30 days can validate this type too.
 *
 * <p>Both bounds are {@code @NotNull}: every trace query pins the window with a bare
 * {@code start_timestamp >= :windowStart AND <= :windowEnd} (no {@code IS NULL} branch like the
 * logs queries have), so a missing bound is never a valid "unbounded" request here — it either
 * NPEs in the histogram's bucket-width picker or silently matches zero rows in the list. Unlike
 * {@link TimeWindowParams}, this object has no {@code ?minutes=} fallback form to degrade to.
 */
@Getter
@Setter
@ValidDateRange
public class TraceFilterParams implements DateRangeBounds {

    @NotNull
    @Parameter(description = "Window start (ISO-8601, required)", example = "2026-06-11T00:00:00Z")
    private Instant startTimestamp;

    @NotNull
    @Parameter(description = "Window end (ISO-8601, required)", example = "2026-06-12T00:00:00Z")
    private Instant endTimestamp;

    @Parameter(
            description = "Trace status filter; repeatable — 'ok' or 'error'",
            example = "error")
    private List<String> status;

    @Parameter(
            description = "Root span name (operation) values to include; rows must match at least one",
            example = "tool.execute")
    private List<String> operation;

    @Parameter(
            description = "Derived service key values to include; rows must match at least one",
            example = "claude_code.tools")
    private List<String> service;

    @Parameter(
            description = "Duration bucket ids to include (d0–d3); rows must match at least one",
            example = "d2")
    private List<String> duration;

    @Parameter(
            description = "Session id values to include; rows must match at least one",
            example = "sess_1a2b3c4d")
    private List<String> session;

    @Parameter(
            description = "Full-text search over traceId, sessionId, and rootSpanName",
            example = "tool.execute")
    private String q;

    // DateRangeBounds — explicit accessors matching the interface's record-style method
    // names, distinct from the Lombok-generated getStartTimestamp()/getEndTimestamp().

    @Override
    public Instant startTimestamp() {
        return startTimestamp;
    }

    @Override
    public Instant endTimestamp() {
        return endTimestamp;
    }
}
