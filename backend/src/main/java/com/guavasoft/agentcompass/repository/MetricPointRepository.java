package com.guavasoft.agentcompass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.MetricPointEntity;

import java.time.Instant;
import java.util.List;

public interface MetricPointRepository extends JpaRepository<MetricPointEntity, Long> {

  // Returns every metric_points row whose attributes jsonb contains every entry
  // in :filters
  // (an AND set of "key=value" strings), sorted newest first. When :filters is an
  // empty
  // array the NOT EXISTS clause is vacuously true and every row is returned.
  //
  // Uses jsonb_each_text on both sides of the match so primitive attribute values
  // compare
  // without JSON quoting (status=200, not status="200"); object/array values are
  // excluded
  // from the autocomplete by findDistinctAttributePairs, so the filter strings
  // reaching
  // this query are always primitive pairs.
  // Optional [startTimestamp, endTimestamp] bounds use the same NULL-or-compare
  // pattern as
  // LogRecordRepository: empty bounds = no time narrowing, single bound =
  // one-sided.
  @Query(value = """
      SELECT *
      FROM metric_points
      WHERE (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
        AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY timestamp DESC
      """, nativeQuery = true)
  List<MetricPointEntity> findAllMatchingFilters(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Returns every distinct "key=value" pair across metric_points.attributes,
  // narrowed to rows
  // that contain every entry in :filters (an AND set of "key=value" strings).
  //
  // Object- and array-valued attributes are excluded from the returned pair set:
  // their text
  // serialization differs between Postgres (jsonb_each_text adds whitespace,
  // {"a": 1}) and the
  // frontend's compact JSON.stringify ({"a":1}), so emitted pairs would never
  // match any row's
  // client-side computed pair set. Primitive types (string, number, boolean,
  // null) round-trip
  // cleanly and remain in the option list. The NOT EXISTS clause uses
  // jsonb_each_text against
  // the full attributes blob for filter matching, since active filters are always
  // primitive
  // pairs by construction (only primitives reach the autocomplete).
  //
  // When :filters is an empty array, the NOT EXISTS clause is vacuously true.
  @Query(value = """
      SELECT DISTINCT attribute_entry.key || '=' || (attribute_entry.value #>> '{}')
      FROM metric_points,
           jsonb_each(attributes) AS attribute_entry
      WHERE attributes IS NOT NULL
        AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
        AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        AND jsonb_typeof(attribute_entry.value) NOT IN ('object', 'array')
        AND NOT EXISTS (
          SELECT 1
          FROM unnest(CAST(:filters AS text[])) AS required_filter
          WHERE required_filter NOT IN (
            SELECT row_entry.key || '=' || row_entry.value
            FROM jsonb_each_text(attributes) AS row_entry
          )
        )
      ORDER BY 1
      """, nativeQuery = true)
  List<String> findDistinctAttributePairs(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Per-(bucket, type) token totals for the configured token-usage metric.
  // date_bin aligns buckets to :since so the first bucket starts exactly at the
  // window's lower bound.
  //
  // claude_code.token.usage is a CUMULATIVE counter that Claude Code re-emits
  // every minute — so a plain SUM over rows re-adds each running total many times
  // over and, because every stream only ever climbs, makes all four token-type
  // series rise together as one shape. A "stream" is one counter identified by
  // its FULL attribute set: the same (session, model, type) carries several
  // concurrent streams (main / auxiliary / subagent via query_source, plus
  // agent.name), so partitioning on only those three merges distinct counters.
  //
  // We take each stream's reset-aware per-row increment: PARTITION BY the whole
  // attributes map, LAG the running value by timestamp, and on a reset — the same
  // attribute set reused by a fresh run that starts low again (e.g. a re-spawned
  // subagent) — count the new level as the increment (Prometheus increase()
  // semantics) so a bucket can never go negative. Each increment is then binned
  // by the timestamp of the row that produced it and summed per (bucket, type).
  // Working at row granularity (not a per-bucket MAX) catches resets that happen
  // inside one bucket — for cache-heavy subagent churn those hide millions of
  // tokens — so the bucket sums reconcile EXACTLY with aggregateTokensByModel and
  // the headline total. The first in-window emission of a stream has no
  // predecessor (LAG NULL -> 0) and carries its cumulative value to that point.
  // value_long / value_double both COALESCE since the value column varies by
  // emitter. Rows are sparse — a (bucket, type) pair only appears when a stream
  // advanced there — and the service fills gaps with zero.
  @Query(value = """
      SELECT bucket, token_type, SUM(delta)::bigint AS total
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :since) AS bucket,
          token_type,
          CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
        FROM (
          SELECT
            timestamp,
            attributes ->> :tokenTypeAttribute AS token_type,
            COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
            COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
              OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND attributes ->> :tokenTypeAttribute IS NOT NULL
            AND timestamp >= :since
        ) AS lagged
      ) AS token_increments
      GROUP BY bucket, token_type
      ORDER BY bucket, token_type
      """, nativeQuery = true)
  List<Object[]> aggregateTokenUsageTimeseries(
      @Param("metricName") String metricName,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("since") Instant since,
      @Param("bucketSeconds") long bucketSeconds);

  @Query(value = """
      SELECT bucket, token_type, SUM(delta)::bigint AS total
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
          token_type,
          CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
        FROM (
          SELECT
            timestamp,
            attributes ->> :tokenTypeAttribute AS token_type,
            COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
            COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
              OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND attributes ->> :tokenTypeAttribute IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS token_increments
      GROUP BY bucket, token_type
      ORDER BY bucket, token_type
      """, nativeQuery = true)
  List<Object[]> aggregateTokenUsageTimeseriesInRange(
      @Param("metricName") String metricName,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Per-session cost and active-time totals, backend-sorted and
  // backend-paginated.
  //
  // cost.usage and active_time.total are CUMULATIVE counters Claude Code re-emits
  // every minute. A "stream" is one counter, identified by its FULL attribute set
  // — (session, model, query_source) is NOT enough: cost.usage also splits on
  // agent.name, so several streams share that triple and merging them with MAX
  // drops all but the largest. So we partition on the whole attributes map, take
  // the per-row increment vs. that stream's previous emission (LAG), and on a
  // reset — the same attribute set reused by a fresh run that starts low — count
  // the new level (Prometheus increase() semantics) so increments never go
  // negative. Summing increments per stream recovers its final value (monotonic
  // case) and the sum of segment peaks (reset case); summing across a session's
  // streams gives its window total. This matches aggregateCostTotal /
  // aggregateTokensByModel exactly, so the page reconciles.
  //
  // Session start/end timestamps are the window of cost/active-time emissions
  // carrying this session id. Wall-clock duration is the difference in whole
  // seconds. Sessions missing one metric still appear: LEFT JOIN keeps cost-only
  // or active-time-only sessions, COALESCE turns the missing side into 0.
  //
  // The [startTimestamp, endTimestamp] bounds use the NULL-or-compare pattern, so
  // the ?minutes= form (start only) and the ?startTimestamp=&endTimestamp= form
  // (both) flow through one query. :sortColumn is one of a service-whitelisted
  // token set ('cost', 'active', 'wall', 'costPerMinute', 'started') and
  // :sortDirection is 'asc' or 'desc' — both arrive normalized, never raw user
  // input, so the CASE-based ORDER BY cannot be turned into SQL injection. The
  // session_id tiebreaker makes paging deterministic across requests, and
  // COUNT(*) OVER() carries the total session count alongside the page so a
  // second count round-trip is unnecessary.
  @Query(value = """
      WITH cost_per_session AS (
        SELECT session_id, SUM(delta) AS cost_usd
        FROM (
          SELECT
            attributes ->> 'session.id' AS session_id,
            CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
          FROM (
            SELECT
              attributes,
              value_double,
              COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
            FROM metric_points
            WHERE metric_name = :costMetric
              AND attributes ->> 'session.id' IS NOT NULL
              AND value_double IS NOT NULL
              AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
              AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          ) AS lagged
        ) AS cost_increments
        GROUP BY session_id
      ),
      active_per_session AS (
        SELECT session_id, SUM(delta) AS active_time_seconds
        FROM (
          SELECT
            attributes ->> 'session.id' AS session_id,
            CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
          FROM (
            SELECT
              attributes,
              value_double,
              COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
            FROM metric_points
            WHERE metric_name = :activeTimeMetric
              AND attributes ->> 'session.id' IS NOT NULL
              AND value_double IS NOT NULL
              AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
              AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          ) AS lagged
        ) AS active_increments
        GROUP BY session_id
      ),
      token_per_session AS (
        SELECT session_id, SUM(delta)::bigint AS tokens
        FROM (
          SELECT
            attributes ->> 'session.id' AS session_id,
            CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
          FROM (
            SELECT
              attributes,
              COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
              COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
                OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
            FROM metric_points
            WHERE metric_name = :tokenMetric
              AND attributes ->> 'session.id' IS NOT NULL
              AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
              AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          ) AS lagged
        ) AS token_increments
        GROUP BY session_id
      ),
      session_meta AS (
        SELECT DISTINCT ON (attributes ->> 'session.id')
          attributes ->> 'session.id'    AS session_id,
          attributes ->> 'start_type'    AS start_type,
          attributes ->> 'terminal.type' AS terminal_type
        FROM metric_points
        WHERE metric_name = :sessionCountMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        ORDER BY attributes ->> 'session.id', COALESCE(start_timestamp, timestamp)
      ),
      session_window AS (
        SELECT
          attributes ->> 'session.id' AS session_id,
          MIN(timestamp)              AS first_seen,
          MAX(timestamp)              AS last_seen
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND attributes ->> 'session.id' IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
      )
      SELECT
        w.session_id,
        COALESCE(c.cost_usd, 0)::double precision            AS cost_usd,
        COALESCE(a.active_time_seconds, 0)::double precision AS active_time_seconds,
        w.first_seen,
        w.last_seen,
        EXTRACT(EPOCH FROM (w.last_seen - w.first_seen))::bigint AS wall_seconds,
        COALESCE(t.tokens, 0)::bigint                            AS tokens,
        m.terminal_type,
        m.start_type,
        COUNT(*) OVER()::bigint                                  AS total_count
      FROM session_window w
      LEFT JOIN cost_per_session c   ON c.session_id = w.session_id
      LEFT JOIN active_per_session a ON a.session_id = w.session_id
      LEFT JOIN token_per_session t  ON t.session_id = w.session_id
      LEFT JOIN session_meta m       ON m.session_id = w.session_id
      ORDER BY
        CASE WHEN :sortDirection = 'asc' THEN
          CASE :sortColumn
            WHEN 'cost'   THEN COALESCE(c.cost_usd, 0)
            WHEN 'active' THEN COALESCE(a.active_time_seconds, 0)
            WHEN 'wall'   THEN EXTRACT(EPOCH FROM (w.last_seen - w.first_seen))
            WHEN 'tokens' THEN COALESCE(t.tokens, 0)
            WHEN 'costPerMinute' THEN CASE WHEN COALESCE(a.active_time_seconds, 0) > 0
              THEN COALESCE(c.cost_usd, 0) / a.active_time_seconds * 60 END
          END
        END ASC NULLS LAST,
        CASE WHEN :sortDirection = 'desc' THEN
          CASE :sortColumn
            WHEN 'cost'   THEN COALESCE(c.cost_usd, 0)
            WHEN 'active' THEN COALESCE(a.active_time_seconds, 0)
            WHEN 'wall'   THEN EXTRACT(EPOCH FROM (w.last_seen - w.first_seen))
            WHEN 'tokens' THEN COALESCE(t.tokens, 0)
            WHEN 'costPerMinute' THEN CASE WHEN COALESCE(a.active_time_seconds, 0) > 0
              THEN COALESCE(c.cost_usd, 0) / a.active_time_seconds * 60 END
          END
        END DESC NULLS LAST,
        CASE WHEN :sortColumn = 'started' AND :sortDirection = 'asc'  THEN w.first_seen END ASC NULLS LAST,
        CASE WHEN :sortColumn = 'started' AND :sortDirection = 'desc' THEN w.first_seen END DESC NULLS LAST,
        w.session_id ASC
      LIMIT :pageSize OFFSET :pageOffset
      """, nativeQuery = true)
  List<Object[]> aggregateSessionSummaries(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("sessionCountMetric") String sessionCountMetric,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp,
      @Param("sortColumn") String sortColumn,
      @Param("sortDirection") String sortDirection,
      @Param("pageSize") int pageSize,
      @Param("pageOffset") int pageOffset);

  // ---------------------------------------------------------------------------
  // Metric catalog
  // ---------------------------------------------------------------------------
  //
  // One row per distinct metric_name in the window. Cardinality = COUNT(DISTINCT
  // attributes) as a heuristic for the number of active attribute combinations.
  // The sparkline is computed as 8 even buckets using date_bin; each bucket
  // carries the point count so the frontend can render a mini bar chart.
  // Bucket width = (end - start) / 8, clamped to at least 1 second.
  //
  // The two separate queries (spark vs. summary) are joined in the service to
  // produce one CatalogMetric per name. A single cross-join between 8 buckets
  // and N metric names would be harder to index efficiently, so we split them.
  @Query(value = """
      SELECT
        metric_name,
        COALESCE(MAX(unit), '')                   AS unit,
        COUNT(DISTINCT attributes)::bigint        AS cardinality
      FROM metric_points
      WHERE timestamp >= :start
        AND timestamp <= :end
      GROUP BY metric_name
      ORDER BY metric_name
      """, nativeQuery = true)
  List<Object[]> aggregateCatalogSummary(
      @Param("start") Instant start,
      @Param("end") Instant end);

  // 8-bucket sparkline for each metric name in the window.
  // date_bin aligns buckets to :start so the first bucket begins exactly at the
  // window lower bound. Returns (metric_name, bucket_index 0..7, row_count) for
  // every non-empty (name, bucket) combination.
  @Query(value = """
      SELECT
        metric_name,
        FLOOR(EXTRACT(EPOCH FROM (date_bin(
            make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
          / :bucketSeconds)::int                  AS bucket_index,
        COUNT(*)::bigint                          AS row_count
      FROM metric_points
      WHERE timestamp >= :start
        AND timestamp <= :end
      GROUP BY metric_name, bucket_index
      ORDER BY metric_name, bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateCatalogSparklines(
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // ---------------------------------------------------------------------------
  // Cost summary
  // ---------------------------------------------------------------------------
  //
  // Per-session cost = SUM of reset-aware per-stream increments (full attribute
  // identity), matching the semantics of aggregateSessionSummaries — see that
  // query for why a plain MAX-per-(session,model,query_source) under-counts.
  // Returns the window total and, for the delta comparison, the equivalent total
  // from the prior window of the same length.
  @Query(value = """
      SELECT COALESCE(SUM(delta), 0)::double precision AS total_cost
      FROM (
        SELECT CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND attributes ->> 'session.id' IS NOT NULL
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS cost_increments
      """, nativeQuery = true)
  List<Object[]> aggregateCostTotal(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-model cost breakdown for the window. Returns (model, model_cost) sorted
  // by model_cost descending. Reset-aware per-stream increments (full attribute
  // identity), then SUM per model — same semantics as aggregateCostTotal.
  @Query(value = """
      SELECT model, SUM(delta)::double precision AS model_cost
      FROM (
        SELECT
          COALESCE(attributes ->> 'model', 'unknown') AS model,
          CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            attributes,
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND attributes ->> 'session.id' IS NOT NULL
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS cost_increments
      GROUP BY model
      ORDER BY model_cost DESC
      """, nativeQuery = true)
  List<Object[]> aggregateCostByModel(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-bucket cost trend (14 even buckets) for the window. Each reset-aware
  // per-stream increment lands in the bucket of the row that produced it, so the
  // bucket sums reconcile with aggregateCostTotal.
  @Query(value = """
      SELECT bucket, SUM(delta)::double precision AS bucket_cost
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
          CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            timestamp,
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND attributes ->> 'session.id' IS NOT NULL
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS cost_increments
      GROUP BY bucket
      ORDER BY bucket
      """, nativeQuery = true)
  List<Object[]> aggregateCostTrend(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Total tokens in the window (for costPer1k denominator). token.usage is a
  // cumulative counter, so we sum reset-aware per-stream increments (full
  // attribute identity) rather than the raw rows — a plain SUM re-adds every
  // re-emitted running total. Matches the headline total from the timeseries.
  @Query(value = """
      SELECT COALESCE(SUM(delta), 0)::bigint AS total_tokens
      FROM (
        SELECT CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
        FROM (
          SELECT
            COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
            COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
              OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS token_increments
      """, nativeQuery = true)
  List<Object[]> aggregateTotalTokens(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // ---------------------------------------------------------------------------
  // Token distribution
  // ---------------------------------------------------------------------------
  //
  // Top N sessions by total token usage. token.usage is a cumulative counter that
  // Claude Code re-emits every minute, split into many concurrent streams per
  // session (model x type x query_source x agent.name) — so a plain SUM over rows
  // re-adds each running total and a MAX over an incomplete key drops concurrent
  // streams. The innermost query takes the reset-aware per-row increment of each
  // full-attribute stream (see aggregateTokensByModel), the middle query sums
  // those per (session, model), the next sums per session and derives the time
  // -column index (0..23) from the session's last emission. One exemplar per
  // session keeps trace ids distinct.
  @Query(value = """
      SELECT
        total_tokens,
        last_timestamp                                                         AS timestamp,
        LEAST(23, GREATEST(0,
          FLOOR(EXTRACT(EPOCH FROM (last_timestamp - :start)) / :colWidthSeconds)::int))
                                                                               AS col_index,
        session_id,
        model
      FROM (
        SELECT
          session_id,
          SUM(model_tokens)::bigint                                           AS total_tokens,
          MAX(last_timestamp)                                                 AS last_timestamp,
          (ARRAY_AGG(model ORDER BY model_tokens DESC))[1]                    AS model
        FROM (
          SELECT
            session_id,
            model,
            SUM(delta)             AS model_tokens,
            MAX(timestamp)         AS last_timestamp
          FROM (
            SELECT
              attributes ->> :sessionIdAttribute AS session_id,
              attributes ->> :modelAttribute     AS model,
              timestamp,
              CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
            FROM (
              SELECT
                attributes,
                timestamp,
                COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
                COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
                  OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
              FROM metric_points
              WHERE metric_name = :metricName
                AND timestamp >= :start
                AND timestamp <= :end
            ) AS lagged
          ) AS increments
          GROUP BY session_id, model
        ) AS per_model
        GROUP BY session_id
      ) AS sessions
      WHERE total_tokens > 0
      ORDER BY total_tokens DESC
      LIMIT :exemplarLimit
      """, nativeQuery = true)
  List<Object[]> findTopTokenRows(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("colWidthSeconds") long colWidthSeconds,
      @Param("sessionIdAttribute") String sessionIdAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("exemplarLimit") int exemplarLimit);

  // Per-model token totals for the window. claude_code.token.usage is a
  // cumulative counter re-emitted every minute and split into many concurrent
  // streams identified by the FULL attribute set (model x type x query_source x
  // agent.name within a session) — so a plain SUM re-adds each running total and
  // a MAX over (session, model, type) merges concurrent streams and drops all but
  // the largest. We instead take each full-attribute stream's reset-aware per-row
  // increment (CASE handles a counter reset by counting the new low value), then
  // SUM per model. The increments telescope to each stream's final value, so this
  // reconciles exactly with the headline total summed from the timeseries.
  @Query(value = """
      SELECT model, SUM(delta)::bigint AS tokens
      FROM (
        SELECT
          attributes ->> :modelAttribute AS model,
          CASE WHEN token_value >= prev_value THEN token_value - prev_value ELSE token_value END AS delta
        FROM (
          SELECT
            attributes,
            COALESCE(value_long, CAST(value_double AS bigint), 0) AS token_value,
            COALESCE(LAG(COALESCE(value_long, CAST(value_double AS bigint), 0))
              OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND timestamp >= :start AND timestamp <= :end
        ) AS lagged
      ) AS token_increments
      WHERE model IS NOT NULL
      GROUP BY model
      ORDER BY tokens DESC
      """, nativeQuery = true)
  List<Object[]> aggregateTokensByModel(
      @Param("metricName") String metricName,
      @Param("modelAttribute") String modelAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Window-level session KPIs: total session count plus cost percentiles. Reuses the
  // same reset-aware, full-attribute per-session rollup as aggregateSessionSummaries
  // (see there for why MAX-per-(session,model,query_source) under-counts), then
  // collapses it with percentile_cont (linear interpolation, matching the dashboard's
  // former client-side percentile helper). The $/active-minute median is taken only
  // over sessions with non-zero active time via FILTER, so idle-only sessions don't
  // drag a zero into the burn-rate distribution.
  //
  // The session population is the cost/active-time set ("billable" sessions). We do
  // NOT split it by start_type: in practice every start_type=resume session in this
  // telemetry is a long-lived non-interactive heartbeat host that emits only
  // session.count (no cost/token/active-time/tool rows), so a fresh/resume breakdown
  // over this population is structurally ~all-fresh and carries no signal. Returns
  // exactly one row.
  @Query(value = """
      WITH cost_per_session AS (
        SELECT session_id, SUM(delta) AS cost_usd
        FROM (
          SELECT
            attributes ->> 'session.id' AS session_id,
            CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
          FROM (
            SELECT
              attributes,
              value_double,
              COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
            FROM metric_points
            WHERE metric_name = :costMetric
              AND attributes ->> 'session.id' IS NOT NULL
              AND value_double IS NOT NULL
              AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
              AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          ) AS lagged
        ) AS cost_increments
        GROUP BY session_id
      ),
      active_per_session AS (
        SELECT session_id, SUM(delta) AS active_time_seconds
        FROM (
          SELECT
            attributes ->> 'session.id' AS session_id,
            CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
          FROM (
            SELECT
              attributes,
              value_double,
              COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
            FROM metric_points
            WHERE metric_name = :activeTimeMetric
              AND attributes ->> 'session.id' IS NOT NULL
              AND value_double IS NOT NULL
              AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
              AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          ) AS lagged
        ) AS active_increments
        GROUP BY session_id
      ),
      session_window AS (
        SELECT
          attributes ->> 'session.id' AS session_id
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND attributes ->> 'session.id' IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
      ),
      per_session AS (
        SELECT
          COALESCE(c.cost_usd, 0)            AS cost_usd,
          COALESCE(a.active_time_seconds, 0) AS active_time_seconds
        FROM session_window w
        LEFT JOIN cost_per_session c   ON c.session_id = w.session_id
        LEFT JOIN active_per_session a ON a.session_id = w.session_id
      )
      SELECT
        COUNT(*)::bigint AS total_sessions,
        COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY cost_usd), 0)::double precision
          AS median_cost_usd,
        COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY cost_usd), 0)::double precision
          AS p95_cost_usd,
        COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY cost_usd / active_time_seconds * 60)
          FILTER (WHERE active_time_seconds > 0), 0)::double precision
          AS median_cost_per_active_minute_usd
      FROM per_session
      """, nativeQuery = true)
  List<Object[]> aggregateSessionKpis(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // New-session sparkline for the Sessions-page "Total sessions" card: how many
  // sessions FIRST appeared in each evenly-spaced bucket of the window. A session is
  // counted in the bucket of its earliest cost/active-time emission (MIN(timestamp)),
  // so it's counted exactly once and the bucket counts sum to total_sessions (the
  // same cost/active-time population as aggregateSessionKpis). Returns one row per
  // non-empty bucket as (bucket_index 0-based, new_sessions); the service fills a
  // dense, zero-padded array, so empty buckets correctly read as zero. Concrete
  // start/end bounds are required (the trend needs a fixed origin for date_bin).
  @Query(value = """
      WITH first_seen AS (
        SELECT
          attributes ->> 'session.id' AS session_id,
          MIN(timestamp)              AS first_ts
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND attributes ->> 'session.id' IS NOT NULL
          AND timestamp >= :start
          AND timestamp <= :end
        GROUP BY 1
      )
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(
            make_interval(secs => :bucketSeconds), first_ts, :start) - :start))
          / :bucketSeconds)::int      AS bucket_index,
        COUNT(*)::bigint              AS new_sessions
      FROM first_seen
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateNewSessionsTrend(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // ---------------------------------------------------------------------------
  // Generic metric series (Metrics page, GET /api/metrics/series)
  // ---------------------------------------------------------------------------
  //
  // Every claude_code.* metric is a CUMULATIVE counter re-emitted every minute,
  // split into concurrent streams identified by the FULL attribute set. The three
  // queries below are the metric-name-parameterised form of the reset-aware
  // row-level increment used for tokens/cost (see aggregateTokensByModel): per
  // full-attribute stream, take the per-row increment vs. its previous emission
  // (LAG), counting the new level on a reset (CASE) so increments never go
  // negative, then aggregate. Telescoping recovers each stream's real
  // contribution, so the window total, the per-bucket trend, and the per-split
  // breakdown all reconcile. value_double carries every claude_code.* metric.

  // Window total (reset-aware) for one metric.
  @Query(value = """
      SELECT COALESCE(SUM(delta), 0)::double precision AS total
      FROM (
        SELECT CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS increments
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTotal(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-bucket trend (reset-aware), each increment binned by the row's timestamp.
  @Query(value = """
      SELECT bucket, COALESCE(SUM(delta), 0)::double precision AS total
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
          CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            timestamp,
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS increments
      GROUP BY bucket
      ORDER BY bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrend(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Per-split-value total (reset-aware), grouped by one attribute key.
  @Query(value = """
      SELECT label, COALESCE(SUM(delta), 0)::double precision AS total
      FROM (
        SELECT
          attributes ->> :splitAttribute AS label,
          CASE WHEN value_double >= prev_value THEN value_double - prev_value ELSE value_double END AS delta
        FROM (
          SELECT
            attributes,
            value_double,
            COALESCE(LAG(value_double) OVER (PARTITION BY attributes ORDER BY timestamp), 0) AS prev_value
          FROM metric_points
          WHERE metric_name = :metricName
            AND value_double IS NOT NULL
            AND timestamp >= :start
            AND timestamp <= :end
        ) AS lagged
      ) AS increments
      WHERE label IS NOT NULL
      GROUP BY label
      ORDER BY total DESC
      """, nativeQuery = true)
  List<Object[]> aggregateMetricBySplit(
      @Param("metricName") String metricName,
      @Param("splitAttribute") String splitAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);
}
