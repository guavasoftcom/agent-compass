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
 * Backs the four {@code GET /api/trends/*} section endpoints: a before/after diff
 * comparing the selected window (current) against the immediately preceding period
 * of equal length (previous). Cost and token metrics read exclusively from the
 * {@code metric_points} cumulative-counter pipeline ({@code SUM(value_delta)});
 * reliability and activity metrics read exclusively from {@code log_records}'s
 * {@code tool_result} rows -- the two pipelines are never mixed within one
 * comparison (see backend/CLAUDE.md's two-pipelines note).
 *
 * <p>Each section builder runs its own independent set of repository calls rather
 * than sharing one combined query: {@link #querySessionTotals} and
 * {@link #queryMetricsSparklinesCombined} are each called from three of the four
 * builders (Cost, Token efficiency, Activity all need session data), which is
 * deliberate duplication -- the point of the per-section split is that each HTTP
 * endpoint does its own database round trips, so the slowest section (Reliability)
 * no longer blocks the other three.
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

  private static final String ROW_TYPE_COST = "cost";
  private static final String ROW_TYPE_TOKEN_TOTAL = "token_total";
  private static final String ROW_TYPE_TOKEN_TYPE = "token_type";

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

  public TrendsResponse costTrends(int minutes) {
    return buildCostTrends(buildContextForMinutes(minutes));
  }

  public TrendsResponse costTrendsInRange(Instant start, Instant end) {
    return buildCostTrends(buildContext(start, end));
  }

  public TrendsResponse tokenEfficiencyTrends(int minutes) {
    return buildTokenEfficiencyTrends(buildContextForMinutes(minutes));
  }

  public TrendsResponse tokenEfficiencyTrendsInRange(Instant start, Instant end) {
    return buildTokenEfficiencyTrends(buildContext(start, end));
  }

  public TrendsResponse reliabilityTrends(int minutes) {
    return buildReliabilityTrends(buildContextForMinutes(minutes));
  }

  public TrendsResponse reliabilityTrendsInRange(Instant start, Instant end) {
    return buildReliabilityTrends(buildContext(start, end));
  }

  public TrendsResponse activityTrends(int minutes) {
    return buildActivityTrends(buildContextForMinutes(minutes));
  }

  public TrendsResponse activityTrendsInRange(Instant start, Instant end) {
    return buildActivityTrends(buildContext(start, end));
  }

  // ---------------------------------------------------------------------------
  // Shared window/property context
  // ---------------------------------------------------------------------------

  /**
   * The from/to/priorFrom window math plus the {@link TuningProperties} reads every
   * section builder needs, computed once per request rather than once per section.
   */
  private record TrendsContext(
      Instant from, Instant to, Instant priorFrom, long bucketSeconds,
      String costMetric, String tokenMetric, String activeTimeMetric,
      String tokenTypeAttribute, String toolEventName, String successAttribute) {}

  private TrendsContext buildContextForMinutes(int minutes) {
    Instant to = Instant.now();
    Instant from = to.minus(Duration.ofMinutes(minutes));
    return buildContext(from, to);
  }

  private TrendsContext buildContext(Instant from, Instant to) {
    Duration windowDuration = Duration.between(from, to);
    Instant priorFrom = from.minus(windowDuration);
    long windowSeconds = Math.max(1L, windowDuration.getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / SPARKLINE_POINTS);

    return new TrendsContext(
        from, to, priorFrom, bucketSeconds,
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        tuningProperties.getTokenTypeAttribute(),
        tuningProperties.getToolEventName(),
        tuningProperties.getSuccessAttribute());
  }

  // ---------------------------------------------------------------------------
  // Section builders
  // ---------------------------------------------------------------------------

  private TrendsResponse buildCostTrends(TrendsContext context) {
    CombinedTotals combinedTotals = queryMetricsTotalsCombined(
        context.costMetric(), context.tokenMetric(), context.tokenTypeAttribute(),
        context.from(), context.to(), context.priorFrom());
    CostAndTokenTotals costAndTokenTotals = combinedTotals.costAndTokenTotals();
    SessionTotals sessionTotals = querySessionTotals(
        context.costMetric(), context.activeTimeMetric(), context.from(), context.to(), context.priorFrom());

    CombinedMetricsSparklines combinedMetricsSparklines = queryMetricsSparklinesCombined(
        context.costMetric(), context.tokenMetric(), context.activeTimeMetric(),
        context.from(), context.to(), context.priorFrom(), context.bucketSeconds());
    BucketSeries beforeCostAndTokens = combinedMetricsSparklines.before();
    BucketSeries afterCostAndTokens = combinedMetricsSparklines.after();
    SessionBucketSeries beforeSessions = combinedMetricsSparklines.beforeSessions();
    SessionBucketSeries afterSessions = combinedMetricsSparklines.afterSessions();

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

    return new TrendsResponse(
        new TrendsResponse.Window(context.from(), context.to()),
        new TrendsResponse.Window(context.priorFrom(), context.from()),
        metrics);
  }

  private TrendsResponse buildTokenEfficiencyTrends(TrendsContext context) {
    CombinedTotals combinedTotals = queryMetricsTotalsCombined(
        context.costMetric(), context.tokenMetric(), context.tokenTypeAttribute(),
        context.from(), context.to(), context.priorFrom());
    CostAndTokenTotals costAndTokenTotals = combinedTotals.costAndTokenTotals();
    CacheReadRatioTotals cacheReadRatioTotals = combinedTotals.cacheReadRatioTotals();
    SessionTotals sessionTotals = querySessionTotals(
        context.costMetric(), context.activeTimeMetric(), context.from(), context.to(), context.priorFrom());

    CombinedMetricsSparklines combinedMetricsSparklines = queryMetricsSparklinesCombined(
        context.costMetric(), context.tokenMetric(), context.activeTimeMetric(),
        context.from(), context.to(), context.priorFrom(), context.bucketSeconds());
    BucketSeries beforeCostAndTokens = combinedMetricsSparklines.before();
    BucketSeries afterCostAndTokens = combinedMetricsSparklines.after();
    SessionBucketSeries beforeSessions = combinedMetricsSparklines.beforeSessions();
    SessionBucketSeries afterSessions = combinedMetricsSparklines.afterSessions();
    CombinedCacheReadRatio combinedCacheReadRatio = queryCacheReadRatioTrendCombined(
        context.tokenMetric(), context.tokenTypeAttribute(),
        context.from(), context.to(), context.priorFrom(), context.bucketSeconds());
    double[] beforeCacheReadRatio = combinedCacheReadRatio.before();
    double[] afterCacheReadRatio = combinedCacheReadRatio.after();

    Map<String, TrendsResponse.MetricTrend> metrics = new LinkedHashMap<>();

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

    return new TrendsResponse(
        new TrendsResponse.Window(context.from(), context.to()),
        new TrendsResponse.Window(context.priorFrom(), context.from()),
        metrics);
  }

  private TrendsResponse buildReliabilityTrends(TrendsContext context) {
    ToolFailureTotals toolFailureTotals = queryToolFailureTotals(
        context.toolEventName(), context.successAttribute(), context.from(), context.to(), context.priorFrom());
    long sessionFailuresBefore;
    long sessionFailuresAfter;
    Object[] sessionFailureRow = firstRow(logRecordRepository.aggregateSessionFailuresCurrentAndPrior(
        context.toolEventName(), context.successAttribute(), context.from(), context.to(), context.priorFrom()));
    if (sessionFailureRow == null) {
      sessionFailuresBefore = 0L;
      sessionFailuresAfter = 0L;
    } else {
      sessionFailuresAfter = longAt(sessionFailureRow, 0);
      sessionFailuresBefore = longAt(sessionFailureRow, 1);
    }

    ToolFailureBucketSeries beforeToolFailures = queryToolFailureTrend(
        context.toolEventName(), context.successAttribute(), context.priorFrom(), context.from(), context.bucketSeconds());
    ToolFailureBucketSeries afterToolFailures = queryToolFailureTrend(
        context.toolEventName(), context.successAttribute(), context.from(), context.to(), context.bucketSeconds());
    double[] beforeSessionFailures = queryLongBucketSeries(logRecordRepository.aggregateSessionFailuresTrend(
        context.toolEventName(), context.successAttribute(), context.priorFrom(), context.from(), context.bucketSeconds()));
    double[] afterSessionFailures = queryLongBucketSeries(logRecordRepository.aggregateSessionFailuresTrend(
        context.toolEventName(), context.successAttribute(), context.from(), context.to(), context.bucketSeconds()));

    Map<String, TrendsResponse.MetricTrend> metrics = new LinkedHashMap<>();

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

    return new TrendsResponse(
        new TrendsResponse.Window(context.from(), context.to()),
        new TrendsResponse.Window(context.priorFrom(), context.from()),
        metrics);
  }

  private TrendsResponse buildActivityTrends(TrendsContext context) {
    SessionTotals sessionTotals = querySessionTotals(
        context.costMetric(), context.activeTimeMetric(), context.from(), context.to(), context.priorFrom());

    SessionBucketSeries beforeSessions = querySessionBucketSeries(
        context.costMetric(), context.activeTimeMetric(), context.priorFrom(), context.from(), context.bucketSeconds());
    SessionBucketSeries afterSessions = querySessionBucketSeries(
        context.costMetric(), context.activeTimeMetric(), context.from(), context.to(), context.bucketSeconds());

    Map<String, TrendsResponse.MetricTrend> metrics = new LinkedHashMap<>();

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
        new TrendsResponse.Window(context.from(), context.to()),
        new TrendsResponse.Window(context.priorFrom(), context.from()),
        metrics);
  }

  // ---------------------------------------------------------------------------
  // Current/prior scalar totals
  // ---------------------------------------------------------------------------

  private record CostAndTokenTotals(double costBefore, double costAfter, double tokensBefore, double tokensAfter) {}

  private record CacheReadRatioTotals(double ratioPctBefore, double ratioPctAfter) {}

  // Reads MetricPointRepository#aggregateMetricsTotalsCombined's single UNION ALL result
  // set -- replacing the three separate calls (aggregateCostCurrentAndPriorTotals,
  // aggregateTotalTokensCurrentAndPrior, aggregateTokenTypeCurrentAndPriorTotals) that
  // used to build queryCostAndTokenTotals and queryCacheReadRatioTotals independently --
  // and reassembles the identical two DTOs from the row_type-discriminated rows.
  private record CombinedTotals(CostAndTokenTotals costAndTokenTotals, CacheReadRatioTotals cacheReadRatioTotals) {}

  private CombinedTotals queryMetricsTotalsCombined(
      String costMetric, String tokenMetric, String tokenTypeAttribute, Instant from, Instant to, Instant priorFrom) {
    List<Object[]> rows = metricPointRepository.aggregateMetricsTotalsCombined(
        costMetric, tokenMetric, tokenTypeAttribute, from, to, priorFrom);
    double costAfter = 0.0;
    double costBefore = 0.0;
    double tokensAfter = 0.0;
    double tokensBefore = 0.0;
    double inputAfter = 0.0;
    double inputBefore = 0.0;
    double cacheCreationAfter = 0.0;
    double cacheCreationBefore = 0.0;
    double cacheReadAfter = 0.0;
    double cacheReadBefore = 0.0;
    for (Object[] row : rows) {
      String rowType = (String) row[0];
      String tokenType = (String) row[1];
      double currentTotal = doubleAt(row, 2);
      double priorTotal = doubleAt(row, 3);
      if (ROW_TYPE_COST.equals(rowType)) {
        costAfter = currentTotal;
        costBefore = priorTotal;
      } else if (ROW_TYPE_TOKEN_TOTAL.equals(rowType)) {
        tokensAfter = currentTotal;
        tokensBefore = priorTotal;
      } else if (ROW_TYPE_TOKEN_TYPE.equals(rowType)) {
        if (TOKEN_TYPE_INPUT.equals(tokenType)) {
          inputAfter = currentTotal;
          inputBefore = priorTotal;
        } else if (TOKEN_TYPE_CACHE_CREATION.equals(tokenType)) {
          cacheCreationAfter = currentTotal;
          cacheCreationBefore = priorTotal;
        } else if (TOKEN_TYPE_CACHE_READ.equals(tokenType)) {
          cacheReadAfter = currentTotal;
          cacheReadBefore = priorTotal;
        }
      }
    }
    CostAndTokenTotals costAndTokenTotals = new CostAndTokenTotals(costBefore, costAfter, tokensBefore, tokensAfter);
    CacheReadRatioTotals cacheReadRatioTotals = new CacheReadRatioTotals(
        cacheReadRatioPct(inputBefore, cacheCreationBefore, cacheReadBefore),
        cacheReadRatioPct(inputAfter, cacheCreationAfter, cacheReadAfter));
    return new CombinedTotals(costAndTokenTotals, cacheReadRatioTotals);
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

  private record SessionBucketSeries(double[] sessionCounts, double[] avgDurationSeconds) {}

  // Reads MetricPointRepository#aggregateMetricsSparklinesCombined's single
  // (period, bucket_index, cost_total, token_total, session_count,
  // avg_duration_seconds) result set into the same BucketSeries/
  // SessionBucketSeries shapes the four now-consolidated calls (two
  // aggregateCostAndTokenTrend + two aggregateSessionCountAndDurationTrend)
  // used to build separately -- 'current' rows fill the "after" arrays,
  // 'prior' rows fill "before", mirroring querySessionTotals' period split.
  private record CombinedMetricsSparklines(
      BucketSeries before, BucketSeries after, SessionBucketSeries beforeSessions, SessionBucketSeries afterSessions) {}

  private CombinedMetricsSparklines queryMetricsSparklinesCombined(
      String costMetric, String tokenMetric, String activeTimeMetric,
      Instant from, Instant to, Instant priorFrom, long bucketSeconds) {
    List<Object[]> rows = metricPointRepository.aggregateMetricsSparklinesCombined(
        costMetric, tokenMetric, activeTimeMetric, from, to, priorFrom, bucketSeconds);
    double[] beforeCosts = new double[SPARKLINE_POINTS];
    double[] afterCosts = new double[SPARKLINE_POINTS];
    double[] beforeTokens = new double[SPARKLINE_POINTS];
    double[] afterTokens = new double[SPARKLINE_POINTS];
    double[] beforeSessionCounts = new double[SPARKLINE_POINTS];
    double[] afterSessionCounts = new double[SPARKLINE_POINTS];
    double[] beforeAvgDurationSeconds = new double[SPARKLINE_POINTS];
    double[] afterAvgDurationSeconds = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 1);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      boolean current = SESSION_PERIOD_CURRENT.equals(row[0]);
      double costTotal = doubleAt(row, 2);
      double tokenTotal = doubleAt(row, 3);
      double sessionCount = doubleAt(row, 4);
      double avgDurationSeconds = doubleAt(row, 5);
      if (current) {
        afterCosts[bucketIndex] = costTotal;
        afterTokens[bucketIndex] = tokenTotal;
        afterSessionCounts[bucketIndex] = sessionCount;
        afterAvgDurationSeconds[bucketIndex] = avgDurationSeconds;
      } else {
        beforeCosts[bucketIndex] = costTotal;
        beforeTokens[bucketIndex] = tokenTotal;
        beforeSessionCounts[bucketIndex] = sessionCount;
        beforeAvgDurationSeconds[bucketIndex] = avgDurationSeconds;
      }
    }
    return new CombinedMetricsSparklines(
        new BucketSeries(beforeCosts, beforeTokens),
        new BucketSeries(afterCosts, afterTokens),
        new SessionBucketSeries(beforeSessionCounts, beforeAvgDurationSeconds),
        new SessionBucketSeries(afterSessionCounts, afterAvgDurationSeconds));
  }

  // Reads MetricPointRepository#aggregateTokenTypeSparklinesCombined's single
  // (period, bucket_index, token_type, total) result set, replacing the two
  // aggregateTokenTypeTrend calls the same way the method above replaces the
  // cost/token/session pair.
  private record CombinedCacheReadRatio(double[] before, double[] after) {}

  private CombinedCacheReadRatio queryCacheReadRatioTrendCombined(
      String tokenMetric, String tokenTypeAttribute, Instant from, Instant to, Instant priorFrom, long bucketSeconds) {
    List<Object[]> rows = metricPointRepository.aggregateTokenTypeSparklinesCombined(
        tokenMetric, tokenTypeAttribute, from, to, priorFrom, bucketSeconds);
    double[] beforeInput = new double[SPARKLINE_POINTS];
    double[] beforeCacheCreation = new double[SPARKLINE_POINTS];
    double[] beforeCacheRead = new double[SPARKLINE_POINTS];
    double[] afterInput = new double[SPARKLINE_POINTS];
    double[] afterCacheCreation = new double[SPARKLINE_POINTS];
    double[] afterCacheRead = new double[SPARKLINE_POINTS];
    for (Object[] row : rows) {
      int bucketIndex = intAt(row, 1);
      if (bucketIndex < 0 || bucketIndex >= SPARKLINE_POINTS) {
        continue;
      }
      boolean current = SESSION_PERIOD_CURRENT.equals(row[0]);
      String tokenType = (String) row[2];
      double total = doubleAt(row, 3);
      if (TOKEN_TYPE_INPUT.equals(tokenType)) {
        if (current) {
          afterInput[bucketIndex] = total;
        } else {
          beforeInput[bucketIndex] = total;
        }
      } else if (TOKEN_TYPE_CACHE_CREATION.equals(tokenType)) {
        if (current) {
          afterCacheCreation[bucketIndex] = total;
        } else {
          beforeCacheCreation[bucketIndex] = total;
        }
      } else if (TOKEN_TYPE_CACHE_READ.equals(tokenType)) {
        if (current) {
          afterCacheRead[bucketIndex] = total;
        } else {
          beforeCacheRead[bucketIndex] = total;
        }
      }
    }
    double[] beforeRatioPct = new double[SPARKLINE_POINTS];
    double[] afterRatioPct = new double[SPARKLINE_POINTS];
    for (int index = 0; index < SPARKLINE_POINTS; index++) {
      beforeRatioPct[index] = cacheReadRatioPct(beforeInput[index], beforeCacheCreation[index], beforeCacheRead[index]);
      afterRatioPct[index] = cacheReadRatioPct(afterInput[index], afterCacheCreation[index], afterCacheRead[index]);
    }
    return new CombinedCacheReadRatio(beforeRatioPct, afterRatioPct);
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

  // Single-period counterpart to queryMetricsSparklinesCombined's session half,
  // for callers (buildActivityTrends) that need only the session bucket series
  // and not the cost/token scan the combined query also pays for.
  private SessionBucketSeries querySessionBucketSeries(
      String costMetric, String activeTimeMetric, Instant start, Instant end, long bucketSeconds) {
    List<Object[]> rows =
        metricPointRepository.aggregateSessionCountAndDurationTrend(costMetric, activeTimeMetric, start, end, bucketSeconds);
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
