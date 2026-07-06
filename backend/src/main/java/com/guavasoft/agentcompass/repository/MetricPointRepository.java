package com.guavasoft.agentcompass.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.entity.MetricPointEntity;

import java.time.Instant;
import java.util.List;

public interface MetricPointRepository extends JpaRepository<MetricPointEntity, Long> {

  // Serializes concurrent ingests for the SAME stream across separate
  // transactions (e.g. an OTLP client retry storm re-POSTing overlapping
  // batches). Acquires a per-stream advisory transaction lock —
  // hashtextextended(stream_id, 0) turns the stream's md5 identity into a lock
  // key — for every distinct stream this batch touches, ordered by stream_id so
  // two concurrent batches that both touch streams A and B always acquire them
  // in the same order (deadlock avoidance).
  //
  // Must run AFTER saveAll() — stream_id is a stored GENERATED column (V11), so
  // it only exists once the batch rows are actually persisted — and BEFORE
  // recomputeValueDeltas(), inside the same transaction (see
  // OtlpMetricService#ingestProtobuf). pg_advisory_xact_lock auto-releases at
  // COMMIT, so a transaction that blocks here resumes only once the other
  // transaction's rows are committed, and its later recomputeValueDeltas UPDATE
  // takes a fresh READ COMMITTED snapshot that can see them — closing the
  // "both transactions compute their delta against the same stale previous
  // row" race.
  //
  // This alone is NOT sufficient: it guarantees the two recomputeValueDeltas
  // UPDATEs run one-at-a-time, not that the second transaction's rows sort
  // AFTER the first's in (timestamp, id) order. When they don't — or when a
  // single later transaction simply inserts a point that arrived out of
  // order — an already-committed row's value_delta can still have been
  // computed against a stale predecessor. recomputeValueDeltas' successor
  // repair (below) is what actually heals that case.
  @Query(value = """
      SELECT pg_advisory_xact_lock(hashtextextended(stream_id, 0))
      FROM (
        SELECT DISTINCT stream_id
        FROM metric_points
        WHERE id IN (:metricPointIds)
        ORDER BY stream_id
      ) AS streams
      """, nativeQuery = true)
  List<Object[]> lockStreamsForIngest(@Param("metricPointIds") List<Long> metricPointIds);

  // Computes value_delta for the given (just-inserted) rows AND, for each of
  // them, repairs the first pre-existing same-stream row that already sorts
  // after it by (timestamp, id) — that row's delta was previously computed
  // against a predecessor that is no longer the true one now that this batch
  // landed between them. One UPDATE statement handles both: the innermost
  // "target" set is the UNION of the inserted ids and each inserted row's
  // immediate successor id (found via a scalar subquery, mirroring the
  // "previous row" lookup below but walking forward); every row in that target
  // set is then recomputed against its TRUE previous same-stream row exactly as
  // before.
  //
  // Why this is needed even with lockStreamsForIngest: that lock only
  // serializes concurrent transactions touching the same stream — it does not
  // guarantee arrival order. Two concrete cases both corrupt a stream's
  // increments without this repair:
  //   (a) Out-of-order arrival: t1 and t3 are ingested (t3's delta computed vs
  //       t1, since t2 doesn't exist yet); a later batch inserts t2. Without
  //       repair, t3's stored delta (vs t1) silently double-counts the
  //       t1..t2 span that t2's own new delta now also covers.
  //   (b) Residual concurrency window: even serialized one-at-a-time by the
  //       advisory lock, the second transaction's rows can sort BEFORE the
  //       first transaction's already-committed rows in (timestamp, id) order
  //       — the successor repair heals that stale successor the same way.
  //
  // previous.stream_id / target.stream_id come from the stored generated
  // column (V11); the (timestamp, id) row-value comparison orders
  // same-timestamp rows deterministically by id, both looking backward (the
  // existing previous-row lookup) and forward (the new successor lookup).
  //
  // Historical rows are NOT covered by this repair — they were backfilled by
  // V11 from what was a single-writer ingest history, and the live database is
  // still single-writer today, so no repair migration is needed; this method
  // only protects new ingests going forward.
  @Transactional
  @Modifying
  @Query(value = """
      UPDATE metric_points AS metric_point
      SET value_delta = computed.delta
      FROM (
        SELECT batch.id,
               CASE WHEN batch.current_value >= batch.previous_value
                    THEN batch.current_value - batch.previous_value
                    ELSE batch.current_value END AS delta
        FROM (
          SELECT target.id,
                 COALESCE(target.value_long::double precision, target.value_double, 0) AS current_value,
                 COALESCE((
                   SELECT COALESCE(previous.value_long::double precision, previous.value_double, 0)
                   FROM metric_points previous
                   WHERE previous.stream_id = target.stream_id
                     AND (previous.timestamp, previous.id) < (target.timestamp, target.id)
                   ORDER BY previous.timestamp DESC, previous.id DESC
                   LIMIT 1
                 ), 0) AS previous_value
          FROM (
            SELECT id, stream_id, timestamp, value_long, value_double
            FROM metric_points
            WHERE id IN (:metricPointIds)

            UNION

            SELECT successor_row.id, successor_row.stream_id, successor_row.timestamp,
                   successor_row.value_long, successor_row.value_double
            FROM metric_points inserted
            JOIN metric_points successor_row
              ON successor_row.id = (
                   SELECT candidate.id
                   FROM metric_points candidate
                   WHERE candidate.stream_id = inserted.stream_id
                     AND (candidate.timestamp, candidate.id) > (inserted.timestamp, inserted.id)
                   ORDER BY candidate.timestamp ASC, candidate.id ASC
                   LIMIT 1
                 )
            WHERE inserted.id IN (:metricPointIds)
          ) AS target
        ) AS batch
      ) AS computed
      WHERE metric_point.id = computed.id
      """, nativeQuery = true)
  void recomputeValueDeltas(@Param("metricPointIds") List<Long> metricPointIds);

