package com.guavasoft.agentcompass.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.model.CostBreakdown;
import com.guavasoft.agentcompass.model.CostCategoryShare;
import com.guavasoft.agentcompass.model.CostIdentifierShare;
import com.guavasoft.agentcompass.model.CostModelEffortCell;
import com.guavasoft.agentcompass.model.CostSessionShare;
import com.guavasoft.agentcompass.model.CostTrendPoint;
import com.guavasoft.agentcompass.repository.LogRecordRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the Cost page (`GET /api/cost/breakdown`): where spend went, broken down by work
 * category (main loop / subagent / skill / auxiliary), by (model, effort), and by session.
 *
 * <p><b>Single pipeline, on purpose.</b> Every figure here is read from {@code api_request} log
 * records ({@code cost_usd}), never from the {@code claude_code.cost.usage} cumulative counter
 * that backs the Tokens and Sessions pages. That counter carries no per-request tag a category
 * partition could be built from without a second, disagreeing vocabulary (its own
 * {@code query_source} values are {@code main}/{@code auxiliary}/{@code subagent}, not the
 * {@code sdk}/{@code agent:*}/... values this service reads) -- see AGENTS.md's two-pipelines
 * note. Consequence: {@link #totalCostUsd} on this page reads a few percent below the KPI shown
 * elsewhere on the dashboard for the same window. That is not a bug in either number.
 *
 * <p><b>The category partition is exact by construction.</b> {@code aggregateCostByWorkCategoryInRange}
 * assigns every row to exactly one of four categories in a fixed precedence order (SUBAGENT beats
 * SKILL beats MAIN_LOOP beats AUXILIARY) specifically so a request tagged both ways -- a skill
 * running inside a subagent -- is not double-counted. {@link CostCategoryShare#costUsd()} across
 * all four categories therefore sums exactly to {@link CostBreakdown#totalCostUsd()}.
 *
 * <p><b>The SUBAGENT and SKILL drilldowns do NOT sum to their category total, and that is
 * expected.</b> They are resolved by two separate, pre-existing queries
 * ({@link LogRecordRepository#aggregateSubagentCostByModelInRange} /
 * {@code aggregateSkillCostByModelInRange}) that were built for the Skills &amp; Subagents page
 * and answer "how much did this identifier spend", not "how much of this category's spend can be
 * named". The subagent query in particular only reaches a dispatch's own direct child LLM spans
 * (see its Javadoc's KNOWN LIMITATION), so a dispatch with no matching execution span, or one
 * nested inside another subagent's dispatch, contributes to the SUBAGENT category total via the
 * category query but has no resolvable identifier here. {@link CostCategoryShare#identifiedCostUsd()}
 * exposes the drilldown's own sum so the frontend can show "$X of $Y identified" honestly rather
 * than silently under- or over-counting to force a match.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CostService {

  private static final String CATEGORY_SUBAGENT = "SUBAGENT";
  private static final String CATEGORY_SKILL = "SKILL";
  private static final String CATEGORY_MAIN_LOOP = "MAIN_LOOP";
  private static final String CATEGORY_AUXILIARY = "AUXILIARY";

  private static final int TREND_BUCKETS = 14;
  private static final long MINIMUM_BUCKET_SECONDS = 60L;
  private static final double SECONDS_PER_HOUR = 3600.0;
  private static final double HOURS_PER_30_DAYS = 24.0 * 30.0;
  private static final double HUNDRED_PERCENT = 100.0;
  private static final int DEFAULT_TOP_SESSION_LIMIT = 10;

  private static final int CATEGORY_ROW_CATEGORY_INDEX = 0;
  private static final int CATEGORY_ROW_BUCKET_INDEX = 1;
  private static final int CATEGORY_ROW_CATEGORY_GROUPED_INDEX = 2;
  private static final int CATEGORY_ROW_BUCKET_GROUPED_INDEX = 3;
  private static final int CATEGORY_ROW_COST_INDEX = 4;
  private static final int CATEGORY_ROW_REQUESTS_INDEX = 5;
  private static final int CATEGORY_ROW_INPUT_TOKENS_INDEX = 6;
  private static final int CATEGORY_ROW_OUTPUT_TOKENS_INDEX = 7;
  private static final int CATEGORY_ROW_CACHE_CREATION_TOKENS_INDEX = 8;
  private static final int CATEGORY_ROW_CACHE_READ_TOKENS_INDEX = 9;

  private final LogRecordRepository logRecordRepository;
  private final TuningProperties tuningProperties;

  public CostBreakdown breakdown(int minutes) {
    Instant end = Instant.now();
    return breakdownInRange(end.minus(Duration.ofMinutes(minutes)), end);
  }

  public CostBreakdown breakdownInRange(Instant start, Instant end) {
    double windowSeconds = Math.max(1.0, Duration.between(start, end).getSeconds());
    Instant priorStart = start.minus(Duration.ofSeconds((long) windowSeconds));
    long bucketSeconds = Math.max(MINIMUM_BUCKET_SECONDS, (long) windowSeconds / TREND_BUCKETS);

    List<Object[]> categoryRows = logRecordRepository.aggregateCostByWorkCategoryInRange(
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getQuerySourceAttribute(),
        tuningProperties.getSubagentQuerySourcePrefix(),
        tuningProperties.getSkillNameAttribute(),
        tuningProperties.getMainLoopQuerySources(),
        tuningProperties.getApiRequestCostAttribute(),
        start,
        end,
        bucketSeconds);

    Object[] totalRow = findTotalRow(categoryRows);
    long totalRequests = totalRow == null ? 0L : asLong(totalRow[CATEGORY_ROW_REQUESTS_INDEX]);
    long totalInputTokens = totalRow == null ? 0L : asLong(totalRow[CATEGORY_ROW_INPUT_TOKENS_INDEX]);
    long totalOutputTokens = totalRow == null ? 0L : asLong(totalRow[CATEGORY_ROW_OUTPUT_TOKENS_INDEX]);
    long totalCacheCreationTokens =
        totalRow == null ? 0L : asLong(totalRow[CATEGORY_ROW_CACHE_CREATION_TOKENS_INDEX]);
    long totalCacheReadTokens = totalRow == null ? 0L : asLong(totalRow[CATEGORY_ROW_CACHE_READ_TOKENS_INDEX]);

    double currentTotal = totalRow == null ? 0.0 : asDouble(totalRow[CATEGORY_ROW_COST_INDEX]);

    Object[] priorTotalRow = firstRow(logRecordRepository.aggregatePriorApiRequestCostTotalInRange(
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getApiRequestCostAttribute(),
        priorStart,
        start));
    double priorTotal = priorTotalRow == null ? 0.0 : asDouble(priorTotalRow[0]);
    double deltaPct = priorTotal == 0.0 ? 0.0 : (currentTotal - priorTotal) / priorTotal * HUNDRED_PERCENT;

    double windowHours = windowSeconds / SECONDS_PER_HOUR;
    double burnRatePerHour = currentTotal / windowHours;
    double projected30dUsd = burnRatePerHour * HOURS_PER_30_DAYS;

    List<Object[]> subagentCostRows = logRecordRepository.aggregateSubagentCostByModelInRange(
        tuningProperties.getToolEventName(),
        tuningProperties.getToolAttribute(),
        tuningProperties.getSubagentToolName(),
        tuningProperties.getSubagentTypeAttribute(),
        tuningProperties.getDefaultSubagentType(),
        tuningProperties.getToolCallIdAttribute(),
        tuningProperties.getToolExecutionSpanName(),
        tuningProperties.getLlmRequestSpanName(),
        tuningProperties.getRequestIdAttribute(),
        tuningProperties.getModelAttribute(),
        tuningProperties.getApiRequestCostAttribute(),
        start,
        end);
    List<Object[]> skillCostRows = logRecordRepository.aggregateSkillCostByModelInRange(
        tuningProperties.getSkillEventName(),
        tuningProperties.getSkillNameAttribute(),
        tuningProperties.getModelAttribute(),
        tuningProperties.getApiRequestCostAttribute(),
        start,
        end);

    List<CostCategoryShare> categories = buildCategories(
        categoryRows, currentTotal, subagentCostRows, skillCostRows);

    List<CostTrendPoint> trend = buildTrend(categoryRows);

    List<Object[]> modelEffortRows = logRecordRepository.aggregateCostByModelAndEffortInRange(
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getModelAttribute(),
        tuningProperties.getApiRequestEffortAttribute(),
        tuningProperties.getApiRequestCostAttribute(),
        start,
        end);
    List<CostModelEffortCell> modelEffort = buildModelEffort(modelEffortRows);

    List<Object[]> topSessionRows = logRecordRepository.aggregateTopCostSessionsInRange(
        tuningProperties.getApiRequestEventName(),
        tuningProperties.getApiRequestCostAttribute(),
        start,
        end,
        DEFAULT_TOP_SESSION_LIMIT);
    List<String> topSessionIds = topSessionRows.stream().map(row -> (String) row[0]).toList();

    // Reuses the Sessions grid's counts query purely for its firstUserPrompt column,
    // the same idiom MetricService#worstCacheEfficiencySessions follows -- the ranking
    // already comes back one row per session, so this is a lookup keyed on those ids,
    // not a second ranking.
    Map<String, String> firstUserPromptBySessionId = topSessionIds.isEmpty()
        ? Map.of()
        : buildFirstUserPromptMap(logRecordRepository.aggregateSessionCounts(
            topSessionIds,
            tuningProperties.getToolEventName(),
            tuningProperties.getToolDecisionEventName(),
            tuningProperties.getUserPromptEventName(),
            tuningProperties.getPromptAttribute()));

    Map<String, Map<String, Double>> categoryCostBySessionId = topSessionIds.isEmpty()
        ? Map.of()
        : buildCategoryCostBySessionId(logRecordRepository.aggregateCostByWorkCategoryForSessionsInRange(
            tuningProperties.getQuerySourceAttribute(),
            tuningProperties.getSubagentQuerySourcePrefix(),
            tuningProperties.getSkillNameAttribute(),
            tuningProperties.getMainLoopQuerySources(),
            tuningProperties.getApiRequestCostAttribute(),
            tuningProperties.getApiRequestEventName(),
            start,
            end,
            topSessionIds));

    List<CostSessionShare> topSessions =
        buildTopSessions(topSessionRows, firstUserPromptBySessionId, categoryCostBySessionId);

    return new CostBreakdown(
        currentTotal,
        priorTotal,
        deltaPct,
        burnRatePerHour,
        projected30dUsd,
        totalRequests,
        totalInputTokens,
        totalOutputTokens,
        totalCacheCreationTokens,
        totalCacheReadTokens,
        categories,
        trend,
        modelEffort,
        topSessions,
        bucketSeconds);
  }

  private static Object[] findTotalRow(List<Object[]> categoryRows) {
    for (Object[] row : categoryRows) {
      if (asLong(row[CATEGORY_ROW_CATEGORY_GROUPED_INDEX]) == 1L) {
        return row;
      }
    }
    return null;
  }

  private List<CostCategoryShare> buildCategories(
      List<Object[]> categoryRows,
      double totalCostUsd,
      List<Object[]> subagentCostRows,
      List<Object[]> skillCostRows) {
    List<CostIdentifierShare> subagentDrilldown = buildIdentifierDrilldown(subagentCostRows);
    List<CostIdentifierShare> skillDrilldown = buildIdentifierDrilldown(skillCostRows);

    List<CostCategoryShare> categories = new ArrayList<>();
    for (Object[] row : categoryRows) {
      boolean isCategoryRow = asLong(row[CATEGORY_ROW_CATEGORY_GROUPED_INDEX]) == 0L
          && asLong(row[CATEGORY_ROW_BUCKET_GROUPED_INDEX]) == 1L;
      if (!isCategoryRow) {
        continue;
      }
      String category = (String) row[CATEGORY_ROW_CATEGORY_INDEX];
      double costUsd = asDouble(row[CATEGORY_ROW_COST_INDEX]);
      long requests = asLong(row[CATEGORY_ROW_REQUESTS_INDEX]);
      int share = totalCostUsd == 0.0 ? 0 : (int) Math.round(costUsd / totalCostUsd * HUNDRED_PERCENT);

      List<CostIdentifierShare> drilldown = List.of();
      Double identifiedCostUsd = null;
      if (CATEGORY_SUBAGENT.equals(category)) {
        drilldown = subagentDrilldown;
        identifiedCostUsd = sumIdentifierShares(drilldown);
      } else if (CATEGORY_SKILL.equals(category)) {
        drilldown = skillDrilldown;
        identifiedCostUsd = sumIdentifierShares(drilldown);
      }

      categories.add(new CostCategoryShare(category, costUsd, requests, share, drilldown, identifiedCostUsd));
    }
    categories.sort(Comparator.comparingDouble(CostCategoryShare::costUsd).reversed());
    return categories;
  }

  private static double sumIdentifierShares(List<CostIdentifierShare> shares) {
    return shares.stream().mapToDouble(CostIdentifierShare::costUsd).sum();
  }

  // Sums (identifier, model, cost_usd) rows -- from aggregateSubagentCostByModelInRange or
  // aggregateSkillCostByModelInRange -- across model per identifier, since a category
  // drilldown ranks identifiers, not (identifier, model) pairs.
  private static List<CostIdentifierShare> buildIdentifierDrilldown(List<Object[]> rows) {
    Map<String, Double> costByIdentifier = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String identifier = (String) row[0];
      double cost = asDouble(row[2]);
      costByIdentifier.merge(identifier, cost, Double::sum);
    }
    double identifiedTotal = costByIdentifier.values().stream().mapToDouble(Double::doubleValue).sum();
    List<CostIdentifierShare> shares = costByIdentifier.entrySet().stream()
        .map(entry -> new CostIdentifierShare(
            entry.getKey(),
            entry.getValue(),
            identifiedTotal == 0.0 ? 0 : (int) Math.round(entry.getValue() / identifiedTotal * HUNDRED_PERCENT)))
        .sorted(Comparator.comparingDouble(CostIdentifierShare::costUsd).reversed())
        .toList();
    return shares;
  }

  // Demultiplexes the BUCKET rows into one CostTrendPoint per bucket, oldest first.
  // Rows already arrive bucket-ascending within each category (see the query's ORDER BY),
  // so a LinkedHashMap keyed by bucket preserves chronological order.
  private static List<CostTrendPoint> buildTrend(List<Object[]> categoryRows) {
    Map<Instant, Map<String, Double>> costByBucketAndCategory = new LinkedHashMap<>();
    for (Object[] row : categoryRows) {
      boolean isBucketRow = asLong(row[CATEGORY_ROW_CATEGORY_GROUPED_INDEX]) == 0L
          && asLong(row[CATEGORY_ROW_BUCKET_GROUPED_INDEX]) == 0L;
      if (!isBucketRow) {
        continue;
      }
      Instant bucket = (Instant) row[CATEGORY_ROW_BUCKET_INDEX];
      String category = (String) row[CATEGORY_ROW_CATEGORY_INDEX];
      double costUsd = asDouble(row[CATEGORY_ROW_COST_INDEX]);
      costByBucketAndCategory
          .computeIfAbsent(bucket, key -> new LinkedHashMap<>())
          .merge(category, costUsd, Double::sum);
    }
    return costByBucketAndCategory.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> new CostTrendPoint(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static List<CostModelEffortCell> buildModelEffort(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new CostModelEffortCell(
            (String) row[0],
            (String) row[1],
            asDouble(row[2]),
            asLong(row[3]),
            asLong(row[4]),
            asLong(row[5]),
            asLong(row[6]),
            asLong(row[7])))
        .toList();
  }

  // aggregateSessionCounts' row shape is (session_id, tool_call_count, denial_count,
  // user_prompt_count, first_user_prompt) -- only the id and the last column matter here.
  private static Map<String, String> buildFirstUserPromptMap(List<Object[]> rows) {
    Map<String, String> firstUserPromptBySessionId = new LinkedHashMap<>(rows.size());
    for (Object[] row : rows) {
      firstUserPromptBySessionId.put((String) row[0], (String) row[4]);
    }
    return firstUserPromptBySessionId;
  }

  // Pivots (session_id, category, cost_usd) rows into session_id -> category -> cost_usd
  // so buildTopSessions can look up each session's four category figures by name.
  private static Map<String, Map<String, Double>> buildCategoryCostBySessionId(List<Object[]> rows) {
    Map<String, Map<String, Double>> categoryCostBySessionId = new LinkedHashMap<>();
    for (Object[] row : rows) {
      String sessionId = (String) row[0];
      String category = (String) row[1];
      double costUsd = asDouble(row[2]);
      categoryCostBySessionId.computeIfAbsent(sessionId, key -> new LinkedHashMap<>()).put(category, costUsd);
    }
    return categoryCostBySessionId;
  }

  private static List<CostSessionShare> buildTopSessions(
      List<Object[]> rows,
      Map<String, String> firstUserPromptBySessionId,
      Map<String, Map<String, Double>> categoryCostBySessionId) {
    return rows.stream()
        .map(row -> {
          String sessionId = (String) row[0];
          Map<String, Double> categoryCosts = categoryCostBySessionId.getOrDefault(sessionId, Map.of());
          return new CostSessionShare(
              sessionId,
              asDouble(row[1]),
              asLong(row[2]),
              firstUserPromptBySessionId.get(sessionId),
              categoryCosts.getOrDefault(CATEGORY_MAIN_LOOP, 0.0),
              categoryCosts.getOrDefault(CATEGORY_SUBAGENT, 0.0),
              categoryCosts.getOrDefault(CATEGORY_SKILL, 0.0),
              categoryCosts.getOrDefault(CATEGORY_AUXILIARY, 0.0));
        })
        .toList();
  }

  private static Object[] firstRow(List<Object[]> rows) {
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static double asDouble(Object value) {
    return value == null ? 0.0 : ((Number) value).doubleValue();
  }

  private static long asLong(Object value) {
    return value == null ? 0L : ((Number) value).longValue();
  }
}
