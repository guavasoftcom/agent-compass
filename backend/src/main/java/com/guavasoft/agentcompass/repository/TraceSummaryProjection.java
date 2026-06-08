package com.guavasoft.agentcompass.repository;

import java.time.Instant;

/**
 * Spring Data JPA interface projection: one row per trace_id from the
 * aggregation query.
 * Root span name is fetched separately and joined in the service layer.
 */
public interface TraceSummaryProjection {

  String getTraceId();

  long getSpanCount();

  Instant getMinStart();

  Instant getMaxEnd();

  long getErrorCount();
}
