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

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.guavasoft.agentcompass.entity.LogRecordEntity;

import java.time.Instant;
import java.util.List;

/**
 * Operational diagnostics for the Settings page: table footprints, ingest freshness, applied
 * migrations, retention estimates — and the purge that acts on them.
 *
 * <p>Everything here reads except the four purge methods at the bottom, which are the only writes
 * the dashboard API performs at all. They are reachable solely through
 * {@code SystemService#purge}, behind an explicit confirmation phrase on a {@code DELETE} endpoint,
 * and they gate on whole sessions rather than individual row timestamps — see
 * {@link #DORMANT_SESSION_IDS_SUBQUERY} for why a row-level purge is the wrong granularity here.
 *
 * <p>Unlike the three domain repositories this one owns no table. It extends the bare
 * {@link Repository} marker rather than {@code JpaRepository} precisely because there is nothing to
 * CRUD — {@link LogRecordEntity} is only a managed-type anchor for the proxy, and every method here
 * reads {@code pg_catalog} or {@code flyway_schema_history} or aggregates across all three telemetry
 * tables at once. Keeping it on Spring Data rather than reaching for {@code JdbcTemplate} keeps the
 * backend on exactly one data-access idiom.
 *
 * <p><b>Every query below was measured against the live database</b> (PostgreSQL 16.13, 4.59M
 * {@code metric_points} / 135k {@code log_records} / 81k {@code spans}, 7.3 GB total). Two results
 * shaped the SQL and should not be "simplified" away:
 *
 * <ul>
 *   <li><b>Exact counts, never {@code reltuples}.</b> The planner statistics are not trustworthy on
 *       this schema: a freshly migrated {@code flyway_schema_history} reports {@code reltuples = -1},
 *       and {@code pg_stat_user_tables.n_live_tup} was measured reporting 6 rows for a table holding
 *       19. Exact {@code COUNT(*)} costs 250 ms on the 4.59M-row table — an index-only scan, and
 *       comfortably inside the 15s {@code statement_timeout} every pooled connection runs with.
 *   <li><b>{@code received_at} is unindexed on all three tables.</b> {@code MAX(received_at)} is a
 *       parallel sequential scan (349 ms), and bounding it by the indexed {@code timestamp} does not
 *       help — a 7-day bound still plans a sequential scan (342 ms) because it covers ~25% of the
 *       table. Reading {@code received_at} off the newest-by-{@code timestamp} row instead is an
 *       Index Scan Backward at 0.026 ms. Measured ingest lag is 2.3 ms and there is no late-arrival
 *       skew in this data, so the ordered lookup and the aggregate agree.
 * </ul>
 */
public interface SystemRepository extends Repository<LogRecordEntity, Long> {

