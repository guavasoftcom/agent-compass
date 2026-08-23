package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.config.TuningPropertyCatalog;
import com.guavasoft.agentcompass.model.EffectiveConfiguration;
import com.guavasoft.agentcompass.model.IngestHealth;
import com.guavasoft.agentcompass.model.PurgePreview;
import com.guavasoft.agentcompass.model.PurgeResult;
import com.guavasoft.agentcompass.model.PurgeTableEstimate;
import com.guavasoft.agentcompass.model.PurgeTableResult;
import com.guavasoft.agentcompass.model.SchemaMigration;
import com.guavasoft.agentcompass.model.SignalIngest;
import com.guavasoft.agentcompass.model.StorageOverview;
import com.guavasoft.agentcompass.model.SystemBuild;
import com.guavasoft.agentcompass.model.TableStorage;
import com.guavasoft.agentcompass.repository.SystemRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operational diagnostics behind {@code /api/system}. Every method reads except {@link #purge}, the
 * one write path in the whole dashboard API.
 *
 * <p>Everything here is measured live per request rather than cached — the queries are cheap enough
 * (see {@link SystemRepository} for per-query timings) and a stale storage figure on a page whose
 * whole job is telling you how much disk you are using would be worse than a slow one.
 *
 * <p>{@link #purgePreview(int)} measures what a retention cutoff would remove and renders the SQL
 * for it, without executing anything; {@link #purge} is the only method that does, and only behind
 * an explicit confirmation phrase. Both gate on whole sessions rather than individual row ages — see
 * {@code SystemRepository.DORMANT_SESSION_IDS_SUBQUERY} for why.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SystemService {

  private static final int GROWTH_WINDOW_DAYS = 7;
  private static final int DEFAULT_RETENTION_DAYS = 30;
  private static final double PERCENT_SCALE = 100.0;

  private static final String LOG_RECORDS_TABLE = "log_records";
  private static final String METRIC_POINTS_TABLE = "metric_points";
  private static final String SPANS_TABLE = "spans";

  private static final String DEVELOPMENT_VERSION = "dev";
  private static final String JAVA_VENDOR_PROPERTY = "java.vendor";
  private static final String JVM_NAME_PROPERTY = "java.vm.name";
  private static final String UNKNOWN_VALUE = "unknown";

  /**
   * Phrase a caller must send to execute a purge. Not a security control — there is no auth in front
   * of this application at all — but it makes the destructive endpoint impossible to trigger by
   * accident: a stray {@code DELETE} from a crawler, a mistyped curl, or a client replaying the
   * preview's URL all fail closed.
   */
  public static final String PURGE_CONFIRMATION_PHRASE = "PURGE";

  /**
   * Ceiling for the purge transaction only. Deleting millions of rows across five indexes takes
   * minutes; the pool's default 15s would abort it partway and roll everything back, repeatedly.
   */
  private static final String PURGE_STATEMENT_TIMEOUT_MILLIS = "1800000";

  /**
   * Ceiling for the preview transaction. {@code findPurgeEstimates} recomputes the dormant-session
   * scan across all three tables (originally measured 16-18s on the live database, dominated by the
   * correlated per-row check {@code metric_points} needs to find its own dormant-stream markers) to
   * report exactly what a purge would touch. {@code SystemRepository#DORMANT_SESSION_IDS_SUBQUERY}'s
   * javadoc describes a since-added redundant timestamp guard that lets the planner restrict that
   * scan by index before running the expensive per-row checks, but the ceiling is left unchanged: the
   * pool's default 15s would still leave little margin as {@code metric_points} keeps growing (~192
   * MB/day measured), so this stays raised the same way the purge itself is rather than let database
   * growth turn a slow preview into a failed one.
   */
  private static final String PURGE_PREVIEW_STATEMENT_TIMEOUT_MILLIS = "60000";

  private static final String RECLAIM_SPACE_SQL = "VACUUM FULL log_records, metric_points, spans;";

  /**
   * Mirror of {@code SystemRepository#DORMANT_SESSION_IDS_SUBQUERY} for rendered SQL, with the
   * {@code :cutoff} bind parameter replaced by a literal — every consumer of a rendered statement
   * (the per-table {@code statement} field, the combined script) formats this once with the same
   * cutoff and embeds the result, so they can never name a different set of dormant sessions than
   * the repository query that will actually run.
   */
  private static final String DORMANT_SESSION_IDS_SUBQUERY_FORMAT = """
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
       HAVING max(last_seen) < TIMESTAMPTZ '%1$s')""";

  /**
   * Mirror of {@code SystemRepository#purgeLogRecords}/{@code #purgeSpans} for the per-table
   * {@code statement} field: delete a dormant session's rows from this table, plus any sessionless
   * row past the cutoff. {@code %1$s} table name, {@code %2$s} the resolved dormant-sessions
   * subquery, {@code %3$s} the timestamp column, {@code %4$s} the cutoff.
   */
  private static final String SESSION_GATED_DELETE_FORMAT = """
      DELETE FROM %1$s AS candidate
      WHERE candidate.%3$s < TIMESTAMPTZ '%4$s'
        AND ((jsonb_exists(candidate.attributes, 'session.id')
                AND candidate.attributes ->> 'session.id' IN %2$s)
          OR NOT jsonb_exists(candidate.attributes, 'session.id'));""";

  /**
   * Mirror of {@code SystemRepository#purgeMetricPoints}. {@code %1$s} the resolved dormant-sessions
   * subquery, {@code %2$s} the cutoff. Kept in lockstep with that query — an operator running a
   * naive delete instead would corrupt their own counters.
   */
  private static final String METRIC_POINTS_DELETE_FORMAT = """
      -- The newest row of every stream is kept unconditionally, even inside an otherwise
      -- eligible (dormant) session: value_delta is computed against a row's predecessor,
      -- so one left with none would record its entire cumulative value as one increment on
      -- its next emission. Session dormancy alone is strong evidence but not a guarantee
      -- that a stream is finished -- no confirmed mechanism reactivates a purged session
      -- id, but none has been ruled out either, so this closes the question rather than
      -- assume it.
      DELETE FROM metric_points AS candidate
      WHERE candidate.timestamp < TIMESTAMPTZ '%2$s'
        AND ((candidate.session_id IS NOT NULL AND candidate.session_id IN %1$s)
          OR candidate.session_id IS NULL)
        AND EXISTS (SELECT 1 FROM metric_points AS newer
                    WHERE newer.stream_id = candidate.stream_id
                      AND (newer.timestamp, newer.id) > (candidate.timestamp, candidate.id));""";

  /**
   * The combined "Copy SQL" script. Unlike the per-table {@code statement} fields (each
   * self-contained, so any one can be copied alone), this computes the dormant-session set exactly
   * once via a temp table and reuses it — an operator pasting all three per-table statements
   * together would otherwise repeat that ~9s scan three times.
   */
  private static final String PURGE_SCRIPT_HEADER = """
      -- Deletes every session whose LAST activity, across logs, metrics, and traces, is
      -- older than %d days (before %s). A session's rows are removed as a whole or not at
      -- all -- a row belonging to a still-active session survives however old it is.
      -- Reclaims roughly %d bytes, per the estimate on the Settings page.
      BEGIN;

      CREATE TEMP TABLE dormant_sessions_for_purge ON COMMIT DROP AS
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
      HAVING max(last_seen) < TIMESTAMPTZ '%2$s';

      DELETE FROM log_records AS candidate
      WHERE candidate.timestamp < TIMESTAMPTZ '%2$s'
        AND ((jsonb_exists(candidate.attributes, 'session.id')
                AND candidate.attributes ->> 'session.id' IN (SELECT session_id FROM dormant_sessions_for_purge))
          OR NOT jsonb_exists(candidate.attributes, 'session.id'));

      DELETE FROM spans AS candidate
      WHERE candidate.start_timestamp < TIMESTAMPTZ '%2$s'
        AND ((jsonb_exists(candidate.attributes, 'session.id')
                AND candidate.attributes ->> 'session.id' IN (SELECT session_id FROM dormant_sessions_for_purge))
          OR NOT jsonb_exists(candidate.attributes, 'session.id'));

      -- The newest row of every stream is kept unconditionally, even inside an otherwise
      -- eligible (dormant) session (see METRIC_POINTS_DELETE_FORMAT in SystemService for why).
      DELETE FROM metric_points AS candidate
      WHERE candidate.timestamp < TIMESTAMPTZ '%2$s'
        AND ((candidate.session_id IS NOT NULL
                AND candidate.session_id IN (SELECT session_id FROM dormant_sessions_for_purge))
          OR candidate.session_id IS NULL)
        AND EXISTS (SELECT 1 FROM metric_points AS newer
                    WHERE newer.stream_id = candidate.stream_id
                      AND (newer.timestamp, newer.id) > (candidate.timestamp, candidate.id));
      """;
  private static final String PURGE_SCRIPT_FOOTER = """
      ANALYZE log_records, metric_points, spans;
      COMMIT;

      -- A DELETE marks the space reusable by Postgres; it does not hand it back to the
      -- operating system. This needs its own transaction and an ACCESS EXCLUSIVE lock, so
      -- run it separately and only when you're ready for that lock:
      -- VACUUM FULL log_records, metric_points, spans;
      """;

  private static final int STORAGE_TABLE_NAME_INDEX = 0;
  private static final int STORAGE_HEAP_BYTES_INDEX = 1;
  private static final int STORAGE_INDEX_BYTES_INDEX = 2;
  private static final int STORAGE_TOAST_BYTES_INDEX = 3;
  private static final int STORAGE_TOTAL_BYTES_INDEX = 4;

  private static final int ROW_STATISTICS_TABLE_NAME_INDEX = 0;
  private static final int ROW_STATISTICS_ROW_COUNT_INDEX = 1;
  private static final int ROW_STATISTICS_OLDEST_INDEX = 2;
  private static final int ROW_STATISTICS_NEWEST_INDEX = 3;
  private static final int ROW_STATISTICS_WINDOW_ROWS_INDEX = 4;

  private static final int INGEST_SIGNAL_INDEX = 0;
  private static final int INGEST_TABLE_NAME_INDEX = 1;
  private static final int INGEST_NEWEST_TIMESTAMP_INDEX = 2;
  private static final int INGEST_NEWEST_RECEIVED_AT_INDEX = 3;
  private static final int INGEST_ROWS_LAST_HOUR_INDEX = 4;
  private static final int INGEST_ROWS_LAST_DAY_INDEX = 5;
  private static final int INGEST_ROWS_LAST_WEEK_INDEX = 6;
  private static final int INGEST_NAME_CARDINALITY_INDEX = 7;
  private static final int INGEST_NAME_CARDINALITY_LABEL_INDEX = 8;
  private static final int INGEST_SERIES_CARDINALITY_INDEX = 9;

  private static final int MIGRATION_RANK_INDEX = 0;
  private static final int MIGRATION_VERSION_INDEX = 1;
  private static final int MIGRATION_DESCRIPTION_INDEX = 2;
  private static final int MIGRATION_TYPE_INDEX = 3;
  private static final int MIGRATION_SCRIPT_INDEX = 4;
  private static final int MIGRATION_SUCCESS_INDEX = 5;
  private static final int MIGRATION_EXECUTION_TIME_INDEX = 6;
  private static final int MIGRATION_INSTALLED_ON_INDEX = 7;

  private static final int PURGE_TABLE_NAME_INDEX = 0;
  private static final int PURGE_TIMESTAMP_COLUMN_INDEX = 1;
  private static final int PURGE_ROWS_OLDER_INDEX = 2;
  private static final int PURGE_PRESERVED_ROWS_INDEX = 3;
  private static final int PURGE_TOTAL_ROWS_INDEX = 4;
  private static final int PURGE_TOTAL_BYTES_INDEX = 5;

  private final SystemRepository systemRepository;
  private final TuningProperties tuningProperties;
  private final TuningPropertyCatalog tuningPropertyCatalog;
  private final Optional<BuildProperties> buildProperties;

  /** Per-table footprint, row counts, time spans, and the seven-day growth estimate. */
  public StorageOverview storageOverview() {
    Instant measuredAt = Instant.now();
    Instant growthWindowStart = measuredAt.minus(Duration.ofDays(GROWTH_WINDOW_DAYS));
    Map<String, Object[]> rowStatisticsByTable = systemRepository
        .findTableRowStatistics(growthWindowStart).stream()
        .collect(Collectors.toMap(
            row -> (String) row[ROW_STATISTICS_TABLE_NAME_INDEX], Function.identity()));

    List<TableStorage> tables = systemRepository.findTableStorage().stream()
        .map(storageRow -> toTableStorage(storageRow, rowStatisticsByTable))
        .toList();
    long estimatedTotalBytesPerDay = tables.stream()
        .mapToLong(TableStorage::estimatedBytesPerDay)
        .sum();

    return new StorageOverview(
        tables, systemRepository.findDatabaseTotalBytes(), estimatedTotalBytesPerDay, measuredAt);
  }

  /** Arrival freshness and volume for each of the three OTLP signals. */
  public IngestHealth ingestHealth() {
    Instant measuredAt = Instant.now();
    List<SignalIngest> signals = systemRepository
        .findIngestHealth(
            measuredAt.minus(Duration.ofHours(1)),
            measuredAt.minus(Duration.ofDays(1)),
            measuredAt.minus(Duration.ofDays(GROWTH_WINDOW_DAYS)))
        .stream()
        .map(SystemService::toSignalIngest)
        .toList();
    return new IngestHealth(signals, measuredAt);
  }

  /** What is running, and the migration history of the schema it is running against. */
  public SystemBuild systemBuild() {
    List<SchemaMigration> migrations = systemRepository.findSchemaMigrations().stream()
        .map(SystemService::toSchemaMigration)
        .toList();
    return new SystemBuild(
        buildProperties.map(BuildProperties::getVersion).orElse(DEVELOPMENT_VERSION),
        buildProperties.map(BuildProperties::getTime).orElse(null),
        Runtime.version().toString(),
        System.getProperty(JAVA_VENDOR_PROPERTY, UNKNOWN_VALUE),
        System.getProperty(JVM_NAME_PROPERTY, UNKNOWN_VALUE),
        systemRepository.findPostgresVersion(),
        migrations);
  }

  /** Every resolved {@code tuning.*} property, grouped and flagged for SQL mirroring. */
  public EffectiveConfiguration effectiveConfiguration() {
    return tuningPropertyCatalog.describe(tuningProperties);
  }

  /**
   * Measures what deleting telemetry older than {@code retentionDays} would remove, and renders the
   * SQL that would do it. Executes nothing.
   */
  @Transactional(readOnly = true)
  public PurgePreview purgePreview(int retentionDays) {
    int effectiveRetentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
    Instant cutoff = Instant.now().minus(Duration.ofDays(effectiveRetentionDays));

    // Read-only, so this cannot write anything even though set_config is technically a write call —
    // verified against Postgres directly: SET/set_config affect session state, not table data, and
    // are permitted inside a READ ONLY transaction.
    systemRepository.raiseStatementTimeoutForTransaction(PURGE_PREVIEW_STATEMENT_TIMEOUT_MILLIS);

    List<PurgeTableEstimate> tables = systemRepository.findPurgeEstimates(cutoff).stream()
        .map(row -> toPurgeTableEstimate(row, cutoff))
        .sorted(Comparator.comparingLong(PurgeTableEstimate::estimatedReclaimableBytes).reversed())
        .toList();
    long totalRowsToDelete = tables.stream().mapToLong(PurgeTableEstimate::rowsToDelete).sum();
    long estimatedReclaimableBytes = tables.stream()
        .mapToLong(PurgeTableEstimate::estimatedReclaimableBytes)
        .sum();

    return new PurgePreview(
        effectiveRetentionDays,
        cutoff,
        tables,
        totalRowsToDelete,
        estimatedReclaimableBytes,
        renderPurgeScript(effectiveRetentionDays, cutoff, estimatedReclaimableBytes));
  }

  /**
   * Deletes telemetry for every session whose last activity, across all three signals, is older
   * than {@code retentionDays}. Irreversible.
   *
   * <p>The one write path in the dashboard API. Guarded three ways: the caller must send
   * {@link #PURGE_CONFIRMATION_PHRASE}, the retention window is bounded by the controller, and the
   * whole thing runs in one transaction against a single, shared {@code cutoff} — every table's
   * delete gates on the exact same dormant-session set (see
   * {@code SystemRepository.DORMANT_SESSION_IDS_SUBQUERY}), which is what makes a session's rows
   * come out whole rather than split between "old, deleted" and "recent, kept". A row-age-only
   * delete would split any session straddling the cutoff — measured on the live database, 0 sessions
   * straddle the 30-day default but 21 straddle a 7-day window, accounting for 585k rows that would
   * otherwise have been partially removed.
   *
   * <p>Deliberately synchronous. On a large database this blocks for minutes, which is honest: the
   * operator asked for it, is watching a modal, and a fire-and-forget job would need progress
   * plumbing that a single-user localhost tool does not earn.
   *
   * @throws IllegalArgumentException when the confirmation phrase is absent or wrong
   */
  @Transactional
  public PurgeResult purge(int retentionDays, String confirmation) {
    if (!PURGE_CONFIRMATION_PHRASE.equals(confirmation)) {
      throw new IllegalArgumentException(
          "Purge requires the confirmation phrase '" + PURGE_CONFIRMATION_PHRASE + "'.");
    }

    int effectiveRetentionDays = retentionDays > 0 ? retentionDays : DEFAULT_RETENTION_DAYS;
    Instant cutoff = Instant.now().minus(Duration.ofDays(effectiveRetentionDays));
    long startedAtMillis = System.currentTimeMillis();

    systemRepository.raiseStatementTimeoutForTransaction(PURGE_STATEMENT_TIMEOUT_MILLIS);
    long databaseTotalBytesBefore = systemRepository.findDatabaseTotalBytes();

    // Read the same estimate the preview uses, before deleting anything, so preservedRows reflects
    // exactly what this purge is about to protect — across all three tables, not just
    // metric_points — rather than re-deriving it from post-delete state or re-running the ~9s
    // dormant-session scan a second time afterwards.
    long preservedRows = systemRepository.findPurgeEstimates(cutoff).stream()
        .mapToLong(row -> toLong(row[PURGE_PRESERVED_ROWS_INDEX]))
        .sum();

    // Logs and spans first, metric points last: the metric delete is by far the longest, and
    // ordering the cheap ones ahead of it keeps a failure from having done the expensive work twice.
    long logRecordsDeleted = systemRepository.purgeLogRecords(cutoff);
    long spansDeleted = systemRepository.purgeSpans(cutoff);
    long metricPointsDeleted = systemRepository.purgeMetricPoints(cutoff);

    systemRepository.analyzeTelemetryTables();

    Map<String, Long> deletedByTable = Map.of(
        LOG_RECORDS_TABLE, logRecordsDeleted,
        SPANS_TABLE, spansDeleted,
        METRIC_POINTS_TABLE, metricPointsDeleted);
    List<PurgeTableResult> tables = systemRepository.findTableRowStatistics(cutoff).stream()
        .filter(row -> deletedByTable.containsKey((String) row[ROW_STATISTICS_TABLE_NAME_INDEX]))
        .map(row -> {
          String tableName = (String) row[ROW_STATISTICS_TABLE_NAME_INDEX];
          return new PurgeTableResult(
              tableName,
              deletedByTable.get(tableName),
              toLong(row[ROW_STATISTICS_ROW_COUNT_INDEX]));
        })
        .sorted(Comparator.comparingLong(PurgeTableResult::rowsDeleted).reversed())
        .toList();

    return new PurgeResult(
        effectiveRetentionDays,
        cutoff,
        tables,
        logRecordsDeleted + spansDeleted + metricPointsDeleted,
        preservedRows,
        databaseTotalBytesBefore,
        systemRepository.findDatabaseTotalBytes(),
        System.currentTimeMillis() - startedAtMillis,
        RECLAIM_SPACE_SQL,
        Instant.now());
  }

  private static TableStorage toTableStorage(
      Object[] storageRow, Map<String, Object[]> rowStatisticsByTable) {
    String tableName = (String) storageRow[STORAGE_TABLE_NAME_INDEX];
    long totalBytes = toLong(storageRow[STORAGE_TOTAL_BYTES_INDEX]);
    Object[] rowStatistics = rowStatisticsByTable.get(tableName);

    long rowCount = rowStatistics == null ? 0L : toLong(rowStatistics[ROW_STATISTICS_ROW_COUNT_INDEX]);
    long rowsLastSevenDays = rowStatistics == null
        ? 0L
        : toLong(rowStatistics[ROW_STATISTICS_WINDOW_ROWS_INDEX]);

    return new TableStorage(
        tableName,
        rowCount,
        toLong(storageRow[STORAGE_HEAP_BYTES_INDEX]),
        toLong(storageRow[STORAGE_INDEX_BYTES_INDEX]),
        toLong(storageRow[STORAGE_TOAST_BYTES_INDEX]),
        totalBytes,
        rowStatistics == null ? null : (Instant) rowStatistics[ROW_STATISTICS_OLDEST_INDEX],
        rowStatistics == null ? null : (Instant) rowStatistics[ROW_STATISTICS_NEWEST_INDEX],
        rowsLastSevenDays,
        estimateBytesPerDay(totalBytes, rowCount, rowsLastSevenDays));
  }

  /**
   * Average on-disk bytes per row multiplied by the recent insert rate.
   *
   * <p>There are no historical size samples to difference, so this is the closest honest answer
   * available from a single point in time. It assumes row size is stationary, which understates a
   * table whose payloads have been growing. Rejected alternative: averaging
   * {@code pg_column_size(table.*)} over recent rows reports the uncompressed logical size of a
   * detoasted composite and overstated {@code log_records} by ~60% (22 646 against 14 288 bytes per
   * row on live data).
   */
  private static long estimateBytesPerDay(long totalBytes, long rowCount, long rowsLastSevenDays) {
    if (rowCount <= 0 || rowsLastSevenDays <= 0) {
      return 0L;
    }
    double averageBytesPerRow = (double) totalBytes / rowCount;
    return Math.round(averageBytesPerRow * rowsLastSevenDays / GROWTH_WINDOW_DAYS);
  }

  private static SignalIngest toSignalIngest(Object[] row) {
    Object seriesCardinality = row[INGEST_SERIES_CARDINALITY_INDEX];
    return new SignalIngest(
        (String) row[INGEST_SIGNAL_INDEX],
        (String) row[INGEST_TABLE_NAME_INDEX],
        (Instant) row[INGEST_NEWEST_TIMESTAMP_INDEX],
        (Instant) row[INGEST_NEWEST_RECEIVED_AT_INDEX],
        toLong(row[INGEST_ROWS_LAST_HOUR_INDEX]),
        toLong(row[INGEST_ROWS_LAST_DAY_INDEX]),
        toLong(row[INGEST_ROWS_LAST_WEEK_INDEX]),
        toLong(row[INGEST_NAME_CARDINALITY_INDEX]),
        (String) row[INGEST_NAME_CARDINALITY_LABEL_INDEX],
        seriesCardinality == null ? null : toLong(seriesCardinality));
  }

  private static SchemaMigration toSchemaMigration(Object[] row) {
    return new SchemaMigration(
        toInt(row[MIGRATION_RANK_INDEX]),
        (String) row[MIGRATION_VERSION_INDEX],
        (String) row[MIGRATION_DESCRIPTION_INDEX],
        (String) row[MIGRATION_TYPE_INDEX],
        (String) row[MIGRATION_SCRIPT_INDEX],
        (Boolean) row[MIGRATION_SUCCESS_INDEX],
        toInt(row[MIGRATION_EXECUTION_TIME_INDEX]),
        (Instant) row[MIGRATION_INSTALLED_ON_INDEX]);
  }

  private static PurgeTableEstimate toPurgeTableEstimate(Object[] row, Instant cutoff) {
    String tableName = (String) row[PURGE_TABLE_NAME_INDEX];
    String timestampColumn = (String) row[PURGE_TIMESTAMP_COLUMN_INDEX];
    long preservedRows = toLong(row[PURGE_PRESERVED_ROWS_INDEX]);
    // What the purge will actually remove: everything past the cutoff, less whatever this table
    // preserves — almost always rows whose session is still active elsewhere. See
    // SystemRepository#DORMANT_SESSION_IDS_SUBQUERY.
    long rowsToDelete = toLong(row[PURGE_ROWS_OLDER_INDEX]) - preservedRows;
    long totalRows = toLong(row[PURGE_TOTAL_ROWS_INDEX]);
    long totalBytes = toLong(row[PURGE_TOTAL_BYTES_INDEX]);

    double rowShare = totalRows <= 0 ? 0.0 : (double) rowsToDelete / totalRows;
    return new PurgeTableEstimate(
        tableName,
        timestampColumn,
        rowsToDelete,
        preservedRows,
        totalRows,
        rowShare * PERCENT_SCALE,
        Math.round(totalBytes * rowShare),
        renderDeleteStatement(tableName, timestampColumn, cutoff));
  }

  /**
   * The self-contained DELETE for one table, matching exactly what {@code SystemRepository} runs.
   * Embeds its own copy of the dormant-sessions subquery so this one statement can be copied and
   * run alone — the combined script in {@link #renderPurgeScript} computes that set once instead
   * and is the one to use when running all three together.
   */
  private static String renderDeleteStatement(String tableName, String timestampColumn, Instant cutoff) {
    String dormantSessionIds = DORMANT_SESSION_IDS_SUBQUERY_FORMAT.formatted(cutoff);
    if (METRIC_POINTS_TABLE.equals(tableName)) {
      return METRIC_POINTS_DELETE_FORMAT.formatted(dormantSessionIds, cutoff);
    }
    return SESSION_GATED_DELETE_FORMAT.formatted(tableName, dormantSessionIds, timestampColumn, cutoff);
  }

  private static String renderPurgeScript(
      int retentionDays, Instant cutoff, long estimatedReclaimableBytes) {
    return PURGE_SCRIPT_HEADER.formatted(retentionDays, cutoff, estimatedReclaimableBytes)
        + PURGE_SCRIPT_FOOTER;
  }

  private static long toLong(Object value) {
    return ((Number) value).longValue();
  }

  private static int toInt(Object value) {
    return ((Number) value).intValue();
  }
}
