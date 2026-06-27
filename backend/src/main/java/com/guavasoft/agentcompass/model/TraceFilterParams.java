package com.guavasoft.agentcompass.model;

import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Parameter object for the shared trace-filter query params consumed by the histogram, facets,
 * and traces list endpoints. Bound via {@code @ModelAttribute} from repeated query-string entries.
 *
 * <p>Uses Lombok {@code @Getter}/{@code @Setter} rather than a record because Spring's
 * WebDataBinder setter path is the only mechanism guaranteed to collect repeated query params
 * ({@code ?status=ok&status=error}) into a {@code List<String>}.
 */
@Getter
@Setter
public class TraceFilterParams {

    @Parameter(description = "Window start (ISO-8601, required)", example = "2026-06-11T00:00:00Z")
    private Instant startTimestamp;

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
}