  /**
   * Scalar subquery: every {@code session.id} whose last activity, across all three telemetry
   * signals, is older than {@code :cutoff}.
   *
   * <p>Embedded (via compile-time string concatenation) into every query that decides what a purge
   * touches, so the preview, the actual delete, and the copyable script all agree on exactly the same
   * set of sessions.
   *
   * <p><b>Why session-level, not row-level.</b> A purge that deletes by row timestamp alone can split
   * a session that straddles the cutoff — its early turns gone, its later ones intact — silently
   * understating that session's lifetime cost and tokens and truncating its prompt timeline. Measured
   * on the live database: 0 sessions straddle the default 30-day cutoff, but 21 straddle a 7-day one,
   * accounting for 604k rows that a row-level delete would have partially removed. Gating on whether
   * the session's activity has stopped everywhere, not on any single row's age, means a session is
   * always purged whole or not at all.
   *
   * <p><b>Why {@code jsonb_exists(...)} and not the {@code ?} operator.</b> They are equivalent, but
   * {@code ?} inside a native {@code @Query} string collides with JDBC's positional-parameter
   * parsing when the same query also binds named parameters — Hibernate has no way to tell the two
   * apart. Every other jsonb key check in this codebase route around the same trap by using
   * {@code jsonb_each}/{@code jsonb_each_text} instead; this is the equivalent move for existence
   * checks.
   *
   * <p>{@code metric_points.session_id} is a stored generated column (V18); {@code log_records} and
   * {@code spans} have no such column, so their legs extract {@code attributes ->> 'session.id'}
   * directly. That costs a sequential scan on both — acceptable at their size (hundreds of thousands
   * of rows, not millions) for a manually triggered admin action, not a page load.
   *
   * <p><b>Every candidate scan below adds a redundant {@code candidate.timestamp < :cutoff} (or
   * {@code start_timestamp} for {@code spans}) guard alongside the dormant-session membership
   * check.</b> It changes no result: a session only qualifies as dormant when {@code max(last_seen)}
   * — the max of this table's own per-session {@code max(timestamp)} and the other two signals' — is
   * already {@code < :cutoff}, so every one of that session's rows in every table necessarily has
   * {@code timestamp <= that session's own per-table max <= max(last_seen) < :cutoff}. The guard is
   * there so the planner can apply it as a leading index-scan restriction (all three tables index
   * this column) before paying for the {@code IN} membership test or, on {@code metric_points}, the
   * correlated {@code EXISTS} — rather than evaluating those against every row in the table,
   * including rows nowhere near old enough to ever match.
   */
  String DORMANT_SESSION_IDS_SUBQUERY = """
      (SELECT session_id
       FROM (
         SELECT session_id, max(timestamp) AS last_seen
         FROM metric_points
         WHERE session_id IS NOT NULL
         GROUP BY session_id
         UNION ALL
         SELECT attributes ->> 'session.id', max(timestamp)
         FROM log_records
         WHERE jsonb_exists(attributes, 'session.id')
         GROUP BY 1
         UNION ALL
         SELECT attributes ->> 'session.id', max(start_timestamp)
         FROM spans
         WHERE jsonb_exists(attributes, 'session.id')
         GROUP BY 1
       ) AS sessionActivity
       GROUP BY session_id
       HAVING max(last_seen) < :cutoff)""";

  /**
   * Per-table on-disk footprint for every ordinary table in the public schema. Measured 2 ms.
   *
   * <p>The three components decompose exactly — verified on live data, {@code heap + index + toast}
   * equals {@code pg_total_relation_size} for all four tables. {@code pg_table_size} already
   * includes the TOAST relation, hence the subtraction to isolate the main fork.
   *
   * <p>Returns {@code (table_name, heap_bytes, index_bytes, toast_bytes, total_bytes)}.
   */
  @Query(value = """
      SELECT tableRelation.relname                                                     AS table_name,
             pg_table_size(tableRelation.oid)
               - COALESCE(pg_total_relation_size(tableRelation.reltoastrelid), 0)      AS heap_bytes,
             pg_indexes_size(tableRelation.oid)                                        AS index_bytes,
             COALESCE(pg_total_relation_size(tableRelation.reltoastrelid), 0)          AS toast_bytes,
             pg_total_relation_size(tableRelation.oid)                                 AS total_bytes
      FROM pg_class tableRelation
      JOIN pg_namespace schemaNamespace ON schemaNamespace.oid = tableRelation.relnamespace
      WHERE schemaNamespace.nspname = 'public'
        AND tableRelation.relkind = 'r'
      ORDER BY pg_total_relation_size(tableRelation.oid) DESC
      """, nativeQuery = true)
  List<Object[]> findTableStorage();

  /** Total size of the connected database, including catalogs and free space. */
  @Query(value = "SELECT pg_database_size(current_database())", nativeQuery = true)
  long findDatabaseTotalBytes();

