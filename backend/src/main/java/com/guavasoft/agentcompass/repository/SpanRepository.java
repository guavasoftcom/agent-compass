package com.guavasoft.agentcompass.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.SpanEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SpanRepository extends JpaRepository<SpanEntity, Long> {

    List<SpanEntity> findAllByOrderByStartTimestampDesc(Pageable pageable);

    List<SpanEntity> findByTraceIdOrderByStartTimestampAsc(String traceId);

    @Query(value = "SELECT trace_id AS traceId, "
            + "       COUNT(*) AS spanCount, "
            + "       MIN(start_timestamp) AS minStart, "
            + "       MAX(end_timestamp) AS maxEnd, "
            + "       SUM(CASE WHEN status_code = 'error' THEN 1 ELSE 0 END) AS errorCount "
            + "FROM spans "
            + "GROUP BY trace_id "
            + "ORDER BY (MAX(end_timestamp) - MIN(start_timestamp)) DESC", nativeQuery = true)
    List<TraceSummaryProjection> findTraceSummaries(Pageable pageable);

    // Overlap semantics: a trace is "in window" if any of its spans overlap the
    // window,
    // so long-running traces straddling the window edges still appear. The
    // aggregated row
    // reflects the trace's full bounds, not just the in-window slice.
    @Query(value = "SELECT trace_id AS traceId, "
            + "       COUNT(*) AS spanCount, "
            + "       MIN(start_timestamp) AS minStart, "
            + "       MAX(end_timestamp) AS maxEnd, "
            + "       SUM(CASE WHEN status_code = 'error' THEN 1 ELSE 0 END) AS errorCount "
            + "FROM spans "
            + "WHERE trace_id IN ("
            + "    SELECT peer.trace_id FROM spans peer "
            + "    WHERE peer.end_timestamp >= :since"
            + ") "
            + "GROUP BY trace_id "
            + "ORDER BY (MAX(end_timestamp) - MIN(start_timestamp)) DESC", nativeQuery = true)
    List<TraceSummaryProjection> findTraceSummariesSince(@Param("since") Instant since, Pageable pageable);

    @Query(value = "SELECT trace_id AS traceId, "
            + "       COUNT(*) AS spanCount, "
            + "       MIN(start_timestamp) AS minStart, "
            + "       MAX(end_timestamp) AS maxEnd, "
            + "       SUM(CASE WHEN status_code = 'error' THEN 1 ELSE 0 END) AS errorCount "
            + "FROM spans "
            + "WHERE trace_id IN ("
            + "    SELECT peer.trace_id FROM spans peer "
            + "    WHERE peer.start_timestamp <= :end AND peer.end_timestamp >= :start"
            + ") "
            + "GROUP BY trace_id "
            + "ORDER BY (MAX(end_timestamp) - MIN(start_timestamp)) DESC", nativeQuery = true)
    List<TraceSummaryProjection> findTraceSummariesInRange(
            @Param("start") Instant start, @Param("end") Instant end, Pageable pageable);

    @Query(value = "SELECT trace_id AS traceId, span_id AS spanId, name, attributes ->> 'session.id' AS sessionId "
            + "FROM spans "
            + "WHERE trace_id IN :traceIds AND parent_span_id IS NULL", nativeQuery = true)
    List<TraceRootProjection> findRootSpansByTraceIds(@Param("traceIds") Collection<String> traceIds);

    @Query("SELECT COUNT(DISTINCT s.traceId) FROM SpanEntity s")
    long countDistinctTraces();

    @Query("SELECT COUNT(DISTINCT s.traceId) FROM SpanEntity s WHERE s.endTimestamp >= :since")
    long countDistinctTracesSince(@Param("since") Instant since);

    @Query("SELECT COUNT(DISTINCT s.traceId) FROM SpanEntity s "
            + "WHERE s.startTimestamp <= :end AND s.endTimestamp >= :start")
    long countDistinctTracesInRange(@Param("start") Instant start, @Param("end") Instant end);

    // For each (session_id, reference_timestamp) pair in the exemplar list, find
    // the span whose start_timestamp is closest to the reference timestamp and
    // whose attributes carry the same session_id. Used by the token-distribution
    // endpoint to correlate metric_points rows with trace_ids.
    // Returns (session_id, trace_id, status_code, duration_nanos) for the nearest
    // span per session. We fetch broadly by session_id and let the service pick the
    // closest timestamp, keeping the query simple and index-friendly.
    // The session id is computed once in the subquery so the outer DISTINCT ON and
    // ORDER BY reference the plain session_id alias. Postgres requires DISTINCT ON
    // expressions to match the leading ORDER BY structurally — repeating the
    // `attributes ->> :param` form in both clauses binds as distinct positional
    // parameters and fails that check.
    @Query(value = """
            SELECT DISTINCT ON (session_id)
              session_id,
              trace_id,
              status_code,
              duration_nanos,
              start_timestamp
            FROM (
              SELECT
                attributes ->> :sessionIdAttribute AS session_id,
                trace_id,
                status_code,
                duration_nanos,
                start_timestamp
              FROM spans
              WHERE attributes ->> :sessionIdAttribute = ANY(CAST(:sessionIds AS text[]))
                AND start_timestamp >= :windowStart
                AND start_timestamp <= :windowEnd
            ) AS session_spans
            ORDER BY session_id, start_timestamp DESC
            """, nativeQuery = true)
    List<Object[]> findLatestSpanPerSession(
            @Param("sessionIdAttribute") String sessionIdAttribute,
            @Param("sessionIds") String[] sessionIds,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd);

    // Per-tool latency percentiles over spans that wrap a single tool invocation.
    // Span name within the Claude Code tracing scope is generic
    // ("claude_code.tool"), so the
    // tool identifier (Read, Bash, ...) lives in the attributes jsonb under
    // :toolAttribute.
    // Uses native SQL because JPQL has no percentile_cont equivalent or jsonb
    // operator support;
    // duration_nanos is already persisted, so no end-start arithmetic is needed.
    @Query(value = """
            SELECT
              attributes->>:toolAttribute                                         AS tool,
              COUNT(*)                                                            AS calls,
              percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_nanos)        AS p50_nanos,
              percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_nanos)        AS p95_nanos
            FROM spans
            WHERE scope_name = :scopeName
              AND name = :spanName
              AND start_timestamp >= :since
              AND duration_nanos IS NOT NULL
              AND jsonb_exists(attributes, :toolAttribute)
            GROUP BY 1
            ORDER BY p95_nanos DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> aggregateToolLatency(
            @Param("scopeName") String scopeName,
            @Param("spanName") String spanName,
            @Param("toolAttribute") String toolAttribute,
            @Param("since") Instant since);

    @Query(value = """
            SELECT
              attributes->>:toolAttribute                                         AS tool,
              COUNT(*)                                                            AS calls,
              percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_nanos)        AS p50_nanos,
              percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_nanos)        AS p95_nanos
            FROM spans
            WHERE scope_name = :scopeName
              AND name = :spanName
              AND start_timestamp >= :start
              AND start_timestamp <= :end
              AND duration_nanos IS NOT NULL
              AND jsonb_exists(attributes, :toolAttribute)
            GROUP BY 1
            ORDER BY p95_nanos DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> aggregateToolLatencyInRange(
            @Param("scopeName") String scopeName,
            @Param("spanName") String spanName,
            @Param("toolAttribute") String toolAttribute,
            @Param("start") Instant start,
            @Param("end") Instant end);
}
