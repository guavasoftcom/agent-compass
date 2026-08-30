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
import com.guavasoft.agentcompass.model.MetricSeries;
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

  private final TuningProperties tuningProperties;
  private final MetricPointRepository repository;

  public List<MetricSeries> metricSeries(Instant from, Instant to) {
    long windowSeconds = Math.max(1L, Duration.between(from, to).getSeconds());
    long bucketSeconds = Math.max(1L, windowSeconds / TREND_BUCKETS);
    Instant priorFrom = from.minusSeconds(windowSeconds);
    String windowLabel = shortWindowLabel(windowSeconds);

    List<MetricSpec> specs = metricSpecs();
    List<String> metricNames = specs.stream().map(MetricSpec::name).toList();

    // Batched across every metric name (see MetricPointRepository's comment on
    // aggregateMetricTotals/aggregateMetricTrend): one scan for every metric's
    // total instead of one scan per metric, one scan for every metric's trend
    // instead of one scan per metric.
    Map<String, MetricWindowTotals> totalsByMetricName = repository
        .aggregateMetricTotals(metricNames, priorFrom, from, to).stream()
        .collect(Collectors.toMap(
            row -> (String) row[0],
            row -> new MetricWindowTotals(
                ((Number) row[1]).doubleValue(), ((Number) row[2]).doubleValue())));
    Map<String, List<Object[]>> trendRowsByMetricName = repository
        .aggregateMetricTrend(metricNames, from, to, bucketSeconds).stream()
        .collect(Collectors.groupingBy(row -> (String) row[0]));
    Map<String, Map<String, Map<String, Double>>> splitTotalsByMetricThenAttribute =
        aggregateSplitTotals(specs, from, to);

    List<MetricSeries> series = new ArrayList<>(specs.size());
    for (MetricSpec spec : specs) {
      MetricWindowTotals totals = totalsByMetricName.getOrDefault(spec.name(), MetricWindowTotals.ZERO);
      List<Object[]> trendRows = trendRowsByMetricName.getOrDefault(spec.name(), List.of());
      Map<String, Map<String, Double>> splitTotalsByAttribute =
          splitTotalsByMetricThenAttribute.getOrDefault(spec.name(), Map.of());
      series.add(buildSeries(
          spec, totals.current(), totals.prior(), trendRows, splitTotalsByAttribute,
          from, windowSeconds, bucketSeconds, windowLabel));
    }
    return series;
  }

  // Every split-bearing curated metric shares just three physical attribute
  // keys (model, the configured token-type attribute, decision), so one call to
  // the batched aggregateMetricSplits query replaces what used to be one
  // repository call per (metric, split) pair -- five scans collapsed into one.
  // Row shape is (metric_name, model, token_type, decision, total); a row
  // contributes to whichever of the three attribute buckets it has a non-null
  // label for, reproducing the "attribute IS NOT NULL" filter the old
  // per-split query applied in SQL.
  private Map<String, Map<String, Map<String, Double>>> aggregateSplitTotals(
      List<MetricSpec> specs, Instant from, Instant to) {
    List<String> splitMetricNames = specs.stream()
        .filter(spec -> !spec.splits().isEmpty())
        .map(MetricSpec::name)
        .toList();
    if (splitMetricNames.isEmpty()) {
      return Map.of();
    }
    String typeAttribute = tuningProperties.getTokenTypeAttribute();
    Map<String, Map<String, Map<String, Double>>> totalsByMetricThenAttribute = new HashMap<>();
    for (Object[] row : repository.aggregateMetricSplits(
        splitMetricNames, MODEL_ATTRIBUTE, typeAttribute, DECISION_ATTRIBUTE, from, to)) {
      String metricName = (String) row[0];
      double rowTotal = ((Number) row[4]).doubleValue();
      addSplitContribution(totalsByMetricThenAttribute, metricName, MODEL_ATTRIBUTE, (String) row[1], rowTotal);
      addSplitContribution(totalsByMetricThenAttribute, metricName, typeAttribute, (String) row[2], rowTotal);
      addSplitContribution(totalsByMetricThenAttribute, metricName, DECISION_ATTRIBUTE, (String) row[3], rowTotal);
    }
    return totalsByMetricThenAttribute;
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
            "token", tuningProperties.getTokenUsageMetric(), "tokens", "Sum",
            "Tokens consumed across Claude Code sessions, summed over the window. The biggest cost "
                + "driver — split by model to see where they go. The input/output/cache breakdown "
                + "lives on the Token Usage page.",
            ValueFormat.NUMBER, orderedSplits("Model", MODEL_ATTRIBUTE)),
        new MetricSpec(
            "cost", tuningProperties.getCostUsageMetric(), "USD", "Spend",
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
      Map<String, Map<String, Double>> splitTotalsByAttribute,
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
        splits);
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
