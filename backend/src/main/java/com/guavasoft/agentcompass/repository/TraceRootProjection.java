package com.guavasoft.agentcompass.repository;

/**
 * Spring Data JPA interface projection for the (traceId, rootSpanName) lookup
 * used to enrich
 * trace summaries with their root span name.
 */
public interface TraceRootProjection {

  String getTraceId();

  String getSpanId();

  String getName();

  String getSessionId();
}
