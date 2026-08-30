package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.TrendsResponse;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs {@code GET /api/trends}: a before/after diff comparing the selected window
 * (current) against the immediately preceding period of equal length (previous),
 * across 11 metrics in 4 groups. Cost and token metrics read exclusively from the
 * {@code metric_points} cumulative-counter pipeline ({@code SUM(value_delta)});
 * reliability and activity metrics read exclusively from {@code log_records}'s
 * {@code tool_result} rows -- the two pipelines are never mixed within one
 * comparison (see backend/CLAUDE.md's two-pipelines note).
 *
 * <p>Kept as its own service rather than folded into {@link MetricService} /
 * {@link LogService} because it aggregates across both of their repositories to
 * build one response.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrendService {

  private static final int SPARKLINE_POINTS = 7;
  private static final long MIN_BUCKET_SECONDS = 60L;

  private static final String DIRECTION_DOWN = "down";
  private static final String DIRECTION_UP = "up";

  private static final String TOKEN_TYPE_INPUT = "input";
  private static final String TOKEN_TYPE_CACHE_CREATION = "cacheCreation";
  private static final String TOKEN_TYPE_CACHE_READ = "cacheRead";

  private static final double PERCENT_SCALE = 100.0;
  private static final double TOKENS_PER_BLENDED_RATE_UNIT = 1_000_000.0;
  private static final double SECONDS_PER_MINUTE = 60.0;

  private static final String SESSION_PERIOD_CURRENT = "current";

  private static final String METRIC_TOTAL_COST = "total_cost";
  private static final String METRIC_COST_PER_SESSION = "cost_per_session";
  private static final String METRIC_BLENDED_RATE_PER_1M = "blended_rate_per_1m";
  private static final String METRIC_CACHE_READ_RATIO_PCT = "cache_read_ratio_pct";
  private static final String METRIC_TOKENS_TOTAL = "tokens_total";
  private static final String METRIC_TOKENS_PER_SESSION = "tokens_per_session";
  private static final String METRIC_TOOL_ERRORS = "tool_errors";
  private static final String METRIC_ERROR_RATE_PCT = "error_rate_pct";
  private static final String METRIC_SESSION_FAILURES = "session_failures";
  private static final String METRIC_SESSIONS = "sessions";
  private static final String METRIC_AVG_DURATION_MIN = "avg_duration_min";

  private final MetricPointRepository metricPointRepository;
  private final LogRecordRepository logRecordRepository;
  private final TuningProperties tuningProperties;

  public TrendsResponse trends(int minutes) {
    Instant to = Instant.now();
    Instant from = to.minus(Duration.ofMinutes(minutes));
    return buildTrends(from, to);
  }

  public TrendsResponse trendsInRange(Instant start, Instant end) {
    return buildTrends(start, end);
  }

  private TrendsResponse buildTrends(Instant from, Instant to) {
    Duration windowDuration = Duration.between(from, to);
    Instant priorFrom = from.minus(windowDuration);
    long windowSeconds = Math.max(1L, windowDuration.getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / SPARKLINE_POINTS);

    String costMetric = tuningProperties.getCostUsageMetric();
    String tokenMetric = tuningProperties.getTokenUsageMetric();
    String activeTimeMetric = tuningProperties.getActiveTimeMetric();
    String tokenTypeAttribute = tuningProperties.getTokenTypeAttribute();
    String toolEventName = tuningProperties.getToolEventName();
    String successAttribute = tuningProperties.getSuccessAttribute();

    CostAndTokenTotals costAndTokenTotals = queryCostAndTokenTotals(costMetric, tokenMetric, from, to, priorFrom);
    CacheReadRatioTotals cacheReadRatioTotals =
        queryCacheReadRatioTotals(tokenMetric, tokenTypeAttribute, from, to, priorFrom);
    SessionTotals sessionTotals =
        querySessionTotals(costMetric, activeTimeMetric, from, to, priorFrom);
    ToolFailureTotals toolFailureTotals =
        queryToolFailureTotals(toolEventName, successAttribute, from, to, priorFrom);
    long sessionFailuresBefore;
    long sessionFailuresAfter;
    Object[] sessionFailureRow = firstRow(logRecordRepository.aggregateSessionFailuresCurrentAndPrior(
        toolEventName, successAttribute, from, to, priorFrom));
    if (sessionFailureRow == null) {
      sessionFailuresBefore = 0L;
      sessionFailuresAfter = 0L;
    } else {
      sessionFailuresAfter = longAt(sessionFailureRow, 0);
      sessionFailuresBefore = longAt(sessionFailureRow, 1);
    }

    // Sparklines: one call per side per query family, bucketed to the same
    // fixed 7-point width on both sides (the two windows are equal length).
    BucketSeries beforeCostAndTokens = queryCostAndTokenTrend(costMetric, tokenMetric, priorFrom, from, bucketSeconds);
    BucketSeries afterCostAndTokens = queryCostAndTokenTrend(costMetric, tokenMetric, from, to, bucketSeconds);
    double[] beforeCacheReadRatio = queryCacheReadRatioTrend(tokenMetric, tokenTypeAttribute, priorFrom, from, bucketSeconds);
    double[] afterCacheReadRatio = queryCacheReadRatioTrend(tokenMetric, tokenTypeAttribute, from, to, bucketSeconds);
    SessionBucketSeries beforeSessions =
        querySessionTrend(costMetric, activeTimeMetric, priorFrom, from, bucketSeconds);
    SessionBucketSeries afterSessions =
        querySessionTrend(costMetric, activeTimeMetric, from, to, bucketSeconds);
    ToolFailureBucketSeries beforeToolFailures =
        queryToolFailureTrend(toolEventName, successAttribute, priorFrom, from, bucketSeconds);
    ToolFailureBucketSeries afterToolFailures =
        queryToolFailureTrend(toolEventName, successAttribute, from, to, bucketSeconds);
    double[] beforeSessionFailures = queryLongBucketSeries(
        logRecordRepository.aggregateSessionFailuresTrend(toolEventName, successAttribute, priorFrom, from, bucketSeconds));
    double[] afterSessionFailures = queryLongBucketSeries(
        logRecordRepository.aggregateSessionFailuresTrend(toolEventName, successAttribute, from, to, bucketSeconds));

    Map<String, TrendsResponse.MetricTrend> metrics = new LinkedHashMap<>();

    metrics.put(METRIC_TOTAL_COST, new TrendsResponse.MetricTrend(
        costAndTokenTotals.costBefore(), costAndTokenTotals.costAfter(),
        toBoxedList(beforeCostAndTokens.costs()), toBoxedList(afterCostAndTokens.costs()), DIRECTION_DOWN));

    metrics.put(METRIC_COST_PER_SESSION, new TrendsResponse.MetricTrend(
        safeDivide(costAndTokenTotals.costBefore(), sessionTotals.sessionsBefore()),
        safeDivide(costAndTokenTotals.costAfter(), sessionTotals.sessionsAfter()),
        toBoxedList(divideBucketArrays(beforeCostAndTokens.costs(), beforeSessions.sessionCounts())),
        toBoxedList(divideBucketArrays(afterCostAndTokens.costs(), afterSessions.sessionCounts())),
        DIRECTION_DOWN));

    metrics.put(METRIC_BLENDED_RATE_PER_1M, new TrendsResponse.MetricTrend(
        blendedRatePer1m(costAndTokenTotals.costBefore(), costAndTokenTotals.tokensBefore()),
        blendedRatePer1m(costAndTokenTotals.costAfter(), costAndTokenTotals.tokensAfter()),
        toBoxedList(blendedRateSeries(beforeCostAndTokens)), toBoxedList(blendedRateSeries(afterCostAndTokens)),
        DIRECTION_DOWN));

    metrics.put(METRIC_CACHE_READ_RATIO_PCT, new TrendsResponse.MetricTrend(
        cacheReadRatioTotals.ratioPctBefore(), cacheReadRatioTotals.ratioPctAfter(),
        toBoxedList(beforeCacheReadRatio), toBoxedList(afterCacheReadRatio), DIRECTION_UP));

    metrics.put(METRIC_TOKENS_TOTAL, new TrendsResponse.MetricTrend(
        costAndTokenTotals.tokensBefore(), costAndTokenTotals.tokensAfter(),
        toBoxedList(beforeCostAndTokens.tokens()), toBoxedList(afterCostAndTokens.tokens()), DIRECTION_DOWN));

    metrics.put(METRIC_TOKENS_PER_SESSION, new TrendsResponse.MetricTrend(
        safeDivide(costAndTokenTotals.tokensBefore(), sessionTotals.sessionsBefore()),
        safeDivide(costAndTokenTotals.tokensAfter(), sessionTotals.sessionsAfter()),
        toBoxedList(divideBucketArrays(beforeCostAndTokens.tokens(), beforeSessions.sessionCounts())),
        toBoxedList(divideBucketArrays(afterCostAndTokens.tokens(), afterSessions.sessionCounts())),
        DIRECTION_DOWN));

    metrics.put(METRIC_TOOL_ERRORS, new TrendsResponse.MetricTrend(
        toolFailureTotals.failuresBefore(), toolFailureTotals.failuresAfter(),
        toBoxedList(beforeToolFailures.failures()), toBoxedList(afterToolFailures.failures()), DIRECTION_DOWN));

    metrics.put(METRIC_ERROR_RATE_PCT, new TrendsResponse.MetricTrend(
        toolFailureTotals.errorRatePctBefore(), toolFailureTotals.errorRatePctAfter(),
        toBoxedList(errorRateSeries(beforeToolFailures)), toBoxedList(errorRateSeries(afterToolFailures)),
        DIRECTION_DOWN));

    metrics.put(METRIC_SESSION_FAILURES, new TrendsResponse.MetricTrend(
        (double) sessionFailuresBefore, (double) sessionFailuresAfter,
        toBoxedList(beforeSessionFailures), toBoxedList(afterSessionFailures), DIRECTION_DOWN));

    metrics.put(METRIC_SESSIONS, new TrendsResponse.MetricTrend(
        sessionTotals.sessionsBefore(), sessionTotals.sessionsAfter(),
        toBoxedList(beforeSessions.sessionCounts()), toBoxedList(afterSessions.sessionCounts()), DIRECTION_UP));

    metrics.put(METRIC_AVG_DURATION_MIN, new TrendsResponse.MetricTrend(
        sessionTotals.avgDurationSecondsBefore() / SECONDS_PER_MINUTE,
        sessionTotals.avgDurationSecondsAfter() / SECONDS_PER_MINUTE,
        toBoxedList(toMinutes(beforeSessions.avgDurationSeconds())),
        toBoxedList(toMinutes(afterSessions.avgDurationSeconds())),
        DIRECTION_DOWN));

    return new TrendsResponse(
        new TrendsResponse.Window(from, to),
        new TrendsResponse.Window(priorFrom, from),
        metrics);
  }

  // ---------------------------------------------------------------------------
  // Current/prior scalar totals
  // ---------------------------------------------------------------------------

  private record CostAndTokenTotals(double costBefore, double costAfter, double tokensBefore, double tokensAfter) {}

  private CostAndTokenTotals queryCostAndTokenTotals(
      String costMetric, String tokenMetric, Instant from, Instant to, Instant priorFrom) {
    Object[] costRow = firstRow(metricPointRepository.aggregateCostCurrentAndPriorTotals(costMetric, from, to, priorFrom));
    Object[] tokenRow = firstRow(metricPointRepository.aggregateTotalTokensCurrentAndPrior(tokenMetric, from, to, priorFrom));
    double costAfter = costRow == null ? 0.0 : doubleAt(costRow, 0);
    double costBefore = costRow == null ? 0.0 : doubleAt(costRow, 1);
    double tokensAfter = tokenRow == null ? 0.0 : doubleAt(tokenRow, 0);
    double tokensBefore = tokenRow == null ? 0.0 : doubleAt(tokenRow, 1);
    return new CostAndTokenTotals(costBefore, costAfter, tokensBefore, tokensAfter);
  }

  private record CacheReadRatioTotals(double ratioPctBefore, double ratioPctAfter) {}

  private CacheReadRatioTotals queryCacheReadRatioTotals(
      String tokenMetric, String tokenTypeAttribute, Instant from, Instant to, Instant priorFrom) {
    List<Object[]> rows = metricPointRepository.aggregateTokenTypeCurrentAndPriorTotals(
        tokenMetric, tokenTypeAttribute, from, to, priorFrom);
    double inputAfter = 0.0;
    double inputBefore = 0.0;
    double cacheCreationAfter = 0.0;
    double cacheCreationBefore = 0.0;
    double cacheReadAfter = 0.0;
    double cacheReadBefore = 0.0;
    for (Object[] row : rows) {
      String tokenType = (String) row[0];
      double afterTotal = doubleAt(row, 1);
      double beforeTotal = doubleAt(row, 2);
      if (TOKEN_TYPE_INPUT.equals(tokenType)) {
        inputAfter = afterTotal;
        inputBefore = beforeTotal;
      } else if (TOKEN_TYPE_CACHE_CREATION.equals(tokenType)) {
        cacheCreationAfter = afterTotal;
        cacheCreationBefore = beforeTotal;
      } else if (TOKEN_TYPE_CACHE_READ.equals(tokenType)) {
        cacheReadAfter = afterTotal;
        cacheReadBefore = beforeTotal;
      }
    }
    return new CacheReadRatioTotals(
        cacheReadRatioPct(inputBefore, cacheCreationBefore, cacheReadBefore),
        cacheReadRatioPct(inputAfter, cacheCreationAfter, cacheReadAfter));
  }

  private static double cacheReadRatioPct(double input, double cacheCreation, double cacheRead) {
    double denominator = input + cacheCreation + cacheRead;
    return denominator == 0.0 ? 0.0 : cacheRead / denominator * PERCENT_SCALE;
  }

  private record SessionTotals(
      double sessionsBefore, double sessionsAfter, double avgDurationSecondsBefore, double avgDurationSecondsAfter) {}

  private SessionTotals querySessionTotals(
      String costMetric, String activeTimeMetric, Instant from, Instant to, Instant priorFrom) {
    List<Object[]> rows = metricPointRepository.aggregateSessionCountAndDurationCurrentAndPrior(
        costMetric, activeTimeMetric, from, to, priorFrom);
    double sessionsBefore = 0.0;
    double sessionsAfter = 0.0;
    double avgDurationSecondsBefore = 0.0;
    double avgDurationSecondsAfter = 0.0;
    for (Object[] row : rows) {
      String period = (String) row[0];
      double sessionCount = doubleAt(row, 1);
      double avgDurationSeconds = doubleAt(row, 2);
      if (SESSION_PERIOD_CURRENT.equals(period)) {
        sessionsAfter = sessionCount;
        avgDurationSecondsAfter = avgDurationSeconds;
      } else {
        sessionsBefore = sessionCount;
        avgDurationSecondsBefore = avgDurationSeconds;
      }
    }
    return new SessionTotals(sessionsBefore, sessionsAfter, avgDurationSecondsBefore, avgDurationSecondsAfter);
  }

  private record ToolFailureTotals(
      double failuresBefore, double failuresAfter, double errorRatePctBefore, double errorRatePctAfter) {}

  private ToolFailureTotals queryToolFailureTotals(
      String toolEventName, String successAttribute, Instant from, Instant to, Instant priorFrom) {
    Object[] row = firstRow(logRecordRepository.aggregateToolFailureCurrentAndPriorTotals(
        toolEventName, successAttribute, from, to, priorFrom));
    if (row == null) {
      return new ToolFailureTotals(0.0, 0.0, 0.0, 0.0);
    }
    double totalCallsAfter = doubleAt(row, 0);
    double failuresAfter = doubleAt(row, 1);
    double totalCallsBefore = doubleAt(row, 2);
    double failuresBefore = doubleAt(row, 3);
    return new ToolFailureTotals(
        failuresBefore, failuresAfter,
        safeDivide(failuresBefore, totalCallsBefore) * PERCENT_SCALE,
        safeDivide(failuresAfter, totalCallsAfter) * PERCENT_SCALE);
  }

  // ---------------------------------------------------------------------------
  // Sparklines
  // ---------------------------------------------------------------------------

  private record BucketSeries(double[] costs, double[] tokens) {}

  private BucketSeries queryCostAndTokenTrend(
      String costMetric, String tokenMetric, Instant start, Instant end, long bucketSeconds) {
    List<Object[]> rows = metricPointRepository.aggregateCostAndTokenTrend(costMetric, tokenMetric, start, end, bucketSeconds);
    double[] costs = new double[SPARKLINE_POINTS];
    double[] tokens = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 0);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      costs[bucketIndex] = doubleAt(row, 1);
      tokens[bucketIndex] = doubleAt(row, 2);
    }
    return new BucketSeries(costs, tokens);
  }

  private double[] queryCacheReadRatioTrend(
      String tokenMetric, String tokenTypeAttribute, Instant start, Instant end, long bucketSeconds) {
    List<Object[]> rows =
        metricPointRepository.aggregateTokenTypeTrend(tokenMetric, tokenTypeAttribute, start, end, bucketSeconds);
    double[] input = new double[SPARKLINE_POINTS];
    double[] cacheCreation = new double[SPARKLINE_POINTS];
    double[] cacheRead = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 0);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      String tokenType = (String) row[1];
      double total = doubleAt(row, 2);
      if (TOKEN_TYPE_INPUT.equals(tokenType)) {
        input[bucketIndex] = total;
      } else if (TOKEN_TYPE_CACHE_CREATION.equals(tokenType)) {
        cacheCreation[bucketIndex] = total;
      } else if (TOKEN_TYPE_CACHE_READ.equals(tokenType)) {
        cacheRead[bucketIndex] = total;
      }
    }
    double[] ratioPct = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      ratioPct[index] = cacheReadRatioPct(input[index], cacheCreation[index], cacheRead[index]);
    }
    return ratioPct;
  }

  private record SessionBucketSeries(double[] sessionCounts, double[] avgDurationSeconds) {}

  private SessionBucketSeries querySessionTrend(
      String costMetric, String activeTimeMetric, Instant start, Instant end, long bucketSeconds) {
    List<Object[]> rows = metricPointRepository.aggregateSessionCountAndDurationTrend(
        costMetric, activeTimeMetric, start, end, bucketSeconds);
    double[] sessionCounts = new double[SPARKLINE_POINTS];
    double[] avgDurationSeconds = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 0);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      sessionCounts[bucketIndex] = doubleAt(row, 1);
      avgDurationSeconds[bucketIndex] = doubleAt(row, 2);
    }
    return new SessionBucketSeries(sessionCounts, avgDurationSeconds);
  }

  private record ToolFailureBucketSeries(double[] totalCalls, double[] failures) {}

  private ToolFailureBucketSeries queryToolFailureTrend(
      String toolEventName, String successAttribute, Instant start, Instant end, long bucketSeconds) {
    List<Object[]> rows =
        logRecordRepository.aggregateToolFailureTrend(toolEventName, successAttribute, start, end, bucketSeconds);
    double[] totalCalls = new double[SPARKLINE_POINTS];
    double[] failures = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 0);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      totalCalls[bucketIndex] = doubleAt(row, 1);
      failures[bucketIndex] = doubleAt(row, 2);
    }
    return new ToolFailureBucketSeries(totalCalls, failures);
  }

  private double[] queryLongBucketSeries(List<Object[]> rows) {
    double[] values = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 0);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      values[bucketIndex] = doubleAt(row, 1);
    }
    return values;
  }

  private static double[] divideBucketArrays(double[] numerators, double[] denominators) {
    double[] result = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      result[index] = safeDivide(numerators[index], denominators[index]);
    }
    return result;
  }

  private static double[] blendedRateSeries(BucketSeries series) {
    double[] result = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      result[index] = blendedRatePer1m(series.costs()[index], series.tokens()[index]);
    }
    return result;
  }

  private static double[] errorRateSeries(ToolFailureBucketSeries series) {
    double[] result = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      result[index] = safeDivide(series.failures()[index], series.totalCalls()[index]) * PERCENT_SCALE;
    }
    return result;
  }

  private static double[] toMinutes(double[] seconds) {
    double[] result = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      result[index] = seconds[index] / SECONDS_PER_MINUTE;
    }
    return result;
  }

  private static double blendedRatePer1m(double cost, double tokens) {
    return tokens == 0.0 ? 0.0 : cost / tokens * TOKENS_PER_BLENDED_RATE_UNIT;
  }

  private static double safeDivide(double numerator, double denominator) {
    return denominator == 0.0 ? 0.0 : numerator / denominator;
  }

  private static List<Double> toBoxedList(double[] values) {
    List<Double> boxed = new ArrayList<>(values.length);
    for (double value : values) {
      boxed.add(value);
    }
    return boxed;
  }

  // ---------------------------------------------------------------------------
  // Row helpers
  // ---------------------------------------------------------------------------

  private static Object[] firstRow(List<Object[]> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    return rows.get(0);
  }

  private static double doubleAt(Object[] row, int columnIndex) {
    return row[columnIndex] == null ? 0.0 : ((Number) row[columnIndex]).doubleValue();
  }

  private static long longAt(Object[] row, int columnIndex) {
    return row[columnIndex] == null ? 0L : ((Number) row[columnIndex]).longValue();
  }

  private static int intAt(Object[] row, int columnIndex) {
    return row[columnIndex] == null ? 0 : ((Number) row[columnIndex]).intValue();
  }
}