  /**
   * Exact row count, oldest and newest row, and rows inside the growth window, for every table
   * {@link #findTableStorage()} can report. Measured 313 ms end to end.
   *
   * <p>Each leg names its own time axis, which is why they cannot be folded together: {@code spans}
   * has no {@code timestamp} column ({@code start_timestamp}, indexed by
   * {@code idx_spans_start_ts}), and {@code flyway_schema_history} has neither ({@code installed_on},
   * a {@code timestamp without time zone}). {@code MIN}/{@code MAX} over an indexed column resolve
   * as InitPlan index-only scans (0.06 ms), so the cost here is dominated by the two full counts.
   *
   * <p>Returns {@code (table_name, row_count, oldest_timestamp, newest_timestamp, rows_in_window)}.
   */
  @Query(value = """
      SELECT 'log_records' AS table_name,
             (SELECT count(*) FROM log_records)                                        AS row_count,
             (SELECT min(timestamp) FROM log_records)                                  AS oldest_timestamp,
             (SELECT max(timestamp) FROM log_records)                                  AS newest_timestamp,
             (SELECT count(*) FROM log_records WHERE timestamp >= :windowStart)        AS rows_in_window
      UNION ALL
      SELECT 'metric_points',
             (SELECT count(*) FROM metric_points),
             (SELECT min(timestamp) FROM metric_points),
             (SELECT max(timestamp) FROM metric_points),
             (SELECT count(*) FROM metric_points WHERE timestamp >= :windowStart)
      UNION ALL
      SELECT 'spans',
             (SELECT count(*) FROM spans),
             (SELECT min(start_timestamp) FROM spans),
             (SELECT max(start_timestamp) FROM spans),
             (SELECT count(*) FROM spans WHERE start_timestamp >= :windowStart)
      UNION ALL
      SELECT 'flyway_schema_history',
             (SELECT count(*) FROM flyway_schema_history),
             (SELECT min(installed_on) AT TIME ZONE 'UTC' FROM flyway_schema_history),
             (SELECT max(installed_on) AT TIME ZONE 'UTC' FROM flyway_schema_history),
             (SELECT count(*) FROM flyway_schema_history
               WHERE installed_on AT TIME ZONE 'UTC' >= :windowStart)
      """, nativeQuery = true)
  List<Object[]> findTableRowStatistics(@Param("windowStart") Instant windowStart);

  /**
   * Arrival freshness and volume per OTLP signal. Measured 1271 ms, dominated by the metric
   * cardinality counts.
   *
   * <p>Each leg computes the three volume windows in ONE index-only scan bounded at seven days
   * (93 ms) rather than three separate range scans, and reads {@code received_at} off the newest row
   * by index order rather than aggregating it — see the class javadoc for why.
   *
   * <p>The cardinality counts are the expensive part: {@code count(DISTINCT metric_name)} measured
   * 569 ms, {@code count(DISTINCT stream_id)} 668 ms, against 40 ms and 32 ms for the log and span
   * name columns. If {@code metric_points} outgrows the budget, replace the two metric counts with a
   * recursive loose-index-scan CTE over {@code idx_metric_points_name_ts} /
   * {@code idx_metric_points_stream_ts}, which returns the same answers in 1.8 ms / 27 ms.
   *
   * <p>Do NOT reuse {@code /api/metrics/catalog} for this: its {@code COUNT(DISTINCT attributes)}
   * over the jsonb payload measured 2188 ms for a single 7-day window, and it is window-scoped where
   * this is all-time.
   *
   * <p>The trailing {@code ORDER BY} is load-bearing, not decoration. {@code UNION ALL} guarantees
   * no ordering, and Postgres does reorder these legs in practice: the live database returned them
   * traces-logs-metrics while a small test container returned them in written order. Without the
   * sort the Settings page's signal rows would reshuffle between refreshes.
   *
   * <p>Returns {@code (signal, table_name, newest_timestamp, newest_received_at, rows_last_hour,
   * rows_last_day, rows_last_week, name_cardinality, name_cardinality_label, series_cardinality)}.
   */
  @Query(value = """
      SELECT 'logs' AS signal,
             'log_records' AS table_name,
             (SELECT max(timestamp) FROM log_records)                                  AS newest_timestamp,
             (SELECT received_at FROM log_records
               ORDER BY timestamp DESC, id DESC LIMIT 1)                               AS newest_received_at,
             logWindow.rows_last_hour,
             logWindow.rows_last_day,
             logWindow.rows_last_week,
             (SELECT count(DISTINCT event_name) FROM log_records)                      AS name_cardinality,
             'event_name'                                                              AS name_cardinality_label,
             CAST(NULL AS bigint)                                                      AS series_cardinality
      FROM (SELECT count(*) FILTER (WHERE timestamp >= :oneHourAgo) AS rows_last_hour,
                   count(*) FILTER (WHERE timestamp >= :oneDayAgo)  AS rows_last_day,
                   count(*)                                         AS rows_last_week
            FROM log_records WHERE timestamp >= :sevenDaysAgo) AS logWindow
      UNION ALL
      SELECT 'metrics',
             'metric_points',
             (SELECT max(timestamp) FROM metric_points),
             (SELECT received_at FROM metric_points ORDER BY timestamp DESC, id DESC LIMIT 1),
             metricWindow.rows_last_hour,
             metricWindow.rows_last_day,
             metricWindow.rows_last_week,
             (SELECT count(DISTINCT metric_name) FROM metric_points),
             'metric_name',
             (SELECT count(DISTINCT stream_id) FROM metric_points)
      FROM (SELECT count(*) FILTER (WHERE timestamp >= :oneHourAgo) AS rows_last_hour,
                   count(*) FILTER (WHERE timestamp >= :oneDayAgo)  AS rows_last_day,
                   count(*)                                         AS rows_last_week
            FROM metric_points WHERE timestamp >= :sevenDaysAgo) AS metricWindow
      UNION ALL
      SELECT 'traces',
             'spans',
             (SELECT max(start_timestamp) FROM spans),
             (SELECT received_at FROM spans ORDER BY start_timestamp DESC LIMIT 1),
             spanWindow.rows_last_hour,
             spanWindow.rows_last_day,
             spanWindow.rows_last_week,
             (SELECT count(DISTINCT name) FROM spans),
             'name',
             CAST(NULL AS bigint)
      FROM (SELECT count(*) FILTER (WHERE start_timestamp >= :oneHourAgo) AS rows_last_hour,
                   count(*) FILTER (WHERE start_timestamp >= :oneDayAgo)  AS rows_last_day,
                   count(*)                                               AS rows_last_week
            FROM spans WHERE start_timestamp >= :sevenDaysAgo) AS spanWindow
      ORDER BY signal
      """, nativeQuery = true)
  List<Object[]> findIngestHealth(
      @Param("oneHourAgo") Instant oneHourAgo,
      @Param("oneDayAgo") Instant oneDayAgo,
      @Param("sevenDaysAgo") Instant sevenDaysAgo);

