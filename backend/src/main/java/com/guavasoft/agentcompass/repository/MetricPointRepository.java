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
  // compare without JSON quoting (status=200, not status="200"); the filter strings
  // reaching this query are the grid's own primitive key=value pairs.
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

  // Per-(bucket, type) token totals, per-model token totals, and the window's
  // grand token total for the configured token-usage metric — in ONE scan of
  // metric_points instead of three. date_bin aligns buckets to :start so the
  // first bucket starts exactly at the window's lower bound.
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
  // SUM(value_delta), servable by the existing (metric_name, timestamp) index —
  // no per-query window function over the full jsonb attribute set. Because
  // value_delta is computed against the row's TRUE predecessor (in or out of
  // this window), a stream that started before :start is no longer overcounted
  // at the boundary the way the old in-window-only LAG was (see V11 migration
  // comment).
  //
  // This merges the former aggregateTokenUsageTimeseriesInRange (GROUP BY
  // bucket, token_type; WHERE token_type IS NOT NULL), aggregateTokensByModel
  // (GROUP BY model; WHERE model IS NOT NULL), and aggregateTotalTokens (no
  // GROUP BY, no filter) the same way aggregateCostBreakdown merged the
  // analogous three cost.usage queries: GROUPING SETS computes all three from
  // one pass, so Postgres reads and detoasts each row's attributes once instead
  // of three times. GROUPING SETS can't apply a different WHERE per grouping
  // combination, so the filtering those two NOT NULL clauses did in SQL is done
  // in Java instead: the service drops a 'bucket' row with a null token_type and
  // a 'model' row with a null model, exactly reproducing the old WHERE-clause
  // exclusions, while the single 'total' row is kept unconditionally, matching
  // aggregateTotalTokens' unfiltered semantics.
  //
  // value_delta IS DISTINCT FROM 0 is the same performance filter V32/V33 document
  // (see aggregateMetricsTotalsCombined for the full argument): it cannot change a
  // SUM, and it takes this scan off the ghost rows that are 99.45% of this table.
  // It made GET /api/sessions/token-usage 12.78 s -> 0.06 s on the live database.
  // One consequence specific to GROUPING SETS: a (bucket, token_type) pair or a
  // model whose every row in the window is a ghost now produces no row at all where
  // it used to produce one summing to 0. That is safe because these two grouping
  // sets were ALREADY sparse by contract -- a pair or model only appeared when a
  // stream advanced there, and the service zero-fills the gaps (see the paragraph
  // below) -- so an all-ghost group was indistinguishable from an absent one to
  // every caller. The 'total' grouping set always returns exactly one row either
  // way, since GROUPING SETS ((), ...) emits the grand total even over no rows.
  //
  // row_type discriminates which grouping set produced a row via GROUPING(),
  // same idiom as aggregateCostBreakdown. Bucket rows sort by (bucket,
  // token_type) ascending (matching the old aggregateTokenUsageTimeseriesInRange
  // order — buckets must arrive chronologically for the trend chart); model rows
  // sort by tokens descending (matching the old aggregateTokensByModel, so the
  // service's list-order-dependent colorIndex assignment is unaffected); the
  // lone total row's position doesn't matter. Rows are sparse for the bucket/
  // model groupings — a (bucket, type) pair or a model only appears when a
  // stream advanced there — and the service fills gaps with zero; the total
  // grouping set always returns exactly one row (COALESCE guards a NULL SUM
  // when the window has no token data at all).
  // The four FILTER columns below are only meaningful on the 'model' row (the
  // per-model breakdown the Tokens page's "Tokens & cost by model" card
  // renders); they compute to a same-kind-only sum on 'bucket' rows (already
  // available unfiltered as token_type) and to the window grand total per kind
  // on the lone 'total' row, both unused by any caller today. They ride the
  // same GROUPING SETS pass rather than a second query, the same reasoning as
  // aggregateSessionSummaries' identically-shaped token_per_session CTE.
  @Query(value = """
      SELECT
        CASE
          WHEN GROUPING(model) = 0 THEN 'model'
          WHEN GROUPING(bucket) = 0 THEN 'bucket'
          ELSE 'total'
        END AS row_type,
        bucket,
        token_type,
        model,
        COALESCE(SUM(value_delta), 0)::bigint AS total,
        COALESCE(SUM(value_delta) FILTER (WHERE token_type = :inputTokenType), 0)::bigint AS input_tokens,
        COALESCE(SUM(value_delta) FILTER (WHERE token_type = :outputTokenType), 0)::bigint AS output_tokens,
        COALESCE(SUM(value_delta) FILTER (WHERE token_type = :cacheCreationTokenType), 0)::bigint
          AS cache_creation_tokens,
        COALESCE(SUM(value_delta) FILTER (WHERE token_type = :cacheReadTokenType), 0)::bigint AS cache_read_tokens
      FROM (
        SELECT
          date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
          attributes ->> :tokenTypeAttribute                                 AS token_type,
          attributes ->> :modelAttribute                                     AS model,
          value_delta
        FROM metric_points
        WHERE metric_name = :metricName
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :start
          AND timestamp <= :end
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      ) AS token_rows
      GROUP BY GROUPING SETS ((bucket, token_type), (model), ())
      ORDER BY
        CASE WHEN GROUPING(model) = 0 THEN 2 WHEN GROUPING(bucket) = 0 THEN 1 ELSE 0 END,
        CASE WHEN GROUPING(bucket) = 0 THEN bucket END ASC,
        CASE WHEN GROUPING(bucket) = 0 THEN token_type END ASC,
        CASE WHEN GROUPING(model) = 0 THEN SUM(value_delta) END DESC
      """, nativeQuery = true)
  List<Object[]> aggregateTokenUsageBreakdown(
      @Param("metricName") String metricName,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("modelAttribute") String modelAttribute,
      @Param("inputTokenType") String inputTokenType,
      @Param("outputTokenType") String outputTokenType,
      @Param("cacheCreationTokenType") String cacheCreationTokenType,
      @Param("cacheReadTokenType") String cacheReadTokenType,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // Per-session cost and active-time totals, backend-sorted and
  // backend-paginated.
  //
  // session_id below is the stored generated column added by V18 (attributes ->>
  // 'session.id'), not a raw jsonb extraction -- see that migration for why: this
  // query's window-scoped CTEs need session_id alongside a (metric_name, timestamp)
  // filter, and only a real column lets idx_metric_points_name_ts's INCLUDE list
  // (also added by V18) serve them as index-only scans instead of a heap visit per
  // matched row.
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
  // aggregateCostCurrentAndPriorTotals / aggregateTokenUsageBreakdown exactly, so
  // the page reconciles.
  //
  // Session start/end timestamps are the window of cost/active-time emissions
  // carrying this session id. Wall-clock duration is the difference in whole
  // seconds. Sessions missing one metric still appear: LEFT JOIN keeps cost-only
  // or active-time-only sessions, COALESCE turns the missing side into 0.
  //
  // Cost and active time are WHOLE-SESSION totals, not window totals. session_window
  // (window-bounded) decides WHICH sessions the page lists; cost_per_session and
  // active_per_session then join
  // back to that id set with NO timestamp predicate, so a session that began before
  // the window still reports its full spend and full active time instead of the
  // sliver that happens to fall inside the range. The two must move together: the
  // grid derives $/active min as cost / active time client-side, so mixing a
  // whole-session numerator with a windowed denominator would overstate burn for
  // exactly the sessions that straddle the window edge. Tokens and the
  // log-record-derived tool call / denial / prompt counts in
  // LogRecordRepository.aggregateSessionCounts ARE still window-scoped, as are the
  // KPI cards -- so the "Median cost/session" card is a window figure and will not
  // equal the median of the Cost column.
  //
  // MEMBERSHIP REQUIRES A NON-ZERO DELTA, and this is load-bearing rather than a
  // micro-optimisation. Claude Code's exporter never retires a metric stream: every
  // session it has ever hosted keeps re-emitting its cumulative cost/active-time
  // counters once a minute forever, at an unchanged value. Measured on the live
  // database, 98.9% of all cost/active-time points are such zero-delta re-exports,
  // and a session that did its last real work two days ago was still emitting them
  // in the current minute. Selecting on "has a point in the window" therefore listed
  // every historical session on every window, each showing $0.00 -- the rollups are
  // SUM(value_delta) and so were already correct -- with a start time clipped to the
  // window edge and a last-activity of "now", which under the default endTimestamp
  // sort floated the dead sessions above the live one. Activity means an increment.
  //
  // session_span then supplies the two timestamps, and it is deliberately NOT
  // window-bounded -- it joins back to the id set the same way the cost and active
  // time rollups do, so the row describes the whole session:
  //   * first_seen is MIN(start_timestamp): for a cumulative stream that is when the
  //     stream itself opened, which beats MIN(timestamp) by one export interval
  //     (~45s on real data) and, unlike a windowed minimum, does not report a
  //     session resumed inside the window as having started inside it. This is what
  //     the grid's "Started" column claims to show and what its tooltip promises is
  //     distinct from "Last activity".
  //   * last_seen is the newest timestamp carrying an increment, so an idle session's
  //     heartbeats cannot pass for activity. It cannot come back NULL: session_window
  //     already guarantees at least one non-zero-delta row in the same metric set.
  // wall_seconds spans the two and is therefore whole-session as well.
  //
  // value_delta IS DISTINCT FROM 0 rather than <> 0 because 16 pre-V11 rows carry a
  // NULL delta; treating "increment unknown" as activity keeps them visible instead
  // of silently dropping a session.
  //
  // The unbounded join is why the id set comes first: without it Postgres would
  // aggregate every cost/active-time row ever ingested. session_span, cost_per_session,
  // and active_per_session used to be three separate CTEs that each rejoined
  // session_window to metric_points for the same metric_name IN (:costMetric,
  // :activeTimeMetric) row set, which meant scanning that ~800K-row set three times.
  // Merging them into one FILTER-qualified pass measured 2x faster end to end
  // (2.57s -> 1.24s on a 7-day window, 4.5M-row table) with identical output.
  //
  // That merged CTE is now a LATERAL, and so are the token and meta lookups, because
  // the merge alone did not fix the shape of the plan. As a plain join, Postgres
  // still would NOT choose a nested loop over idx_metric_points_session_id_name_ts
  // (V13): session_window's row estimate is a flat ~199 regardless of window, so at
  // this table's size the planner preferred a hash join against a sequential scan of
  // the full cost+active-time history -- 843,918 rows and ~3.9 GB of heap read to
  // serve the ~50 sessions the window actually selects. token_per_session had the
  // same defect independently (a parallel seq scan aggregating EVERY session in the
  // window when only the page's sessions are ever read), and session_meta sorted 84K
  // rows to DISTINCT ON its way down to 68. LATERAL removes the planner's choice:
  // each subquery is correlated on w.session_id, so all three become index-driven
  // per-session lookups. Measured on the live database (7-day window, warm cache):
  // 1450 ms -> 730 ms, no sequential scans, 500K+ buffers -> 122K on the totals join.
  // As a side effect the plan's cost estimate drops below jit_inline_above_cost, so
  // the query stops paying ~575 ms of JIT compilation that never earned it back.
  //
  // Row-for-row identical output, with one caveat: SUM(double precision) now
  // accumulates in a different order, so cost_usd and active_time_seconds can differ
  // in the last digits (5.7279469 vs 5.727946899999999). Same value to well beyond
  // the cent and the minute the UI renders, but exact-equality assertions on those
  // two columns will not hold across this change.
  //
  // The page size is clamped by PageBounds.
  //
  // The [startTimestamp, endTimestamp] bounds use the NULL-or-compare pattern, so
  // the ?minutes= form (start only) and the ?startTimestamp=&endTimestamp= form
  // (both) flow through one query. :sortColumn is one of a service-whitelisted
  // token set ('cost', 'active', 'wall', 'tokens', 'cacheEfficiency',
  // 'costPerMinute', 'started', 'ended') and
  // :sortDirection is 'asc' or 'desc' — both arrive normalized, never raw user
  // input, so the CASE-based ORDER BY cannot be turned into SQL injection. The
  // session_id tiebreaker makes paging deterministic across requests, and
  // COUNT(*) OVER() carries the total session count alongside the page so a
  // second count round-trip is unnecessary.
  //
  // The tokens lateral returns the four-way type breakdown directly (one
  // SUM(value_delta) FILTER per kind) rather than a single "tokens" total: the
  // service sums these four columns to derive SessionSummary.tokens, so the
  // "breakdown sums to tokens" invariant holds by construction instead of relying
  // on two independently-computed totals staying in sync. :tokenTypeAttribute and
  // the four :xxxTokenType params are sourced from TuningProperties /
  // MetricService's token-type constants rather than hardcoded, matching every
  // other type-attribute lookup in this class. Rows whose type doesn't match any
  // of the four known kinds (unexpected/future token types) are silently excluded
  // from the breakdown and therefore from the row's total -- same trade-off the
  // dashboard's TokenUsageSummary breakdown already makes.
  // The totals and tokens laterals both carry "value_delta IS DISTINCT FROM 0", which is what
  // lets them ride V32's partial index -- without it this query timed out on the live database
  // (15,424 ms against the pooled 15s statement_timeout; 18.9 ms after). See V32's own header
  // for the full measurements and for why a filter without the index bought almost nothing.
  // Three notes on why the filter is safe, because it is NOT obviously a no-op:
  //
  //   1. Every SUM is unaffected by construction -- a zero-delta row contributes exactly 0 --
  //      and last_seen already carried this exact filter as its own FILTER clause. Verified on
  //      live data: the four token sums come back identical (e.g. input 983,031 either way).
  //   2. first_seen is the one output that reads every row rather than summing, and it is the
  //      reason the filter needs an argument rather than an assertion. MIN(COALESCE(
  //      start_timestamp, timestamp)) is a fact about when the session's STREAM opened, and a
  //      ghost row belongs to the same stream and carries the same start_timestamp, so dropping
  //      ghosts cannot move it -- unless a session has NO non-zero row at all, in which case
  //      first_seen would go null. Such sessions exist (13 on the live database) but can never
  //      reach this lateral: session_window has always required a non-zero row to admit a
  //      session at all. Measured across all 324 sessions that do reach it, first_seen is
  //      identical to the unfiltered value, drift 0.000000s.
  //   3. The cost/active sums can differ in their last bit or two, and that is expected rather
  //      than a defect. double precision addition is not associative, and the partial index
  //      returns rows in index order where the old bitmap heap scan returned them in physical
  //      order, so the same addends accumulate differently. Measured over the same window: max
  //      absolute difference 2.8e-14 USD, max relative 3.0e-16 (about one ULP), and the
  //      window's own total is unchanged at $1390.4079862500005. Do not "fix" this by summing
  //      as numeric -- that changes the column types the service and DTOs read.
  @Query(value = """
      WITH session_window AS (
        SELECT
          session_id
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      )
      SELECT
        w.session_id,
        COALESCE(totals.cost_usd, 0)::double precision            AS cost_usd,
        COALESCE(totals.active_time_seconds, 0)::double precision AS active_time_seconds,
        totals.first_seen,
        totals.last_seen,
        EXTRACT(EPOCH FROM (totals.last_seen - totals.first_seen))::bigint AS wall_seconds,
        COALESCE(tokens.input_tokens, 0)::bigint                  AS input_tokens,
        COALESCE(tokens.output_tokens, 0)::bigint                 AS output_tokens,
        COALESCE(tokens.cache_creation_tokens, 0)::bigint         AS cache_creation_tokens,
        COALESCE(tokens.cache_read_tokens, 0)::bigint             AS cache_read_tokens,
        meta.terminal_type,
        meta.start_type,
        COUNT(*) OVER()::bigint                                   AS total_count
      FROM session_window w
      CROSS JOIN LATERAL (
        SELECT
          MIN(COALESCE(p.start_timestamp, p.timestamp)) AS first_seen,
          MAX(p.timestamp) FILTER (WHERE p.value_delta IS DISTINCT FROM 0) AS last_seen,
          SUM(p.value_delta)
            FILTER (WHERE p.metric_name = :costMetric AND p.value_double IS NOT NULL) AS cost_usd,
          SUM(p.value_delta)
            FILTER (WHERE p.metric_name = :activeTimeMetric AND p.value_double IS NOT NULL)
            AS active_time_seconds
        FROM metric_points p
        WHERE p.session_id = w.session_id
          AND p.metric_name IN (:costMetric, :activeTimeMetric)
          AND p.value_delta IS DISTINCT FROM 0
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
      ) totals
      LEFT JOIN LATERAL (
        SELECT
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :inputTokenType), 0)::bigint
            AS input_tokens,
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :outputTokenType), 0)::bigint
            AS output_tokens,
          COALESCE(SUM(value_delta)
            FILTER (WHERE attributes ->> :tokenTypeAttribute = :cacheCreationTokenType), 0)::bigint
            AS cache_creation_tokens,
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :cacheReadTokenType), 0)::bigint
            AS cache_read_tokens
        FROM metric_points tp
        WHERE tp.session_id = w.session_id
          AND tp.metric_name = :tokenMetric
          AND tp.value_delta IS DISTINCT FROM 0
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR tp.timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR tp.timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR tp.repository_url = :repositoryUrl)
      ) tokens ON true
      LEFT JOIN LATERAL (
        SELECT
          mp.attributes ->> 'start_type'    AS start_type,
          mp.attributes ->> 'terminal.type' AS terminal_type
        FROM metric_points mp
        WHERE mp.session_id = w.session_id
          AND mp.metric_name = :sessionCountMetric
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR mp.timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR mp.timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR mp.repository_url = :repositoryUrl)
        ORDER BY COALESCE(mp.start_timestamp, mp.timestamp)
        LIMIT 1
      ) meta ON true
      ORDER BY
        CASE WHEN :sortDirection = 'asc' THEN
          CASE :sortColumn
            WHEN 'cost'   THEN COALESCE(totals.cost_usd, 0)
            WHEN 'active' THEN COALESCE(totals.active_time_seconds, 0)
            WHEN 'wall'   THEN EXTRACT(EPOCH FROM (totals.last_seen - totals.first_seen))
            WHEN 'tokens' THEN COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.output_tokens, 0)
              + COALESCE(tokens.cache_creation_tokens, 0) + COALESCE(tokens.cache_read_tokens, 0)
            WHEN 'cacheEfficiency' THEN CASE
              WHEN COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.cache_creation_tokens, 0)
                + COALESCE(tokens.cache_read_tokens, 0) > 0
              THEN COALESCE(tokens.cache_read_tokens, 0)::double precision
                / (COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.cache_creation_tokens, 0)
                  + COALESCE(tokens.cache_read_tokens, 0)) END
            WHEN 'costPerMinute' THEN CASE WHEN COALESCE(totals.active_time_seconds, 0) > 0
              THEN COALESCE(totals.cost_usd, 0) / totals.active_time_seconds * 60 END
          END
        END ASC NULLS LAST,
        CASE WHEN :sortDirection = 'desc' THEN
          CASE :sortColumn
            WHEN 'cost'   THEN COALESCE(totals.cost_usd, 0)
            WHEN 'active' THEN COALESCE(totals.active_time_seconds, 0)
            WHEN 'wall'   THEN EXTRACT(EPOCH FROM (totals.last_seen - totals.first_seen))
            WHEN 'tokens' THEN COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.output_tokens, 0)
              + COALESCE(tokens.cache_creation_tokens, 0) + COALESCE(tokens.cache_read_tokens, 0)
            WHEN 'cacheEfficiency' THEN CASE
              WHEN COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.cache_creation_tokens, 0)
                + COALESCE(tokens.cache_read_tokens, 0) > 0
              THEN COALESCE(tokens.cache_read_tokens, 0)::double precision
                / (COALESCE(tokens.input_tokens, 0) + COALESCE(tokens.cache_creation_tokens, 0)
                  + COALESCE(tokens.cache_read_tokens, 0)) END
            WHEN 'costPerMinute' THEN CASE WHEN COALESCE(totals.active_time_seconds, 0) > 0
              THEN COALESCE(totals.cost_usd, 0) / totals.active_time_seconds * 60 END
          END
        END DESC NULLS LAST,
        CASE WHEN :sortColumn = 'started' AND :sortDirection = 'asc'  THEN totals.first_seen END ASC NULLS LAST,
        CASE WHEN :sortColumn = 'started' AND :sortDirection = 'desc' THEN totals.first_seen END DESC NULLS LAST,
        CASE WHEN :sortColumn = 'ended' AND :sortDirection = 'asc'  THEN totals.last_seen END ASC NULLS LAST,
        CASE WHEN :sortColumn = 'ended' AND :sortDirection = 'desc' THEN totals.last_seen END DESC NULLS LAST,
        w.session_id ASC
      LIMIT :pageSize OFFSET :pageOffset
      """, nativeQuery = true)
  List<Object[]> aggregateSessionSummaries(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("sessionCountMetric") String sessionCountMetric,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("inputTokenType") String inputTokenType,
      @Param("outputTokenType") String outputTokenType,
      @Param("cacheCreationTokenType") String cacheCreationTokenType,
      @Param("cacheReadTokenType") String cacheReadTokenType,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp,
      @Param("sortColumn") String sortColumn,
      @Param("sortDirection") String sortDirection,
      @Param("pageSize") int pageSize,
      @Param("pageOffset") int pageOffset,
      @Param("repositoryUrl") String repositoryUrl);

  // Sessions ranked by WORST cache efficiency — the Tokens page's ranked list.
  //
  // cacheEfficiency is cacheRead / (input + cacheCreation + cacheRead): the share
  // of a session's input-side tokens served from the prompt cache. Output tokens
  // are generated rather than sent, so they stay out of the ratio (they are still
  // reported in total_tokens as a scale hint). The denominator is also returned
  // decomposed — input_tokens / cache_creation_tokens alongside cache_read_tokens,
  // the three always summing back to input_side_tokens — so the Tokens page's
  // session detail can draw the split without a second query. This is the SAME expression the
  // 'cacheEfficiency' sort column in aggregateSessionSummaries orders by and the
  // same one the frontend's shared cacheEfficiencyRatio helper computes for the
  // Sessions grid column — the three must move together or the ranking, the
  // sortable column, and the rendered percentages stop agreeing.
  //
  // :minimumInputSideTokens is the noise floor (TuningProperties
  // cacheEfficiencyMinimumInputTokens): a session that made two small calls can
  // sit at 0% without anything being wrong and would crowd out the large sessions
  // where a poor ratio actually costs money. Because the floor is applied to the
  // ratio's own denominator and the service clamps it to at least 1, it is also
  // what makes the division below unconditionally safe — there is no zero-guard
  // CASE here because no surviving row can have a zero denominator.
  //
  // Structural resume-heartbeat exclusion: session_window keys off the cost and
  // active-time metrics, which resume streams never emit (they carry only
  // session.count), so heartbeat-only sessions are absent from the id set before
  // the token join happens. The INNER JOIN to token_per_session then drops any
  // session with no token points at all. Neither is a filter that can be dropped
  // by accident — see MetricServiceCacheEfficiencyIT, which pins the behaviour.
  //
  // Cost is whole-session (joined back with no timestamp predicate), matching the
  // Sessions grid's Cost column; tokens stay window-scoped like every other token
  // rollup. Same deliberate split documented on aggregateSessionSummaries.
  //
  // session_window here deliberately does NOT carry the value_delta membership test
  // that aggregateSessionSummaries and aggregateSessionKpis use against the
  // exporter's zero-delta re-emissions, because the token floor already excludes
  // them: a session that only heartbeats accrues no token increments, so its
  // input_side_tokens is 0 and it fails the >= :minimumInputSideTokens predicate
  // (verified against live data -- all 11 ghost sessions in a sample window summed
  // to exactly 0). Adding the filter would be redundant here and not merely
  // redundant: cost and tokens are separate streams, so it could also drop a session
  // that genuinely moved tokens in the window without moving cost.
  //
  // last_seen (surfaced to the Tokens page's rank card as endTimestamp, for a
  // human-readable "last activity" instead of a bare session id): MAX(timestamp)
  // FILTER (WHERE value_delta IS DISTINCT FROM 0). Unlike session_window's own
  // membership test, this filter is NOT optional here -- Claude Code's exporter
  // keeps re-emitting a finished session's cumulative cost/active-time counters
  // once a minute forever at an unchanged value, so an unfiltered MAX(timestamp)
  // would read every session as active in the current minute regardless of when
  // it actually last did anything. Can be null when every one of a session's
  // cost/active-time points in session_window is such a zero-delta re-export.
  //
  // last_seen and cost_usd are computed together in one session_totals CTE rather
  // than two (formerly session_span + cost_per_session), mirroring
  // aggregateSessionSummaries' totals lateral above: each separately
  // rejoined session_window to metric_points for an overlapping metric_name IN
  // (:costMetric, :activeTimeMetric) row set, and Postgres does not turn that
  // into a cheap per-session nested loop over idx_metric_points_session_id_name_ts
  // -- it prefers a hash join against a sequential scan of the full cost+active-time
  // metric history (~840K rows on the live database), so two CTEs meant scanning
  // that set twice. Merging measured 1994ms -> 1417ms end to end on a 7-day
  // window against the live 4.5M-row table (EXPLAIN ANALYZE), same output.
  @Query(value = """
      WITH session_window AS (
        SELECT session_id
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      ),
      session_totals AS (
        SELECT
          w.session_id,
          MAX(p.timestamp) FILTER (WHERE p.value_delta IS DISTINCT FROM 0) AS last_seen,
          SUM(p.value_delta)
            FILTER (WHERE p.metric_name = :costMetric AND p.value_double IS NOT NULL) AS cost_usd
        -- NOTE: no apostrophes in this comment -- Spring Data tracks quote state without
        -- skipping SQL line comments, so one here breaks repository creation at startup.
        -- p.value_delta IS DISTINCT FROM 0 lets this otherwise unbounded per-session join
        -- ride the V32 partial index. Both outputs are unaffected: last_seen already
        -- carries the identical test, and cost_usd is a SUM, to which a ghost row
        -- contributes exactly 0. Unlike the session_span joins elsewhere in this class
        -- there is no MIN(start_timestamp) here, so the first_seen argument those need
        -- does not even arise. A session whose cost rows are all ghosts now produces no
        -- row here rather than one holding 0.0 and a NULL last_seen; the outer LEFT JOIN
        -- plus COALESCE(st.cost_usd, 0) turns that back into the same 0.0 and NULL.
        FROM session_window w
        JOIN metric_points p ON p.session_id = w.session_id
        WHERE p.metric_name IN (:costMetric, :activeTimeMetric)
          AND p.value_delta IS DISTINCT FROM 0
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
        GROUP BY 1
      ),
      token_per_session AS (
        SELECT
          session_id,
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :inputTokenType), 0)::bigint
            AS input_tokens,
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :outputTokenType), 0)::bigint
            AS output_tokens,
          COALESCE(SUM(value_delta)
            FILTER (WHERE attributes ->> :tokenTypeAttribute = :cacheCreationTokenType), 0)::bigint
            AS cache_creation_tokens,
          COALESCE(SUM(value_delta) FILTER (WHERE attributes ->> :tokenTypeAttribute = :cacheReadTokenType), 0)::bigint
            AS cache_read_tokens
        FROM metric_points
        WHERE metric_name = :tokenMetric
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      )
      SELECT
        w.session_id,
        t.cache_read_tokens::double precision
          / (t.input_tokens + t.cache_creation_tokens + t.cache_read_tokens) AS cache_efficiency,
        t.cache_read_tokens                                                  AS cache_read_tokens,
        (t.input_tokens + t.cache_creation_tokens + t.cache_read_tokens)::bigint AS input_side_tokens,
        t.input_tokens                                                       AS input_tokens,
        t.cache_creation_tokens                                              AS cache_creation_tokens,
        t.output_tokens                                                      AS output_tokens,
        COALESCE(st.cost_usd, 0)::double precision                           AS cost_usd,
        st.last_seen                                                         AS last_seen
      FROM session_window w
      JOIN token_per_session t      ON t.session_id = w.session_id
      LEFT JOIN session_totals st   ON st.session_id = w.session_id
      WHERE (t.input_tokens + t.cache_creation_tokens + t.cache_read_tokens) >= :minimumInputSideTokens
      ORDER BY cache_efficiency ASC, input_side_tokens DESC, w.session_id ASC
      LIMIT :resultLimit
      """, nativeQuery = true)
  List<Object[]> aggregateWorstCacheEfficiencySessions(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("inputTokenType") String inputTokenType,
      @Param("outputTokenType") String outputTokenType,
      @Param("cacheCreationTokenType") String cacheCreationTokenType,
      @Param("cacheReadTokenType") String cacheReadTokenType,
      @Param("startTimestamp") Instant startTimestamp,
      @Param("endTimestamp") Instant endTimestamp,
      @Param("minimumInputSideTokens") long minimumInputSideTokens,
      @Param("resultLimit") int resultLimit,
      @Param("repositoryUrl") String repositoryUrl);

  // ---------------------------------------------------------------------------
  // Prompt-timeline per-turn rollups (Sessions page GET /api/sessions/{id}/prompts)
  // ---------------------------------------------------------------------------
  //
  // Both queries below return raw, unaggregated rows for one session ordered by
  // timestamp ascending. Aggregation (bucketing into turns, then summing/grouping
  // per turn) happens in LogService by walking these rows against the session's
  // ascending prompt timestamps -- the same "sorted merge" attribution the
  // tool-events query uses -- rather than an in-SQL interval join, since the
  // turn boundaries live in log_records (user_prompt) while these rows live in
  // metric_points and the prompt list is already fetched separately.

  // Cost points for one session, oldest first, bounded to
  // [firstTurnStart, turnsEndBoundary) -- the same interval LogService buckets
  // rows into turns against, so rows outside every turn's range (before the
  // first prompt, or at/after the cap boundary on a truncated session) are
  // never fetched in the first place rather than being fetched and discarded in
  // Java. turnsEndBoundary is NULL for a non-truncated session (open-ended last
  // turn), so the CAST(... AS timestamptz) IS NULL branch keeps that case
  // unbounded above, matching turnIndexForTimestamp's semantics exactly.
  // claude_code.cost.usage is a CUMULATIVE counter re-emitted per stream (full
  // attribute identity); value_delta (V11) is already the reset-aware per-row
  // increment, so the per-turn rollup is a plain SUM(value_delta) over the rows
  // LogService buckets into each turn -- no read-time LAG or bucket-MAX,
  // matching every other cost rollup in this class. Served by
  // idx_metric_points_session_id_name_ts (V13).
  @Query(value = """
      SELECT timestamp, value_delta
      FROM metric_points
      WHERE metric_name = :metricName
        AND session_id = :sessionId
        AND value_double IS NOT NULL
        AND timestamp >= :firstTurnStart
        AND (CAST(:turnsEndBoundary AS timestamptz) IS NULL OR timestamp < :turnsEndBoundary)
      ORDER BY timestamp ASC
      """, nativeQuery = true)
  List<Object[]> findCostPointsForSession(
      @Param("metricName") String metricName,
      @Param("sessionId") String sessionId,
      @Param("firstTurnStart") Instant firstTurnStart,
      @Param("turnsEndBoundary") Instant turnsEndBoundary);

  // Token points for one session, oldest first, carrying the model AND type
  // attributes so LogService can, from this single fetch, both pick the turn's
  // dominant model (largest summed value_delta) and accumulate the turn's
  // four-way token-type breakdown. Same value_delta reset-aware semantics and
  // [firstTurnStart, turnsEndBoundary) bound as findCostPointsForSession, served
  // by the same idx_metric_points_session_id_name_ts (V13) index.
  @Query(value = """
      SELECT
        timestamp,
        attributes ->> :modelAttribute     AS model,
        attributes ->> :tokenTypeAttribute AS token_type,
        value_delta
      FROM metric_points
      WHERE metric_name = :metricName
        AND session_id = :sessionId
        AND attributes ->> :modelAttribute IS NOT NULL
        AND timestamp >= :firstTurnStart
        AND (CAST(:turnsEndBoundary AS timestamptz) IS NULL OR timestamp < :turnsEndBoundary)
      ORDER BY timestamp ASC
      """, nativeQuery = true)
  List<Object[]> findTokenPointsForSession(
      @Param("metricName") String metricName,
      @Param("sessionId") String sessionId,
      @Param("modelAttribute") String modelAttribute,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("firstTurnStart") Instant firstTurnStart,
      @Param("turnsEndBoundary") Instant turnsEndBoundary);

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
  //
  // The non-zero filter (V32/V33; see aggregateMetricsTotalsCombined for the argument)
  // matters more here than the row counts above suggest, because this query spans TWO
  // windows: it was the last unfiltered scan on the Tokens page and held
  // GET /api/sessions/token-usage at 1.55 s after the page's other two queries had
  // dropped to 20 ms and 0.4 s. Both outputs are SUMs, so the filter cannot change them.
  @Query(value = """
      SELECT
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::double precision
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::double precision
          AS prior_total
      FROM metric_points
      WHERE metric_name = :metricName
        AND session_id IS NOT NULL
        AND value_double IS NOT NULL
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      """, nativeQuery = true)
  List<Object[]> aggregateCostCurrentAndPriorTotals(
      @Param("metricName") String metricName,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom,
      @Param("repositoryUrl") String repositoryUrl);

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
  //
  // Carries the same value_delta IS DISTINCT FROM 0 performance filter, on the same
  // reasoning as aggregateTokenUsageBreakdown above -- including that its bucket and
  // model grouping sets are already sparse-by-contract, so a group losing its last
  // non-ghost row simply stops appearing rather than reporting a spurious 0. Note the
  // COALESCE(attributes ->> 'model', 'unknown') means the model grouping set never
  // emits a NULL model, so no Java-side null filtering depends on group presence here.
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
          AND session_id IS NOT NULL
          AND value_double IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :from
          AND timestamp <= :to
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
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
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // Total tokens in the window (for costPer1k denominator). token.usage is a
  // cumulative counter, so we SUM value_delta (reset-aware per-stream increments,
  // full attribute identity) rather than the raw value columns — a plain SUM over
  // value_long/value_double would re-add every re-emitted running total. Matches
  // the headline total from the timeseries.
  @Query(value = """
      SELECT COALESCE(SUM(value_delta), 0)::bigint AS total_tokens
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
      """, nativeQuery = true)
  List<Object[]> aggregateTotalTokens(
      @Param("metricName") String metricName,
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
  //
  // session_window carries the same value_delta IS DISTINCT FROM 0 membership test as
  // aggregateSessionSummaries, and for the same reason -- see the long note there on
  // the exporter's zero-delta re-emissions. Without it totalSessions counted every
  // session ever recorded, and both cost percentiles were computed over a population
  // padded out with $0.00 ghosts, dragging the median toward zero.
  @Query(value = """
      WITH cost_per_session AS (
        SELECT session_id, SUM(value_delta) AS cost_usd
        FROM metric_points
        WHERE metric_name = :costMetric
          AND session_id IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      ),
      active_per_session AS (
        SELECT session_id, SUM(value_delta) AS active_time_seconds
        FROM metric_points
        WHERE metric_name = :activeTimeMetric
          AND session_id IS NOT NULL
          AND value_double IS NOT NULL
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      ),
      session_window AS (
        SELECT
          session_id
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND (CAST(:startTimestamp AS timestamptz) IS NULL OR timestamp >= :startTimestamp)
          AND (CAST(:endTimestamp AS timestamptz) IS NULL OR timestamp <= :endTimestamp)
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
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
      @Param("endTimestamp") Instant endTimestamp,
      @Param("repositoryUrl") String repositoryUrl);

  // New-session sparkline for the Sessions-page "Total sessions" card: how many
  // sessions actually OPENED in each evenly-spaced bucket of the window. A session is
  // counted in the bucket of its stream start (MIN(start_timestamp)), so it's counted
  // at most once. Returns one row per non-empty bucket as (bucket_index 0-based,
  // new_sessions); the service fills a dense, zero-padded array, so empty buckets
  // correctly read as zero. Concrete start/end bounds are required (the trend needs a
  // fixed origin for date_bin).
  //
  // The bucket counts NO LONGER sum to aggregateSessionKpis' total_sessions, and that
  // is the intended behaviour rather than drift between two population definitions.
  // The card counts sessions that were ACTIVE in the window; the sparkline counts
  // sessions that BEGAN in it, and a session resumed after days of idling belongs in
  // the first population but not the second. The previous query conflated them by
  // bucketing on the earliest emission inside the window, which made every
  // long-running session look brand new the moment you narrowed the window -- the
  // same zero-delta re-emission artefact documented at length on
  // aggregateSessionSummaries. Expect an all-zero sparkline over a short window in
  // which real work continued on an older session; that reads correctly as "no new
  // sessions started".
  //
  // first_seen is unbounded on purpose (it joins back to the windowed id set the way
  // the summary query's rollups do) because a session's true start is a property of
  // the session, not of the window: bounding it would reintroduce the clipping this
  // change exists to remove. The window filter then applies to that true start.
  @Query(value = """
      WITH session_window AS (
        SELECT session_id
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :start
          AND timestamp <= :end
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        GROUP BY 1
      ),
      first_seen AS (
        SELECT
          w.session_id,
          MIN(COALESCE(p.start_timestamp, p.timestamp)) AS first_ts
        FROM session_window w
        JOIN metric_points p ON p.session_id = w.session_id
        WHERE p.metric_name IN (:costMetric, :activeTimeMetric)
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
        GROUP BY 1
      )
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(
            make_interval(secs => :bucketSeconds), first_ts, :start) - :start))
          / :bucketSeconds)::int      AS bucket_index,
        COUNT(*)::bigint              AS new_sessions
      FROM first_seen
      WHERE first_ts >= :start
        AND first_ts <= :end
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateNewSessionsTrend(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // ---------------------------------------------------------------------------
  // Generic metric series (Metrics page, GET /api/metrics/series)
  // ---------------------------------------------------------------------------
  //
  // Every claude_code.* metric is a CUMULATIVE counter re-emitted every minute,
  // split into concurrent streams identified by the FULL attribute set. The
  // queries below are the metric-name-parameterised form of the reset-aware
  // increment used for tokens/cost (see aggregateTokenUsageBreakdown): value_delta
  // (V11) is already each full-attribute stream's per-row increment vs. its true
  // previous emission, precomputed at ingest — counting the new level on a reset
  // so increments never go negative — so these are plain SUM(value_delta)
  // aggregations. Telescoping recovers each stream's real contribution, so the
  // window total, the per-bucket trend, and the per-split breakdown all
  // reconcile. value_double carries every claude_code.* metric.
  //
  // aggregateMetricTotals and aggregateMetricTrend are batched across EVERY
  // metric name in one query (GROUP BY metric_name) rather than run once per
  // metric. metric_points is not physically clustered by metric_name — rows
  // arrive interleaved in ingest/timestamp order — so a metric-scoped WHERE
  // clause pays close to full-table scan cost once that metric's share of the
  // table crosses roughly 5%: confirmed via EXPLAIN ANALYZE, where even a
  // low-volume metric like session.count (5% of rows) already costs 200ms+ via
  // a scattered bitmap heap scan, and any query whose planner cost crosses
  // jit_above_cost pays a further ~200ms of one-shot JIT compilation that's
  // never amortized (each query here runs exactly once per request). Before
  // batching, MetricSeriesService called the single-metric predecessors of
  // these two queries once per curated metric (current window + prior window
  // + trend) — 24 sequential near-full-table scans for the 8 curated metrics
  // alone — which measured 3.6-3.9s end to end on a 7-day window over this
  // table's 4.5M rows. Grouping by metric_name collapses that to one
  // combined-totals scan of [priorFrom, to] and one combined-trend scan of
  // [from, to].
  //
  // GROUP BY means a metric with ZERO matching rows in the scanned range is
  // simply ABSENT from the result set, unlike a per-metric query which always
  // returns exactly one (possibly zero) row. Callers must default a missing
  // metric name to zero/empty rather than treat a missing key as an error —
  // MetricSeriesQueryIntegrationTest's unseeded-metric and
  // outside-the-window-discovery tests both depend on this.
  //
  // The optional attribute filter (N ANDed key:value pairs, scoped to ONE metric) is NOT
  // a clause on the plain batched queries above/below. It lives on dedicated single-metric
  // "...Filtered" variants (totals, trend, splits, cardinality) plus, always present, on the
  // three agg trends; MetricSeriesService calls the plain batched query for every metric
  // EXCEPT the filtered one and the filtered variant for that one, then merges the rows.
  // Why it is split rather than one clause: the earlier single-query form
  //   (:filterMetricName IS NULL OR metric_name <> :filterMetricName OR NOT EXISTS (...))
  // read `attributes` -- which is not in idx_metric_points_name_ts's INCLUDE list -- for
  // every row of every metric, so with a filter active the planner fell back to a Seq Scan
  // (aggregateMetricTotals over 8 metric names and a 60-day span: 8.2 s, 1.2M buffers), and
  // because the WHERE was an OR it could never imply the partial index's predicate either.
  // Keeping the plain queries free of any filter clause is what keeps the unfiltered path the
  // byte-for-byte index-only scan it measured as, with no generic-plan risk.
  //
  // The matches ride in as ONE bound JSON string built by Jackson
  // (MetricSeriesFilter#matchesJson; "[]" when inactive), so there is no dynamic SQL and no
  // per-pair parameter count, and a value can never break out of the document:
  //   NOT EXISTS (SELECT 1
  //               FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
  //               WHERE attributes ->> (filter_match.entry ->> 'key')
  //                 IS DISTINCT FROM (filter_match.entry ->> 'value'))
  // Every value is compared as TEXT via ->>, so a numeric or boolean attribute still matches
  // its text form (status=200), which is why this is not a jsonb @> containment test (that
  // would need 200 to be a JSON number and would reject "200"). A missing attribute is NULL,
  // which IS DISTINCT FROM every value, so the row is excluded. An array rather than an object
  // so two pairs naming the same key are both enforced. The same clause on every filtered
  // variant and every agg trend is what makes the header stats, trend, split breakdown,
  // cardinality and agg trend describe one filtered population.

  // Every distinct metric name in the table, with its unit, so the Metrics page
  // can show a card for a counter nobody has curated yet (see
  // MetricSeriesService.discoveredMetricSpec).
  //
  // Deliberately NOT window-scoped. A card set that changes as the user pans the
  // window would make metrics appear and vanish; the curated ones already render
  // at zero when a window has no data, and discovered ones match that.
  //
  // This is a loose index scan, not the plain GROUP BY it looks like it should
  // be. Postgres has no skip scan, so `SELECT metric_name FROM metric_points
  // GROUP BY metric_name` reads every row: measured at 247 ms over a 30-day
  // window (parallel seq scan, 390k buffers) purely to learn seven strings. The
  // recursive form walks idx_metric_points_name_ts one key at a time -- one
  // index probe per distinct name, eight probes total -- and measures 0.245 ms,
  // independent of table size. For scale: the batched totals/trend queries
  // below cost orders of magnitude more than this either way, so this was
  // never the expensive part of the request.
  //
  // Unit is read off whichever row each probe lands on rather than aggregated,
  // because a metric name carries exactly one unit (verified: seven names, seven
  // distinct (name, unit) pairs). The empty string is a real value -- only
  // token.usage, cost.usage, and active_time.total declare a unit at all.
  @Query(value = """
      WITH RECURSIVE distinct_metrics AS (
          (SELECT metric_name, unit FROM metric_points ORDER BY metric_name LIMIT 1)
        UNION ALL
          SELECT probe.metric_name, probe.unit
          FROM distinct_metrics AS walker
          CROSS JOIN LATERAL (
            SELECT candidate.metric_name, candidate.unit
            FROM metric_points AS candidate
            WHERE candidate.metric_name > walker.metric_name
            ORDER BY candidate.metric_name
            LIMIT 1) AS probe)
      SELECT metric_name, COALESCE(unit, '') AS unit
      FROM distinct_metrics
      ORDER BY metric_name
      """, nativeQuery = true)
  List<Object[]> findDistinctMetricNames();

  // Window total (current AND prior window, via FILTER) for every metric name
  // in one scan of [priorFrom, to]. Row shape: (metric_name, current_total,
  // prior_total).
  //
  // No value_long/value_double NOT-NULL filter here: every row in this table
  // carries one or the other (verified: zero rows fail that predicate), so it
  // was pure dead weight -- and not free dead weight, since neither column is
  // in idx_metric_points_name_ts's INCLUDE list (V18), so evaluating it forced
  // a heap visit per row and lost the index-only scan that INCLUDE list exists
  // to serve. Same trap V18's comment calls out for session_id, one predicate
  // short: dropping it turns the parallel seq scan into a parallel index-only
  // scan (366ms -> 138ms measured on a 7-day window), and the cheaper plan
  // then falls under jit_inline_above_cost too, so it stops paying ~250ms of
  // JIT compilation on top.
  //
  // value_delta IS DISTINCT FROM 0 is the partial-index predicate of idx_metric_points_nonzero_name_ts
  // (V33), and it cannot change a SUM: 99.5% of rows are the exporter's zero-delta re-emissions of an
  // unchanged counter. Without it this read every one of them -- 718 ms for the 8 curated metrics over
  // a 48 h span (490k rows, all heap-fetched); with it 14 ms (2.5k rows). See "Streams are never
  // retired" in backend/CLAUDE.md, whose rule is that any SUM(value_delta) scan carries it. A metric
  // whose every row in the range is a re-emission is absent from the result, which the caller already
  // treats as zero (see the GROUP BY note above).
  @Query(value = """
      SELECT
        metric_name,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from), 0)::double precision AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp < :from), 0)::double precision  AS prior_total
      FROM metric_points
      WHERE metric_name IN (:metricNames)
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      GROUP BY metric_name
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTotals(
      @Param("metricNames") List<String> metricNames,
      @Param("priorFrom") Instant priorFrom,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("repositoryUrl") String repositoryUrl);

  // Filtered sibling of aggregateMetricTotals for the ONE metric an attribute filter targets;
  // same row shape (metric_name, current_total, prior_total), a metric with no matching rows is
  // simply absent (the caller defaults it to zero). Restricted to value_delta IS DISTINCT FROM 0
  // -- byte-for-byte the predicate of idx_metric_points_nonzero_name_ts (V32/V33) -- as a
  // top-level conjunct, so the planner can prove the partial index applies and reads only the
  // ~0.55% of rows that moved; zero-delta ghost re-exports contribute nothing to a SUM anyway.
  // The jsonb filter then runs on those few rows only. Measured on the live database for the
  // 8-metric, 60-day totals: the single-clause form seq-scanned in 8.2 s (1.2M buffers); this
  // shape is ~0.2 s (194 ms) via the partial index.
  @Query(value = """
      SELECT
        metric_name,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from), 0)::double precision AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp < :from), 0)::double precision  AS prior_total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND NOT EXISTS (
          SELECT 1
          FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
          WHERE attributes ->> (filter_match.entry ->> 'key')
            IS DISTINCT FROM (filter_match.entry ->> 'value'))
      GROUP BY metric_name
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTotalsFiltered(
      @Param("metricName") String metricName,
      @Param("priorFrom") Instant priorFrom,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // Distinct-stream cardinality for every metric name in one scan of [start, end].
  // Row shape: (metric_name, cardinality). Counts stream_id -- the stored
  // generated md5(metric_name || '|' || attributes::text) column V11 added for
  // the reset-aware delta computation -- rather than COUNT(DISTINCT attributes),
  // which is what aggregateCatalogSummary above uses and measured 2188ms for a
  // single 7-day window (see backend/CLAUDE.md's planner-statistics note): a
  // plain text/md5 comparison is far cheaper for Postgres to distinct-aggregate
  // than a jsonb equality check, and this query runs on every Metrics page load
  // rather than only behind the currently-uncalled /api/metrics/catalog.
  //
  // Counts streams with NON-ZERO activity in the window ("distinct ACTIVE label-sets"), not every
  // stream that merely emitted: the exporter never retires a stream, so ghosts re-emitting an
  // unchanged value forever inflated the figure ~4x on live data (token.usage over 24 h: 264 emitting,
  // 63 moving) and forced a scan of every ghost row -- 963 ms against 29 ms with the predicate, which
  // lets idx_metric_points_nonzero_name_ts serve it. It is also the definition
  // aggregateMetricCardinalityFiltered already used, so applying an attribute filter no longer makes
  // the figure drop for a reason unrelated to the filter.
  @Query(value = """
      SELECT
        metric_name,
        COUNT(DISTINCT stream_id)::bigint AS cardinality
      FROM metric_points
      WHERE metric_name IN (:metricNames)
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      GROUP BY metric_name
      """, nativeQuery = true)
  List<Object[]> aggregateMetricCardinality(
      @Param("metricNames") List<String> metricNames,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("repositoryUrl") String repositoryUrl);

  // Filtered sibling of aggregateMetricCardinality for the ONE metric an attribute filter targets;
  // same row shape (metric_name, cardinality). Under a filter, cardinality is the number of
  // label-sets with NON-ZERO activity in the window: the value_delta IS DISTINCT FROM 0 predicate
  // (what lets the partial index serve it, see aggregateMetricTotalsFiltered) drops the streams
  // that only re-emitted an unchanged value, so it can be lower than the unfiltered figure,
  // which counts every stream that emitted at all. That matches what the attributes picker
  // (aggregateMetricAttributeFacets) counts, so a picker count and the filtered cardinality agree.
  @Query(value = """
      SELECT
        metric_name,
        COUNT(DISTINCT stream_id)::bigint AS cardinality
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND NOT EXISTS (
          SELECT 1
          FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
          WHERE attributes ->> (filter_match.entry ->> 'key')
            IS DISTINCT FROM (filter_match.entry ->> 'value'))
      GROUP BY metric_name
      """, nativeQuery = true)
  List<Object[]> aggregateMetricCardinalityFiltered(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // Per-bucket trend for every metric name in one scan of [start, end], each
  // row's precomputed value_delta binned by its timestamp. Row shape:
  // (metric_name, bucket, total).
  //
  // Same dead-predicate removal as aggregateMetricTotals above (508ms ->
  // ~350ms measured). No ORDER BY: buildTrend places each row by its bucket's
  // offset from the window start rather than relying on result order, and the
  // planner's row-count misestimate for GROUP BY metric_name, date_bin(...)
  // (millions estimated, 175 actual) was picking a sort-based GroupAggregate
  // over a HashAggregate partly to serve that ordering for free.
  //
  // Carries value_delta IS DISTINCT FROM 0 for the same reason aggregateMetricTotals does: it cannot
  // change a SUM, and it keeps the scan on the ~0.5% of rows that moved (427 ms over 490k rows for a
  // 24 h window without it). A bucket holding only re-emissions is simply absent, which buildTrend
  // already zero-fills by offset.
  @Query(value = """
      SELECT
        metric_name,
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name IN (:metricNames)
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      GROUP BY metric_name, bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrend(
      @Param("metricNames") List<String> metricNames,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // Filtered sibling of aggregateMetricTrend for the ONE metric an attribute filter targets; same
  // row shape (metric_name, bucket, total). Same non-zero restriction and partial-index reasoning
  // as aggregateMetricTotalsFiltered (zero deltas add nothing to a bucket's SUM).
  @Query(value = """
      SELECT
        metric_name,
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND NOT EXISTS (
          SELECT 1
          FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
          WHERE attributes ->> (filter_match.entry ->> 'key')
            IS DISTINCT FROM (filter_match.entry ->> 'value'))
      GROUP BY metric_name, bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrendFiltered(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // Per-bucket AVG / p95 / COUNT trend for ONE metric, for the Metrics page "agg" switch
  // (GET /api/metrics/series?aggMetricId=&agg=). Each returns the same row shape as
  // aggregateMetricTrend -- (metric_name, bucket, value) -- over the identical date_bin
  // origin and width, so MetricSeriesService.buildTrend places and zero-fills the rows exactly
  // as it does for the sum trend; a bucket with no qualifying increment is simply absent.
  //
  // These are three pre-written queries rather than one query with the aggregate function
  // chosen by a parameter: a function name cannot be bound, and interpolating it would break
  // the always-parameterise rule. The service picks the method with a Java switch on the enum.
  // They are also scoped to a single metric_name instead of the batched IN (:metricNames)
  // form, because only the one selected metric is ever aggregated this way.
  //
  // Only NON-ZERO increments are aggregated. Claude Code re-emits every cumulative counter
  // once a minute with an unchanged value, so ~99% of rows carry value_delta = 0 (see the
  // "Streams are never retired" note in backend/CLAUDE.md). A SUM is indifferent to those
  // ghosts, but an AVG or p95 over them would collapse towards 0 and a COUNT would report
  // heartbeats rather than activity. The predicate is written "value_delta IS DISTINCT FROM 0
  // AND value_delta > 0": the first half is byte-for-byte the partial-index predicate of
  // V32/V33 (idx_metric_points_nonzero_name_ts), so the planner can prove the index applies
  // and reads only the ~0.55% of rows that moved; the second half then drops the NULL
  // (pre-V11) rows that IS DISTINCT FROM keeps, since an unknown increment is not a value to
  // average. The optional repository clause is the one the batched series queries carry. The
  // attribute filter is ALWAYS bound here (:filterMatchesJson, "[]" when no filter applies to
  // THIS metric -- the service passes the real matches only when the aggregated metric is the
  // filtered one), which is the same NOT EXISTS the ...Filtered totals/trend use, so a filtered
  // avg trend describes the same population as the filtered header figures. The
  // jsonb_array_length(...) = 0 arm short-circuits the empty case: with a bound "[]" Postgres
  // folds it to true in a custom plan, so an unfiltered agg does not touch `attributes` at all.
  //
  // avg: the mean of the individual increments in the bucket.
  @Query(value = """
      SELECT
        metric_name,
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(AVG(value_delta), 0)::double precision AS aggregated_value
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND value_delta > 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND (jsonb_array_length(CAST(:filterMatchesJson AS jsonb)) = 0
             OR NOT EXISTS (
               SELECT 1
               FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
               WHERE attributes ->> (filter_match.entry ->> 'key')
                 IS DISTINCT FROM (filter_match.entry ->> 'value')))
      GROUP BY metric_name, bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrendAverage(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // p95: the continuous 95th percentile of the individual increments in the bucket. See
  // aggregateMetricTrendAverage for why the predicate is non-zero only.
  @Query(value = """
      SELECT
        metric_name,
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY value_delta), 0)::double precision
          AS aggregated_value
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND value_delta > 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND (jsonb_array_length(CAST(:filterMatchesJson AS jsonb)) = 0
             OR NOT EXISTS (
               SELECT 1
               FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
               WHERE attributes ->> (filter_match.entry ->> 'key')
                 IS DISTINCT FROM (filter_match.entry ->> 'value')))
      GROUP BY metric_name, bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrendP95(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // count: how many increments landed in the bucket (a non-zero increment is one emission in
  // which the counter actually moved). See aggregateMetricTrendAverage for the predicate.
  @Query(value = """
      SELECT
        metric_name,
        date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) AS bucket,
        COUNT(*)::double precision AS aggregated_value
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND value_delta > 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND (jsonb_array_length(CAST(:filterMatchesJson AS jsonb)) = 0
             OR NOT EXISTS (
               SELECT 1
               FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
               WHERE attributes ->> (filter_match.entry ->> 'key')
                 IS DISTINCT FROM (filter_match.entry ->> 'value')))
      GROUP BY metric_name, bucket
      """, nativeQuery = true)
  List<Object[]> aggregateMetricTrendCount(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // Every attribute-split value for every split-bearing metric (token.usage,
  // cost.usage, lines_of_code.count, code_edit_tool.decision), batched into
  // ONE scan of [start, end] instead of one scan per (metric, attribute) pair.
  // MetricSeriesService used to call the single-split predecessor of this query
  // once per curated split (five calls: token/model, token/type, cost/model,
  // loc/type, decision/decision), each rescanning that metric's window and
  // detoasting attributes independently -- 1046ms measured on a 7-day window
  // for five queries producing 16 rows total. Grouping by all three attribute
  // columns at once (model, the configured token-type attribute, decision)
  // collapses that to one scan (~320ms), the same batching
  // aggregateMetricTotals/aggregateMetricTrend already apply across metric
  // names.
  //
  // Every row carries all three attribute columns regardless of which one(s)
  // that row's metric actually populates -- the unpopulated columns are simply
  // null. Filtering by metric server-side would reintroduce the per-metric
  // scan this exists to avoid, so the service groups by (metric, attribute)
  // and drops null labels itself, reproducing each old per-split query's
  // "attribute IS NOT NULL" filter and per-metric row set exactly (verified
  // row-for-row identical to the old token/model query's output).
  //
  // Restricted to value_delta IS DISTINCT FROM 0 like the totals and trend queries, and here it also
  // detoasts `attributes` for ~0.5% of the rows instead of all of them. The one visible consequence:
  // a label whose every row in the window was a re-emission (a model that ran last week, still
  // echoing its counter) no longer appears as a 0% split row. The filtered sibling has always
  // behaved that way.
  @Query(value = """
      SELECT
        metric_name,
        attributes ->> :modelAttribute AS model,
        attributes ->> :typeAttribute AS token_type,
        attributes ->> :decisionAttribute AS decision,
        COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name IN (:metricNames)
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      GROUP BY metric_name, model, token_type, decision
      """, nativeQuery = true)
  List<Object[]> aggregateMetricSplits(
      @Param("metricNames") List<String> metricNames,
      @Param("modelAttribute") String modelAttribute,
      @Param("typeAttribute") String typeAttribute,
      @Param("decisionAttribute") String decisionAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("repositoryUrl") String repositoryUrl);

  // Filtered sibling of aggregateMetricSplits for the ONE metric an attribute filter targets; same
  // row shape (metric_name, model, token_type, decision, total). Same non-zero restriction and
  // partial-index reasoning as aggregateMetricTotalsFiltered. One consequence: a label whose every
  // row in the window is a zero-delta re-export produces no row at all (the unfiltered query would
  // list it at 0), so an idle label drops out of a filtered Group-by rather than showing 0.
  @Query(value = """
      SELECT
        metric_name,
        attributes ->> :modelAttribute AS model,
        attributes ->> :typeAttribute AS token_type,
        attributes ->> :decisionAttribute AS decision,
        COALESCE(SUM(value_delta), 0)::double precision AS total
      FROM metric_points
      WHERE metric_name = :metricName
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :start
        AND timestamp <= :end
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        AND NOT EXISTS (
          SELECT 1
          FROM jsonb_array_elements(CAST(:filterMatchesJson AS jsonb)) AS filter_match(entry)
          WHERE attributes ->> (filter_match.entry ->> 'key')
            IS DISTINCT FROM (filter_match.entry ->> 'value'))
      GROUP BY metric_name, model, token_type, decision
      """, nativeQuery = true)
  List<Object[]> aggregateMetricSplitsFiltered(
      @Param("metricName") String metricName,
      @Param("modelAttribute") String modelAttribute,
      @Param("typeAttribute") String typeAttribute,
      @Param("decisionAttribute") String decisionAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("filterMatchesJson") String filterMatchesJson);

  // Attribute key/value facets for ONE metric NAME, for the Metrics page filter picker
  // (GET /api/metrics/attributes). A name with no rows in the window simply returns no
  // rows, which the service surfaces as an empty attribute list, not an error. Row shape: (attribute_key, attribute_value,
  // stream_count), ordered by key ascending, then stream_count descending, then
  // value ascending.
  //
  // Collapses to distinct streams FIRST -- stream_id is the stored generated
  // md5(metric_name || '|' || attributes::text) column (V11), so every row of a
  // stream carries the identical attribute set and DISTINCT ON (stream_id) keeps
  // one arbitrary representative. A counter is re-emitted every minute, so there
  // are far fewer streams than rows; jsonb_each_text then expands only the
  // representatives rather than every row in the window. stream_count is
  // therefore the number of distinct active label-sets carrying that key=value,
  // not a row or increment count.
  //
  // Only rows carrying a real increment are read (value_delta IS DISTINCT FROM 0,
  // V33's partial-index predicate). 99.45% of rows are zero-delta re-exports from
  // streams whose session ended long ago, so without this a label-set that has not
  // done anything in the window still counted as "active" and the query heap-fetched
  // every ghost row: measured on token.usage over 7 days, 2662 ms with a 790 MB
  // external sort to find 740 streams; with the predicate, a 30-day window runs in
  // 688 ms off idx_metric_points_nonzero_name_ts with an in-memory sort.
  //
  // A key with more than :maxDistinctValues distinct values in the window is
  // dropped entirely (session ids and other unbounded identifiers are useless in
  // a picker); the per-key distinct count comes from a window function over the
  // grouped pairs, so the cutoff is applied in the same statement. A null or
  // empty attribute value is skipped.
  @Query(value = """
      WITH metric_streams AS (
        SELECT DISTINCT ON (stream_id) stream_id, attributes
        FROM metric_points
        WHERE metric_name = :metricName
          AND timestamp >= :start
          AND timestamp <= :end
          AND value_delta IS DISTINCT FROM 0
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        ORDER BY stream_id
      ),
      attribute_pairs AS (
        SELECT
          attribute_entry.key   AS attribute_key,
          attribute_entry.value AS attribute_value,
          COUNT(*)::bigint      AS stream_count
        FROM metric_streams
        CROSS JOIN LATERAL jsonb_each_text(metric_streams.attributes) AS attribute_entry
        WHERE attribute_entry.value IS NOT NULL
          AND attribute_entry.value <> ''
        GROUP BY attribute_entry.key, attribute_entry.value
      ),
      bounded_pairs AS (
        SELECT
          attribute_key,
          attribute_value,
          stream_count,
          COUNT(*) OVER (PARTITION BY attribute_key) AS distinct_value_count
        FROM attribute_pairs
      )
      SELECT attribute_key, attribute_value, stream_count
      FROM bounded_pairs
      WHERE distinct_value_count <= :maxDistinctValues
      ORDER BY attribute_key ASC, stream_count DESC, attribute_value ASC
      """, nativeQuery = true)
  List<Object[]> aggregateMetricAttributeFacets(
      @Param("metricName") String metricName,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("repositoryUrl") String repositoryUrl,
      @Param("maxDistinctValues") int maxDistinctValues);

  // ---------------------------------------------------------------------------
  // Trend report (GET /api/trends)
  // ---------------------------------------------------------------------------
  //
  // Every query below follows the half-open boundary convention established by
  // aggregateCostCurrentAndPriorTotals: current = [:from, :to], prior =
  // [:priorFrom, :from) -- a point landing exactly on :from counts once, in the
  // current period only.

  // Current+prior grand token total (tokens_total), mirroring aggregateTotalTokens
  // but with the same current/prior FILTER shape as aggregateCostCurrentAndPriorTotals.
  // Unlike aggregateTokenTypeCurrentAndPriorTotals below, this counts every
  // value_delta unconditionally (no per-type grouping), matching
  // aggregateTotalTokens' semantics exactly.
  @Query(value = """
      SELECT
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::bigint
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::bigint
          AS prior_total
      FROM metric_points
      WHERE metric_name = :metricName
        AND timestamp >= :priorFrom
        AND timestamp <= :to
      """, nativeQuery = true)
  List<Object[]> aggregateTotalTokensCurrentAndPrior(
      @Param("metricName") String metricName,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom);

  // Per-token-type current+prior totals (cache_read_ratio_pct's three components:
  // input, cacheCreation, cacheRead -- output is fetched too but unused by the
  // ratio, since it shares this one scan for free). Row shape: (token_type,
  // current_total, prior_total). A row with a null token_type (any point whose
  // attributes lack the type key) is included for completeness but dropped by
  // the service, mirroring aggregateTokenUsageBreakdown's WHERE token_type IS NOT
  // NULL semantics.
  @Query(value = """
      SELECT
        attributes ->> :tokenTypeAttribute AS token_type,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::bigint
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::bigint
          AS prior_total
      FROM metric_points
      WHERE metric_name = :metricName
        AND timestamp >= :priorFrom
        AND timestamp <= :to
      GROUP BY token_type
      """, nativeQuery = true)
  List<Object[]> aggregateTokenTypeCurrentAndPriorTotals(
      @Param("metricName") String metricName,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom);

  // Combines aggregateCostCurrentAndPriorTotals, aggregateTotalTokensCurrentAndPrior,
  // and aggregateTokenTypeCurrentAndPriorTotals (three round trips) into ONE. The three
  // legs filter on different metric_name values with different extra predicates (cost
  // additionally requires session_id IS NOT NULL AND value_double IS NOT NULL; the token
  // legs carry neither), so a single FILTER-based scan of one metric_name set can't
  // express all three -- UNION ALL keeps each leg's WHERE clause exact while still
  // costing one round trip instead of three. A row_type discriminator ('cost' /
  // 'token_total' / 'token_type') tells the service which leg produced a row, the same
  // idiom aggregateSessionCountAndDurationCurrentAndPrior's period column already uses;
  // token_type is NULL on the 'cost' and 'token_total' legs, populated only on
  // 'token_type' rows (mirroring aggregateTokenTypeCurrentAndPriorTotals's own token_type
  // column, including a possible NULL for points whose attributes lack the type key).
  //
  // Every leg keeps the exact predicate and half-open current/prior FILTER shape of the
  // query it replaces, so output is row-for-row equivalent (up to which query produced
  // each number) to calling the three original methods -- current_total/prior_total are
  // cast to double precision on every leg (not bigint on the token legs) purely so
  // UNION ALL's legs agree on a type; the service already reads every current/prior total
  // in this class through the same doubleAt helper.
  //
  // THE NON-ZERO FILTER ON EVERY LEG IS A PERFORMANCE FIX, AND IT IS A NO-OP ON THE RESULT.
  // Shared by all four Trend Report queries in this class (this one,
  // aggregateSessionCountAndDurationCurrentAndPrior, aggregateMetricsSparklinesCombined,
  // aggregateTokenTypeSparklinesCombined); see V33 for the measurements -- /api/trends/cost was
  // 9.52 s and /api/trends/token-efficiency 11.96 s before it, this query alone 6828 ms -> 194 ms.
  // Only 46,995 of 8,586,064 metric_points rows carry a non-zero value_delta (0.55%), so without
  // the filter every one of these SUMs read ~180x the rows it needed. The reason it went unnoticed
  // is the reason backend/CLAUDE.md flags this trap: a zero-delta row contributes exactly 0 to a
  // SUM, so the numbers were always right while the row set was badly wrong.
  //
  // Two consequences worth knowing before editing any of the four:
  //   1. A GROUP BY leg can now omit a group entirely where it previously emitted an explicit 0
  //      (a token_type whose every row in the window is a ghost). That is safe here because
  //      TrendService#queryMetricsTotalsCombined initialises every accumulator to 0.0 and only
  //      overwrites it when a row arrives -- a missing group reads as zero, which is its true
  //      value. Keep that property if you add a leg.
  //   2. The cost sums can move in their last bit or two: double precision addition is not
  //      associative and the index returns rows in a different order than the old heap scan.
  //      Measured over a fixed 30-day window, the prior-period cost total went
  //      1391.3704928500024 -> 1391.3704928500008 (1.6e-12 absolute, ~1.1e-15 relative) while
  //      every token total was identical to the digit. Same effect V32 documents; not a defect.
  @Query(value = """
      SELECT
        'cost'       AS row_type,
        NULL         AS token_type,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::double precision
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::double precision
          AS prior_total
      FROM metric_points
      WHERE metric_name = :costMetric
        AND session_id IS NOT NULL
        AND value_double IS NOT NULL
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)

      UNION ALL

      SELECT
        'token_total' AS row_type,
        NULL          AS token_type,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::double precision
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::double precision
          AS prior_total
      FROM metric_points
      WHERE metric_name = :tokenMetric
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)

      UNION ALL

      SELECT
        'token_type' AS row_type,
        attributes ->> :tokenTypeAttribute AS token_type,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :from AND timestamp <= :to), 0)::double precision
          AS current_total,
        COALESCE(SUM(value_delta) FILTER (WHERE timestamp >= :priorFrom AND timestamp < :from), 0)::double precision
          AS prior_total
      FROM metric_points
      WHERE metric_name = :tokenMetric
        AND value_delta IS DISTINCT FROM 0
        AND timestamp >= :priorFrom
        AND timestamp <= :to
        AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      GROUP BY token_type
      """, nativeQuery = true)
  List<Object[]> aggregateMetricsTotalsCombined(
      @Param("costMetric") String costMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom,
      @Param("repositoryUrl") String repositoryUrl);

  // Session count (sessions) and average whole-session wall-clock duration
  // (avg_duration_min) for the current and prior period, in one scan.
  //
  // Session membership reuses aggregateSessionKpis' value_delta IS DISTINCT FROM 0
  // test against the cost/active-time metrics -- see that query's Javadoc and the
  // exporter zero-delta-re-export note in backend/CLAUDE.md -- so a session only
  // counts toward a period if it had a real increment (not a heartbeat re-export)
  // sometime in that period's [priorFrom, from) / [from, to] half.
  //
  // Duration is deliberately the WHOLE-SESSION wall clock (unbounded first_seen /
  // last_seen, same as aggregateSessionSummaries' wall_seconds and
  // SessionSummary.wallSeconds), not the sliver of that session's duration that
  // happens to fall inside the half being measured -- a session can be assigned
  // to both the current and prior period (its points span the boundary) and
  // reports the identical whole-session duration in both rows, matching how the
  // rest of this class treats whole-session totals as a property of the session,
  // not of the window used to find it.
  @Query(value = """
      WITH windowed_points AS (
        SELECT
          session_id,
          timestamp,
          start_timestamp,
          value_delta,
          CASE
            WHEN timestamp >= :from AND timestamp <= :to THEN 'current'
            WHEN timestamp >= :priorFrom AND timestamp < :from THEN 'prior'
          END AS period
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :priorFrom
          AND timestamp <= :to
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      ),
      session_window AS (
        SELECT session_id, period
        FROM windowed_points
        WHERE period IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
        GROUP BY session_id, period
      ),
      session_span AS (
        SELECT
          w.session_id,
          w.period,
          MIN(COALESCE(p.start_timestamp, p.timestamp)) AS first_seen,
          MAX(p.timestamp) FILTER (WHERE p.value_delta IS DISTINCT FROM 0) AS last_seen
        FROM session_window w
        -- NOTE: no apostrophes in this comment -- Spring Data tracks quote state without
        -- skipping SQL line comments, so one here breaks repository creation at startup.
        -- p.value_delta IS DISTINCT FROM 0 is what lets this otherwise unbounded
        -- per-session join ride the V32 partial index. It is safe for last_seen (which
        -- already carries the same test) and for first_seen for the reason the
        -- aggregateSessionSummaries comment sets out at length: a ghost row shares the
        -- start_timestamp of its own stream so it cannot move the MIN, and the only case
        -- that could -- a session with no non-zero row at all -- cannot reach here,
        -- because session_window admits a session only on a non-zero increment.
        JOIN metric_points p ON p.session_id = w.session_id
        WHERE p.metric_name IN (:costMetric, :activeTimeMetric)
          AND p.value_delta IS DISTINCT FROM 0
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
        GROUP BY w.session_id, w.period
      )
      SELECT
        period,
        COUNT(*)::bigint AS session_count,
        COALESCE(AVG(EXTRACT(EPOCH FROM (last_seen - first_seen))), 0)::double precision
          AS avg_duration_seconds
      FROM session_span
      GROUP BY period
      """, nativeQuery = true)
  List<Object[]> aggregateSessionCountAndDurationCurrentAndPrior(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom,
      @Param("repositoryUrl") String repositoryUrl);

  // ---------------------------------------------------------------------------
  // Trend report sparklines (7 points per side, one call per side)
  // ---------------------------------------------------------------------------
  //
  // Every query below returns a zero-based bucket_index (dense zero-fill happens
  // in the service, same idiom as MetricService#buildSessionsTrend) rather than a
  // bucket Instant, so the trend service can combine rows from different queries
  // (e.g. cost and session-count buckets, to derive cost_per_session per bucket)
  // by array index without reproducing date_bin's rounding in Java.

  // Bucketed cost and token totals in one scan -- backs total_cost's and
  // tokens_total's own sparklines, and (divided bucket-by-bucket against
  // aggregateSessionCountAndDurationTrend's session_count) cost_per_session /
  // tokens_per_session / blended_rate_per_1m's sparklines too.
  @Query(value = """
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
          / :bucketSeconds)::int AS bucket_index,
        COALESCE(SUM(value_delta) FILTER (WHERE metric_name = :costMetric), 0)::double precision AS cost_total,
        COALESCE(SUM(value_delta) FILTER (WHERE metric_name = :tokenMetric), 0)::bigint AS token_total
      FROM metric_points
      WHERE metric_name IN (:costMetric, :tokenMetric)
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateCostAndTokenTrend(
      @Param("costMetric") String costMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Bucketed per-token-type totals -- backs cache_read_ratio_pct's sparkline
  // (cacheRead / (input + cacheCreation + cacheRead) per bucket).
  @Query(value = """
      SELECT
        FLOOR(EXTRACT(EPOCH FROM (date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
          / :bucketSeconds)::int AS bucket_index,
        attributes ->> :tokenTypeAttribute AS token_type,
        COALESCE(SUM(value_delta), 0)::bigint AS total
      FROM metric_points
      WHERE metric_name = :tokenMetric
        AND timestamp >= :start
        AND timestamp <= :end
      GROUP BY bucket_index, token_type
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateTokenTypeTrend(
      @Param("tokenMetric") String tokenMetric,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds);

  // Bucketed session count and average whole-session wall-clock duration --
  // backs sessions' and avg_duration_min's own sparklines, and is reused as the
  // denominator for cost_per_session / tokens_per_session's sparklines. Same
  // value_delta IS DISTINCT FROM 0 membership test and whole-session (unbounded)
  // first_seen/last_seen as aggregateSessionCountAndDurationCurrentAndPrior --
  // see that query's Javadoc.
  @Query(value = """
      WITH windowed_points AS (
        SELECT
          session_id,
          timestamp,
          start_timestamp,
          value_delta,
          FLOOR(EXTRACT(EPOCH FROM (date_bin(make_interval(secs => :bucketSeconds), timestamp, :start) - :start))
            / :bucketSeconds)::int AS bucket_index
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND timestamp >= :start
          AND timestamp <= :end
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      ),
      session_window AS (
        SELECT session_id, bucket_index
        FROM windowed_points
        WHERE value_delta IS DISTINCT FROM 0
        GROUP BY session_id, bucket_index
      ),
      session_span AS (
        SELECT
          w.session_id,
          w.bucket_index,
          MIN(COALESCE(p.start_timestamp, p.timestamp)) AS first_seen,
          MAX(p.timestamp) FILTER (WHERE p.value_delta IS DISTINCT FROM 0) AS last_seen
        FROM session_window w
        JOIN metric_points p ON p.session_id = w.session_id
        WHERE p.metric_name IN (:costMetric, :activeTimeMetric)
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
        GROUP BY w.session_id, w.bucket_index
      )
      SELECT
        bucket_index,
        COUNT(*)::bigint AS session_count,
        COALESCE(AVG(EXTRACT(EPOCH FROM (last_seen - first_seen))), 0)::double precision AS avg_duration_seconds
      FROM session_span
      GROUP BY bucket_index
      ORDER BY bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateSessionCountAndDurationTrend(
      @Param("costMetric") String costMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // ---------------------------------------------------------------------------
  // Consolidated trend sparklines (both periods, one round trip)
  // ---------------------------------------------------------------------------
  //
  // The pair below replace FOUR of TrendService's eight metric_points sparkline
  // calls -- aggregateCostAndTokenTrend(priorFrom, from) / (from, to) and
  // aggregateSessionCountAndDurationTrend(priorFrom, from) / (from, to) -- with
  // ONE call each, by computing both the current and prior period's buckets in
  // the same scan instead of calling the single-period query twice with a
  // different bucket origin. A "period" discriminator column (CASE on
  // timestamp, exactly the current = [:from, :to] / prior = [:priorFrom, :from)
  // half-open split every current/prior query in this class already uses)
  // replaces the two separate calls; the service reads 'current' rows into its
  // "after" arrays and 'prior' rows into "before", the same split
  // aggregateSessionCountAndDurationCurrentAndPrior's period column already
  // drives.
  //
  // Each period buckets against its OWN origin (:from for current, :priorFrom
  // for prior) so bucket 0 always starts at that period's own lower bound,
  // matching what two separate date_bin(..., :start) calls with different
  // :start values already produced -- the CASE on bucket origin picks :from or
  // :priorFrom per row before handing it to date_bin, rather than binning both
  // periods against one shared origin.

  // Cost, token, and session (count + whole-session avg duration) sparklines
  // for both periods in one scan. cost_token_bucketed mirrors
  // aggregateCostAndTokenTrend's FILTER-based per-bucket sums; session_window /
  // session_span / session_bucketed mirror aggregateSessionCountAndDurationTrend's
  // CTE chain verbatim (same value_delta IS DISTINCT FROM 0 membership test
  // against the exporter's zero-delta re-exports, same unbounded whole-session
  // first_seen/last_seen join back to metric_points -- see that query's Javadoc
  // and the exporter note in backend/CLAUDE.md). The two families are computed
  // as separate per-(period, bucket_index) CTEs and combined with a FULL OUTER
  // JOIN because a bucket can carry cost/token activity with no session
  // increment or vice versa; COALESCE keeps every combination at zero rather
  // than dropping the bucket.
  //
  // Row shape: (period, bucket_index, cost_total, token_total, session_count,
  // avg_duration_seconds). A bucket absent from both source CTEs never appears
  // (same sparse-rows contract as every other bucketed query here) -- the
  // service zero-fills a dense SPARKLINE_POINTS-length array exactly as it does
  // today.
  @Query(value = """
      WITH cost_token_bucketed AS (
        SELECT
          period,
          bucket_index,
          COALESCE(SUM(value_delta) FILTER (WHERE metric_name = :costMetric), 0)::double precision AS cost_total,
          COALESCE(SUM(value_delta) FILTER (WHERE metric_name = :tokenMetric), 0)::bigint           AS token_total
        FROM (
          SELECT
            CASE WHEN timestamp >= :from AND timestamp <= :to THEN 'current'
                 WHEN timestamp >= :priorFrom AND timestamp < :from THEN 'prior' END AS period,
            FLOOR(EXTRACT(EPOCH FROM (
                date_bin(make_interval(secs => :bucketSeconds), timestamp,
                  CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END)
                - CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END))
              / :bucketSeconds)::int AS bucket_index,
            metric_name,
            value_delta
          FROM metric_points
          WHERE metric_name IN (:costMetric, :tokenMetric)
            AND value_delta IS DISTINCT FROM 0
            AND timestamp >= :priorFrom
            AND timestamp <= :to
            AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
        ) AS labelled_rows
        WHERE period IS NOT NULL
        GROUP BY period, bucket_index
      ),
      session_windowed_points AS (
        SELECT
          session_id,
          timestamp,
          start_timestamp,
          value_delta,
          CASE WHEN timestamp >= :from AND timestamp <= :to THEN 'current'
               WHEN timestamp >= :priorFrom AND timestamp < :from THEN 'prior' END AS period,
          FLOOR(EXTRACT(EPOCH FROM (
              date_bin(make_interval(secs => :bucketSeconds), timestamp,
                CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END)
              - CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END))
            / :bucketSeconds)::int AS bucket_index
        FROM metric_points
        WHERE metric_name IN (:costMetric, :activeTimeMetric)
          AND session_id IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :priorFrom
          AND timestamp <= :to
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      ),
      session_window AS (
        SELECT session_id, period, bucket_index
        FROM session_windowed_points
        WHERE period IS NOT NULL
          AND value_delta IS DISTINCT FROM 0
        GROUP BY session_id, period, bucket_index
      ),
      session_span AS (
        SELECT
          w.session_id,
          w.period,
          w.bucket_index,
          MIN(COALESCE(p.start_timestamp, p.timestamp))                    AS first_seen,
          MAX(p.timestamp) FILTER (WHERE p.value_delta IS DISTINCT FROM 0) AS last_seen
        FROM session_window w
        -- NOTE: no apostrophes in this comment -- Spring Data tracks quote state without
        -- skipping SQL line comments, so one here breaks repository creation at startup.
        -- p.value_delta IS DISTINCT FROM 0 is what lets this otherwise unbounded
        -- per-session join ride the V32 partial index. It is safe for last_seen (which
        -- already carries the same test) and for first_seen for the reason the
        -- aggregateSessionSummaries comment sets out at length: a ghost row shares the
        -- start_timestamp of its own stream so it cannot move the MIN, and the only case
        -- that could -- a session with no non-zero row at all -- cannot reach here,
        -- because session_window admits a session only on a non-zero increment.
        JOIN metric_points p ON p.session_id = w.session_id
        WHERE p.metric_name IN (:costMetric, :activeTimeMetric)
          AND p.value_delta IS DISTINCT FROM 0
          AND (:repositoryUrl IS NULL OR p.repository_url = :repositoryUrl)
        GROUP BY w.session_id, w.period, w.bucket_index
      ),
      session_bucketed AS (
        SELECT
          period,
          bucket_index,
          COUNT(*)::bigint AS session_count,
          COALESCE(AVG(EXTRACT(EPOCH FROM (last_seen - first_seen))), 0)::double precision AS avg_duration_seconds
        FROM session_span
        GROUP BY period, bucket_index
      )
      SELECT
        COALESCE(c.period, s.period)             AS period,
        COALESCE(c.bucket_index, s.bucket_index) AS bucket_index,
        COALESCE(c.cost_total, 0)::double precision           AS cost_total,
        COALESCE(c.token_total, 0)::bigint                    AS token_total,
        COALESCE(s.session_count, 0)::bigint                  AS session_count,
        COALESCE(s.avg_duration_seconds, 0)::double precision AS avg_duration_seconds
      FROM cost_token_bucketed c
      FULL OUTER JOIN session_bucketed s
        ON s.period = c.period AND s.bucket_index = c.bucket_index
      ORDER BY period, bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateMetricsSparklinesCombined(
      @Param("costMetric") String costMetric,
      @Param("tokenMetric") String tokenMetric,
      @Param("activeTimeMetric") String activeTimeMetric,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);

  // Per-token-type sparkline (cache_read_ratio_pct's three components) for both
  // periods in one scan, replacing the remaining two aggregateTokenTypeTrend
  // calls (priorFrom/from and from/to) the same way
  // aggregateMetricsSparklinesCombined replaces the cost/token/session pair --
  // one CTE materializes (period, bucket_index) per row via the same CASE/
  // date_bin-per-own-origin shape, then the outer SELECT groups by those real
  // columns rather than recomputing the expression (see backend/CLAUDE.md on
  // why a repeated parameterized expression must not be regrouped directly).
  //
  // Row shape: (period, bucket_index, token_type, total). A (period,
  // bucket_index, token_type) triple with no matching rows is simply absent,
  // same sparse contract as aggregateTokenTypeTrend; the service still derives
  // cacheReadRatioPct per bucket from the three token types it cares about and
  // zero-fills the rest.
  @Query(value = """
      WITH token_type_rows AS (
        SELECT
          CASE WHEN timestamp >= :from AND timestamp <= :to THEN 'current'
               WHEN timestamp >= :priorFrom AND timestamp < :from THEN 'prior' END AS period,
          FLOOR(EXTRACT(EPOCH FROM (
              date_bin(make_interval(secs => :bucketSeconds), timestamp,
                CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END)
              - CASE WHEN timestamp >= :from THEN CAST(:from AS timestamptz) ELSE CAST(:priorFrom AS timestamptz) END))
            / :bucketSeconds)::int AS bucket_index,
          attributes ->> :tokenTypeAttribute AS token_type,
          value_delta
        FROM metric_points
        WHERE metric_name = :tokenMetric
          AND value_delta IS DISTINCT FROM 0
          AND timestamp >= :priorFrom
          AND timestamp <= :to
          AND (:repositoryUrl IS NULL OR repository_url = :repositoryUrl)
      )
      SELECT
        period,
        bucket_index,
        token_type,
        COALESCE(SUM(value_delta), 0)::bigint AS total
      FROM token_type_rows
      WHERE period IS NOT NULL
      GROUP BY period, bucket_index, token_type
      ORDER BY period, bucket_index
      """, nativeQuery = true)
  List<Object[]> aggregateTokenTypeSparklinesCombined(
      @Param("tokenMetric") String tokenMetric,
      @Param("tokenTypeAttribute") String tokenTypeAttribute,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("priorFrom") Instant priorFrom,
      @Param("bucketSeconds") long bucketSeconds,
      @Param("repositoryUrl") String repositoryUrl);
}
