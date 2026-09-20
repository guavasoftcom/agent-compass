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
import com.guavasoft.agentcompass.model.MetricAggregation;
import com.guavasoft.agentcompass.model.MetricAttributes;
import com.guavasoft.agentcompass.model.MetricFacet;
import com.guavasoft.agentcompass.model.MetricFacetValue;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricSeriesAggregation;
import com.guavasoft.agentcompass.model.MetricSeriesFilter;
import com.guavasoft.agentcompass.model.MetricSplitRow;
import com.guavasoft.agentcompass.repository.MetricPointRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the {@code GET /api/metrics/series} payload: one {@link MetricSeries} per
 * {@code claude_code.*} counter, each with a windowed trend and any attribute
 * splits, for the Metrics page master-detail.
 *
 * <p>The counter list is curated-plus-discovered rather than fixed. Eight counters
 * have hand-written descriptions, units, and splits; any other metric name the
 * database has ever received is appended with a generated spec, so a metric added
 * by a newer agent release shows up without a code change. See
 * {@code metricSpecs()}.
 *
 * <p>Every metric is a cumulative counter re-emitted every minute and split into
 * concurrent streams identified by the full attribute set, so all aggregation goes
 * through the reset-aware, full-attribute row-level increment in
 * {@link MetricPointRepository} (the same logic the token/cost rollups use). That
 * keeps each metric's headline total, trend, and split breakdown reconciled.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MetricSeriesService {

  /**
   * Spec ids of the two metrics {@code GET /api/metrics/distribution} can serve (it accepts their
   * full names, the token-usage and cost-usage metrics these two specs carry), which is what the
   * {@code hasDistribution} flag on {@link MetricSeries} reports.
   */
  private static final String TOKEN_METRIC_ID = "token";
  private static final String COST_METRIC_ID = "cost";
  private static final Set<String> DISTRIBUTION_METRIC_IDS = Set.of(TOKEN_METRIC_ID, COST_METRIC_ID);

  /**
   * A key with more distinct values than this in the window is left out of the attributes response:
   * unbounded, id-like attributes (session ids and the like) are useless in a filter picker.
   */
  static final int FACET_MAX_DISTINCT_VALUES = 25;

  private static final int TREND_BUCKETS = 24;
  private static final double SECONDS_PER_HOUR = 3_600.0;
  private static final long SECONDS_PER_HOUR_LONG = 3_600L;
  private static final long SECONDS_PER_MINUTE = 60L;
  private static final long SECONDS_PER_DAY = 86_400L;
  private static final long HOURS_PER_TWO_DAYS = 48L;
  private static final double HUNDRED_PERCENT = 100.0;
  private static final double THOUSAND = 1_000.0;
  private static final double MILLION = 1_000_000.0;
  private static final double BILLION = 1_000_000_000.0;
  private static final double TREND_ROUNDING = 100.0;

  private static final String METRIC_TYPE_COUNTER = "counter";
  private static final String MODEL_ATTRIBUTE = "model";
  private static final String DECISION_ATTRIBUTE = "decision";
  private static final String RATE_UNIT_PER_HOUR = "/h";
  private static final String DEFAULT_CAPTION_PREFIX = "Sum";
  private static final String USD_UNIT = "USD";
  private static final String SECONDS_UNIT = "s";
  private static final String DIR_UP = "up";
  private static final String DIR_DOWN = "down";

  private enum ValueFormat { NUMBER, USD, DURATION }

  // One metric's static shape; the windowed numbers are filled in per request.
  private record MetricSpec(
      String id,
      String name,
      String unit,
      String captionPrefix,
      String description,
      ValueFormat format,
      Map<String, String> splits) {
  }

  // One metric's current + prior window total, read off the batched totals
  // query. A metric name absent from that query's result (zero matching rows
  // in the scanned range) defaults to ZERO rather than being treated as missing.
  private record MetricWindowTotals(double current, double prior) {
    private static final MetricWindowTotals ZERO = new MetricWindowTotals(0.0, 0.0);
  }

  // What every query that has no filter to apply binds for the match list: an empty JSON array.
  private static final String NO_MATCHES_JSON = MetricSeriesFilter.NONE.matchesJson();

  // The resolved attribute filter: the NAME of the one metric it targets (null when inactive) and the
  // Jackson-built match list. It decides which metrics take the plain batched queries (all but the
  // filtered one) and which the single-metric ...Filtered variants (only the filtered one).
  private record FilterScope(String metricName, String matchesJson) {
    private static final FilterScope INACTIVE = new FilterScope(null, NO_MATCHES_JSON);

    // @throws IllegalArgumentException when the filter names a metric id no spec carries (a 400)
    static FilterScope of(MetricSeriesFilter filter, List<MetricSpec> specs) {
      if (!filter.isActive()) {
        return INACTIVE;
      }
      return new FilterScope(specForId(specs, filter.metricId()).name(), filter.matchesJson());
    }

    boolean isActive() {
      return metricName != null;
    }

    List<String> unfilteredNames(List<String> metricNames) {
      if (!isActive()) {
        return metricNames;
      }
      return metricNames.stream().filter(candidate -> !candidate.equals(metricName)).toList();
    }

    // The matches to bind for a query about one metric: the real ones only for the filtered metric.
    String matchesJsonFor(String candidateMetricName) {
      return candidateMetricName.equals(metricName) ? matchesJson : NO_MATCHES_JSON;
    }
  }

  // Cardinality a metric name absent from the batched cardinality query
  // defaults to -- zero matching rows in the scanned range, same "missing key
  // means zero, not an error" convention aggregateMetricTotals documents.
  private static final long DEFAULT_CARDINALITY = 0L;

  private final TuningProperties tuningProperties;
  private final MetricPointRepository repository;

  public List<MetricSeries> metricSeries(Instant from, Instant to, String repositoryUrl) {
    return metricSeries(from, to, repositoryUrl, MetricSeriesFilter.NONE);
  }

  /**
   * Same as the unfiltered form, with optional ANDed {@code key:value} attribute filters applied to
   * ONE metric's rows only (see {@link MetricSeriesFilter}); every other metric in the batch is
   * aggregated as if no filter existed.
   *
   * @throws IllegalArgumentException when the filter names a metric id no spec carries (mapped to a 400)
   */
  public List<MetricSeries> metricSeries(
      Instant from, Instant to, String repositoryUrl, MetricSeriesFilter filter) {
    return metricSeries(from, to, repositoryUrl, filter, MetricSeriesAggregation.NONE);
  }

  /**
   * Same as the filtered form, with an optional per-bucket aggregation for ONE metric's
   * {@code trend} array (see {@link MetricSeriesAggregation}). Everything else about that metric --
   * sum, rate, peak, delta, splits, cardinality -- is built from the sum data exactly as before, and
   * every other metric's series is untouched. It composes with the attribute filter and repository
   * scope. {@link MetricAggregation#SUM} is a valid no-op.
   *
   * @throws IllegalArgumentException when the aggregation names a metric id no spec carries (mapped
   *     to a 400), or when the filter does
   */
  public List<MetricSeries> metricSeries(
      Instant from, Instant to, String repositoryUrl, MetricSeriesFilter filter,
      MetricSeriesAggregation aggregation) {
    long windowSeconds = Math.max(1L, Duration.between(from, to).getSeconds());
    long bucketSeconds = Math.max(1L, windowSeconds / TREND_BUCKETS);
    Instant priorFrom = from.minusSeconds(windowSeconds);
    String windowLabel = shortWindowLabel(windowSeconds);

    List<MetricSpec> specs = metricSpecs();
    List<String> metricNames = specs.stream().map(MetricSpec::name).toList();
    FilterScope filterScope = FilterScope.of(filter, specs);
    // Resolved up front so an unknown aggMetricId is a 400 even for the no-op agg=sum.
    MetricSpec aggregatedSpec = aggregation.isActive() ? specForId(specs, aggregation.metricId()) : null;

    // Batched across every metric name (see MetricPointRepository's comment on
    // aggregateMetricTotals/aggregateMetricTrend): one scan for every metric's
    // total instead of one scan per metric, one scan for every metric's trend
    // instead of one scan per metric. When an attribute filter is active the plain batched query
    // covers every metric EXCEPT the filtered one, which has its own single-metric ...Filtered
    // query; the two row sets share a shape and are simply concatenated.
    List<Object[]> totalRows = new ArrayList<>(repository.aggregateMetricTotals(
        filterScope.unfilteredNames(metricNames), priorFrom, from, to, repositoryUrl));
    List<Object[]> trendRows = new ArrayList<>(repository.aggregateMetricTrend(
        filterScope.unfilteredNames(metricNames), from, to, bucketSeconds, repositoryUrl));
    List<Object[]> cardinalityRows = new ArrayList<>(repository.aggregateMetricCardinality(
        filterScope.unfilteredNames(metricNames), from, to, repositoryUrl));
    if (filterScope.isActive()) {
      totalRows.addAll(repository.aggregateMetricTotalsFiltered(
          filterScope.metricName(), priorFrom, from, to, repositoryUrl, filterScope.matchesJson()));
      trendRows.addAll(repository.aggregateMetricTrendFiltered(
          filterScope.metricName(), from, to, bucketSeconds, repositoryUrl, filterScope.matchesJson()));
      cardinalityRows.addAll(repository.aggregateMetricCardinalityFiltered(
          filterScope.metricName(), from, to, repositoryUrl, filterScope.matchesJson()));
    }
    Map<String, MetricWindowTotals> totalsByMetricName = totalRows.stream()
        .collect(Collectors.toMap(
            row -> (String) row[0],
            row -> new MetricWindowTotals(
                ((Number) row[1]).doubleValue(), ((Number) row[2]).doubleValue())));
    Map<String, List<Object[]>> trendRowsByMetricName = trendRows.stream()
        .collect(Collectors.groupingBy(row -> (String) row[0]));
    Map<String, Map<String, Map<String, Double>>> splitTotalsByMetricThenAttribute =
        aggregateSplitTotals(specs, from, to, repositoryUrl, filterScope);
    Map<String, Long> cardinalityByMetricName = cardinalityRows.stream()
        .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));

    List<MetricSeries> series = new ArrayList<>(specs.size());
    for (MetricSpec spec : specs) {
      MetricWindowTotals totals = totalsByMetricName.getOrDefault(spec.name(), MetricWindowTotals.ZERO);
      List<Object[]> metricTrendRows = trendRowsByMetricName.getOrDefault(spec.name(), List.of());
      Map<String, Map<String, Double>> splitTotalsByAttribute =
          splitTotalsByMetricThenAttribute.getOrDefault(spec.name(), Map.of());
      long cardinality = cardinalityByMetricName.getOrDefault(spec.name(), DEFAULT_CARDINALITY);
      series.add(buildSeries(
          spec, totals.current(), totals.prior(), metricTrendRows, splitTotalsByAttribute, cardinality,
          from, windowSeconds, bucketSeconds, windowLabel));
    }
    if (aggregatedSpec != null && aggregation.aggregation() != MetricAggregation.SUM) {
      // Swapped in AFTER buildSeries, which derives peak from the sum trend, so the headline
      // figures keep describing sums and only the plotted array changes. The attribute filter
      // applies to the aggregated trend only when the aggregated metric IS the filtered one.
      List<Object[]> aggregatedRows = aggregatedTrendRows(
          aggregation.aggregation(), aggregatedSpec.name(), from, to, bucketSeconds, repositoryUrl,
          filterScope.matchesJsonFor(aggregatedSpec.name()));
      List<Double> aggregatedTrend = buildTrend(aggregatedRows, from, bucketSeconds);
      series.replaceAll(candidate -> candidate.id().equals(aggregatedSpec.id())
          ? candidate.withTrend(aggregatedTrend) : candidate);
    }
    return series;
  }

  // One repository method per aggregate (the function name can never be a bind parameter), picked
  // here. SUM never reaches this: it is the batched trend the caller already has.
  private List<Object[]> aggregatedTrendRows(
      MetricAggregation aggregation, String metricName, Instant from, Instant to, long bucketSeconds,
      String repositoryUrl, String filterMatchesJson) {
    return switch (aggregation) {
      case AVG -> repository.aggregateMetricTrendAverage(
          metricName, from, to, bucketSeconds, repositoryUrl, filterMatchesJson);
      case P95 -> repository.aggregateMetricTrendP95(
          metricName, from, to, bucketSeconds, repositoryUrl, filterMatchesJson);
      case COUNT -> repository.aggregateMetricTrendCount(
          metricName, from, to, bucketSeconds, repositoryUrl, filterMatchesJson);
      case SUM -> throw new IllegalStateException("SUM uses the batched trend, not a dedicated query");
    };
  }

  // Every split-bearing curated metric shares just three physical attribute
  // keys (model, the configured token-type attribute, decision), so one call to
  // the batched aggregateMetricSplits query replaces what used to be one
  // repository call per (metric, split) pair -- five scans collapsed into one.
  // Row shape is (metric_name, model, token_type, decision, total); a row
  // contributes to whichever of the three attribute buckets it has a non-null
  // label for, reproducing the "attribute IS NOT NULL" filter the old
  // per-split query applied in SQL.
  // With an attribute filter active, the filtered metric (if it bears splits) goes through its own
  // single-metric aggregateMetricSplitsFiltered and the rest through the plain batched query.
  private Map<String, Map<String, Map<String, Double>>> aggregateSplitTotals(
      List<MetricSpec> specs, Instant from, Instant to, String repositoryUrl, FilterScope filterScope) {
    List<String> splitMetricNames = specs.stream()
        .filter(spec -> !spec.splits().isEmpty())
        .map(MetricSpec::name)
        .toList();
    if (splitMetricNames.isEmpty()) {
      return Map.of();
    }
    String typeAttribute = tuningProperties.getTokenTypeAttribute();
    List<String> unfilteredSplitMetricNames = filterScope.unfilteredNames(splitMetricNames);
    List<Object[]> splitRows = new ArrayList<>();
    if (!unfilteredSplitMetricNames.isEmpty()) {
      splitRows.addAll(repository.aggregateMetricSplits(
          unfilteredSplitMetricNames, MODEL_ATTRIBUTE, typeAttribute, DECISION_ATTRIBUTE, from, to, repositoryUrl));
    }
    if (filterScope.isActive() && splitMetricNames.contains(filterScope.metricName())) {
      splitRows.addAll(repository.aggregateMetricSplitsFiltered(
          filterScope.metricName(), MODEL_ATTRIBUTE, typeAttribute, DECISION_ATTRIBUTE, from, to, repositoryUrl,
          filterScope.matchesJson()));
    }
    Map<String, Map<String, Map<String, Double>>> totalsByMetricThenAttribute = new HashMap<>();
    for (Object[] row : splitRows) {
      String metricName = (String) row[0];
      double rowTotal = ((Number) row[4]).doubleValue();
      addSplitContribution(totalsByMetricThenAttribute, metricName, MODEL_ATTRIBUTE, (String) row[1], rowTotal);
      addSplitContribution(totalsByMetricThenAttribute, metricName, typeAttribute, (String) row[2], rowTotal);
      addSplitContribution(totalsByMetricThenAttribute, metricName, DECISION_ATTRIBUTE, (String) row[3], rowTotal);
    }
    return totalsByMetricThenAttribute;
  }

  /**
   * Attribute key/value facets for one metric over the window, for the Metrics page filter picker
   * ({@code GET /api/metrics/attributes}). Keys are alphabetical; each key's values run most common
   * first, ties alphabetical, and a key with more than {@link #FACET_MAX_DISTINCT_VALUES} distinct
   * values is omitted. A metric with no qualifying attributes yields an empty list.
   *
   * <p>Keyed by the metric's FULL NAME, straight into the query: a name that was never seen (or has no
   * active rows in the window) simply matches nothing and yields an empty list rather than an error,
   * which the frontend reads as "no filterable attributes".
   */
  public MetricAttributes metricAttributes(Instant from, Instant to, String repositoryUrl, String metricName) {
    // The query returns rows already ordered (key, count desc, value), so grouping into an
    // insertion-ordered map preserves that order in both the keys and each key's values.
    Map<String, List<MetricFacetValue>> valuesByKey = new LinkedHashMap<>();
    for (Object[] row : repository.aggregateMetricAttributeFacets(
        metricName, from, to, repositoryUrl, FACET_MAX_DISTINCT_VALUES)) {
      valuesByKey
          .computeIfAbsent((String) row[0], key -> new ArrayList<>())
          .add(new MetricFacetValue((String) row[1], ((Number) row[2]).longValue()));
    }
    List<MetricFacet> facets = new ArrayList<>(valuesByKey.size());
    for (Map.Entry<String, List<MetricFacetValue>> entry : valuesByKey.entrySet()) {
      facets.add(new MetricFacet(entry.getKey(), entry.getValue()));
    }
    return new MetricAttributes(facets);
  }

  private static MetricSpec specForId(List<MetricSpec> specs, String metricId) {
    return specs.stream()
        .filter(spec -> spec.id().equals(metricId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown metricId '" + metricId + "'"));
  }

  private static void addSplitContribution(
      Map<String, Map<String, Map<String, Double>>> totalsByMetricThenAttribute,
      String metricName, String attribute, String label, double value) {
    if (label == null) {
      return;
    }
    totalsByMetricThenAttribute
        .computeIfAbsent(metricName, key -> new HashMap<>())
        .computeIfAbsent(attribute, key -> new LinkedHashMap<>())
        .merge(label, value, Double::sum);
  }

  // Curated specs first, in their declared order, then anything else the database
  // has ever received, alphabetically. A deployment on a newer Claude Code (or on
  // a different agent entirely) gets a working card for a metric this code has
  // never heard of, instead of silently dropping it -- which is exactly what
  // happened to claude_code.commit.count, emitted from 2026-06-12 and invisible
  // on this page until it was added to the curated list below.
  private List<MetricSpec> metricSpecs() {
    List<MetricSpec> specs = new ArrayList<>(curatedMetricSpecs());
    Set<String> curatedNames = specs.stream().map(MetricSpec::name).collect(Collectors.toSet());
    for (Object[] row : repository.findDistinctMetricNames()) {
      String metricName = (String) row[0];
      if (curatedNames.contains(metricName)) {
        continue;
      }
      specs.add(discoveredMetricSpec(metricName, row[1] == null ? "" : (String) row[1]));
    }
    return specs;
  }

  // A metric with no curated entry still gets a real card: headline total, trend,
  // rate, and delta all come from the same reset-aware aggregation as the curated
  // ones. What it cannot have is editorial -- a description explaining what the
  // number means, and the attribute splits worth breaking it down by, both of
  // which require knowing the metric. The description says so rather than
  // inventing prose, so an uncurated card is visibly uncurated and someone can
  // promote it to curatedMetricSpecs() when it turns out to matter.
  private static MetricSpec discoveredMetricSpec(String metricName, String unit) {
    return new MetricSpec(
        metricName.replace('.', '-'),
        metricName,
        unit,
        DEFAULT_CAPTION_PREFIX,
        "Discovered counter — emitted to this backend but not yet curated in the dashboard, so it "
            + "carries no description and no attribute splits. The total is the same reset-aware "
            + "sum used by every other metric here.",
        formatForUnit(unit),
        Map.of());
  }

  // Units are OTLP UCUM-ish: "USD" for money, "s" for seconds, an annotation in
  // braces ("{session}") or the empty string for dimensionless counts. Only the
  // first two change how a number reads; everything else formats as a count.
  private static ValueFormat formatForUnit(String unit) {
    if (USD_UNIT.equalsIgnoreCase(unit)) {
      return ValueFormat.USD;
    }
    if (SECONDS_UNIT.equals(unit)) {
      return ValueFormat.DURATION;
    }
    return ValueFormat.NUMBER;
  }

  // The eight known counters and how each one reads. Metric names come from
  // TuningProperties so deployments can override the emission namespace; split
  // attribute keys (model / type / decision) are the OTLP attribute names.
  private List<MetricSpec> curatedMetricSpecs() {
    String typeAttribute = tuningProperties.getTokenTypeAttribute();
    return List.of(
        new MetricSpec(
            TOKEN_METRIC_ID, tuningProperties.getTokenUsageMetric(), "tokens", "Sum",
            "Tokens consumed across Claude Code sessions, summed over the window. The biggest cost "
                + "driver — split by model to see where they go. The input/output/cache breakdown "
                + "lives on the Token Usage page.",
            ValueFormat.NUMBER, orderedSplits("Model", MODEL_ATTRIBUTE)),
        new MetricSpec(
            COST_METRIC_ID, tuningProperties.getCostUsageMetric(), "USD", "Spend",
            "Billed spend in USD over the window. Split by model to see which model drives the bill.",
            ValueFormat.USD, orderedSplits("Model", MODEL_ATTRIBUTE)),
        new MetricSpec(
            "session", tuningProperties.getSessionCountMetric(), "{session}", "Sessions",
            "New Claude Code sessions started in the window. A simple volume signal for how much the "
                + "CLI is being used.",
            ValueFormat.NUMBER, Map.of()),
        new MetricSpec(
            "active", tuningProperties.getActiveTimeMetric(), "s", "Active",
            "Total active engagement time, summed across sessions. Active time excludes idle gaps "
                + "between turns.",
            ValueFormat.DURATION, Map.of()),
        new MetricSpec(
            "loc", tuningProperties.getLinesOfCodeMetric(), "{line}", "Lines",
            "Lines of code added or removed by edit tools. Split by change to separate additions from "
                + "removals.",
            ValueFormat.NUMBER, orderedSplits("Change", typeAttribute)),
        new MetricSpec(
            "decision", tuningProperties.getCodeEditDecisionMetric(), "{decision}", "Decisions",
            "Edit-tool permission decisions. Split by decision to see the accept / reject mix.",
            ValueFormat.NUMBER, orderedSplits("Decision", DECISION_ATTRIBUTE)),
        new MetricSpec(
            "commit", tuningProperties.getCommitCountMetric(), "{commit}", "Commits",
            "Git commits created during Claude Code sessions. A low-volume counter — it moves only "
                + "when the agent actually commits, so read it as a throughput signal rather than a "
                + "rate. No split: it carries only session and user identity attributes.",
            ValueFormat.NUMBER, Map.of()),
        new MetricSpec(
            "pull_request", tuningProperties.getPullRequestCountMetric(), "", "Sum",
            "Pull requests opened during Claude Code sessions. The narrowest funnel on this page — "
                + "commits show work happening, this shows work being handed to a reviewer, so a day "
                + "of commits with no PR is worth a look.",
            ValueFormat.NUMBER, Map.of()));
  }

  // (displayName -> attribute) pairs, insertion-ordered so the UI shows the splits
  // in the order declared above.
  private static Map<String, String> orderedSplits(String... displayNameThenAttribute) {
    LinkedHashMap<String, String> splits = new LinkedHashMap<>();
    for (int i = 0; i + 1 < displayNameThenAttribute.length; i += 2) {
      splits.put(displayNameThenAttribute[i], displayNameThenAttribute[i + 1]);
    }
    return splits;
  }

  private MetricSeries buildSeries(
      MetricSpec spec, double total, double priorTotal, List<Object[]> trendRows,
      Map<String, Map<String, Double>> splitTotalsByAttribute, long cardinality,
      Instant from, long windowSeconds, long bucketSeconds, String windowLabel) {
    List<Double> trend = buildTrend(trendRows, from, bucketSeconds);
    double peak = trend.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    double rate = total / (windowSeconds / SECONDS_PER_HOUR);
    double deltaPct = priorTotal == 0.0 ? 0.0 : (total - priorTotal) / priorTotal * HUNDRED_PERCENT;

    Map<String, List<MetricSplitRow>> splits = new LinkedHashMap<>();
    for (Map.Entry<String, String> split : spec.splits().entrySet()) {
      Map<String, Double> totalsByLabel = splitTotalsByAttribute.getOrDefault(split.getValue(), Map.of());
      splits.put(split.getKey(), buildSplitRows(totalsByLabel, spec.format(), total));
    }

    return new MetricSeries(
        spec.id(),
        spec.name(),
        METRIC_TYPE_COUNTER,
        spec.unit(),
        formatValue(total, spec.format()),
        spec.captionPrefix() + " (" + windowLabel + ")",
        formatValue(rate, spec.format()),
        RATE_UNIT_PER_HOUR,
        formatValue(peak, spec.format()),
        formatDeltaPct(deltaPct),
        deltaPct >= 0.0 ? DIR_UP : DIR_DOWN,
        spec.description(),
        trend,
        splits,
        cardinality,
        CardinalityHealth.cardinalityHealth(cardinality),
        DISTRIBUTION_METRIC_IDS.contains(spec.id()));
  }

  // Exactly TREND_BUCKETS evenly-spaced values across the window; sparse query rows
  // are placed by their bucket offset from the window start and gaps stay zero.
  // rows come from the batched aggregateMetricTrend query, already narrowed to
  // this metric's rows by metricSeries -- row shape is (metric_name, bucket, total).
  private List<Double> buildTrend(List<Object[]> rows, Instant from, long bucketSeconds) {
    double[] buckets = new double[TREND_BUCKETS];
    for (Object[] row : rows) {
      Instant bucket = (Instant) row[1];
      double value = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
      long index = Duration.between(from, bucket).getSeconds() / bucketSeconds;
      if (index < 0L) {
        index = 0L;
      }
      if (index >= TREND_BUCKETS) {
        index = TREND_BUCKETS - 1L;
      }
      buckets[(int) index] += value;
    }
    List<Double> trend = new ArrayList<>(TREND_BUCKETS);
    for (double value : buckets) {
      trend.add(Math.round(value * TREND_ROUNDING) / TREND_ROUNDING);
    }
    return trend;
  }

  // Labels come pre-summed from aggregateSplitTotals (null labels already
  // dropped there); this just orders them into the descending, color-indexed
  // shape the old per-split query's ORDER BY total DESC used to produce.
  private static List<MetricSplitRow> buildSplitRows(
      Map<String, Double> totalByLabel, ValueFormat format, double metricTotal) {
    List<Map.Entry<String, Double>> sortedEntries = totalByLabel.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .toList();
    List<MetricSplitRow> splitRows = new ArrayList<>(sortedEntries.size());
    for (int colorIndex = 0; colorIndex < sortedEntries.size(); colorIndex++) {
      Map.Entry<String, Double> entry = sortedEntries.get(colorIndex);
      double value = entry.getValue();
      int pct = metricTotal == 0.0 ? 0 : (int) Math.round(value / metricTotal * HUNDRED_PERCENT);
      splitRows.add(new MetricSplitRow(entry.getKey(), formatValue(value, format), pct, colorIndex));
    }
    return splitRows;
  }

  // --- Formatting (pre-formatted display strings, matching the handoff style) ---

  private static String formatValue(double value, ValueFormat format) {
    return switch (format) {
      case USD -> formatUsd(value);
      case DURATION -> formatDuration(value);
      case NUMBER -> formatNumber(value);
    };
  }

  private static String formatNumber(double value) {
    double abs = Math.abs(value);
    if (abs >= BILLION) {
      return trimDecimal(value / BILLION) + "B";
    }
    if (abs >= MILLION) {
      return trimDecimal(value / MILLION) + "M";
    }
    if (abs >= THOUSAND) {
      return trimDecimal(value / THOUSAND) + "K";
    }
    // Whole counts read as plain grouped integers ("184", "1,042"); fractional
    // sub-thousand values (typically per-hour rates) keep one decimal ("2.3").
    if (value == Math.rint(value)) {
      return String.format(Locale.US, "%,.0f", value);
    }
    return String.format(Locale.US, "%.1f", value);
  }

  private static String formatUsd(double value) {
    if (value >= MILLION) {
      return "$" + trimDecimal(value / MILLION) + "M";
    }
    if (value >= THOUSAND) {
      return String.format(Locale.US, "$%,.0f", value);
    }
    return String.format(Locale.US, "$%.2f", value);
  }

  private static String formatDuration(double seconds) {
    if (seconds >= SECONDS_PER_HOUR) {
      return trimDecimal(seconds / SECONDS_PER_HOUR) + "h";
    }
    if (seconds >= SECONDS_PER_MINUTE) {
      return Math.round(seconds / SECONDS_PER_MINUTE) + "m";
    }
    return Math.round(seconds) + "s";
  }

  // One decimal place, dropping a trailing ".0" (e.g. 13.0 -> "13", 12.4 -> "12.4").
  private static String trimDecimal(double value) {
    String formatted = String.format(Locale.US, "%.1f", value);
    return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
  }

  private static String formatDeltaPct(double deltaPct) {
    if (deltaPct >= 0.0) {
      return String.format(Locale.US, "+%.1f%%", deltaPct);
    }
    return String.format(Locale.US, "%.1f%%", deltaPct);
  }

  // Compact window caption: minutes under an hour, hours up to two days, then days.
  private static String shortWindowLabel(long windowSeconds) {
    if (windowSeconds < SECONDS_PER_HOUR_LONG) {
      return Math.max(1L, windowSeconds / SECONDS_PER_MINUTE) + "m";
    }
    long hours = Math.round((double) windowSeconds / SECONDS_PER_HOUR);
    if (hours < HOURS_PER_TWO_DAYS) {
      return hours + "h";
    }
    return Math.round((double) windowSeconds / SECONDS_PER_DAY) + "d";
  }
}
