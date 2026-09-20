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
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.mapper.MetricPointMapper;
import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostModelShare;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.MetricDistribution;
import com.guavasoft.agentcompass.model.MetricPage;
import com.guavasoft.agentcompass.model.ModelTokenShare;
import com.guavasoft.agentcompass.model.SessionCacheEfficiency;
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.model.SessionTokenBreakdown;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

  // Column indices into aggregateSessionSummaries' Object[] rows. The four token
  // columns replaced the former single "tokens" total (see the query's Javadoc):
  // the row's tokens field is now derived in Java as their sum.
  private static final int SESSION_ROW_INPUT_TOKENS_INDEX = 6;
  private static final int SESSION_ROW_OUTPUT_TOKENS_INDEX = 7;
  private static final int SESSION_ROW_CACHE_CREATION_TOKENS_INDEX = 8;
  private static final int SESSION_ROW_CACHE_READ_TOKENS_INDEX = 9;
  private static final int SESSION_ROW_TERMINAL_TYPE_INDEX = 10;
  private static final int SESSION_ROW_START_TYPE_INDEX = 11;
  private static final int SESSION_TOTAL_COUNT_INDEX = 12;
  private static final int SESSION_COUNTS_SESSION_ID_INDEX = 0;
  private static final int SESSION_COUNTS_TOOL_CALL_INDEX = 1;
  private static final int SESSION_COUNTS_DENIAL_INDEX = 2;
  private static final int SESSION_COUNTS_USER_PROMPT_COUNT_INDEX = 3;
  private static final int SESSION_COUNTS_FIRST_USER_PROMPT_INDEX = 4;
  private static final int SESSIONS_TREND_BUCKETS = 24;

  /** Rows returned by the worst-cache-efficiency ranking when the caller sends no limit. */
  private static final int DEFAULT_CACHE_EFFICIENCY_LIMIT = 8;

  /**
   * Lower bound applied to the configured cache-efficiency token floor. The floor
   * is the ranking query's only guarantee that the ratio's denominator is non-zero,
   * so a configured 0 must never reach the SQL.
   */
  private static final long MINIMUM_CACHE_EFFICIENCY_TOKEN_FLOOR = 1L;

  private static final String START_TYPE_FRESH = "fresh";
  private static final String START_TYPE_RESUME = "resume";
  private static final String TERMINAL_INTERACTIVE = "interactive";
  private static final String TERMINAL_NON_INTERACTIVE = "non-interactive";

  // Matches a trace's root claude_code.interaction span, for the Sessions grid's per-row
  // running indicator (SessionSummary#inProgress -- see findInProgressSessionIds). Mirrors
  // LogService.INTERACTION_ROOT_SPAN_NAME_PATTERN exactly; duplicated rather than shared for
  // the same reason that constant gives for its own duplication of SpanRepository's ~40
  // inline copies -- a structural span-name literal, not a deployment-tunable property.
  private static final String INTERACTION_ROOT_SPAN_NAME_PATTERN = "claude_code.interaction%";

  private static final String SORT_DIRECTION_ASC = "asc";
  private static final String SORT_DIRECTION_DESC = "desc";
  private static final String DEFAULT_SORT_COLUMN = "cost";

  // Catalog
  private static final int CATALOG_SPARK_BUCKETS = 8;
  private static final String METRIC_TYPE_COUNTER = "counter";

  // Cost
  private static final int COST_TREND_BUCKETS = 14;
  private static final double SECONDS_PER_HOUR = 3_600.0;
  private static final double HOURS_PER_MONTH = 720.0;
  private static final double TOKENS_PER_COST_UNIT = 1_000.0;
  private static final double HUNDRED_PERCENT = 100.0;

  // B4 perf: discriminator values produced by MetricPointRepository#aggregateCostBreakdown's
  // GROUPING SETS ((), (bucket), (model)) query, and the column indices of its rows.
  private static final String COST_BREAKDOWN_ROW_TYPE_BUCKET = "bucket";
  private static final String COST_BREAKDOWN_ROW_TYPE_MODEL = "model";
  private static final int COST_BREAKDOWN_ROW_TYPE_INDEX = 0;
  private static final int COST_BREAKDOWN_BUCKET_INDEX = 1;
  private static final int COST_BREAKDOWN_MODEL_INDEX = 2;
  private static final int COST_BREAKDOWN_AMOUNT_INDEX = 3;

  // Column indices of MetricPointRepository#aggregateCostCurrentAndPriorTotals's
  // single-row (current_total, prior_total) result.
  private static final int COST_TOTALS_CURRENT_INDEX = 0;
  private static final int COST_TOTALS_PRIOR_INDEX = 1;

  // Discriminator values produced by MetricPointRepository#aggregateTokenUsageBreakdown's
  // GROUPING SETS ((bucket, token_type), (model), ()) query, and the column
  // indices of its rows.
  private static final String TOKEN_BREAKDOWN_ROW_TYPE_BUCKET = "bucket";
  private static final String TOKEN_BREAKDOWN_ROW_TYPE_MODEL = "model";
  private static final String TOKEN_BREAKDOWN_ROW_TYPE_TOTAL = "total";
  private static final int TOKEN_BREAKDOWN_ROW_TYPE_INDEX = 0;
  private static final int TOKEN_BREAKDOWN_BUCKET_INDEX = 1;
  private static final int TOKEN_BREAKDOWN_TOKEN_TYPE_INDEX = 2;
  private static final int TOKEN_BREAKDOWN_MODEL_INDEX = 3;
  private static final int TOKEN_BREAKDOWN_AMOUNT_INDEX = 4;
  // Per-kind FILTER columns, meaningful only on 'model' rows -- see the query's
  // own comment for why they ride every grouping set rather than a second query.
  private static final int TOKEN_BREAKDOWN_INPUT_INDEX = 5;
  private static final int TOKEN_BREAKDOWN_OUTPUT_INDEX = 6;
  private static final int TOKEN_BREAKDOWN_CACHE_CREATION_INDEX = 7;
  private static final int TOKEN_BREAKDOWN_CACHE_READ_INDEX = 8;

  // Distribution: the newest this-many requests in the window are returned (ascending).
  private static final int DISTRIBUTION_REQUEST_LIMIT = 2_000;
  // Column indices of LogRecordRepository#findNewestTokenRequestPoints / findNewestCostRequestPoints rows.
  private static final int DISTRIBUTION_ROW_TIMESTAMP_INDEX = 0;
  private static final int DISTRIBUTION_ROW_VALUE_INDEX = 1;
  private static final int DISTRIBUTION_ROW_TRACE_ID_INDEX = 2;
  private static final int DISTRIBUTION_ROW_REQUEST_ID_INDEX = 3;
  private static final int DISTRIBUTION_ROW_FAILED_INDEX = 4;
  private static final String MODEL_ATTRIBUTE = "model";

  // DataGrid column field -> whitelisted ORDER BY token understood by
  // aggregateSessionSummaries.
  private static final Map<String, String> SORT_COLUMNS_BY_FIELD = Map.of(
      "costUsd", "cost",
      "activeTimeSeconds", "active",
      "wallSeconds", "wall",
      "tokens", "tokens",
      "cacheEfficiency", "cacheEfficiency",
      "costPerActiveMinuteUsd", "costPerMinute",
      "startTimestamp", "started",
      "endTimestamp", "ended");

  private final MetricPointRepository metricPointRepository;
  private final MetricPointMapper metricPointMapper;
  private final TuningProperties tuningProperties;
  private final LogRecordRepository logRecordRepository;
  private final SpanRepository spanRepository;

  private record SessionCounts(
      long toolCallCount, long denialCount, long userPromptCount, String firstUserPrompt) {}

  /**
   * Offset-paged rows for the Metrics DataGrid. Replaces the former unbounded
   * {@code findAllMatchingFilters} call (MEDIUM-3: unbounded result set) with a
   * clamped {@code LIMIT}/{@code OFFSET} query, mirroring {@code LogService#offsetPage}.
   */
  public MetricPage recentEvents(
      List<String> activeFilters, Instant startTimestamp, Instant endTimestamp, int page, int size) {
    String[] filters = toFilterArray(activeFilters);
    long totalCount = metricPointRepository.countMatchingFilters(filters, startTimestamp, endTimestamp);

    int pageSize = PageBounds.clampPageSize(size, PageBounds.DEFAULT_OFFSET_PAGE_SIZE);
    int pageOffset = PageBounds.computeOffset(page, pageSize);
    List<MetricPointEntity> metricPointEntities = metricPointRepository.findPageMatchingFilters(
        filters, startTimestamp, endTimestamp, pageSize, pageOffset);

    return new MetricPage(metricPointMapper.toEventRows(metricPointEntities), totalCount);
  }

  public TokenUsageSummary aggregateTokenUsage(int minutes, String repositoryUrl) {
    Instant end = Instant.now();
    Instant start = end.minus(Duration.ofMinutes(minutes));
    long bucketSeconds = bucketWidthSeconds(minutes);
    return buildTokenUsageSummaryForWindow(start, end, bucketSeconds, repositoryUrl);
  }

  public TokenUsageSummary aggregateTokenUsageInRange(Instant start, Instant end, String repositoryUrl) {
    long windowSeconds = Math.max(1L, Duration.between(start, end).getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);
    return buildTokenUsageSummaryForWindow(start, end, bucketSeconds, repositoryUrl);
  }

  // Perf: aggregateTokenUsageBreakdown replaces what used to be three separate
  // near-full-table scans of the token.usage metric (bucket-by-type timeseries,
  // per-model totals, grand total) with one GROUPING SETS query -- see that
  // query's Javadoc. The grand total it already returns is also what
  // aggregateCostTotalsAndByModel needs for costPer1k, so the Tokens page's cost
  // figure no longer pays for its own separate aggregateTotalTokens scan either.
  //
  // This endpoint's cost figure deliberately uses aggregateCostTotalsAndByModel
  // rather than the full aggregateCostSummary: the Tokens page (this method's
  // only caller) reads CostSummary.spend24h, .deltaPct, and .byModel off the
  // embedded object (see TokensPageView.tsx / CostByModelCard) but never .trend
  // -- so aggregateCostTotalsAndByModel still runs aggregateCostBreakdown's
  // GROUPING SETS scan for the per-model rows (cheap: one pass either way) but
  // requests it with a single time bucket spanning the whole window, discarding
  // the bucket rows a real trend would need rather than computing a 14-bucket
  // series solely to throw it away. aggregateCostSummary itself is unchanged
  // and still backs GET /api/metrics/cost, which is the endpoint that actually
  // needs the trend.
  private TokenUsageSummary buildTokenUsageSummaryForWindow(
      Instant start, Instant end, long bucketSeconds, String repositoryUrl) {
    List<Object[]> breakdownRows = metricPointRepository.aggregateTokenUsageBreakdown(
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getTokenTypeAttribute(),
        MODEL_ATTRIBUTE,
        INPUT_TYPE,
        OUTPUT_TYPE,
        CACHE_CREATION_TYPE,
        CACHE_READ_TYPE,
        start,
        end,
        bucketSeconds,
        repositoryUrl);
    List<ModelTokenShare> byModel = buildTokensByModel(breakdownRows);
    long totalTokens = extractTokenGrandTotal(breakdownRows);
    CostSummary cost = aggregateCostTotalsAndByModel(start, end, totalTokens, repositoryUrl);
    return buildTokenUsageSummary(breakdownRows, bucketSeconds, byModel, cost);
  }

  private static List<ModelTokenShare> buildTokensByModel(List<Object[]> breakdownRows) {
    List<Object[]> modelRows = breakdownRows.stream()
        .filter(row -> TOKEN_BREAKDOWN_ROW_TYPE_MODEL.equals(row[TOKEN_BREAKDOWN_ROW_TYPE_INDEX])
            && row[TOKEN_BREAKDOWN_MODEL_INDEX] != null)
        .toList();
    long grandTotal = 0L;
    for (Object[] modelRow : modelRows) {
      grandTotal += modelRow[TOKEN_BREAKDOWN_AMOUNT_INDEX] == null
          ? 0L : ((Number) modelRow[TOKEN_BREAKDOWN_AMOUNT_INDEX]).longValue();
    }
    List<ModelTokenShare> shares = new ArrayList<>(modelRows.size());
    for (int colorIndex = 0; colorIndex < modelRows.size(); colorIndex++) {
      Object[] modelRow = modelRows.get(colorIndex);
      String modelName = (String) modelRow[TOKEN_BREAKDOWN_MODEL_INDEX];
      long modelTokens = modelRow[TOKEN_BREAKDOWN_AMOUNT_INDEX] == null
          ? 0L : ((Number) modelRow[TOKEN_BREAKDOWN_AMOUNT_INDEX]).longValue();
      int sharePercent = grandTotal == 0L ? 0 : (int) Math.round((double) modelTokens / grandTotal * HUNDRED_PERCENT);
      SessionTokenBreakdown breakdown = new SessionTokenBreakdown(
          longAt(modelRow, TOKEN_BREAKDOWN_INPUT_INDEX),
          longAt(modelRow, TOKEN_BREAKDOWN_OUTPUT_INDEX),
          longAt(modelRow, TOKEN_BREAKDOWN_CACHE_CREATION_INDEX),
          longAt(modelRow, TOKEN_BREAKDOWN_CACHE_READ_INDEX));
      shares.add(new ModelTokenShare(modelName, formatTokenCount(modelTokens), sharePercent, colorIndex, breakdown));
    }
    return shares;
  }

  private static long longAt(Object[] row, int index) {
    return row[index] == null ? 0L : ((Number) row[index]).longValue();
  }

  private static long extractTokenGrandTotal(List<Object[]> breakdownRows) {
    for (Object[] row : breakdownRows) {
      if (TOKEN_BREAKDOWN_ROW_TYPE_TOTAL.equals(row[TOKEN_BREAKDOWN_ROW_TYPE_INDEX])) {
        return row[TOKEN_BREAKDOWN_AMOUNT_INDEX] == null ? 0L : ((Number) row[TOKEN_BREAKDOWN_AMOUNT_INDEX]).longValue();
      }
    }
    return 0L;
  }

  private static TokenUsageSummary buildTokenUsageSummary(
      List<Object[]> breakdownRows, long bucketSeconds,
      List<ModelTokenShare> byModel, CostSummary cost) {
    // (bucket -> [input, output, cacheCreation, cacheRead]). LinkedHashMap
    // preserves the ascending bucket order aggregateTokenUsageBreakdown's ORDER
    // BY already produced.
    LinkedHashMap<Instant, long[]> bucketTotals = new LinkedHashMap<>();
    long inputTotal = 0L;
    long outputTotal = 0L;
    long cacheCreationTotal = 0L;
    long cacheReadTotal = 0L;

    for (Object[] row : breakdownRows) {
      if (!TOKEN_BREAKDOWN_ROW_TYPE_BUCKET.equals(row[TOKEN_BREAKDOWN_ROW_TYPE_INDEX])) {
        continue;
      }
      String tokenType = (String) row[TOKEN_BREAKDOWN_TOKEN_TYPE_INDEX];
      if (tokenType == null) {
        continue;
      }
      Instant bucket = (Instant) row[TOKEN_BREAKDOWN_BUCKET_INDEX];
      long total = ((Number) row[TOKEN_BREAKDOWN_AMOUNT_INDEX]).longValue();
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

    // Window-level cache efficiency, defined identically to the per-session ratio
    // the Sessions grid column sorts and renders: cacheRead over ALL input-side
    // tokens. Output is excluded because it is generated, never sent. Keeping
    // cacheCreation in the denominator is what makes the number a cost proxy —
    // dropping it would let a session that constantly rebuilds its cache read as
    // efficient. Change this and the frontend's shared cacheEfficiencyRatio helper
    // plus aggregateSessionSummaries' 'cacheEfficiency' ORDER BY together.
    long cacheDenominator = inputTotal + cacheCreationTotal + cacheReadTotal;
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
      int minutes, String sortField, String sortDirection, int page, int size, String repositoryUrl) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return querySessionsPage(since, null, sortField, sortDirection, page, size, repositoryUrl);
  }

  public SessionSummaryPage sessionsSummaryInRange(
      Instant start, Instant end, String sortField, String sortDirection, int page, int size,
      String repositoryUrl) {
    return querySessionsPage(start, end, sortField, sortDirection, page, size, repositoryUrl);
  }

  public SessionKpis sessionsKpis(int minutes, String repositoryUrl) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return buildSessionKpis(since, null, repositoryUrl);
  }

  public SessionKpis sessionsKpisInRange(Instant start, Instant end, String repositoryUrl) {
    return buildSessionKpis(start, end, repositoryUrl);
  }

  public List<SessionCacheEfficiency> worstCacheEfficiencySessions(int minutes, int limit, String repositoryUrl) {
    Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
    return buildWorstCacheEfficiencySessions(since, null, limit, repositoryUrl);
  }

  public List<SessionCacheEfficiency> worstCacheEfficiencySessionsInRange(
      Instant start, Instant end, int limit, String repositoryUrl) {
    return buildWorstCacheEfficiencySessions(start, end, limit, repositoryUrl);
  }

  // Sessions with the lowest share of input-side tokens served from cache, worst
  // first. The token floor is clamped to at least one token: it is the ranking
  // query's only guarantee that the ratio's denominator is non-zero, so a
  // misconfigured tuning.cache-efficiency-minimum-input-tokens=0 must not be able
  // to reach the SQL. The result limit goes through the same PageBounds clamp
  // every other list endpoint uses.
  private List<SessionCacheEfficiency> buildWorstCacheEfficiencySessions(
      Instant start, Instant end, int limit, String repositoryUrl) {
    long minimumInputSideTokens =
        Math.max(MINIMUM_CACHE_EFFICIENCY_TOKEN_FLOOR, tuningProperties.getCacheEfficiencyMinimumInputTokens());
    List<Object[]> rows = metricPointRepository.aggregateWorstCacheEfficiencySessions(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getTokenTypeAttribute(),
        INPUT_TYPE,
        OUTPUT_TYPE,
        CACHE_CREATION_TYPE,
        CACHE_READ_TYPE,
        start,
        end,
        minimumInputSideTokens,
        PageBounds.clampPageSize(limit, DEFAULT_CACHE_EFFICIENCY_LIMIT),
        repositoryUrl);

    // A lookup keyed on the ranking's own session ids -- it already comes back one
    // row per session, so there is no second aggregation to write.
    //
    // This used to reuse the Sessions grid's aggregateSessionCounts "purely for its
    // firstUserPrompt column" and paid dearly for it: that query is unwindowed by
    // design, matches every event_name, and reads three jsonb keys per row, so a
    // 10-session prompt lookup visited every log record those sessions ever emitted
    // -- 3544 ms and 421K buffers on the live database, against 18 ms and 573 for
    // the purpose-built query. Only the prompt was ever read here; the three counts
    // it also computed were discarded. See aggregateFirstUserPromptsForSessions'
    // own comment for the measurements.
    List<String> sessionIds = rows.stream().map(row -> (String) row[0]).toList();
    Map<String, String> firstUserPromptBySessionId = sessionIds.isEmpty()
        ? Map.of()
        : buildFirstUserPromptMap(logRecordRepository.aggregateFirstUserPromptsForSessions(
            sessionIds,
            tuningProperties.getUserPromptEventName(),
            tuningProperties.getPromptAttribute()));

    // Row shape: session_id, cache_efficiency, cache_read_tokens, input_side_tokens
    // (read only by ORDER BY, not mapped into the record -- SessionCacheEfficiency
    // derives it from the three input-side token-kind fields, and derives
    // totalTokens from all four), input_tokens, cache_creation_tokens,
    // output_tokens, cost_usd, last_seen.
    List<SessionCacheEfficiency> sessions = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      String sessionId = (String) row[0];
      sessions.add(new SessionCacheEfficiency(
          sessionId,
          ((Number) row[1]).doubleValue(),
          ((Number) row[2]).longValue(),
          ((Number) row[4]).longValue(),
          ((Number) row[5]).longValue(),
          ((Number) row[6]).longValue(),
          ((Number) row[7]).doubleValue(),
          (Instant) row[8],
          firstUserPromptBySessionId.get(sessionId)));
    }
    return sessions;
  }

  private SessionKpis buildSessionKpis(Instant start, Instant end, String repositoryUrl) {
    List<Object[]> kpiRows = metricPointRepository.aggregateSessionKpis(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        start,
        end,
        repositoryUrl);
    // The trend needs a concrete upper bound for date_bin; the ?minutes= form leaves
    // end null, so close the window at "now".
    Instant trendEnd = end != null ? end : Instant.now();
    long windowSeconds = Math.max(1L, Duration.between(start, trendEnd).getSeconds());
    long bucketSeconds = Math.max(MIN_BUCKET_SECONDS, windowSeconds / SESSIONS_TREND_BUCKETS);
    List<Long> sessionsTrend =
        buildSessionsTrend(start, trendEnd, windowSeconds, bucketSeconds, repositoryUrl);
    return mapSessionKpis(kpiRows, sessionsTrend);
  }

  // Dense, zero-padded new-session counts per bucket. The query returns only
  // non-empty buckets, so we expand into a fixed-length array (one slot per bucket
  // that fits in the window) and drop the rare boundary index == bucketCount for a
  // session landing exactly on the window's upper edge.
  private List<Long> buildSessionsTrend(
      Instant start, Instant end, long windowSeconds, long bucketSeconds, String repositoryUrl) {
    int bucketCount = (int) Math.max(1L, (windowSeconds + bucketSeconds - 1) / bucketSeconds);
    long[] dense = new long[bucketCount];
    List<Object[]> trendRows = metricPointRepository.aggregateNewSessionsTrend(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        start,
        end,
        bucketSeconds,
        repositoryUrl);
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
      Instant start, Instant end, String sortField, String sortDirection, int page, int size,
      String repositoryUrl) {
    int pageSize = PageBounds.clampPageSize(size, PageBounds.DEFAULT_OFFSET_PAGE_SIZE);
    int pageOffset = PageBounds.computeOffset(page, pageSize);
    List<Object[]> rows = metricPointRepository.aggregateSessionSummaries(
        tuningProperties.getCostUsageMetric(),
        tuningProperties.getActiveTimeMetric(),
        tuningProperties.getTokenUsageMetric(),
        tuningProperties.getSessionCountMetric(),
        tuningProperties.getTokenTypeAttribute(),
        INPUT_TYPE,
        OUTPUT_TYPE,
        CACHE_CREATION_TYPE,
        CACHE_READ_TYPE,
        start,
        end,
        normalizeSortColumn(sortField),
        normalizeSortDirection(sortDirection),
        pageSize,
        pageOffset,
        repositoryUrl);
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
            tuningProperties.getToolDecisionEventName(),
            tuningProperties.getUserPromptEventName(),
            tuningProperties.getPromptAttribute()));
    // Bulk, page-scoped, not window-scoped: a session started long before the window can
    // still be running right now, so this asks about EVERY row on the returned page rather
    // than filtering by the window's own start/end. See LogService#resolveRunningTurnIndex
    // for the identical single-session liveness definition this bulk query mirrors.
    Set<String> inProgressSessionIds = sessionIds.isEmpty() ? Set.of() : resolveInProgressSessionIds(sessionIds);
    return new SessionSummaryPage(mapSessionSummaries(rows, countsBySessionId, inProgressSessionIds), totalCount);
  }

  // A session is running when its newest turn has not finished, OR when a turn that already
  // finished still has a dispatched subagent working under its trace. The second half is the one
  // the newest-turn rule cannot see: a session driving background subagents typically has a
  // short <task-notification> as its newest turn while the real work bills to an older turn's
  // trace. Same two-part rule LogService#promptsForSession applies per turn, so a grid row and
  // the timeline cards inside its drawer never disagree about whether the session is live.
  private Set<String> resolveInProgressSessionIds(List<String> sessionIds) {
    Set<String> inProgressSessionIds = new HashSet<>(logRecordRepository.findInProgressSessionIds(
        sessionIds,
        tuningProperties.getUserPromptEventName(),
        INTERACTION_ROOT_SPAN_NAME_PATTERN,
        Instant.now().minus(LogService.IN_PROGRESS_STALENESS_LIMIT)));
    for (Object[] row : logRecordRepository.findBackgroundActiveTraces(
        sessionIds,
        INTERACTION_ROOT_SPAN_NAME_PATTERN,
        Instant.now().minus(LogService.BACKGROUND_ACTIVITY_WINDOW),
        LogService.ROOT_SPAN_CLOSE_GRACE_SECONDS)) {
      inProgressSessionIds.add((String) row[0]);
    }
    return inProgressSessionIds;
  }

  private static List<SessionSummary> mapSessionSummaries(
      List<Object[]> rows, Map<String, SessionCounts> countsBySessionId, Set<String> inProgressSessionIds) {
    SessionCounts zeroCounts = new SessionCounts(0L, 0L, 0L, null);
    return rows.stream()
        .map(row -> {
          String sessionId = (String) row[0];
          SessionCounts counts = countsBySessionId.getOrDefault(sessionId, zeroCounts);
          SessionTokenBreakdown tokenBreakdown = new SessionTokenBreakdown(
              row[SESSION_ROW_INPUT_TOKENS_INDEX] == null ? 0L : ((Number) row[SESSION_ROW_INPUT_TOKENS_INDEX]).longValue(),
              row[SESSION_ROW_OUTPUT_TOKENS_INDEX] == null ? 0L
                  : ((Number) row[SESSION_ROW_OUTPUT_TOKENS_INDEX]).longValue(),
              row[SESSION_ROW_CACHE_CREATION_TOKENS_INDEX] == null ? 0L
                  : ((Number) row[SESSION_ROW_CACHE_CREATION_TOKENS_INDEX]).longValue(),
              row[SESSION_ROW_CACHE_READ_TOKENS_INDEX] == null ? 0L
                  : ((Number) row[SESSION_ROW_CACHE_READ_TOKENS_INDEX]).longValue());
          return new SessionSummary(
              sessionId,
              row[1] == null ? 0.0 : ((Number) row[1]).doubleValue(),
              row[2] == null ? 0.0 : ((Number) row[2]).doubleValue(),
              (Instant) row[3],
              (Instant) row[4],
              row[5] == null ? 0L : ((Number) row[5]).longValue(),
              counts.toolCallCount(),
              counts.denialCount(),
              tokenBreakdown.total(),
              normalizeTerminalType((String) row[SESSION_ROW_TERMINAL_TYPE_INDEX]),
              normalizeStartType((String) row[SESSION_ROW_START_TYPE_INDEX]),
              counts.firstUserPrompt(),
              counts.userPromptCount(),
              tokenBreakdown,
              inProgressSessionIds.contains(sessionId));
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

  // aggregateFirstUserPromptsForSessions' row shape is (session_id, first_user_prompt).
  // A session whose prompts are all empty or absent has no row rather than a null prompt,
  // so a missing key reads back as null -- the same "no prompt captured" value the wider
  // counts query produced via a null in its last column.
  private static Map<String, String> buildFirstUserPromptMap(List<Object[]> rows) {
    Map<String, String> firstUserPromptBySessionId = new LinkedHashMap<>(rows.size());
    for (Object[] row : rows) {
      firstUserPromptBySessionId.put((String) row[0], (String) row[1]);
    }
    return firstUserPromptBySessionId;
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
      long userPromptCount = row[SESSION_COUNTS_USER_PROMPT_COUNT_INDEX] == null
          ? 0L : ((Number) row[SESSION_COUNTS_USER_PROMPT_COUNT_INDEX]).longValue();
      String firstUserPrompt = (String) row[SESSION_COUNTS_FIRST_USER_PROMPT_INDEX];
      map.put(sessionId, new SessionCounts(toolCallCount, denialCount, userPromptCount, firstUserPrompt));
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
          String cardinalityLabel = CardinalityHealth.formatCardinality(cardinality);
          String health = CardinalityHealth.cardinalityHealth(cardinality);
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

    CostTotals costTotals = queryCostTotals(costMetricName, from, to, priorWindowStart, null);
    double currentSpend = costTotals.currentTotal();
    double priorSpend = costTotals.priorTotal();
    double deltaPct = priorSpend == 0.0 ? 0.0 : (currentSpend - priorSpend) / priorSpend * 100.0;

    double burnRate = currentSpend / windowHours;
    double projected30d = burnRate * HOURS_PER_MONTH;

    long totalTokens = queryTotalTokens(tokenMetricName, from, to);
    double costPer1k = totalTokens == 0L ? 0.0 : currentSpend / (totalTokens / TOKENS_PER_COST_UNIT);

    long trendBucketSeconds = Math.max(1L, (long) windowSeconds / COST_TREND_BUCKETS);
    List<Object[]> breakdownRows = metricPointRepository.aggregateCostBreakdown(
        costMetricName, from, to, trendBucketSeconds, null);
    List<Double> trend = buildCostTrend(breakdownRows);
    List<CostModelShare> byModel = buildCostByModel(breakdownRows, currentSpend);

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

  // Lean sibling of aggregateCostSummary for callers that only need the scalar
  // figures plus the per-model breakdown (spend, delta, burn rate, projection,
  // cost/1k, byModel) but never the trend -- currently just the Tokens page via
  // buildTokenUsageSummaryForWindow, see its Javadoc. Takes totalTokens as a
  // parameter rather than running its own aggregateTotalTokens scan, since the
  // caller already has it from aggregateTokenUsageBreakdown's grand-total row.
  // Still runs aggregateCostBreakdown for the per-model rows, but with a single
  // bucket spanning the whole window -- the GROUPING SETS scan is one pass
  // regardless of bucket count, so this pays for the per-model rows without
  // paying for a trend series nothing here reads. trend comes back empty -- not
  // wrong, just not computed -- so this must never back an endpoint whose
  // caller reads it.
  private CostSummary aggregateCostTotalsAndByModel(
      Instant from, Instant to, long totalTokens, String repositoryUrl) {
    double windowSeconds = Math.max(1.0, Duration.between(from, to).getSeconds());
    double windowHours = windowSeconds / SECONDS_PER_HOUR;
    Duration windowDuration = Duration.ofSeconds((long) windowSeconds);
    Instant priorWindowStart = from.minus(windowDuration);
    String costMetricName = tuningProperties.getCostUsageMetric();

    CostTotals costTotals = queryCostTotals(costMetricName, from, to, priorWindowStart, repositoryUrl);
    double currentSpend = costTotals.currentTotal();
    double priorSpend = costTotals.priorTotal();
    double deltaPct = priorSpend == 0.0 ? 0.0 : (currentSpend - priorSpend) / priorSpend * 100.0;

    double burnRate = currentSpend / windowHours;
    double projected30d = burnRate * HOURS_PER_MONTH;
    double costPer1k = totalTokens == 0L ? 0.0 : currentSpend / (totalTokens / TOKENS_PER_COST_UNIT);

    List<Object[]> breakdownRows = metricPointRepository.aggregateCostBreakdown(
        costMetricName, from, to, (long) windowSeconds, repositoryUrl);
    List<CostModelShare> byModel = buildCostByModel(breakdownRows, currentSpend);

    return new CostSummary(
        formatUsd(currentSpend),
        formatDeltaPct(deltaPct),
        formatBurnRate(burnRate),
        formatProjected(projected30d),
        formatCostPer1k(costPer1k),
        List.of(),
        byModel,
        "");
  }

  private record CostTotals(double currentTotal, double priorTotal) {}

  // B4 perf: current-period and prior-period totals now come from ONE scan (see
  // MetricPointRepository#aggregateCostCurrentAndPriorTotals's FILTER-based
  // query) instead of two.
  private CostTotals queryCostTotals(
      String metricName, Instant from, Instant to, Instant priorFrom, String repositoryUrl) {
    Object[] totalsRow = firstRow(metricPointRepository.aggregateCostCurrentAndPriorTotals(
        metricName, from, to, priorFrom, repositoryUrl));
    if (totalsRow == null) {
      return new CostTotals(0.0, 0.0);
    }
    double currentTotal = totalsRow[COST_TOTALS_CURRENT_INDEX] == null
        ? 0.0 : ((Number) totalsRow[COST_TOTALS_CURRENT_INDEX]).doubleValue();
    double priorTotal = totalsRow[COST_TOTALS_PRIOR_INDEX] == null
        ? 0.0 : ((Number) totalsRow[COST_TOTALS_PRIOR_INDEX]).doubleValue();
    return new CostTotals(currentTotal, priorTotal);
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
    Object[] firstRow = firstRow(rows);
    if (firstRow == null || firstRow.length == 0 || firstRow[0] == null) {
      return null;
    }
    return (Number) firstRow[0];
  }

  // Same empty-list/null-row guard as firstScalar, but for aggregate queries that
  // return more than one column per row (e.g. the FILTER-based current/prior
  // totals pair), where the caller reads several indices off the same row.
  private static Object[] firstRow(List<Object[]> rows) {
    if (rows.isEmpty()) {
      return null;
    }
    return rows.get(0);
  }

  // Demultiplexes the 'bucket' rows out of aggregateCostBreakdown's GROUPING SETS
  // result. Rows already arrive bucket-ascending (see the query's ORDER BY), so
  // the LinkedHashMap preserves that order exactly like the pre-merge
  // aggregateCostTrend did.
  private static List<Double> buildCostTrend(List<Object[]> breakdownRows) {
    Map<Instant, Double> bucketValues = new LinkedHashMap<>();
    for (Object[] breakdownRow : breakdownRows) {
      if (!COST_BREAKDOWN_ROW_TYPE_BUCKET.equals(breakdownRow[COST_BREAKDOWN_ROW_TYPE_INDEX])) {
        continue;
      }
      Instant bucket = (Instant) breakdownRow[COST_BREAKDOWN_BUCKET_INDEX];
      double bucketCost = breakdownRow[COST_BREAKDOWN_AMOUNT_INDEX] == null
          ? 0.0 : ((Number) breakdownRow[COST_BREAKDOWN_AMOUNT_INDEX]).doubleValue();
      bucketValues.put(bucket, bucketCost);
    }
    List<Double> trend = new ArrayList<>(bucketValues.size());
    for (double costValue : bucketValues.values()) {
      trend.add(costValue);
    }
    return trend;
  }

  // Demultiplexes the 'model' rows out of aggregateCostBreakdown's GROUPING SETS
  // result. Rows already arrive spend-descending (see the query's ORDER BY), so
  // colorIndex assignment by list position matches the pre-merge
  // aggregateCostByModel exactly.
  private static List<CostModelShare> buildCostByModel(List<Object[]> breakdownRows, double totalSpend) {
    List<Object[]> modelRows = breakdownRows.stream()
        .filter(breakdownRow -> COST_BREAKDOWN_ROW_TYPE_MODEL.equals(breakdownRow[COST_BREAKDOWN_ROW_TYPE_INDEX]))
        .toList();
    List<CostModelShare> byModel = new ArrayList<>(modelRows.size());
    for (int colorIndex = 0; colorIndex < modelRows.size(); colorIndex++) {
      Object[] modelRow = modelRows.get(colorIndex);
      String modelName = modelRow[COST_BREAKDOWN_MODEL_INDEX] == null
          ? "unknown" : (String) modelRow[COST_BREAKDOWN_MODEL_INDEX];
      double modelCost = modelRow[COST_BREAKDOWN_AMOUNT_INDEX] == null
          ? 0.0 : ((Number) modelRow[COST_BREAKDOWN_AMOUNT_INDEX]).doubleValue();
      int sharePercent = totalSpend == 0.0 ? 0 : (int) Math.round(modelCost / totalSpend * HUNDRED_PERCENT);
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

  // 5 decimals, not 3: cache-read tokens dominate the denominator (see this
  // class's two-pipelines note and AGENTS.md) but are billed at a steep
  // discount, so on a cache-heavy window the real figure is routinely
  // sub-mill (e.g. $0.00033/1k) -- 3 decimals rounded that straight to
  // "$0.000", indistinguishable from a genuine zero.
  private static String formatCostPer1k(double costPer1k) {
    return String.format(Locale.US, "$%.5f", costPer1k);
  }

  // ---------------------------------------------------------------------------
  // Metric distribution
  // ---------------------------------------------------------------------------

  /**
   * Per-request points of the token or cost metric for {@code GET /api/metrics/distribution}: one
   * point per {@code api_request} log in the window, ascending by timestamp, capped to the newest
   * {@code DISTRIBUTION_REQUEST_LIMIT} requests. A handful of points (see
   * {@link DistributionExemplarSelector}) carry a real trace id (and, when its span is ingested,
   * that request's {@code llm_request} span id) for click-through; the rest carry null. The frontend
   * plots every point and computes p50/p95/p99 itself.
   *
   * <p>Reads the exact per-call {@code api_request} logs, NOT the cumulative counters in
   * {@code metric_points}, so these values intentionally do not reconcile with
   * {@code MetricSeries.sum}. The two pipelines disagree by tens of percent on real data (see the
   * two-pipelines note in {@code backend/CLAUDE.md}); this card names the per-request one and never
   * blends the two.
   *
   * @param metric the FULL metric name, i.e. the configured token-usage or cost-usage metric
   * @throws IllegalArgumentException for any other name (mapped to a 400)
   */
  public MetricDistribution aggregateMetricDistribution(
      Instant from, Instant to, String repositoryUrl, String metric) {
    String eventName = tuningProperties.getApiRequestEventName();
    String tokenMetricName = tuningProperties.getTokenUsageMetric();
    String costMetricName = tuningProperties.getCostUsageMetric();
    List<Object[]> newestFirstRows;
    if (tokenMetricName.equals(metric)) {
      newestFirstRows = logRecordRepository.findNewestTokenRequestPoints(
          from, to, repositoryUrl, eventName, tuningProperties.getRequestIdAttribute(),
          DISTRIBUTION_REQUEST_LIMIT);
    } else if (costMetricName.equals(metric)) {
      newestFirstRows = logRecordRepository.findNewestCostRequestPoints(
          from, to, repositoryUrl, eventName, tuningProperties.getApiRequestCostAttribute(),
          tuningProperties.getRequestIdAttribute(), DISTRIBUTION_REQUEST_LIMIT);
    } else {
      throw new IllegalArgumentException("Unsupported metric '" + metric + "': expected '"
          + tokenMetricName + "' or '" + costMetricName + "'");
    }
    return new MetricDistribution(DistributionExemplarSelector.toPoints(
        toAscendingRequests(newestFirstRows), this::llmRequestSpanIdOf));
  }

  // The llm_request span of one exemplar, so a click-through lands on it instead of the trace root.
  // Null when the request has no trace id or request id, or its span is not ingested.
  private String llmRequestSpanIdOf(DistributionExemplarSelector.RequestPoint request) {
    if (request.traceId() == null || request.requestId() == null) {
      return null;
    }
    return spanRepository.findLlmRequestSpanId(
            request.traceId(),
            tuningProperties.getLlmRequestSpanName(),
            tuningProperties.getRequestIdAttribute(),
            request.requestId())
        .orElse(null);
  }

  // The queries return the newest requests first (so LIMIT keeps the newest); the response is oldest first.
  private static List<DistributionExemplarSelector.RequestPoint> toAscendingRequests(List<Object[]> newestFirstRows) {
    List<DistributionExemplarSelector.RequestPoint> ascendingRequests = new ArrayList<>(newestFirstRows.size());
    for (int rowIndex = newestFirstRows.size() - 1; rowIndex >= 0; rowIndex--) {
      Object[] row = newestFirstRows.get(rowIndex);
      ascendingRequests.add(new DistributionExemplarSelector.RequestPoint(
          (Instant) row[DISTRIBUTION_ROW_TIMESTAMP_INDEX],
          ((Number) row[DISTRIBUTION_ROW_VALUE_INDEX]).doubleValue(),
          (String) row[DISTRIBUTION_ROW_TRACE_ID_INDEX],
          (String) row[DISTRIBUTION_ROW_REQUEST_ID_INDEX],
          Boolean.TRUE.equals(row[DISTRIBUTION_ROW_FAILED_INDEX])));
    }
    return ascendingRequests;
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

  private static long bucketWidthSeconds(int minutes) {
    long windowSeconds = (long) minutes * SECONDS_PER_MINUTE;
    return Math.max(MIN_BUCKET_SECONDS, windowSeconds / TARGET_BUCKETS_PER_WINDOW);
  }

  private static String[] toFilterArray(List<String> activeFilters) {
    return activeFilters == null ? new String[0] : activeFilters.toArray(new String[0]);
  }
}