  // Total count of metric_points rows matching the active filters/window — shared
  // denominator for the offset-paged Metrics DataGrid (GET /api/metrics). Mirrors
  // LogRecordRepository#countFiltered.
  @Query(value = """
      SELECT COUNT(*)
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
      """, nativeQuery = true)
  long countMatchingFilters(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp);

  // Bounded page of metric_points rows whose attributes jsonb contains every entry
  // in :filters (an AND set of "key=value" strings), newest first. Replaces the
  // former findAllMatchingFilters, which had no LIMIT and sorted the entire
  // ~1.64M-row table on every call (MEDIUM-3: unbounded result / ~1.3GB external
  // merge sort). The fixed ORDER BY timestamp DESC, id DESC walks
  // idx_metric_points_ts_id (V12) as a backward index scan, so LIMIT/OFFSET is
  // served without materializing or sorting the full filtered set.
  //
  // Uses jsonb_each_text on both sides of the match so primitive attribute values
  // compare without JSON quoting (status=200, not status="200"); object/array
  // values are excluded from the autocomplete by findDistinctAttributePairs, so
  // the filter strings reaching this query are always primitive pairs.
  // Optional [startTimestamp, endTimestamp] bounds use the same NULL-or-compare
  // pattern as LogRecordRepository: empty bounds = no time narrowing, single bound
  // = one-sided.
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
      ORDER BY timestamp DESC, id DESC
      LIMIT :pageSize OFFSET :pageOffset
      """, nativeQuery = true)
  List<MetricPointEntity> findPageMatchingFilters(
      @Param("filters") String[] filters,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp,
      @Param("pageSize") int pageSize,
      @Param("pageOffset") int pageOffset);

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
  // date_bin aligns buckets to :start so the first bucket starts exactly at the
  // window's lower bound.
  //
  // claude_code.token.usage is a CUMULATIVE counter that Claude Code re-emits
  // every minute — so a plain SUM over the raw value columns would re-add each
  // running total many times over. A "stream" is one counter identified by its
  // FULL attribute set: the same (session, model, type) carries several
  // concurrent streams (main / auxiliary / subagent via query_source, plus
  // agent.name), so any narrower grouping key merges distinct counters.
  //
  // value_delta (V11) is that reset-aware per-row increment, precomputed once at
  // ingest: md5(metric_name || attributes) identifies the stream, and the ingest
  // -time UPDATE (see recomputeValueDeltas) takes each row's increment vs. its
  // true previous emission, counting the new level on a reset (the same
  // attribute set reused by a fresh run that starts low again, e.g. a re-spawned
  // subagent) so a bucket can never go negative. This query is therefore a plain
  // SUM(value_delta) binned by the timestamp of the row that produced it,
  // servable by the existing (metric_name, timestamp) index — no per-query
  // window function over the full jsonb attribute set. Because value_delta is
  // computed against the row's TRUE predecessor (in or out of this window), a
  // stream that started before :start is no longer overcounted at the boundary
  // the way the old in-window-only LAG was (see V11 migration comment).
  // Rows are sparse — a (bucket, type) pair only appears when a stream advanced
  // there — and the service fills gaps with zero.
  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        attributes ->> :tokenTypeAttribute AS token_type,
        SUM(value_delta)::bigint AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND attributes ->> :tokenTypeAttribute IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
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
  // drops all but the largest. value_delta (V11) is the reset-aware per-row
  // increment for that full-attribute stream, precomputed once at ingest (see
  // recomputeValueDeltas): on a reset — the same attribute set reused by a fresh
  // run that starts low — the new level is counted in full so increments never
  // go negative. Summing value_delta per stream recovers its final value
  // (monotonic case) and the sum of segment peaks (reset case); summing across a
  // session's streams gives its window total. This matches
  // aggregateCostCurrentAndPriorTotals / aggregateTokensByModel exactly, so the
  // page reconciles.
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
        SELECT attributes ->> 'session.id' AS session_id, SUM(value_delta) AS cost_usd
        FROM metric_points
        WHERE metric_name = :costMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
      ),
      active_per_session AS (
        SELECT attributes ->> 'session.id' AS session_id, SUM(value_delta) AS active_time_seconds
        FROM metric_points
        WHERE metric_name = :activeTimeMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
      ),
      token_per_session AS (
        SELECT attributes ->> 'session.id' AS session_id, SUM(value_delta)::bigint AS tokens
        FROM metric_points
        WHERE metric_name = :tokenMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
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
  // Per-session cost = SUM of value_delta (reset-aware per-stream increments,
  // full attribute identity, precomputed at ingest), matching the semantics of
  // aggregateSessionSummaries (see that query for why a plain
  // MAX-per-(session,model,query_source) under-counts).
  //
  // B4 perf: the current-period and prior-period totals used to be two separate
  // detoasting passes over metric_points (each ~71ms on ~197k cost.usage rows).
  // FILTER lets one pass over the combined [:priorFrom, :to] range compute both
  // sums. The two FILTER windows are deliberately half-open on their shared edge
  // — current = [:from, :to], prior = [:priorFrom, :from) — so a point landing
  // exactly on :from counts in the current total only, never in both (the two
  // windows used to both include timestamp = :from, double-counting that
  // boundary point).
  @Query(value = """
      SELECT
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::double precision
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::double precision
          AS prior_total
      FROM metric_points
      WHERE metric_name = :metricName
        AND attributes ->> 'session.id' IS NOT NULL
        AND value_double IS NOT NULL
        AND timestamp >= :priorFrom
        AND timestamp <= :to
      """, nativeQuery = true)
  List<Object[]> aggregateCostCurrentAndPriorTotals(
      @Param("metricName") String metricName,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom);

  // B4 perf: the window grand total, the 14-bucket trend, and the per-model
  // breakdown used to be three more separate passes over the same [:from, :to]
  // cost.usage rows (the former aggregateCostTotal(from, to) / aggregateCostTrend
  // / aggregateCostByModel). GROUPING SETS ((), (bucket), (model)) computes all
  // three from ONE scan: Postgres reads and detoasts each row's attributes once,
  // then fans the already-materialized (bucket, model, value_delta) tuple out
  // into the three grouping combinations. Each grouping set is still a plain
  // SUM(value_delta) over the identical predicate, so the totals reconcile
  // exactly with the pre-merge queries.
  //
  // row_type discriminates which grouping set produced a row via GROUPING(), not
  // via nullability, because the inner COALESCE keeps "model" non-null even on
  // the grand-total and bucket rows. Bucket rows sort ascending (matching the old
  // aggregateCostTrend) and model rows sort by spend descending (matching the
  // old aggregateCostByModel), so the service's list-order-dependent colorIndex
  // assignment for the model breakdown is unaffected by the merge.
  @Query(value = """
      SELECT
        CASE
          WHEN GROUPING(model) = 0 THEN 'model'
          WHEN GROUPING(bucket) = 0 THEN 'bucket'
          ELSE 'total'
        END AS row_type,
        bucket,
        model,
        COALESCE(SUM(value_delta), 0)::double precision AS amount
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :from) AS bucket,
          COALESCE(attributes ->> 'model', 'unknown')                      AS model,
          value_delta
        FROM metric_points
        WHERE metric_name = :metricName
          AND attributes ->> 'session.id' IS NOT NULL
          AND value_double IS NOT NULL
          AND timestamp >= :from
          AND timestamp <= :to
      ) AS cost_rows
      GROUP BY GROUPING SETS ((), (bucket), (model))
      ORDER BY
        CASE WHEN GROUPING(model) = 0 THEN 2 WHEN GROUPING(bucket) = 0 THEN 1 ELSE 0 END,
        CASE WHEN GROUPING(bucket) = 0 THEN bucket END ASC,
        CASE WHEN GROUPING(model) = 0 THEN SUM(value_delta) END DESC
      """, nativeQuery = true)
  List<Object[]> aggregateCostBreakdown(
      @Param("metricName") String metricName,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("bucketSeconds") long bucketSeconds);

  // Total tokens in the window (for costPer1k denominator). token.usage is a
  // cumulative counter, so we SUM value_delta (reset-aware per-stream increments,
  // full attribute identity) rather than the raw value columns — a plain SUM over
  // value_long/value_double would re-add every re-emitted running total. Matches
  // the headline total from the timeseries.
  @Query(value = """
      SELECT COALESCE(SUM(value_delta), 0)::bigint AS total_tokens
      FROM metric_points
      WHERE metric_name = :metricName
        AND timestamp >= :start
        AND timestamp <= :end
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
  // session (model x type x query_source x agent.name) — so a plain SUM over the
  // raw value columns would re-add each running total. value_delta (V11) is
  // already each full-attribute stream's reset-aware per-row increment,
  // precomputed at ingest (see aggregateTokensByModel / recomputeValueDeltas), so
  // the innermost query only needs to SUM it per (session, model); the outer
  // query sums per session and derives the time-column index (0..23) from the
  // session's last emission. One exemplar per session keeps trace ids distinct.
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
            attributes ->> :sessionIdAttribute AS session_id,
            attributes ->> :modelAttribute     AS model,
            SUM(value_delta)                   AS model_tokens,
            MAX(timestamp)                     AS last_timestamp
          FROM metric_points
          WHERE metric_name = :metricName
            AND timestamp >= :start
            AND timestamp <= :end
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
  // agent.name within a session) — so a plain SUM over the raw value columns
  // would re-add each running total. value_delta (V11) is already each
  // full-attribute stream's reset-aware per-row increment, precomputed at ingest
  // (recomputeValueDeltas counts a counter reset as the new low value, never
  // negative), so this is a plain SUM per model. The increments telescope to each
  // stream's final value, so this reconciles exactly with the headline total
  // summed from the timeseries.
  @Query(value = """
      SELECT attributes ->> :modelAttribute AS model, SUM(value_delta)::bigint AS tokens
      FROM metric_points
      WHERE metric_name = :metricName
        AND timestamp >= :start AND timestamp <= :end
        AND attributes ->> :modelAttribute IS NOT NULL
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
        SELECT attributes ->> 'session.id' AS session_id, SUM(value_delta) AS cost_usd
        FROM metric_points
        WHERE metric_name = :costMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
      ),
      active_per_session AS (
        SELECT attributes ->> 'session.id' AS session_id, SUM(value_delta) AS active_time_seconds
        FROM metric_points
        WHERE metric_name = :activeTimeMetric
          AND attributes ->> 'session.id' IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
        GROUP BY 1
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
  // increment used for tokens/cost (see aggregateTokensByModel): value_delta
  // (V11) is already each full-attribute stream's per-row increment vs. its true
  // previous emission, precomputed at ingest — counting the new level on a reset
  // so increments never go negative — so these are now plain SUM(value_delta)
  // aggregations. Telescoping recovers each stream's real contribution, so the
  // window total, the per-bucket trend, and the per-split breakdown all
  // reconcile. value_double carries every claude_code.* metric.

  // Window total for one metric.
  @Query(value = """
      SELECT COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_double IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTotal(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end);

  // Per-bucket trend, each row's precomputed value_delta binned by its timestamp.
  @Query(value = """
      SELECT
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_double IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket
      ORDER BY bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrend(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Per-split-value total, grouped by one attribute key.
  @Query(value = """
      SELECT attributes ->> :splitAttribute AS label, COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_double IS NOT NULL
        AND timestamp >= :start
        AND timestamp <= :end
        AND attributes ->> :splitAttribute IS NOT NULL
      GROUP BY label
      ORDER BY total DESC
      """, nativeQuery = true)
  List<Object[]> aggregateMetricBySplit(
      @Param("metricName") String metricName,
      @Param("splitAttribute") String splitAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end);
}
