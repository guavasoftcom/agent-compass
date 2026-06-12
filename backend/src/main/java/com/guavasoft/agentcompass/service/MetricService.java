package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.mapper.MetricPointMapper;
import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostModelShare;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.EventRow;
import com.guavasoft.agentcompass.model.ExemplarPoint;
import com.guavasoft.agentcompass.model.ModelTokenShare;
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.model.TokenDistribution;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.model.TraceSpanDto;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetricService {

  private static final String INPUT_TYPE = "input";
  private static final String OUTPUT_TYPE = "output";
  private static final String CACHE_CREATION_TYPE = "cacheCreation";
  private static final String CACHE_READ_TYPE = "cacheRead";

  private static final long MIN_BUCKET_SECONDS = 60L;
  private static final int TARGET_BUCKETS_PER_WINDOW = 40;
  private static final int SECONDS_PER_MINUTE = 60;
  private static final int SESSION_RESULT_LIMIT = 500;
  private static final int DEFAULT_SESSION_PAGE_SIZE = 25;
  private static final int SESSION_TOTAL_COUNT_INDEX = 9;
  private static final int SESSION_COUNTS_SESSION_ID_INDEX = 0;
  private static final int SESSION_COUNTS_TOOL_CALL_INDEX = 1;
  private static final int SESSION_COUNTS_DENIAL_INDEX = 2;
  private static final int SESSIONS_TREND_BUCKETS = 24;

  private static final String START_TYPE_FRESH = "fresh";
  private static final String START_TYPE_RESUME = "resume";
  private static final String TERMINAL_INTERACTIVE = "interactive";
  private static final String TERMINAL_NON_INTERACTIVE = "non-interactive";

  private static final String SORT_DIRECTION_ASC = "asc";
  private static final String SORT_DIRECTION_DESC = "desc";
  private static final String DEFAULT_SORT_COLUMN = "cost";

  // Catalog
  private static final int CATALOG_SPARK_BUCKETS = 8;
  private static final long CARDINALITY_WARN_THRESHOLD = 1_000L;
  private static final long CARDINALITY_BAD_THRESHOLD = 5_000L;
  private static final String HEALTH_OK = "ok";
  private static final String HEALTH_WARN = "warn";
  private static final String HEALTH_BAD = "bad";
  private static final String METRIC_TYPE_COUNTER = "counter";

  // Cost
  private static final int COST_TREND_BUCKETS = 14;
  private static final double SECONDS_PER_HOUR = 3_600.0;
  private static final double HOURS_PER_MONTH = 720.0;
  private static final double TOKENS_PER_COST_UNIT = 1_000.0;
  private static final double HUNDRED_PERCENT = 100.0;

  // Distribution
  private static final int DISTRIBUTION_COLUMNS = 24;
  private static final int DISTRIBUTION_EXEMPLAR_LIMIT = 8;
  private static final long BAND_256K = 262_144L;
  private static final long BAND_128K = 131_072L;
  private static final long BAND_64K = 65_536L;
  private static final long BAND_32K = 32_768L;
  private static final long BAND_16K = 16_384L;
  private static final long BAND_8K = 8_192L;
  private static final long BAND_4K = 4_096L;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final String SESSION_ID_ATTRIBUTE = "session.id";
  private static final String MODEL_ATTRIBUTE = "model";
  private static final String STATUS_ERROR = "error";
  private static final String STATUS_OK = "ok";
  private static final String TRACE_ID_DISPLAY_SEPARATOR = "·";
  private static final int TRACE_ID_PREFIX_LENGTH = 4;
  private static final int TRACE_ID_SUFFIX_LENGTH = 5;
  private static final int TRACE_ID_SUFFIX_OFFSET = 4;

  // Trace span waterfall
  private static final long NANOS_PER_MILLI = 1_000_000L;
  private static final int SPAN_CAP = 50;
  private static final double SLOW_SPAN_THRESHOLD = 0.60;
  private static final String SPAN_KIND_ROOT = "root";
  private static final String SPAN_KIND_GENAI = "genai";
  private static final String SPAN_KIND_TOOL = "tool";
  private static final String SPAN_KIND_HTTP = "http";
  private static final String SPAN_KIND_DB = "db";

  // DataGrid column field -> whitelisted ORDER BY token understood by
  // aggregateSessionSummaries.
  private static final Map<String, String> SORT_COLUMNS_BY_FIELD = Map.of(
      "costUsd", "cost",
      "activeTimeSeconds", "active",
      "wallSeconds", "wall",
      "tokens", "tokens",
      "costPerActiveMinuteUsd", "costPerMinute",
      "startTimestamp", "started");

  private final MetricPointRepository metricPointRepository;
  private final MetricPointMapper metricPointMapper;
  private final TuningProperties tuningProperties;
  private final LogRecordRepository logRecordRepository;
  private final SpanRepository spanRepository;

  private record SessionCounts(long toolCallCount, long denialCount) {}

  public List<EventRow> recentEvents(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    List<MetricPointEntity> metricPointEntities = metricPointRepository.findAllMatchingFilters(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
    return metricPointMapper.toEventRows(metricPointEntities);
  }

  public List<String> availableAttributePairs(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp) {
    return metricPointRepository.findDistinctAttributePairs(
        toFilterArray(activeFilters), startTimestamp, endTimestamp);
  }

  public TokenUsageSummary aggregateTokenUsage(int minutes) {
    Instant end = Instant.now();
    Instant start = end.minus(Duration.ofMinutes(minutes));
    long bucketSeconds = bucketWidthSeconds(minutes);
    List<Object[]> rawRows = metricPointRepository.aggregateTokenUsageTimeseries(
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getTokenTypeAttribute(),
        start,
        bucketSeconds);
    List<ModelTokenShare> byModel = buildTokensByModel(start, end);
    CostSummary cost = aggregateCostSummary(start, end);
    return buildTokenUsageSummary(rawRows, bucketSeconds, byModel, cost);
  }

  public TokenUsageSummary aggregateTokenUsageInRange(Instant start, Instant end) {
    long windowSeconds = Math.max(1L, Duration.between(start, end).getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);
    List<Object[]> rawRows = metricPointRepository.aggregateTokenUsageTimeseriesInRange(
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getTokenTypeAttribute(),
        start,
        end,
        bucketSeconds);
    List<ModelTokenShare> byModel = buildTokensByModel(start, end);
    CostSummary cost = aggregateCostSummary(start, end);
    return buildTokenUsageSummary(rawRows, bucketSeconds, byModel, cost);
  }

  private List<ModelTokenShare> buildTokensByModel(Instant start, Instant end) {
    List<Object[]> modelRows = metricPointRepository.aggregateTokensByModel(
        tuningProperties.getTokenUsageMetric(),
        MODEL_ATTRIBUTE,
        start,
        end);
    long grandTotal = 0L;
    for (Object[] modelRow : modelRows) {
      grandTotal += modelRow[1] == null ? 0L : ((Number) modelRow[1]).longValue();
    }
    List<ModelTokenShare> shares = new ArrayList<>(modelRows.size());
    for (int colorIndex = 0; colorIndex < modelRows.size(); colorIndex++) {
      Object[] modelRow = modelRows.get(colorIndex);
      String modelName = modelRow[0] == null ? "unknown" : (String) modelRow[0];
      long modelTokens = modelRow[1] == null ? 0L : ((Number) modelRow[1]).longValue();
      int sharePercent = grandTotal == 0L ? 0 : (int) Math.round((double) modelTokens / grandTotal * HUNDRED_PERCENT);
      shares.add(new ModelTokenShare(modelName, formatTokenCount(modelTokens), sharePercent, colorIndex));
    }
    return shares;
  }

  private static TokenUsageSummary buildTokenUsageSummary(
      List<Object[]> rawRows, long bucketSeconds,
      List<ModelTokenShare> byModel, CostSummary cost) {
    // (bucket -> [input, output, cacheCreation, cacheRead]). LinkedHashMap
    // preserves the
    // ascending bucket order the query already produced.
    LinkedHashMap<Instant, long[]> bucketTotals = new LinkedHashMap<>();
    long inputTotal = 0L;
    long outputTotal = 0L;
    long cacheCreationTotal = 0L;
    long cacheReadTotal = 0L;

    for (Object[] row : rawRows) {
      Instant bucket = (Instant) row[0];
      String tokenType = (String) row[1];
      long total = ((Number) row[2]).longValue();
      long[] counts = bucketTotals.computeIfAbsent(bucket, ignored -> new long[4]);
      switch (tokenType) {
        case INPUT_TYPE -> {
          counts[0] += total;
          inputTotal += total;
        }
        case OUTPUT_TYPE -> {
          counts[1] += total;
          outputTotal += total;
        }
        case CACHE_CREATION_TYPE -> {
          counts[2] += total;
          cacheCreationTotal += total;
        }
        case CACHE_READ_TYPE -> {
          counts[3] += total;
          cacheReadTotal += total;
        }
        default -> {
          /* skip unknown token types so the ratio stays well-defined */ }
      }
    }

    List<TokenUsageSummary.Point> points = new ArrayList<>(bucketTotals.size());
    for (Map.Entry<Instant, long[]> entry : bucketTotals.entrySet().stream()
        .sorted(Comparator.comparing(Map.Entry::getKey))
        .toList()) {
      long[] counts = entry.getValue();
      points.add(new TokenUsageSummary.Point(
          entry.getKey(), counts[0], counts[1], counts[2], counts[3]));
    }

    long cacheDenominator = cacheCreationTotal + cacheReadTotal;
    double cacheReadRatio = cacheDenominator == 0L ? 0.0 : (double) cacheReadTotal / (double) cacheDenominator;

    return new TokenUsageSummary(
        inputTotal,
        outputTotal,
        cacheCreationTotal,
        cacheReadTotal,
        cacheReadRatio,
        bucketSeconds,
        points,
        byModel,
        cost);
  }

  public SessionSummaryPage sessionsSummary(
      int minutes, String sortField, String sortDirection, int page, int size) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return querySessionsPage(since, null, sortField, sortDirection, page, size);
  }

  public SessionSummaryPage sessionsSummaryInRange(
      Instant start, Instant end, String sortField, String sortDirection, int page, int size) {
    return querySessionsPage(start, end, sortField, sortDirection, page, size);
  }

  public SessionKpis sessionsKpis(int minutes) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return buildSessionKpis(since, null);
  }

  public SessionKpis sessionsKpisInRange(Instant start, Instant end) {
    return buildSessionKpis(start, end);
  }

  private SessionKpis buildSessionKpis(Instant start, Instant end) {
    List<Object[]> kpiRows = metricPointRepository.aggregateSessionKpis(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        start,
        end);
    // The trend needs a concrete upper bound for date_bin; the ?minutes= form leaves
    // end null, so close the window at "now".
    Instant trendEnd = end != null ? end : Instant.now();
    long windowSeconds = Math.max(1L, Duration.between(start, trendEnd).getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / SESSIONS_TREND_BUCKETS);
    List<Long> sessionsTrend = buildSessionsTrend(start, trendEnd, windowSeconds, bucketSeconds);
    return mapSessionKpis(kpiRows, sessionsTrend);
  }

  // Dense, zero-padded new-session counts per bucket. The query returns only
  // non-empty buckets, so we expand into a fixed-length array (one slot per bucket
  // that fits in the window) and drop the rare boundary index == bucketCount for a
  // session landing exactly on the window's upper edge.
  private List<Long> buildSessionsTrend(
      Instant start, Instant end, long windowSeconds, long bucketSeconds) {
    int bucketCount = (int) Math.max(1L, (windowSeconds + bucketSeconds - 1) / bucketSeconds);
    long[] dense = new long[bucketCount];
    List<Object[]> trendRows = metricPointRepository.aggregateNewSessionsTrend(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        start,
        end,
        bucketSeconds);
    for (Object[] trendRow : trendRows) {
      int bucketIndex = ((Number) trendRow[0]).intValue();
      long newSessions = trendRow[1] == null ? 0L : ((Number) trendRow[1]).longValue();
      if (bucketIndex >= 0 && bucketIndex < bucketCount) {
        dense[bucketIndex] = newSessions;
      }
    }
    List<Long> trend = new ArrayList<>(bucketCount);
    for (long bucketValue : dense) {
      trend.add(bucketValue);
    }
    return trend;
  }

  private SessionSummaryPage querySessionsPage(
      Instant start, Instant end, String sortField, String sortDirection, int page, int size) {
    int pageSize = clampPageSize(size);
    int pageOffset = Math.max(0, page) * pageSize;
    List<Object[]> rows = metricPointRepository.aggregateSessionSummaries(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getSessionCountMetric(),
        start,
        end,
        normalizeSortColumn(sortField),
        normalizeSortDirection(sortDirection),
        pageSize,
        pageOffset);
    long totalCount = rows.isEmpty() || rows.get(0)[SESSION_TOTAL_COUNT_INDEX] == null
        ? 0L
        : ((Number) rows.get(0)[SESSION_TOTAL_COUNT_INDEX]).longValue();
    List<String> sessionIds = rows.stream()
        .map(row -> (String) row[0])
        .filter(Objects::nonNull)
        .toList();
    Map<String, SessionCounts> countsBySessionId = sessionIds.isEmpty()
        ? Map.of()
        : buildSessionCountsMap(logRecordRepository.aggregateSessionCounts(
            sessionIds,
            tuningProperties.getToolEventName(),
            tuningProperties.getToolDecisionEventName()));
    return new SessionSummaryPage(mapSessionSummaries(rows, countsBySessionId), totalCount);
  }

  private static List<SessionSummary> mapSessionSummaries(
      List<Object[]> rows, Map<String, SessionCounts> countsBySessionId) {
    SessionCounts zeroCounts = new SessionCounts(0L, 0L);
    return rows.stream()
        .map(row -> {
          String sessionId = (String) row[0];
          SessionCounts counts = countsBySessionId.getOrDefault(sessionId, zeroCounts);
          return new SessionSummary(
              sessionId,
              row[1] == null ? 0.0 : ((Number) row[1]).doubleValue(),
              row[2] == null ? 0.0 : ((Number) row[2]).doubleValue(),
              (Instant) row[3],
              (Instant) row[4],
              row[5] == null ? 0L : ((Number) row[5]).longValue(),
              counts.toolCallCount(),
              counts.denialCount(),
              row[6] == null ? 0L : ((Number) row[6]).longValue(),
              normalizeTerminalType((String) row[7]),
              normalizeStartType((String) row[8]));
        })
        .toList();
  }

  // session.count's terminal.type is free-form; the UI badge is a two-value enum,
  // so anything that isn't exactly "interactive" reads as non-interactive.
  private static String normalizeTerminalType(String rawTerminalType) {
    return TERMINAL_INTERACTIVE.equals(rawTerminalType) ? TERMINAL_INTERACTIVE : TERMINAL_NON_INTERACTIVE;
  }

  // Sessions with no start_type (older data) bucket into "fresh" so the row badge
  // and the fresh/resume KPI split stay consistent.
  private static String normalizeStartType(String rawStartType) {
    return START_TYPE_RESUME.equals(rawStartType) ? START_TYPE_RESUME : START_TYPE_FRESH;
  }

  private static Map<String, SessionCounts> buildSessionCountsMap(List<Object[]> rows) {
    Map<String, SessionCounts> map = new LinkedHashMap<>(rows.size());
    for (Object[] row : rows) {
      String sessionId = (String) row[SESSION_COUNTS_SESSION_ID_INDEX];
      if (sessionId == null) {
        continue;
      }
      long toolCallCount = row[SESSION_COUNTS_TOOL_CALL_INDEX] == null
          ? 0L : ((Number) row[SESSION_COUNTS_TOOL_CALL_INDEX]).longValue();
      long denialCount = row[SESSION_COUNTS_DENIAL_INDEX] == null
          ? 0L : ((Number) row[SESSION_COUNTS_DENIAL_INDEX]).longValue();
      map.put(sessionId, new SessionCounts(toolCallCount, denialCount));
    }
    return map;
  }

  private static SessionKpis mapSessionKpis(List<Object[]> rows, List<Long> sessionsTrend) {
    if (rows.isEmpty()) {
      return new SessionKpis(0L, 0.0, 0.0, 0.0, sessionsTrend);
    }
    Object[] row = rows.get(0);
    return new SessionKpis(
        row[0] == null ? 0L : ((Number) row[0]).longValue(),
        row[1] == null ? 0.0 : ((Number) row[1]).doubleValue(),
        row[2] == null ? 0.0 : ((Number) row[2]).doubleValue(),
        row[3] == null ? 0.0 : ((Number) row[3]).doubleValue(),
        sessionsTrend);
  }

  // ---------------------------------------------------------------------------
  // Metric catalog
  // ---------------------------------------------------------------------------

  public List<CatalogMetric> aggregateMetricCatalog(Instant from, Instant to) {
    List<Object[]> summaryRows = metricPointRepository.aggregateCatalogSummary(from, to);
    long windowSeconds = Math.max(1L, Duration.between(from, to).getSeconds());
    long bucketSeconds = Math.max(1L, windowSeconds / CATALOG_SPARK_BUCKETS);
    List<Object[]> sparkRows = metricPointRepository.aggregateCatalogSparklines(from, to, bucketSeconds);

    Map<String, long[]> sparksByMetricName = buildSparkMap(sparkRows);

    return summaryRows.stream()
        .map(row -> {
          String metricName = (String) row[0];
          String unit = (String) row[1];
          long cardinality = row[2] == null ? 0L : ((Number) row[2]).longValue();
          String cardinalityLabel = formatCardinality(cardinality);
          String health = cardinalityHealth(cardinality);
          List<Long> spark = sparkBuckets(sparksByMetricName.getOrDefault(metricName, new long[CATALOG_SPARK_BUCKETS]));
          return new CatalogMetric(metricName, unit, METRIC_TYPE_COUNTER, cardinalityLabel, health, spark);
        })
        .toList();
  }

  private static Map<String, long[]> buildSparkMap(List<Object[]> sparkRows) {
    Map<String, long[]> sparksByMetricName = new HashMap<>();
    for (Object[] sparkRow : sparkRows) {
      String metricName = (String) sparkRow[0];
      int bucketIndex = ((Number) sparkRow[1]).intValue();
      long rowCount = ((Number) sparkRow[2]).longValue();
      long[] buckets = sparksByMetricName.computeIfAbsent(metricName, ignored -> new long[CATALOG_SPARK_BUCKETS]);
      if (bucketIndex >= 0 && bucketIndex < CATALOG_SPARK_BUCKETS) {
        buckets[bucketIndex] = rowCount;
      }
    }
    return sparksByMetricName;
  }

  private static List<Long> sparkBuckets(long[] buckets) {
    List<Long> result = new ArrayList<>(CATALOG_SPARK_BUCKETS);
    for (long bucketValue : buckets) {
      result.add(bucketValue);
    }
    return result;
  }

  private static String formatCardinality(long cardinality) {
    if (cardinality >= 1_000_000L) {
      return String.format(Locale.US, "%.1fM", cardinality / 1_000_000.0);
    }
    if (cardinality >= 1_000L) {
      return String.format(Locale.US, "%.1fK", cardinality / 1_000.0);
    }
    return String.valueOf(cardinality);
  }

  private static String cardinalityHealth(long cardinality) {
    if (cardinality > CARDINALITY_BAD_THRESHOLD) {
      return HEALTH_BAD;
    }
    if (cardinality >= CARDINALITY_WARN_THRESHOLD) {
      return HEALTH_WARN;
    }
    return HEALTH_OK;
  }

  // ---------------------------------------------------------------------------
  // Cost summary
  // ---------------------------------------------------------------------------

  public CostSummary aggregateCostSummary(Instant from, Instant to) {
    double windowSeconds = Math.max(1.0, Duration.between(from, to).getSeconds());
    double windowHours = windowSeconds / SECONDS_PER_HOUR;
    Duration windowDuration = Duration.ofSeconds((long) windowSeconds);
    Instant priorWindowStart = from.minus(windowDuration);
    String costMetricName = tuningProperties.getCostUsageMetric();
    String tokenMetricName = tuningProperties.getTokenUsageMetric();

    double currentSpend = queryCostTotal(costMetricName, from, to);
    double priorSpend = queryCostTotal(costMetricName, priorWindowStart, from);
    double deltaPct = priorSpend == 0.0 ? 0.0 : (currentSpend - priorSpend) / priorSpend * 100.0;

    double burnRate = currentSpend / windowHours;
    double projected30d = burnRate * HOURS_PER_MONTH;

    long totalTokens = queryTotalTokens(tokenMetricName, from, to);
    double costPer1k = totalTokens == 0L ? 0.0 : currentSpend / (totalTokens / TOKENS_PER_COST_UNIT);

    long trendBucketSeconds = Math.max(1L, (long) windowSeconds / COST_TREND_BUCKETS);
    List<Double> trend = buildCostTrend(costMetricName, from, to, trendBucketSeconds);
    List<CostModelShare> byModel = buildCostByModel(costMetricName, from, to, currentSpend);

    return new CostSummary(
        formatUsd(currentSpend),
        formatDeltaPct(deltaPct),
        formatBurnRate(burnRate),
        formatProjected(projected30d),
        formatCostPer1k(costPer1k),
        trend,
        byModel,
        "");
  }

  private double queryCostTotal(String metricName, Instant start, Instant end) {
    Number total = firstScalar(metricPointRepository.aggregateCostTotal(metricName, start, end));
    return total == null ? 0.0 : total.doubleValue();
  }

  private long queryTotalTokens(String metricName, Instant start, Instant end) {
    Number total = firstScalar(metricPointRepository.aggregateTotalTokens(metricName, start, end));
    return total == null ? 0L : total.longValue();
  }

  // Single-column aggregate queries (SUM/COUNT with no GROUP BY) return exactly one
  // row, but when that lone column is NULL Hibernate maps the whole row to a null
  // list element rather than Object[]{null} — so guarding only rows.get(0)[0]
  // NPEs. Handle the empty list, the null row, and the null cell uniformly.
  private static Number firstScalar(List<Object[]> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    Object[] firstRow = rows.get(0);
    if (firstRow == null || firstRow.length == 0 || firstRow[0] == null) {
      return null;
    }
    return (Number) firstRow[0];
  }

  private List<Double> buildCostTrend(String metricName, Instant from, Instant to, long bucketSeconds) {
    List<Object[]> trendRows = metricPointRepository.aggregateCostTrend(metricName, from, to, bucketSeconds);
    Map<Instant, Double> bucketValues = new LinkedHashMap<>();
    for (Object[] trendRow : trendRows) {
      Instant bucket = (Instant) trendRow[0];
      double bucketCost = trendRow[1] == null ? 0.0 : ((Number) trendRow[1]).doubleValue();
      bucketValues.put(bucket, bucketCost);
    }
    List<Double> trend = new ArrayList<>(bucketValues.size());
    for (double costValue : bucketValues.values()) {
      trend.add(costValue);
    }
    return trend;
  }

  private List<CostModelShare> buildCostByModel(
      String metricName, Instant from, Instant to, double totalSpend) {
    List<Object[]> modelRows = metricPointRepository.aggregateCostByModel(metricName, from, to);
    List<CostModelShare> byModel = new ArrayList<>(modelRows.size());
    for (int colorIndex = 0; colorIndex < modelRows.size(); colorIndex++) {
      Object[] modelRow = modelRows.get(colorIndex);
      String modelName = modelRow[0] == null ? "unknown" : (String) modelRow[0];
      double modelCost = modelRow[1] == null ? 0.0 : ((Number) modelRow[1]).doubleValue();
      int sharePercent = totalSpend == 0.0 ? 0 : (int) Math.round(modelCost / totalSpend * 100.0);
      byModel.add(new CostModelShare(modelName, formatUsd(modelCost), sharePercent, colorIndex));
    }
    return byModel;
  }

  private static String formatUsd(double amount) {
    if (amount >= 1_000_000.0) {
      return String.format(Locale.US, "$%.1fM", amount / 1_000_000.0);
    }
    if (amount >= 1_000.0) {
      return String.format(Locale.US, "$%,.0f", amount);
    }
    return String.format(Locale.US, "$%.2f", amount);
  }

  private static String formatDeltaPct(double deltaPct) {
    if (deltaPct >= 0.0) {
      return String.format(Locale.US, "+%.1f%%", deltaPct);
    }
    return String.format(Locale.US, "%.1f%%", deltaPct);
  }

  private static String formatBurnRate(double burnRate) {
    return String.format(Locale.US, "$%.0f/h", burnRate);
  }

  private static String formatProjected(double projected) {
    if (projected >= 1_000.0) {
      return String.format(Locale.US, "$%.1fK", projected / 1_000.0);
    }
    return String.format(Locale.US, "$%.0f", projected);
  }

  private static String formatCostPer1k(double costPer1k) {
    return String.format(Locale.US, "$%.3f", costPer1k);
  }

  // ---------------------------------------------------------------------------
  // Token distribution
  // ---------------------------------------------------------------------------

  public TokenDistribution aggregateTokenDistribution(Instant from, Instant to) {
    double windowSeconds = Math.max(1.0, Duration.between(from, to).getSeconds());
    long colWidthSeconds = Math.max(1L, (long) windowSeconds / DISTRIBUTION_COLUMNS);
    String tokenMetricName = tuningProperties.getTokenUsageMetric();

    List<Object[]> tokenRows = metricPointRepository.findTopTokenRows(
        tokenMetricName, from, to, colWidthSeconds,
        SESSION_ID_ATTRIBUTE, MODEL_ATTRIBUTE,
        DISTRIBUTION_EXEMPLAR_LIMIT);

    if (tokenRows.isEmpty()) {
      return new TokenDistribution(buildBandLabels(), Collections.emptyList());
    }

    // Collect distinct session ids to look up correlated spans.
    List<String> sessionIds = tokenRows.stream()
        .map(row -> (String) row[3])
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    Map<String, SpanInfo> spanInfoBySessionId = sessionIds.isEmpty()
        ? Collections.emptyMap()
        : buildSpanInfoMap(spanRepository.findLatestSpanPerSession(
            SESSION_ID_ATTRIBUTE,
            sessionIds.toArray(new String[0]),
            from, to));

    long maxTokenCount = ((Number) tokenRows.get(0)[0]).longValue();

    List<ExemplarPoint> exemplars = new ArrayList<>(tokenRows.size());
    for (int rowIndex = 0; rowIndex < tokenRows.size(); rowIndex++) {
      Object[] tokenRow = tokenRows.get(rowIndex);
      long tokenCount = ((Number) tokenRow[0]).longValue();
      Instant timestamp = (Instant) tokenRow[1];
      int colIndex = ((Number) tokenRow[2]).intValue();
      String sessionId = (String) tokenRow[3];
      String rawModel = (String) tokenRow[4];

      int bandRow = tokenCountToBandRow(tokenCount);
      SpanInfo spanInfo = sessionId != null ? spanInfoBySessionId.get(sessionId) : null;

      String traceIdDisplay = spanInfo != null ? formatTraceIdDisplay(spanInfo.traceId()) : "";
      String durationLabel = spanInfo != null ? formatDuration(spanInfo.durationNanos()) : "";
      String status = spanInfo != null && STATUS_ERROR.equalsIgnoreCase(spanInfo.statusCode())
          ? STATUS_ERROR : STATUS_OK;

      String modelDisplay = shortenModel(rawModel);
      String tsDisplay = formatTimestamp(timestamp);
      String bucketLabel = bandBucketLabel(bandRow);
      boolean isWorst = tokenCount == maxTokenCount && rowIndex == 0;

      List<List<String>> attributePairs = buildAttributePairs(rawModel, sessionId);

      List<TraceSpanDto> traceSpans = (spanInfo != null && spanInfo.traceId() != null
          && !spanInfo.traceId().isEmpty())
          ? buildTraceSpans(spanRepository.findByTraceIdOrderByStartTimestampAsc(spanInfo.traceId()))
          : Collections.emptyList();

      exemplars.add(new ExemplarPoint(
          colIndex, bandRow,
          formatTokenCount(tokenCount), durationLabel,
          modelDisplay, status, traceIdDisplay,
          bucketLabel, tsDisplay, isWorst,
          traceSpans, attributePairs));
    }

    return new TokenDistribution(buildBandLabels(), exemplars);
  }

  private record SpanInfo(String traceId, String statusCode, Long durationNanos) {}

  private static Map<String, SpanInfo> buildSpanInfoMap(List<Object[]> spanRows) {
    Map<String, SpanInfo> spanInfoMap = new HashMap<>(spanRows.size());
    for (Object[] spanRow : spanRows) {
      String sessionId = (String) spanRow[0];
      String traceId = (String) spanRow[1];
      String statusCode = (String) spanRow[2];
      Long durationNanos = spanRow[3] == null ? null : ((Number) spanRow[3]).longValue();
      if (sessionId != null) {
        spanInfoMap.put(sessionId, new SpanInfo(traceId, statusCode, durationNanos));
      }
    }
    return spanInfoMap;
  }

  private static List<String> buildBandLabels() {
    return List.of("256K", "128K", "64K", "32K", "16K", "8K", "4K", "0");
  }

  private static int tokenCountToBandRow(long tokenCount) {
    if (tokenCount >= BAND_256K) {
      return 0;
    }
    if (tokenCount >= BAND_128K) {
      return 1;
    }
    if (tokenCount >= BAND_64K) {
      return 2;
    }
    if (tokenCount >= BAND_32K) {
      return 3;
    }
    if (tokenCount >= BAND_16K) {
      return 4;
    }
    if (tokenCount >= BAND_8K) {
      return 5;
    }
    if (tokenCount >= BAND_4K) {
      return 6;
    }
    return 7;
  }

  private static String bandBucketLabel(int bandRow) {
    return switch (bandRow) {
      case 0 -> "128K – 256K+";
      case 1 -> "64K – 128K";
      case 2 -> "32K – 64K";
      case 3 -> "16K – 32K";
      case 4 -> "8K – 16K";
      case 5 -> "4K – 8K";
      case 6 -> "0 – 4K";
      default -> "0";
    };
  }

  private static String formatTokenCount(long tokenCount) {
    if (tokenCount >= 1_000_000L) {
      return String.format(Locale.US, "%.1fM", tokenCount / 1_000_000.0);
    }
    if (tokenCount >= 1_000L) {
      return String.format(Locale.US, "%.1fK", tokenCount / 1_000.0);
    }
    return String.valueOf(tokenCount);
  }

  private static String formatDuration(Long durationNanos) {
    if (durationNanos == null) {
      return "";
    }
    double seconds = durationNanos / NANOS_PER_SECOND;
    return String.format(Locale.US, "%.1fs", seconds);
  }

  private static String shortenModel(String model) {
    if (model == null) {
      return "";
    }
    return model.startsWith("claude-") ? model.substring("claude-".length()) : model;
  }

  private static String formatTraceIdDisplay(String traceId) {
    if (traceId == null || traceId.length() < TRACE_ID_PREFIX_LENGTH + TRACE_ID_SUFFIX_OFFSET + TRACE_ID_SUFFIX_LENGTH) {
      return traceId == null ? "" : traceId;
    }
    return traceId.substring(0, TRACE_ID_PREFIX_LENGTH)
        + TRACE_ID_DISPLAY_SEPARATOR
        + traceId.substring(TRACE_ID_SUFFIX_OFFSET, TRACE_ID_SUFFIX_OFFSET + TRACE_ID_SUFFIX_LENGTH);
  }

  private static String formatTimestamp(Instant timestamp) {
    if (timestamp == null) {
      return "";
    }
    return DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(ZoneOffset.UTC)
        .format(timestamp);
  }

  private static List<List<String>> buildAttributePairs(String model, String sessionId) {
    List<List<String>> pairs = new ArrayList<>();
    if (model != null && !model.isEmpty()) {
      pairs.add(List.of(MODEL_ATTRIBUTE, model));
    }
    if (sessionId != null && !sessionId.isEmpty()) {
      pairs.add(List.of(SESSION_ID_ATTRIBUTE, sessionId));
    }
    return Collections.unmodifiableList(pairs);
  }

  // Maps a DataGrid column field to the whitelisted ORDER BY token the native
  // query understands.
  // Unknown / null fields fall back to cost so a malformed sort param can never
  // reach the SQL.
  private static String normalizeSortColumn(String sortField) {
    if (sortField == null) {
      return DEFAULT_SORT_COLUMN;
    }
    return SORT_COLUMNS_BY_FIELD.getOrDefault(sortField, DEFAULT_SORT_COLUMN);
  }

  private static String normalizeSortDirection(String sortDirection) {
    return SORT_DIRECTION_ASC.equalsIgnoreCase(sortDirection) ? SORT_DIRECTION_ASC : SORT_DIRECTION_DESC;
  }

  private static int clampPageSize(int size) {
    if (size < 1) {
      return DEFAULT_SESSION_PAGE_SIZE;
    }
    return Math.min(size, SESSION_RESULT_LIMIT);
  }

  private static long bucketWidthSeconds(int minutes) {
    long windowSeconds = (long) minutes * SECONDS_PER_MINUTE;
    return Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);
  }

  private static String[] toFilterArray(List<String> activeFilters) {
    return activeFilters == null ? new String[0] : activeFilters.toArray(new String[0]);
  }

  // ---------------------------------------------------------------------------
  // Trace span waterfall builder
  // ---------------------------------------------------------------------------

  private List<TraceSpanDto> buildTraceSpans(List<SpanEntity> rawSpans) {
    if (rawSpans == null || rawSpans.isEmpty()) {
      return Collections.emptyList();
    }

    // Cap to the earliest SPAN_CAP spans (list is already ordered by start_timestamp ASC).
    List<SpanEntity> cappedSpans = rawSpans.size() > SPAN_CAP
        ? rawSpans.subList(0, SPAN_CAP)
        : rawSpans;

    // Find the trace start (minimum start_timestamp across all capped spans).
    Instant traceStart = cappedSpans.stream()
        .map(SpanEntity::getStartTimestamp)
        .filter(Objects::nonNull)
        .min(Comparator.naturalOrder())
        .orElse(Instant.EPOCH);

    // Build a set of span IDs present in this trace for root detection.
    java.util.Set<String> spanIdSet = new java.util.HashSet<>(cappedSpans.size());
    for (SpanEntity spanEntity : cappedSpans) {
      if (spanEntity.getSpanId() != null) {
        spanIdSet.add(spanEntity.getSpanId());
      }
    }

    // Compute depth via BFS: build parent→children map, assign root=0, child=parent+1.
    Map<String, List<String>> childrenByParentId = new HashMap<>();
    List<String> rootSpanIds = new ArrayList<>();
    for (SpanEntity spanEntity : cappedSpans) {
      String spanId = spanEntity.getSpanId();
      String parentSpanId = spanEntity.getParentSpanId();
      boolean isRoot = parentSpanId == null || parentSpanId.isEmpty() || !spanIdSet.contains(parentSpanId);
      if (isRoot) {
        rootSpanIds.add(spanId);
      } else {
        childrenByParentId.computeIfAbsent(parentSpanId, ignored -> new ArrayList<>()).add(spanId);
      }
    }

    Map<String, Integer> depthBySpanId = new HashMap<>(cappedSpans.size());
    java.util.Queue<String> bfsQueue = new java.util.ArrayDeque<>(rootSpanIds);
    for (String rootSpanId : rootSpanIds) {
      depthBySpanId.put(rootSpanId, 0);
    }
    while (!bfsQueue.isEmpty()) {
      String currentSpanId = bfsQueue.poll();
      int currentDepth = depthBySpanId.getOrDefault(currentSpanId, 0);
      List<String> children = childrenByParentId.getOrDefault(currentSpanId, Collections.emptyList());
      for (String childSpanId : children) {
        depthBySpanId.put(childSpanId, currentDepth + 1);
        bfsQueue.add(childSpanId);
      }
    }

    // Find the root span's durMs (or max durMs) for slow-span threshold computation.
    long rootDurMs = cappedSpans.stream()
        .filter(spanEntity -> {
          String parentSpanId = spanEntity.getParentSpanId();
          return parentSpanId == null || parentSpanId.isEmpty() || !spanIdSet.contains(parentSpanId);
        })
        .mapToLong(spanEntity -> spanEntity.getDurationNanos() == null
            ? 0L : spanEntity.getDurationNanos() / NANOS_PER_MILLI)
        .max()
        .orElse(0L);
    long slowThresholdMs = (long) (rootDurMs * SLOW_SPAN_THRESHOLD);

    // Build a lookup map for fast span access by spanId.
    Map<String, SpanEntity> spanEntityById = new HashMap<>(cappedSpans.size());
    for (SpanEntity spanEntity : cappedSpans) {
      if (spanEntity.getSpanId() != null) {
        spanEntityById.put(spanEntity.getSpanId(), spanEntity);
      }
    }

    // Transform each SpanEntity into a TraceSpanDto.
    List<TraceSpanDto> result = new ArrayList<>(cappedSpans.size());
    for (SpanEntity spanEntity : cappedSpans) {
      long offsetMs = spanEntity.getStartTimestamp() == null
          ? 0L
          : java.time.Duration.between(traceStart, spanEntity.getStartTimestamp()).toMillis();
      long durMs = spanEntity.getDurationNanos() == null
          ? 0L
          : spanEntity.getDurationNanos() / NANOS_PER_MILLI;
      int depth = depthBySpanId.getOrDefault(spanEntity.getSpanId(), 0);
      boolean isRootSpan = depth == 0;
      String derivedKind = deriveSpanKind(spanEntity, isRootSpan);
      boolean isSlow = slowThresholdMs > 0 && durMs >= slowThresholdMs;
      result.add(new TraceSpanDto(spanEntity.getName(), derivedKind, offsetMs, durMs, depth, isSlow));
    }

    // Order by offsetMs ascending (root/earliest first).
    result.sort(Comparator.comparingLong(TraceSpanDto::offsetMs));
    return Collections.unmodifiableList(result);
  }

  private String deriveSpanKind(SpanEntity spanEntity, boolean isRootSpan) {
    if (isRootSpan) {
      return SPAN_KIND_ROOT;
    }
    String spanName = spanEntity.getName() == null ? "" : spanEntity.getName().toLowerCase(Locale.ROOT);
    String scopeName = spanEntity.getScopeName() == null ? "" : spanEntity.getScopeName().toLowerCase(Locale.ROOT);

    if (spanName.contains("gen_ai") || spanName.contains("chat") || spanName.contains("llm")
        || spanName.contains("completion")
        || scopeName.contains("gen_ai") || scopeName.contains("chat") || scopeName.contains("llm")
        || scopeName.contains("completion")) {
      return SPAN_KIND_GENAI;
    }

    boolean nameStartsTool = spanEntity.getName() != null
        && spanEntity.getName().toLowerCase(Locale.ROOT).startsWith("tool");
    boolean scopeMatchesTool = spanEntity.getScopeName() != null
        && spanEntity.getScopeName().equals(tuningProperties.getToolSpanScope());
    boolean nameMatchesToolSpan = spanEntity.getName() != null
        && spanEntity.getName().equals(tuningProperties.getToolSpanName());
    if (nameStartsTool || scopeMatchesTool || nameMatchesToolSpan) {
      return SPAN_KIND_TOOL;
    }

    Map<String, Object> spanAttributes = spanEntity.getAttributes();
    boolean hasHttpAttribute = spanAttributes != null
        && spanAttributes.keySet().stream().anyMatch(key -> key.startsWith("http."));
    if (spanName.contains("http") || spanName.contains("/v1/") || hasHttpAttribute) {
      return SPAN_KIND_HTTP;
    }

    if (spanName.contains("db") || spanName.contains("sql") || spanName.contains("postgres")
        || spanName.contains("select ")) {
      return SPAN_KIND_DB;
    }

    return SPAN_KIND_TOOL;
  }
}