  /**
   * Every applied Flyway migration, most recent first.
   *
   * <p>{@code installed_on} is declared {@code timestamp without time zone} and written in the
   * server's local zone, so it is converted explicitly. The bundled compose Postgres and the
   * released image both run UTC; a server on another zone would report this column shifted.
   *
   * <p>Returns {@code (installed_rank, version, description, type, script, success, execution_time,
   * installed_on_utc)}.
   */
  @Query(value = """
      SELECT installed_rank,
             version,
             description,
             type,
             script,
             success,
             execution_time,
             installed_on AT TIME ZONE 'UTC' AS installed_on_utc
      FROM flyway_schema_history
      ORDER BY installed_rank DESC
      """, nativeQuery = true)
  List<Object[]> findSchemaMigrations();

  /**
   * Postgres server version. Preferred over {@code version()}, which returns a whole sentence
   * carrying the build host, architecture, and compiler — that needs parsing and leaks more than the
   * Settings page should show. Note this is not always a bare {@code major.minor}: packaged builds
   * append their distribution, so the bundled compose image reports
   * {@code 16.13 (Debian 16.13-1.pgdg13+1)}.
   */
  @Query(value = "SELECT current_setting('server_version')", nativeQuery = true)
  String findPostgresVersion();

  /**
   * Exact rows older than a cutoff, the rows a purge would nevertheless keep, and the totals needed
   * to turn all that into a proportional byte estimate.
   *
   * <p>Counting {@code rows_older} is cheap because the predicate rides each table's timestamp
   * index. {@code rows_to_delete} is the expensive part — it mirrors the exact predicate
   * {@link #purgeLogRecords}/{@link #purgeMetricPoints}/{@link #purgeSpans} run, including the
   * {@link #DORMANT_SESSION_IDS_SUBQUERY} scan, so preview and purge can never disagree about what
   * gets removed. Sizing has no cheap exact answer either — summing {@code pg_column_size} per row
   * detoasts every row it visits — so the service scales {@code total_bytes} by the row share.
   *
   * <p><b>{@code preserved_rows} = rows_older − rows_to_delete: what stays despite being old, and
   * why.</b> Two independent reasons a row can be protected:
   *
   * <ul>
   *   <li><b>Its session is still active.</b> A row whose {@code session.id} has activity anywhere
   *       (any of the three tables) more recent than the cutoff is kept regardless of its own age —
   *       see {@link #DORMANT_SESSION_IDS_SUBQUERY}. This is the dominant case: on the live database,
   *       595k metric-point rows alone are preserved this way at a 7-day cutoff (604k across all
   *       three tables, including the small per-stream marker contribution below).
   *   <li><b>({@code metric_points} only) it is the newest row of its stream.</b> Kept
   *       unconditionally, even inside a session that <i>is</i> dormant and therefore otherwise
   *       eligible for deletion. {@code value_delta} is computed at ingest against the previous row
   *       in the same stream, falling back to zero when there is none, so deleting a stream's last
   *       surviving row would make its next emission record the whole cumulative counter as one
   *       increment — and "no activity anywhere for a full retention window" is strong evidence the
   *       process has exited (Claude Code re-emits every counter roughly once a minute for the life
   *       of the process) but not a proof of it. This is a defensive assumption, not a confirmed
   *       mechanism: nothing here has verified a way for a purged session id to re-emit, only that
   *       we cannot rule one out, so the marker costs one row per dormant stream to close the
   *       question rather than trust the assumption. See {@link #purgeMetricPoints}.
   * </ul>
   *
   * <p><b>Uses its own {@code WITH ... AS MATERIALIZED} rather than
   * {@link #DORMANT_SESSION_IDS_SUBQUERY}</b> — the only query here with three legs referencing the
   * dormant-session computation in one statement. Embedding the scalar subquery three times (as the
   * three single-table purge methods do, correctly, since each only needs it once) forced Postgres to
   * repeat the 4.6M-row {@code metric_points} scan three times, and {@code MATERIALIZED} computes it
   * once and shares the result across all three legs instead. The {@code metric_points} leg is the
   * expensive one regardless — its {@code rows_to_delete} count runs the same per-row correlated
   * {@code EXISTS} that {@link #purgeMetricPoints} does. Originally measured 16-18s end to end
   * (dominated by that leg scanning all 4.6M rows before checking membership or {@code EXISTS}); each
   * candidate subquery now leads with the redundant {@code timestamp < :cutoff} guard described on
   * {@link #DORMANT_SESSION_IDS_SUBQUERY}, so the planner can restrict via the existing timestamp
   * index first and only pay for the membership/{@code EXISTS} check on rows old enough to matter.
   * Still well inside the 60s {@code SystemService.purgePreview} raises this transaction's statement
   * timeout to (the same way the purge itself raises its own), so database growth degrades this into
   * a slower preview rather than a failed one.
   *
   * <p>Returns {@code (table_name, timestamp_column, rows_older_than_cutoff, preserved_rows,
   * total_rows, total_bytes)}.
   */
  @Query(value = """
      WITH dormant_sessions AS MATERIALIZED (
        SELECT session_id
        FROM (
          SELECT session_id, max(timestamp) AS last_seen
          FROM metric_points
          WHERE session_id IS NOT NULL
          GROUP BY session_id
          UNION ALL
          SELECT attributes ->> 'session.id', max(timestamp)
          FROM log_records
          WHERE jsonb_exists(attributes, 'session.id')
          GROUP BY 1
          UNION ALL
          SELECT attributes ->> 'session.id', max(start_timestamp)
          FROM spans
          WHERE jsonb_exists(attributes, 'session.id')
          GROUP BY 1
        ) AS sessionActivity
        GROUP BY session_id
        HAVING max(last_seen) < :cutoff
      )
      SELECT 'log_records' AS table_name,
             'timestamp'   AS timestamp_column,
             (SELECT count(*) FROM log_records WHERE timestamp < :cutoff)              AS rows_older,
             (SELECT count(*) FROM log_records WHERE timestamp < :cutoff)
               - (SELECT count(*) FROM log_records candidate
                   WHERE candidate.timestamp < :cutoff
                     AND ((jsonb_exists(candidate.attributes, 'session.id')
                             AND candidate.attributes ->> 'session.id'
                                   IN (SELECT session_id FROM dormant_sessions))
                       OR NOT jsonb_exists(candidate.attributes, 'session.id')))  AS preserved_rows,
             (SELECT count(*) FROM log_records)                                        AS total_rows,
             pg_total_relation_size('log_records')                                     AS total_bytes
      UNION ALL
      SELECT 'metric_points',
             'timestamp',
             (SELECT count(*) FROM metric_points WHERE timestamp < :cutoff),
             (SELECT count(*) FROM metric_points WHERE timestamp < :cutoff)
               - (SELECT count(*) FROM metric_points candidate
                   WHERE candidate.timestamp < :cutoff
                     AND ((candidate.session_id IS NOT NULL
                             AND candidate.session_id IN (SELECT session_id FROM dormant_sessions))
                       OR candidate.session_id IS NULL)
                     AND EXISTS (
                           SELECT 1 FROM metric_points AS newer
                           WHERE newer.stream_id = candidate.stream_id
                             AND (newer.timestamp, newer.id) > (candidate.timestamp, candidate.id))),
             (SELECT count(*) FROM metric_points),
             pg_total_relation_size('metric_points')
      UNION ALL
      SELECT 'spans',
             'start_timestamp',
             (SELECT count(*) FROM spans WHERE start_timestamp < :cutoff),
             (SELECT count(*) FROM spans WHERE start_timestamp < :cutoff)
               - (SELECT count(*) FROM spans candidate
                   WHERE candidate.start_timestamp < :cutoff
                     AND ((jsonb_exists(candidate.attributes, 'session.id')
                             AND candidate.attributes ->> 'session.id'
                                   IN (SELECT session_id FROM dormant_sessions))
                       OR NOT jsonb_exists(candidate.attributes, 'session.id'))),
             (SELECT count(*) FROM spans),
             pg_total_relation_size('spans')
      ORDER BY table_name
      """, nativeQuery = true)
  List<Object[]> findPurgeEstimates(@Param("cutoff") Instant cutoff);

