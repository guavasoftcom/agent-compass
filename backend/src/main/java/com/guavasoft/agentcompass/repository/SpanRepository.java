/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.SpanEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpanRepository extends JpaRepository<SpanEntity, Long> {

    List<SpanEntity> findByTraceIdOrderByStartTimestampAsc(String traceId);

    // One grouped query per trace for TraceService#spansForTrace, replacing the
    // per-row correlated subquery an earlier revision ran as a SpanEntity
    // @Formula (measured ~25ms of a 33ms trace load across 1,282 spans, paid
    // even by callers that never read cost). Returns (span_id, cost_usd);
    // spans absent from the result had no api_request log carrying their span
    // id, and the service defaults those to 0 the same way the @Formula did.
    @Query(value = """
            SELECT span_id, cost_usd
            FROM span_costs
            WHERE trace_id = :traceId
            """, nativeQuery = true)
    List<Object[]> findSpanCostsForTrace(@Param("traceId") String traceId);

    // Per-trace reasoning effort, same one-grouped-query-per-trace shape as
    // findSpanCostsForTrace above. Returns (span_id, effort); spans absent from
    // the result either are not llm_request calls or had no effort recorded on
    // their api_request log, and the service leaves those null. See the
    // span_efforts view (V15) for why the correlation is by request_id and not
    // span_id.
    @Query(value = """
            SELECT span_id, effort
            FROM span_efforts
            WHERE trace_id = :traceId
            """, nativeQuery = true)
    List<Object[]> findSpanEffortsForTrace(@Param("traceId") String traceId);

    // Cheap freshness probe for TraceAnalysisService#getStored: just the latest span activity
    // for one trace, without traceSummaryById's root-span resolution and cost LEFT JOIN LATERAL.
    // Null when the trace has no spans (MAX over an empty set).
    @Query(value = "SELECT MAX(end_timestamp) FROM spans WHERE trace_id = :traceId", nativeQuery = true)
    Instant findLatestEndTimestampForTrace(@Param("traceId") String traceId);

    // The llm_request span one api_request log belongs to, for MetricService's distribution
    // exemplars. Correlated by request_id, never span_id: the log is stamped with whatever span
    // was ACTIVE when it fired (the interaction root or a tool span), not the llm_request child
    // (see the span_efforts view, V15). Bounded by the trace_id index; empty when the span has
    // not been ingested yet.
    @Query(value = """
            SELECT span_id
            FROM spans
            WHERE trace_id = :traceId
              AND name = :llmRequestSpanName
              AND attributes ->> :requestIdAttribute = :requestId
            ORDER BY start_timestamp
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLlmRequestSpanId(
            @Param("traceId") String traceId,
            @Param("llmRequestSpanName") String llmRequestSpanName,
            @Param("requestIdAttribute") String requestIdAttribute,
            @Param("requestId") String requestId);

    // Liveness probe for the prompt timeline's newest turn (LogService#promptsForSession).
    // Returns one row: (root_closed, last_activity). root_closed says whether the trace's
    // claude_code.interaction root span has been exported -- spans are exported only once
    // they end, so its absence means the turn has not finished (or was abandoned and never
    // will). last_activity is the newest span end or log timestamp on the trace, which is
    // what tells those two apart. Every leg is a trace_id index lookup; measured 2.3 ms against a
    // live ~240-span / ~420-log running turn, which matters because the Sessions drawer re-reads
    // the timeline every few seconds while a turn is running.
    @Query(value = """
            SELECT
              EXISTS (
                SELECT 1 FROM spans
                WHERE trace_id = :traceId
                  AND parent_span_id IS NULL
                  AND name LIKE :rootSpanNamePattern
              ) AS root_closed,
              GREATEST(
                (SELECT MAX(end_timestamp) FROM spans WHERE trace_id = :traceId),
                (SELECT MAX(timestamp) FROM log_records WHERE trace_id = :traceId)
              ) AS last_activity
            """, nativeQuery = true)
    List<Object[]> findTurnProgressForTrace(
            @Param("traceId") String traceId,
            @Param("rootSpanNamePattern") String rootSpanNamePattern);

    // Batch liveness probe for the Traces Explorer's per-row "still running" indicator
    // (TraceSummary#inProgress). Same liveness definition as findTurnProgressForTrace above
    // and LogRecordRepository#findInProgressSessionIds (no exported claude_code.interaction
    // root span yet, and activity within :recentActivitySince), but scoped directly by the
    // given trace ids rather than resolved through a session's newest turn -- the Sessions
    // query needs its own latest_prompt CTE to first find which trace a session's latest turn
    // even points at; here the caller (TraceExplorerService) already knows which traces are on
    // the current page, so that resolution step disappears and this collapses to a plain
    // filter over :traceIds. Mirrors findInProgressSessionIds' two-stage MATERIALIZED CTE
    // staging for the same reason: NOT EXISTS is the cheap half and narrows the candidate set
    // before the two per-trace MAX(...) subqueries -- the expensive half -- run only over
    // traces still missing a root span, not every trace on the page.
    @Query(value = """
            WITH candidate_trace AS MATERIALIZED (
              SELECT DISTINCT trace_id
              FROM spans
              WHERE trace_id IN :traceIds
            ),
            unresolved_trace AS MATERIALIZED (
              SELECT ct.trace_id
              FROM candidate_trace ct
              WHERE NOT EXISTS (
                SELECT 1 FROM spans s
                WHERE s.trace_id = ct.trace_id
                  AND s.parent_span_id IS NULL
                  AND s.name LIKE :rootSpanNamePattern
              )
            )
            SELECT ut.trace_id
            FROM unresolved_trace ut
            WHERE GREATEST(
              (SELECT MAX(end_timestamp) FROM spans s2 WHERE s2.trace_id = ut.trace_id),
              (SELECT MAX(timestamp) FROM log_records l2 WHERE l2.trace_id = ut.trace_id)
            ) > :recentActivitySince
            """, nativeQuery = true)
    List<String> findInProgressTraceIds(
            @Param("traceIds") Collection<String> traceIds,
            @Param("rootSpanNamePattern") String rootSpanNamePattern,
            @Param("recentActivitySince") Instant recentActivitySince);

    // Per-tool latency percentiles over spans that wrap a single tool invocation.
    // Span name within the Claude Code tracing scope is generic
    // ("claude_code.tool"), so the
    // tool identifier (Read, Bash, ...) lives in the attributes jsonb under
    // :toolAttribute.
    // Uses native SQL because JPQL has no percentile_cont equivalent or jsonb
    // operator support;
    // duration_nanos is already persisted, so no end-start arithmetic is needed.
    //
    // MCP calls are the SPAN-side mirror image of the log-side problem the rest of this feature
    // fixes: they arrive as 19+ separate raw "mcp__<server>__<tool>" names (one per server tool)
    // rather than one collapsed constant, crowding the latency table. starts_with()/split_part()
    // collapse them to one 'mcp:<server>' row per method — NOT LIKE :mcpPrefix || '%', because
    // Postgres treats a bare underscore as the LIKE single-character wildcard, so
    // 'LIKE mcp__%' would match "mcp" plus any two characters plus anything, not literally two
    // underscores; it only "works" today by accident since no other tool name starts with "mcp".
    // This latency figure intentionally stays span-derived (span duration includes time blocked on
    // user approval), unlike the log-derived MCP aggregation in LogRecordRepository — see
    // TuningProperties.mcpToolName's javadoc for why the two signals disagree and are read
    // differently.
    @Query(value = """
            SELECT
              CASE WHEN starts_with(attributes->>:toolAttribute, :mcpPrefix)
                   THEN 'mcp:' || split_part(attributes->>:toolAttribute, '__', 2)
                   ELSE attributes->>:toolAttribute END                            AS tool,
              COUNT(*)                                                            AS calls,
              percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_nanos)        AS p50_nanos,
              percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_nanos)        AS p95_nanos
            FROM spans
            WHERE scope_name = :scopeName
              AND name = :spanName
              AND start_timestamp >= :since
              AND duration_nanos IS NOT NULL
              AND jsonb_exists(attributes, :toolAttribute)
              AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
            GROUP BY 1
            ORDER BY p95_nanos DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> aggregateToolLatency(
            @Param("scopeName") String scopeName,
            @Param("spanName") String spanName,
            @Param("toolAttribute") String toolAttribute,
            @Param("mcpPrefix") String mcpPrefix,
            @Param("since") Instant since,
            @Param("repositoryUrl") String repositoryUrl);

    @Query(value = """
            SELECT
              CASE WHEN starts_with(attributes->>:toolAttribute, :mcpPrefix)
                   THEN 'mcp:' || split_part(attributes->>:toolAttribute, '__', 2)
                   ELSE attributes->>:toolAttribute END                            AS tool,
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
              AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
            GROUP BY 1
            ORDER BY p95_nanos DESC NULLS LAST
            """, nativeQuery = true)
    List<Object[]> aggregateToolLatencyInRange(
            @Param("scopeName") String scopeName,
            @Param("spanName") String spanName,
            @Param("toolAttribute") String toolAttribute,
            @Param("mcpPrefix") String mcpPrefix,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("repositoryUrl") String repositoryUrl);

    // =========================================================================
    // Trace Explorer queries (histogram, facets, cursor paging, offset paging).
    //
    // All queries share a CTE that aggregates spans to one row per trace_id and
    // resolves the root span via DISTINCT ON ordered by (parent_span_id IS NULL) DESC, start_timestamp ASC:
    // a real root (parent_span_id IS NULL) wins; when no parentless span exists the earliest span stands in,
    // so rootSpanName is never null for in-flight traces. The window
    // predicate is applied to MIN(start_timestamp) so a trace is "in-window" when
    // its start falls within [windowStart, windowEnd]. This matches the client-side
    // inWindow() helper in tracesApi.ts and ensures totalCount == histogram sum ==
    // facet status totals for identical filters.
    //
    // Service dimension is derived from rootSpanName prefix via CASE expression,
    // mirroring serviceOf() in tracesApi.ts. Status is errorCount > 0 → 'error'.
    // Duration bucket is derived from (maxEnd - minStart) / 1e6 ms thresholds,
    // mirroring durationBucketOf() in tracesApi.ts.
    //
    // All repeatable filter arrays use the cardinality-guard pattern:
    //   cardinality(CAST(:param AS text[])) = 0 OR col = ANY(CAST(:param AS text[]))
    // so an empty array means "no filter" without a separate query branch.
    //
    // total_cost_usd is filled by a LEFT JOIN LATERAL over log_records, not a
    // plain join against the trace_costs view (V14). Postgres can't push a
    // join predicate past that view's GROUP BY, so a plain `LEFT JOIN
    // trace_costs tc ON tc.trace_id = a.trace_id` re-aggregates every
    // api_request log in the table on every call regardless of the window or
    // LIMIT (measured: ~150ms / ~30k buffers on ~96k log_records / ~13k
    // api_request rows, even returning 20 rows) and only gets worse as
    // retention grows. The lateral form re-runs the identical predicates
    // per-trace through the idx_log_records_trace index instead (measured:
    // ~0.06ms / 6 buffers for a single trace via traceSummaryById). The
    // predicates duplicate trace_costs' WHERE clause on purpose — same
    // event.name / cost_usd literals TuningProperties documents as mirrored
    // in Flyway SQL — so changing apiRequestEventName or
    // apiRequestCostAttribute means updating both the view and every lateral
    // block below.
    // =========================================================================

    // Histogram — bucket trace starts by date_bin, count ok/error, compute p95.
    // Returns sparse rows (only buckets with at least one trace); zero-fill is done
    // in the service layer.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.error_count,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
            )
            SELECT
              date_bin(make_interval(secs => :bucketSeconds), min_start, :windowStart) AS bucket,
              COUNT(*) FILTER (WHERE status = 'ok')    AS ok_count,
              COUNT(*) FILTER (WHERE status = 'error') AS error_count,
              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_ms
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            GROUP BY bucket
            ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> traceHistogramBuckets(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("bucketSeconds") long bucketSeconds,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl);

    // Window-wide p50/p95 and total/errorCount for the histogram header.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.error_count,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
            )
            SELECT
              COUNT(*)                                                              AS total,
              COUNT(*) FILTER (WHERE status = 'error')                             AS error_count,
              COALESCE(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration_ms), 0) AS p50_ms,
              COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95_ms
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            """, nativeQuery = true)
    List<Object[]> traceHistogramGlobals(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl);

    // =========================================================================
    // Consolidated facets query (perf item B3).
    //
    // The five facet dimensions (status, operation, service, duration bucket,
    // session) each keep the pre-existing self-exclusion contract: a facet's own
    // dimension filter is left OUT of its WHERE clause so the client can still see
    // counts for options the user hasn't (yet) selected, while every other active
    // filter still applies. That means each facet needs a *different* filter
    // predicate over the same rows, which rules out a literal single-WHERE
    // `GROUP BY GROUPING SETS` (one WHERE clause would have to serve all five
    // facets identically). Instead: build `traces` once, CROSS JOIN it with a
    // five-row `facet_kinds` VALUES list, and pick the correct filter subset per
    // kind via a CASE in the WHERE clause. `traces` is referenced through a single
    // FROM item here (not five separate FROMs), so `trace_agg` / `trace_roots` /
    // `traces` are built exactly once per call instead of five times.
    //
    // facet_kind discriminates the row shape for the service to demultiplex.
    // operation/service/session stay capped and ordered by count descending via
    // ROW_NUMBER() PARTITION BY facet_kind, reproducing the original per-facet
    // `ORDER BY row_count DESC LIMIT :n`. status/duration have low fixed
    // cardinality (2 and 4 respectively) so the shared :facetLimit (50) never
    // trims them — behavior matches the old uncapped queries.
    // =========================================================================
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
            ),
            facet_kinds(kind) AS (
              VALUES ('status'), ('operation'), ('service'), ('duration_bucket'), ('session')
            ),
            facet_counts AS (
              SELECT
                fk.kind AS facet_kind,
                CASE fk.kind
                  WHEN 'status'          THEN t.status
                  WHEN 'operation'       THEN t.root_span_name
                  WHEN 'service'         THEN t.service
                  WHEN 'duration_bucket' THEN t.duration_bucket
                  ELSE                        t.session_id
                END AS facet_key,
                COUNT(*) AS row_count
              FROM traces t
              CROSS JOIN facet_kinds fk
              WHERE
                CASE fk.kind
                  WHEN 'operation' THEN t.root_span_name <> ''
                  WHEN 'session'   THEN t.session_id <> ''
                  ELSE true
                END
                AND (
                  fk.kind = 'status'
                  OR cardinality(CAST(:statuses AS text[])) = 0
                  OR t.status = ANY(CAST(:statuses AS text[]))
                )
                AND (
                  fk.kind = 'operation'
                  OR cardinality(CAST(:operations AS text[])) = 0
                  OR t.root_span_name = ANY(CAST(:operations AS text[]))
                )
                AND (
                  fk.kind = 'service'
                  OR cardinality(CAST(:services AS text[])) = 0
                  OR t.service = ANY(CAST(:services AS text[]))
                )
                AND (
                  fk.kind = 'duration_bucket'
                  OR cardinality(CAST(:durations AS text[])) = 0
                  OR t.duration_bucket = ANY(CAST(:durations AS text[]))
                )
                AND (
                  fk.kind = 'session'
                  OR cardinality(CAST(:sessions AS text[])) = 0
                  OR t.session_id = ANY(CAST(:sessions AS text[]))
                )
                AND (
                  :fullTextQuery = ''
                  OR t.trace_id ILIKE '%' || :fullTextQuery || '%'
                  OR t.session_id ILIKE '%' || :fullTextQuery || '%'
                  OR t.root_span_name ILIKE '%' || :fullTextQuery || '%'
                )
              GROUP BY 1, 2
            ),
            facet_ranked AS (
              SELECT
                facet_kind,
                facet_key,
                row_count,
                ROW_NUMBER() OVER (PARTITION BY facet_kind ORDER BY row_count DESC) AS facet_rank
              FROM facet_counts
            )
            SELECT facet_kind, facet_key, row_count
            FROM facet_ranked
            WHERE facet_rank <= CASE WHEN facet_kind = 'session' THEN :sessionLimit ELSE :facetLimit END
            ORDER BY facet_kind, row_count DESC
            """, nativeQuery = true)
    List<Object[]> facetTraceAll(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("facetLimit") int facetLimit,
            @Param("sessionLimit") int sessionLimit);

    // Total filtered trace count — used for initial cursor page and offset pages.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
            )
            SELECT COUNT(*)
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            """, nativeQuery = true)
    long countFilteredTraces(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl);

    // Cursor list — initial page (sort=new: start DESC, trace_id DESC).
    // Returns columns: trace_id, min_start, max_end, error_count, span_count,
    //                  root_span_name, session_id, root_span_id, duration_ms, total_tokens, total_cost_usd
    // The ORDER BY is fixed to start DESC, trace_id DESC for sort=new.
    // Sort variations are handled by separate query methods below.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortNew(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Cursor list — scroll-back page for sort=new:
    // rows where (min_start, trace_id) < (cursorTs, cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (min_start, trace_id) < (CAST(:cursorTs AS timestamptz), :cursorTraceId)
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortNewBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live tail for sort=new: rows where (min_start, trace_id) > (cursorTs, cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (min_start, trace_id) > (CAST(:cursorTs AS timestamptz), :cursorTraceId)
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortNewAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Generic list query for non-time sorts and offset paging.
    // :sortClause is NOT used here — ORDER BY is embedded per-sort via separate methods.
    // sort=old: min_start ASC, trace_id ASC
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start ASC, trace_id ASC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortOld(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // Cursor list — first page for sort=old (mirrors traceListSortNew with the ordering flipped).
    // Without this the cursor dispatch fell through to sort=new and Stream mode silently served
    // newest-first for sort=old.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start ASC, trace_id ASC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortOldCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Cursor list — scroll-back page for sort=old. "Scroll back" means "further along the sort
    // order", which for an ascending list is later in time:
    // rows where (min_start, trace_id) > (cursorTs, cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (min_start, trace_id) > (CAST(:cursorTs AS timestamptz), :cursorTraceId)
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start ASC, trace_id ASC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortOldBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live tail for sort=old: rows above the head of an ascending list, i.e.
    // (min_start, trace_id) < (cursorTs, cursorTraceId).
    // The LIMIT has to keep the rows ADJACENT to the head — the newest of the older rows — so the
    // inner select orders DESC and the outer one restores ascending order. Selecting them in
    // ascending order directly would take the oldest N and leave a permanent gap under the head.
    // Caveat: TraceExplorerService.buildCursorPage still trims an over-full page with
    // subList(0, limit), which drops from the head-adjacent end again. That trim is shared with
    // sort=new and the logs tail and has not been reworked.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT * FROM (
              SELECT trace_id, min_start, max_end, error_count, span_count,
                     root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
              FROM traces
              WHERE (min_start, trace_id) < (CAST(:cursorTs AS timestamptz), :cursorTraceId)
              AND (
                cardinality(CAST(:statuses AS text[])) = 0
                OR status = ANY(CAST(:statuses AS text[]))
              )
              AND (
                cardinality(CAST(:operations AS text[])) = 0
                OR root_span_name = ANY(CAST(:operations AS text[]))
              )
              AND (
                cardinality(CAST(:services AS text[])) = 0
                OR service = ANY(CAST(:services AS text[]))
              )
              AND (
                cardinality(CAST(:durations AS text[])) = 0
                OR duration_bucket = ANY(CAST(:durations AS text[]))
              )
              AND (
                cardinality(CAST(:sessions AS text[])) = 0
                OR session_id = ANY(CAST(:sessions AS text[]))
              )
              AND (
                :fullTextQuery = ''
                OR trace_id ILIKE '%' || :fullTextQuery || '%'
                OR session_id ILIKE '%' || :fullTextQuery || '%'
                OR root_span_name ILIKE '%' || :fullTextQuery || '%'
              )
              ORDER BY min_start DESC, trace_id DESC
              LIMIT :pageLimit
            ) adjacent_to_head
            ORDER BY min_start ASC, trace_id ASC
            """, nativeQuery = true)
    List<Object[]> traceListSortOldAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTs") Instant cursorTs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // sort=slow: duration_ms DESC, trace_id DESC
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms DESC, trace_id DESC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortSlow(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // sort=fast: duration_ms ASC, trace_id ASC
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms ASC, trace_id ASC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortFast(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // sort=spans: span_count DESC, trace_id DESC
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY span_count DESC, trace_id DESC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortSpans(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // sort=err: error_count DESC, min_start DESC, trace_id DESC
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY error_count DESC, min_start DESC, trace_id DESC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortErr(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // sort=new offset paging variant (mirrors traceListSortNew but with OFFSET).
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY min_start DESC, trace_id DESC
            LIMIT :pageLimit OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortNewOffset(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit,
            @Param("pageOffset") int pageOffset);

    // Cursor sort-key lookup — resolves (duration_ms, span_count, error_count, min_start,
    // total_tokens, total_cost_usd) for the cursor trace within the active window+filters
    // so keyset pages can be built without the caller knowing the sort-key in advance.
    // Returns at most one row in that column order.
    // Returns empty if the traceId is not present in the filtered window (fallback to first page).
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT duration_ms, span_count, error_count, min_start, total_tokens, total_cost_usd
            FROM traces
            WHERE trace_id = :cursorTraceId
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> traceCursorSortKey(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl);

    // Cursor list — scroll-back page for sort=slow:
    // ORDER BY duration_ms DESC, trace_id DESC
    // before = rows that come AFTER the cursor row in that order (strictly lower rank in DESC):
    //   (duration_ms < cursorDuration) OR (duration_ms = cursorDuration AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              duration_ms < :cursorDurationMs
              OR (duration_ms = :cursorDurationMs AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSlowBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorDurationMs") double cursorDurationMs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=slow:
    // after = rows that come BEFORE the cursor row in that order (strictly higher rank in DESC):
    //   (duration_ms > cursorDuration) OR (duration_ms = cursorDuration AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              duration_ms > :cursorDurationMs
              OR (duration_ms = :cursorDurationMs AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSlowAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorDurationMs") double cursorDurationMs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Cursor list — scroll-back page for sort=fast:
    // ORDER BY duration_ms ASC, trace_id ASC
    // before = rows that come AFTER the cursor row in that order (strictly higher rank in ASC = larger values):
    //   (duration_ms > cursorDuration) OR (duration_ms = cursorDuration AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              duration_ms > :cursorDurationMs
              OR (duration_ms = :cursorDurationMs AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms ASC, trace_id ASC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortFastBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorDurationMs") double cursorDurationMs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=fast:
    // after = rows that come BEFORE the cursor row in that order (strictly lower rank in ASC = smaller values):
    //   (duration_ms < cursorDuration) OR (duration_ms = cursorDuration AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              duration_ms < :cursorDurationMs
              OR (duration_ms = :cursorDurationMs AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms ASC, trace_id ASC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortFastAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorDurationMs") double cursorDurationMs,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Cursor list — scroll-back page for sort=spans:
    // ORDER BY span_count DESC, trace_id DESC
    // before = rows that come AFTER the cursor row in that order (strictly lower rank in DESC = smaller values):
    //   (span_count < cursorSpanCount) OR (span_count = cursorSpanCount AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              span_count < :cursorSpanCount
              OR (span_count = :cursorSpanCount AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY span_count DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSpansBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorSpanCount") long cursorSpanCount,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=spans:
    // after = rows that come BEFORE the cursor row in that order (strictly higher rank in DESC = larger values):
    //   (span_count > cursorSpanCount) OR (span_count = cursorSpanCount AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              span_count > :cursorSpanCount
              OR (span_count = :cursorSpanCount AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY span_count DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSpansAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorSpanCount") long cursorSpanCount,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Cursor list — scroll-back page for sort=err:
    // ORDER BY error_count DESC, min_start DESC, trace_id DESC
    // before = rows that come AFTER the cursor row in that order (strictly lower rank in DESC = smaller values):
    //   (error_count < cursorErrorCount)
    //   OR (error_count = cursorErrorCount AND min_start < cursorMinStart)
    //   OR (error_count = cursorErrorCount AND min_start = cursorMinStart AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              error_count < :cursorErrorCount
              OR (error_count = :cursorErrorCount AND min_start < CAST(:cursorMinStart AS timestamptz))
              OR (error_count = :cursorErrorCount AND min_start = CAST(:cursorMinStart AS timestamptz)
                  AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY error_count DESC, min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortErrBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorErrorCount") long cursorErrorCount,
            @Param("cursorMinStart") Instant cursorMinStart,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=err:
    // after = rows that come BEFORE the cursor row in that order (strictly higher rank in DESC = larger values):
    //   (error_count > cursorErrorCount)
    //   OR (error_count = cursorErrorCount AND min_start > cursorMinStart)
    //   OR (error_count = cursorErrorCount AND min_start = cursorMinStart AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              error_count > :cursorErrorCount
              OR (error_count = :cursorErrorCount AND min_start > CAST(:cursorMinStart AS timestamptz))
              OR (error_count = :cursorErrorCount AND min_start = CAST(:cursorMinStart AS timestamptz)
                  AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY error_count DESC, min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortErrAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorErrorCount") long cursorErrorCount,
            @Param("cursorMinStart") Instant cursorMinStart,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Initial cursor page for sort=slow (no cursor, no OFFSET — uses LIMIT only).
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSlowCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Initial cursor page for sort=fast.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY duration_ms ASC, trace_id ASC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortFastCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Initial cursor page for sort=spans.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY span_count DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortSpansCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Initial cursor page for sort=err.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY error_count DESC, min_start DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortErrCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Initial cursor page for sort=tokens (total_tokens DESC, trace_id DESC — mirrors sort=slow structure).
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_tokens DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortTokensCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Scroll-back page for sort=tokens:
    // ORDER BY total_tokens DESC, trace_id DESC
    // before = rows that come AFTER the cursor row in that order (strictly lower rank in DESC):
    //   (total_tokens < cursorTokens) OR (total_tokens = cursorTokens AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              total_tokens < :cursorTokens
              OR (total_tokens = :cursorTokens AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_tokens DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortTokensBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTokens") long cursorTokens,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=tokens:
    // after = rows that come BEFORE the cursor row in that order (strictly higher rank in DESC):
    //   (total_tokens > cursorTokens) OR (total_tokens = cursorTokens AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              total_tokens > :cursorTokens
              OR (total_tokens = :cursorTokens AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_tokens DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortTokensAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorTokens") long cursorTokens,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Offset paged query for sort=tokens: total_tokens DESC, trace_id DESC.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_tokens DESC, trace_id DESC
            LIMIT :pageSize OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortTokens(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageSize") int pageSize,
            @Param("pageOffset") int pageOffset);

    // Initial cursor page for sort=cost (total_cost_usd DESC, trace_id DESC — mirrors sort=tokens structure).
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_cost_usd DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortCostCursor(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Scroll-back page for sort=cost:
    // ORDER BY total_cost_usd DESC, trace_id DESC
    // before = rows that come AFTER the cursor row in that order (strictly lower rank in DESC):
    //   (total_cost_usd < cursorCostUsd) OR (total_cost_usd = cursorCostUsd AND trace_id < cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              total_cost_usd < CAST(:cursorCostUsd AS numeric)
              OR (total_cost_usd = CAST(:cursorCostUsd AS numeric) AND trace_id < :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_cost_usd DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortCostBefore(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorCostUsd") BigDecimal cursorCostUsd,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Live-tail page for sort=cost:
    // after = rows that come BEFORE the cursor row in that order (strictly higher rank in DESC):
    //   (total_cost_usd > cursorCostUsd) OR (total_cost_usd = cursorCostUsd AND trace_id > cursorTraceId)
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              total_cost_usd > CAST(:cursorCostUsd AS numeric)
              OR (total_cost_usd = CAST(:cursorCostUsd AS numeric) AND trace_id > :cursorTraceId)
            )
            AND (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_cost_usd DESC, trace_id DESC
            LIMIT :pageLimit
            """, nativeQuery = true)
    List<Object[]> traceListSortCostAfter(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("cursorCostUsd") BigDecimal cursorCostUsd,
            @Param("cursorTraceId") String cursorTraceId,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageLimit") int pageLimit);

    // Offset paged query for sort=cost: total_cost_usd DESC, trace_id DESC.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.start_timestamp >= :windowStart
                AND s.start_timestamp <= :windowEnd
                AND (:repositoryUrl IS NULL OR s.repository_url = :repositoryUrl)
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id IN (SELECT trace_id FROM trace_agg)
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            ),
            traces AS (
              SELECT
                a.trace_id,
                a.min_start,
                a.max_end,
                a.span_count,
                a.error_count,
                a.total_tokens,
                COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd,
                COALESCE(rt.root_span_name, '')         AS root_span_name,
                COALESCE(rt.session_id, '')             AS session_id,
                COALESCE(rt.root_span_id, '')           AS root_span_id,
                EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
                CASE WHEN a.error_count > 0 THEN 'error' ELSE 'ok' END AS status,
                CASE
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.interaction%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'session.%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'context.%'   THEN 'claude_code.session'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.tool%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'tool.%'        THEN 'claude_code.tools'
                  WHEN COALESCE(rt.root_span_name, '') LIKE 'claude_code.llm%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'claude_code.model%'
                    OR COALESCE(rt.root_span_name, '') LIKE 'model.%'       THEN 'claude_code.models'
                  ELSE 'claude_code'
                END AS service,
                CASE
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 <  100  THEN 'd0'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 1000  THEN 'd1'
                  WHEN EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 < 5000  THEN 'd2'
                  ELSE 'd3'
                END AS duration_bucket
              FROM trace_agg a
              LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
              LEFT JOIN LATERAL (
                SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
                FROM log_records l
                WHERE l.trace_id = a.trace_id
                  AND l.event_name = 'api_request'
                  AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
              ) tc ON TRUE
            )
            SELECT trace_id, min_start, max_end, error_count, span_count,
                   root_span_name, session_id, root_span_id, duration_ms, total_tokens,
                   total_cost_usd
            FROM traces
            WHERE (
              cardinality(CAST(:statuses AS text[])) = 0
              OR status = ANY(CAST(:statuses AS text[]))
            )
            AND (
              cardinality(CAST(:operations AS text[])) = 0
              OR root_span_name = ANY(CAST(:operations AS text[]))
            )
            AND (
              cardinality(CAST(:services AS text[])) = 0
              OR service = ANY(CAST(:services AS text[]))
            )
            AND (
              cardinality(CAST(:durations AS text[])) = 0
              OR duration_bucket = ANY(CAST(:durations AS text[]))
            )
            AND (
              cardinality(CAST(:sessions AS text[])) = 0
              OR session_id = ANY(CAST(:sessions AS text[]))
            )
            AND (
              :fullTextQuery = ''
              OR trace_id ILIKE '%' || :fullTextQuery || '%'
              OR session_id ILIKE '%' || :fullTextQuery || '%'
              OR root_span_name ILIKE '%' || :fullTextQuery || '%'
            )
            ORDER BY total_cost_usd DESC, trace_id DESC
            LIMIT :pageSize OFFSET :pageOffset
            """, nativeQuery = true)
    List<Object[]> traceListSortCost(
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            @Param("statuses") String[] statuses,
            @Param("operations") String[] operations,
            @Param("services") String[] services,
            @Param("durations") String[] durations,
            @Param("sessions") String[] sessions,
            @Param("fullTextQuery") String fullTextQuery,
            @Param("repositoryUrl") String repositoryUrl,
            @Param("pageSize") int pageSize,
            @Param("pageOffset") int pageOffset);

    // Single-trace summary for the trace detail page — the same aggregate the list
    // queries build, keyed on one trace id instead of a window + filter set, and
    // emitted in the identical column order so TraceExplorerService#toTraceSummary
    // maps both. Deliberately NOT window-scoped: a permalinked trace has to resolve
    // regardless of the window the user last had selected.
    // trace_roots mirrors the list queries' root resolution, including the
    // in-flight fallback: (parent_span_id IS NULL) DESC prefers a real root but
    // falls back to the earliest span when only children have been exported.
    // Returns an empty list when no spans carry the trace id.
    @Query(value = """
            WITH trace_agg AS (
              SELECT
                s.trace_id,
                MIN(s.start_timestamp)  AS min_start,
                MAX(s.end_timestamp)    AS max_end,
                COUNT(*)                AS span_count,
                SUM(CASE WHEN s.status_code = 'error' THEN 1 ELSE 0 END) AS error_count,
                COALESCE(SUM(span_token_total(s.attributes)), 0) AS total_tokens
              FROM spans s
              WHERE s.trace_id = :traceId
              GROUP BY s.trace_id
            ),
            trace_roots AS (
              SELECT DISTINCT ON (r.trace_id)
                r.trace_id,
                r.span_id                               AS root_span_id,
                r.name                                  AS root_span_name,
                r.attributes ->> 'session.id'           AS session_id
              FROM spans r
              WHERE r.trace_id = :traceId
              ORDER BY r.trace_id, (r.parent_span_id IS NULL) DESC, r.start_timestamp ASC
            )
            SELECT
              a.trace_id,
              a.min_start,
              a.max_end,
              a.error_count,
              a.span_count,
              COALESCE(rt.root_span_name, '')         AS root_span_name,
              COALESCE(rt.session_id, '')             AS session_id,
              COALESCE(rt.root_span_id, '')           AS root_span_id,
              EXTRACT(EPOCH FROM (a.max_end - a.min_start)) * 1000.0 AS duration_ms,
              a.total_tokens,
              COALESCE(tc.total_cost_usd, 0)          AS total_cost_usd
            FROM trace_agg a
            LEFT JOIN trace_roots rt ON rt.trace_id = a.trace_id
            LEFT JOIN LATERAL (
              SELECT SUM((l.attributes ->> 'cost_usd')::numeric) AS total_cost_usd
              FROM log_records l
              WHERE l.trace_id = a.trace_id
                AND l.event_name = 'api_request'
                AND l.attributes ->> 'cost_usd' ~ '^-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?$'
            ) tc ON TRUE
            """, nativeQuery = true)
    List<Object[]> traceSummaryById(@Param("traceId") String traceId);
}