  // ---------------------------------------------------------------------------------------------
  // Purge — the only writes in this repository.
  //
  // Called exclusively by SystemService#purge, inside one transaction, after the caller has
  // confirmed. Each method deletes strictly older than an explicit cutoff and returns the number of
  // rows it removed. Nothing here is reachable from a GET.
  // ---------------------------------------------------------------------------------------------

  /**
   * Raises {@code statement_timeout} for the current transaction only.
   *
   * <p>Every pooled connection runs with a 15s timeout (see {@code application.yml}), which is right
   * for dashboard aggregations and far too short for a mass delete: removing 2.2M rows from
   * {@code metric_points} with its five indexes takes minutes, not seconds. {@code set_config} with
   * {@code is_local = true} reverts at COMMIT or ROLLBACK, so the raised ceiling cannot leak onto the
   * next borrower of this connection. Using {@code set_config} rather than {@code SET LOCAL} is
   * deliberate — {@code SET} takes no bind parameters.
   */
  @Query(value = "SELECT set_config('statement_timeout', :timeoutMillis, true)", nativeQuery = true)
  String raiseStatementTimeoutForTransaction(@Param("timeoutMillis") String timeoutMillis);

  /**
   * Deletes log records belonging to a dormant session, plus any sessionless log record older than
   * the cutoff. A row whose session is still active anywhere is kept even when this row itself is
   * far older than the cutoff — see {@link #DORMANT_SESSION_IDS_SUBQUERY}. Returns the number removed.
   */
  @Modifying
  @Query(value = """
      DELETE FROM log_records AS candidate
      WHERE candidate.timestamp < :cutoff
        AND ((jsonb_exists(candidate.attributes, 'session.id')
                AND candidate.attributes ->> 'session.id' IN """ + DORMANT_SESSION_IDS_SUBQUERY + """
)
          OR NOT jsonb_exists(candidate.attributes, 'session.id'))
      """, nativeQuery = true)
  int purgeLogRecords(@Param("cutoff") Instant cutoff);

  /**
   * Deletes spans belonging to a dormant session, plus any sessionless span started before the
   * cutoff. Same session-level gating as {@link #purgeLogRecords}. Returns the number removed.
   */
  @Modifying
  @Query(value = """
      DELETE FROM spans AS candidate
      WHERE candidate.start_timestamp < :cutoff
        AND ((jsonb_exists(candidate.attributes, 'session.id')
                AND candidate.attributes ->> 'session.id' IN """ + DORMANT_SESSION_IDS_SUBQUERY + """
)
          OR NOT jsonb_exists(candidate.attributes, 'session.id'))
      """, nativeQuery = true)
  int purgeSpans(@Param("cutoff") Instant cutoff);

  /**
   * Deletes metric points belonging to a dormant session (or, for a sessionless row, older than the
   * cutoff) — <b>except the newest row of each stream, kept unconditionally.</b>
   *
   * <p>Two protections stack here, and both are load-bearing:
   *
   * <ul>
   *   <li><b>Session-level gating decides eligibility</b> (see {@link #DORMANT_SESSION_IDS_SUBQUERY}):
   *       a row belonging to a session still active in any signal is never touched, however old it
   *       is. This is what stops a session from being split across signals or across the cutoff.
   *   <li><b>The per-stream marker decides which of an eligible session's rows survive</b>: the
   *       newest row of every stream is kept regardless. {@code value_delta} is computed at ingest
   *       against the previous row in the same stream, falling back to zero when there is none, so
   *       deleting a stream's last surviving row would make its next emission record the whole
   *       cumulative counter as one increment.
   * </ul>
   *
   * <p>The second protection is deliberately unconditional rather than trusting session dormancy
   * alone. "No activity anywhere for a full retention window" is strong evidence the underlying
   * process has exited — Claude Code re-emits every counter roughly once a minute for the life of
   * the process — but not a proof of it, and this codebase has not verified a concrete mechanism by
   * which a purged {@code session.id} could re-emit. (It is specifically NOT known to be
   * {@code claude --resume}: this project's own measurements found resuming mints a disjoint,
   * never-before-seen session id for the heartbeat it emits, which would build a brand-new stream
   * with nothing to protect rather than reactivate an old one — what session id ongoing interactive
   * work uses after a real resume is unverified.) Absent a way to rule the possibility out entirely,
   * keeping the marker regardless of dormancy costs one retained row per stream in an otherwise
   * fully dormant session, in exchange for closing the question rather than assuming it.
   *
   * <p>The {@code EXISTS} rides {@code idx_metric_points_stream_ts} ({@code (stream_id, timestamp,
   * id)}, V11) and mirrors the ordering {@code recomputeValueDeltas} uses to find a row's
   * predecessor, so "has a newer row in this stream" means exactly "is not the row a future insert
   * would compute its delta against".
   *
   * <p>Returns the number removed.
   */
  @Modifying
  @Query(value = """
      DELETE FROM metric_points AS candidate
      WHERE candidate.timestamp < :cutoff
        AND ((candidate.session_id IS NOT NULL
                AND candidate.session_id IN """ + DORMANT_SESSION_IDS_SUBQUERY + """
)
          OR candidate.session_id IS NULL)
        AND EXISTS (
              SELECT 1 FROM metric_points AS newer
              WHERE newer.stream_id = candidate.stream_id
                AND (newer.timestamp, newer.id) > (candidate.timestamp, candidate.id))
      """, nativeQuery = true)
  int purgeMetricPoints(@Param("cutoff") Instant cutoff);

  /**
   * Refreshes planner statistics on the three telemetry tables.
   *
   * <p>Run in the same transaction as the deletes — {@code ANALYZE} is permitted inside a
   * transaction block where {@code VACUUM} is not. A purge can remove half of
   * {@code metric_points}, and leaving the planner believing the old row counts mis-plans every
   * other page until autovacuum catches up on its own schedule.
   *
   * <p>This does NOT reclaim disk. Only {@code VACUUM FULL} returns space to the operating system,
   * and it cannot run here: it needs its own transaction and an ACCESS EXCLUSIVE lock. The purge
   * result says so rather than implying the files shrank.
   */
  @Modifying
  @Query(value = "ANALYZE log_records, metric_points, spans", nativeQuery = true)
  void analyzeTelemetryTables();
}
