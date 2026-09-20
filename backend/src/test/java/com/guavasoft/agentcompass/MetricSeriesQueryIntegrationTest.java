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
package com.guavasoft.agentcompass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.config.TuningProperties;
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.MetricAggregation;
import com.guavasoft.agentcompass.model.MetricFacet;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricSeriesAggregation;
import com.guavasoft.agentcompass.model.MetricSeriesFilter;
import com.guavasoft.agentcompass.model.MetricSplitRow;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Exercises {@link MetricSeriesService} against a real Postgres so the reset-aware,
 * full-attribute increment SQL behind {@code GET /api/metrics/series} is validated
 * end-to-end — the {@code MetricsControllerTest} mocks the service and can't catch
 * SQL-level mistakes (concurrent-stream merging, counter resets, split grouping).
 */
@SpringBootTest
@Testcontainers
class MetricSeriesQueryIntegrationTest {

  private static final String TOKEN_METRIC = "claude_code.token.usage";
  private static final String DECISION_METRIC = "claude_code.code_edit_tool.decision";
  private static final String REPOSITORY_A = "https://github.com/acme/repo-a";
  private static final String REPOSITORY_ATTRIBUTE = "vcs.repository.url.full";
  private static final String AGGREGATION_METRIC = "acme.agg.metric";
  private static final String AGGREGATION_METRIC_ID = "acme-agg-metric";
  private static final String TYPED_METRIC = "acme.typed.metric";
  private static final String TYPED_METRIC_ID = "acme-typed-metric";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  MetricSeriesService metricSeriesService;

  @Autowired
  TuningProperties tuningProperties;

  private Instant base;
  private final List<Long> seededMetricPointIds = new ArrayList<>();

  @BeforeEach
  void seed() {
    metricPointRepository.deleteAll();
    seededMetricPointIds.clear();
    base = Instant.now().minus(20, ChronoUnit.MINUTES);

    // token.usage — three concurrent cumulative streams (distinct full attribute
    // sets). Reset-aware increments telescope to each stream's final value:
    //   sonnet/input:     200 -> 500   => 500
    //   opus/input:       1500          => 1500
    //   sonnet/cacheRead: 3000          => 3000
    // total 5000; Model: sonnet 3500 / opus 1500; Type: cacheRead 3000 / input 2000.
    saveToken("sonnet", "input", 200, base);
    saveToken("sonnet", "input", 500, base.plusSeconds(60));
    saveToken("opus", "input", 1500, base.plusSeconds(30));
    saveToken("sonnet", "cacheRead", 3000, base.plusSeconds(45));

    // code_edit_tool.decision — accept stream that RESETS (a re-spawned run starts
    // low again), exercising the CASE reset handling:
    //   accept: 5 -> 8 -> (reset) 2  => 5 + 3 + 2 = 10
    //   reject: 1                     => 1
    saveDecision("accept", 5, base);
    saveDecision("accept", 8, base.plusSeconds(60));
    saveDecision("accept", 2, base.plusSeconds(120));
    saveDecision("reject", 1, base.plusSeconds(30));

    // Fixtures seed rows directly (bypassing OtlpMetricService), so value_delta
    // starts NULL. Replicate the ingest-time computation here — same
    // recomputeValueDeltas call the real ingest path uses after saveAll — so the
    // reset-aware aggregations under test see the same value_delta they would in
    // production.
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  @Test
  void tokenSeriesReconcilesTotalWithModelSplit() {
    MetricSeries token = seriesById("token");

    assertThat(token.name()).isEqualTo(TOKEN_METRIC);
    assertThat(token.type()).isEqualTo("counter");
    assertThat(token.unit()).isEqualTo("tokens");
    assertThat(token.sum()).isEqualTo("5K");
    assertThat(token.trend()).hasSize(24);
    // Trend buckets are the same reset-aware increments, so they sum to the total.
    assertThat(token.trend().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(5000.0);
    // Cardinality is a plain number (three concurrent streams), never a pre-formatted "3"; health stays
    // the server-computed ok / warn / bad string.
    assertThat(token.cardinality()).isEqualTo(3L);
    assertThat(token.health()).isEqualTo("ok");

    List<MetricSplitRow> byModel = token.splits().get("Model");
    assertThat(byModel).extracting(MetricSplitRow::label).containsExactly("sonnet", "opus");
    assertThat(byModel.get(0).value()).isEqualTo("3.5K");
    assertThat(byModel.get(0).pct()).isEqualTo(70);
    assertThat(byModel.get(0).colorIndex()).isEqualTo(0);
    assertThat(byModel.get(1).value()).isEqualTo("1.5K");
    assertThat(byModel.get(1).pct()).isEqualTo(30);

    // Type split removed; only Model split remains
    assertThat(token.splits()).hasSize(1).containsKey("Model");
  }

  @Test
  void decisionSeriesCountsTheResetAsNewUsage() {
    MetricSeries decision = seriesById("decision");

    // 5 + (8-5) + 2 = 10 accepts (reset counted), + 1 reject = 11 total.
    assertThat(decision.sum()).isEqualTo("11");
    List<MetricSplitRow> byDecision = decision.splits().get("Decision");
    assertThat(byDecision).extracting(MetricSplitRow::label).containsExactly("accept", "reject");
    assertThat(byDecision.get(0).value()).isEqualTo("10");
    assertThat(byDecision.get(0).pct()).isEqualTo(91);
    assertThat(byDecision.get(1).value()).isEqualTo("1");
  }

  @Test
  void returnsEveryCuratedMetricEvenWhenUnseeded() {
    List<MetricSeries> series = metricSeriesService.metricSeries(windowStart(), Instant.now(), null);
    assertThat(series).extracting(MetricSeries::id)
        .containsExactly("token", "cost", "session", "active", "loc", "decision", "commit", "pull_request");
    // Metrics with no rows still return a well-formed zero series, no splits populated.
    MetricSeries session = series.stream().filter(s -> "session".equals(s.id())).findFirst().orElseThrow();
    assertThat(session.sum()).isEqualTo("0");
    assertThat(session.splits()).isEmpty();

    MetricSeries commit = series.stream().filter(s -> "commit".equals(s.id())).findFirst().orElseThrow();
    assertThat(commit.name()).isEqualTo("claude_code.commit.count");
    assertThat(commit.splits()).isEmpty();
  }

  @Test
  void onlyTokenAndCostAdvertiseADistribution() {
    List<MetricSeries> series = metricSeriesService.metricSeries(windowStart(), Instant.now(), null);

    assertThat(series).filteredOn(MetricSeries::hasDistribution)
        .extracting(MetricSeries::id)
        .containsExactly("token", "cost");
  }

  @Test
  void appendsAGeneratedCardForAMetricNoSpecCovers() {
    // One cumulative stream, 40 then 100, so the reset-aware sum telescopes to 100.
    saveWithUnit("acme.agent.spend.total", "USD", 40.0, base);
    saveWithUnit("acme.agent.spend.total", "USD", 100.0, base.plusSeconds(60));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    List<MetricSeries> series = metricSeriesService.metricSeries(windowStart(), Instant.now(), null);

    // Curated order is preserved and the unknown metric lands after it.
    assertThat(series).extracting(MetricSeries::id)
        .containsExactly("token", "cost", "session", "active", "loc", "decision", "commit",
            "pull_request", "acme-agent-spend-total");

    MetricSeries discovered = series.get(series.size() - 1);
    assertThat(discovered.name()).isEqualTo("acme.agent.spend.total");
    // The declared unit drives the format, so a discovered money counter still reads as money.
    assertThat(discovered.sum()).isEqualTo("$100.00");
    assertThat(discovered.trend()).hasSize(24);
    assertThat(discovered.splits()).isEmpty();
    assertThat(discovered.description()).contains("not yet curated");
  }

  @Test
  void discoveryIgnoresTheWindowSoCardsDoNotAppearAndVanishAsTheUserPans() {
    saveWithUnit("acme.agent.legacy.count", "", 7.0, base.minus(90, ChronoUnit.DAYS));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    MetricSeries discovered = metricSeriesService.metricSeries(windowStart(), Instant.now(), null).stream()
        .filter(candidate -> "acme-agent-legacy-count".equals(candidate.id()))
        .findFirst()
        .orElseThrow();

    // Every row it has is far outside the queried window, so it reads zero rather
    // than disappearing -- the same way an unseeded curated metric does.
    assertThat(discovered.sum()).isEqualTo("0");
  }

  // --- Attribute filter (GET /api/metrics/series?filterMetricId=&filter=key:value&filter=...) ---

  @Test
  void anExplicitNoFilterIsIdenticalToTheUnfilteredOverload() {
    Instant to = Instant.now();

    assertThat(metricSeriesService.metricSeries(windowStart(), to, null, MetricSeriesFilter.NONE))
        .isEqualTo(metricSeriesService.metricSeries(windowStart(), to, null));
  }

  @Test
  void aFilterNarrowsOnlyTheNamedMetricEverywhereItIsReported() {
    seedSessionCounter();
    Instant to = Instant.now();

    List<MetricSeries> unfiltered = metricSeriesService.metricSeries(windowStart(), to, null);
    List<MetricSeries> filtered = metricSeriesService.metricSeries(
        windowStart(), to, null, filterOn("token", "model:sonnet"));

    // Header total, trend, peak, Group-by breakdown and cardinality all describe model=sonnet:
    // input 200 -> 500 and cacheRead 3000 are its two streams, 3500 in all, in one bucket.
    MetricSeries token = byId(filtered, "token");
    assertThat(token.sum()).isEqualTo("3.5K");
    assertThat(token.trend().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(3500.0);
    assertThat(token.peak()).isEqualTo("3.5K");
    assertThat(token.splits().get("Model")).extracting(MetricSplitRow::label).containsExactly("sonnet");
    assertThat(token.splits().get("Model").get(0).value()).isEqualTo("3.5K");
    assertThat(token.splits().get("Model").get(0).pct()).isEqualTo(100);
    assertThat(token.cardinality()).isEqualTo(2L);
    // The same metric unfiltered still sees all three streams.
    MetricSeries unfilteredToken = byId(unfiltered, "token");
    assertThat(unfilteredToken.sum()).isEqualTo("5K");
    assertThat(unfilteredToken.cardinality()).isEqualTo(3L);

    // Every other metric in the batch is byte-identical to the unfiltered response: session.count
    // has no model attribute and must not be zeroed by a model filter.
    assertThat(byId(filtered, "session").sum()).isEqualTo("6");
    assertThat(filtered).filteredOn(series -> !"token".equals(series.id()))
        .containsExactlyElementsOf(unfiltered.stream().filter(series -> !"token".equals(series.id())).toList());
  }

  @Test
  void aFilterCanTargetAMetricThatIsNotTokens() {
    Instant to = Instant.now();

    MetricSeries decision = byId(
        metricSeriesService.metricSeries(
            windowStart(), to, null, filterOn("decision", "decision:accept")),
        "decision");

    assertThat(decision.sum()).isEqualTo("10");
    assertThat(decision.splits().get("Decision")).extracting(MetricSplitRow::label).containsExactly("accept");
    assertThat(decision.cardinality()).isEqualTo(1L);
  }

  @Test
  void severalFiltersAreANDedAcrossTotalsTrendSplitsAndCardinality() {
    Instant to = Instant.now();

    // model=sonnet leaves two streams (input, cacheRead); adding type=cacheRead leaves just one.
    MetricSeries sonnetCacheRead = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:sonnet", "type:cacheRead")),
        "token");
    assertThat(sonnetCacheRead.sum()).isEqualTo("3K");
    assertThat(sonnetCacheRead.trend().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(3000.0);
    assertThat(sonnetCacheRead.splits().get("Model")).extracting(MetricSplitRow::label).containsExactly("sonnet");
    assertThat(sonnetCacheRead.cardinality()).isEqualTo(1L);

    // The same two filters written in the other order are the same population.
    assertThat(byId(metricSeriesService.metricSeries(
        windowStart(), to, null, filterOn("token", "type:cacheRead", "model:sonnet")), "token"))
        .isEqualTo(sonnetCacheRead);

    // Each additional pair can only narrow: sonnet AND input is 200 -> 500 (500), not the 3000 of
    // sonnet's other stream and not opus/input's 1500.
    MetricSeries sonnetInput = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:sonnet", "type:input")),
        "token");
    assertThat(sonnetInput.sum()).isEqualTo("500");
    assertThat(sonnetInput.cardinality()).isEqualTo(1L);

    // Two pairs no single row satisfies, and two pairs naming the SAME key with different values,
    // both match nothing (AND, never OR, and the second pair does not replace the first).
    assertThat(byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:opus", "type:cacheRead")),
        "token").sum()).isEqualTo("0");
    MetricSeries contradictory = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:sonnet", "model:opus")),
        "token");
    assertThat(contradictory.sum()).isEqualTo("0");
    assertThat(contradictory.cardinality()).isZero();
  }

  @Test
  void severalFiltersAlsoNarrowTheAggregatedTrend() {
    // sonnet/input moved by 200 then 300, both in trend bucket 16 of the fixed window.
    for (MetricAggregation aggregation : List.of(MetricAggregation.COUNT, MetricAggregation.AVG, MetricAggregation.P95)) {
      List<Double> narrowed = byId(
          metricSeriesService.metricSeries(
              windowStart(), windowEnd(), null, filterOn("token", "model:sonnet", "type:input"),
              new MetricSeriesAggregation("token", aggregation)),
          "token").trend();
      List<Double> onlyOneFilter = byId(
          metricSeriesService.metricSeries(
              windowStart(), windowEnd(), null, filterOn("token", "model:sonnet"),
              new MetricSeriesAggregation("token", aggregation)),
          "token").trend();

      double expectedNarrowed = switch (aggregation) {
        case COUNT -> 2.0;
        case AVG -> 250.0;
        case P95 -> 295.0;
        case SUM -> throw new IllegalStateException("not exercised");
      };
      assertThat(narrowed.get(16)).isEqualTo(expectedNarrowed);
      // Dropping the type pair lets sonnet's cacheRead increment (3000) back in, so the two differ.
      assertThat(onlyOneFilter.get(16)).isNotEqualTo(narrowed.get(16));
    }
  }

  @Test
  void filterValuesCompareAsTextSoNumericAndBooleanAttributesStillMatch() {
    seedTypedAttributeMetric();
    Instant to = Instant.now();

    // status is stored as the JSON NUMBER 200/404 and cached as the JSON BOOLEAN true/false; a jsonb
    // containment test against the string "200" would miss both, ->> text comparison does not.
    assertThat(typedMetricSum(to, "status:200")).isEqualTo(2.0);
    assertThat(typedMetricSum(to, "status:404")).isEqualTo(1.0);
    assertThat(typedMetricSum(to, "cached:true")).isEqualTo(1.0);
    assertThat(typedMetricSum(to, "status:200", "cached:false")).isEqualTo(1.0);
    assertThat(typedMetricSum(to, "status:200", "cached:true")).isEqualTo(1.0);
    assertThat(typedMetricSum(to, "status:200", "cached:true", "region:eu")).isEqualTo(1.0);
    // Text, not number: "200.0" and " 200" are different strings, and a missing key matches nothing.
    assertThat(typedMetricSum(to, "status:200.0")).isZero();
    assertThat(typedMetricSum(to, "status: 200")).isZero();
    assertThat(typedMetricSum(to, "no.such.key:200")).isZero();
    assertThat(typedMetricSum(to, "status:200", "no.such.key:200")).isZero();
  }

  @Test
  void aValueMayContainColonsQuotesAndBackslashesAndStillMatchesExactly() {
    seedTypedAttributeMetric();
    Instant to = Instant.now();

    // The pair is split on the first colon only, so the value keeps its own colons.
    assertThat(typedMetricSum(to, "url:http://host:8080/a:b")).isEqualTo(1.0);
    // Quotes and backslashes travel inside a Jackson-built JSON document, so they cannot break out of
    // it and they round-trip to the exact stored text.
    assertThat(typedMetricSum(to, "label:say \"hi\" \\ back")).isEqualTo(1.0);
    assertThat(typedMetricSum(to, "label:say \"hi\" \\ back\"}] OR 1=1 --")).isZero();
    assertThat(typedMetricSum(to, "region:eu' OR '1'='1")).isZero();
    assertThat(typedMetricSum(to, "region\":\"eu:eu")).isZero();
  }

  @Test
  void aFilterMatchingNothingZeroesOnlyThatMetric() {
    seedSessionCounter();

    List<MetricSeries> filtered = metricSeriesService.metricSeries(
        windowStart(), Instant.now(), null, filterOn("token", "model:no-such-model"));

    MetricSeries token = byId(filtered, "token");
    assertThat(token.sum()).isEqualTo("0");
    assertThat(token.trend()).hasSize(24).containsOnly(0.0);
    assertThat(token.splits().get("Model")).isEmpty();
    assertThat(token.cardinality()).isZero();
    assertThat(byId(filtered, "session").sum()).isEqualTo("6");
  }

  @Test
  void aFilterKeyOrValueIsBoundAsDataNeverInterpolatedIntoSql() {
    String injection = "sonnet' OR '1'='1";

    MetricSeries token = byId(
        metricSeriesService.metricSeries(
            windowStart(), Instant.now(), null, filterOn("token", "model:" + injection)),
        "token");
    MetricSeries tokenWithHostileKey = byId(
        metricSeriesService.metricSeries(
            windowStart(), Instant.now(), null, filterOn("token", "model' OR '1'='1:sonnet")),
        "token");

    assertThat(token.sum()).isEqualTo("0");
    assertThat(tokenWithHostileKey.sum()).isEqualTo("0");
  }

  @Test
  void aFilterMayTargetADiscoveredMetric() {
    seedWideMetric();

    MetricSeries wide = byId(
        metricSeriesService.metricSeries(
            windowStart(), Instant.now(), null, filterOn("acme-wide-metric", "region:eu")),
        "acme-wide-metric");

    // 30 single-point streams, 15 in each region; each first point counts its full 1.0.
    assertThat(wide.sum()).isEqualTo("15");
    assertThat(wide.cardinality()).isEqualTo(15L);

    // Two pairs on the same metric AND: region=eu AND tier=alpha is i even AND i >= 20, i.e. 5 streams.
    MetricSeries euAlpha = byId(
        metricSeriesService.metricSeries(
            windowStart(), Instant.now(), null, filterOn("acme-wide-metric", "region:eu", "tier:alpha")),
        "acme-wide-metric");
    assertThat(euAlpha.sum()).isEqualTo("5");
    assertThat(euAlpha.cardinality()).isEqualTo(5L);
  }

  @Test
  void aFilterForAnUnknownMetricIdIsRejected() {
    assertThatThrownBy(() -> metricSeriesService.metricSeries(
        windowStart(), Instant.now(), null, filterOn("latency", "model:sonnet")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latency");
  }

  @Test
  void aFilterCombinesWithTheRepositoryScope() {
    seedRepositoryScopedRows();
    Instant to = Instant.now();

    List<MetricSeries> sonnetInRepository = metricSeriesService.metricSeries(
        windowStart(), to, REPOSITORY_A, filterOn("token", "model:sonnet"));
    List<MetricSeries> unfilteredInRepository = metricSeriesService.metricSeries(windowStart(), to, REPOSITORY_A);
    List<MetricSeries> haikuInRepository = metricSeriesService.metricSeries(
        windowStart(), to, REPOSITORY_A, filterOn("token", "model:haiku"));

    // Only repository A's rows count: sonnet 100 -> 300 is 300 and haiku is 40; the seeded
    // repository-less sonnet/opus rows are out of scope, and so is everything they contribute.
    assertThat(byId(unfilteredInRepository, "token").sum()).isEqualTo("340");
    assertThat(byId(sonnetInRepository, "token").sum()).isEqualTo("300");
    assertThat(byId(sonnetInRepository, "token").splits().get("Model"))
        .extracting(MetricSplitRow::label).containsExactly("sonnet");
    assertThat(byId(haikuInRepository, "token").sum()).isEqualTo("40");
    // The repository's session counter has no model attribute either, and is untouched by the filter.
    assertThat(byId(sonnetInRepository, "session").sum()).isEqualTo("2");
  }

  // --- Attributes by metric NAME (GET /api/metrics/attributes?metric=) ---

  @Test
  void attributesListEachKeyWithItsValuesByStreamCount() {
    // A stream outside the window must not contribute.
    save(TOKEN_METRIC, Map.of("session.id", "s1", "model", "ancient", "type", "input"), 1.0,
        base.minus(90, ChronoUnit.DAYS));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    List<MetricFacet> attributes = attributesOf(TOKEN_METRIC, null);

    // Keys alphabetical. Counts are distinct label-sets, not rows: sonnet/input has two rows but is
    // one stream, so sonnet carries 2 streams (input, cacheRead) and opus 1.
    assertThat(attributes).extracting(MetricFacet::key).containsExactly("model", "session.id", "type");
    assertThat(facetValues(attributes, "model")).containsExactly("sonnet:2", "opus:1");
    assertThat(facetValues(attributes, "session.id")).containsExactly("s1:3");
    assertThat(facetValues(attributes, "type")).containsExactly("input:2", "cacheRead:1");
  }

  @Test
  void attributesAreEmptyForAMetricWithNoDataInTheWindow() {
    // A curated metric nobody emitted in the window.
    assertThat(attributesOf(tuningProperties.getSessionCountMetric(), null)).isEmpty();
  }

  @Test
  void aMetricNameNeverSeenIsNotAnErrorAndYieldsNoAttributes() {
    for (String unseenName : List.of("acme.never.emitted", "token", "acme-wide-metric", "")) {
      assertThat(attributesOf(unseenName, null)).isEmpty();
    }
  }

  @Test
  void aDiscoveredMetricHasAttributesToo() {
    seedWideMetric();

    assertThat(attributesOf("acme.wide.metric", null)).extracting(MetricFacet::key)
        .containsExactly("bucket", "region", "tier");
  }

  @Test
  void attributesOmitKeysWithMoreThan25DistinctValuesAndSkipEmptyValues() {
    seedWideMetric();

    List<MetricFacet> attributes = attributesOf("acme.wide.metric", null);

    // request.id has 30 distinct values and is dropped; bucket has exactly 25 and is kept; the
    // empty-string "note" and JSON-null "flag" values are skipped, so those keys never appear.
    assertThat(attributes).extracting(MetricFacet::key).containsExactly("bucket", "region", "tier");
    assertThat(facetValues(attributes, "bucket")).hasSize(25);
    assertThat(facetValues(attributes, "bucket").subList(0, 5))
        .containsExactly("b0:2", "b1:2", "b2:2", "b3:2", "b4:2");
    // Equal counts fall back to value order; otherwise most common first.
    assertThat(facetValues(attributes, "region")).containsExactly("eu:15", "us:15");
    assertThat(facetValues(attributes, "tier")).containsExactly("zeta:20", "alpha:10");
  }

  @Test
  void attributesAreScopedToTheRepository() {
    seedRepositoryScopedRows();

    List<MetricFacet> attributes = attributesOf(TOKEN_METRIC, REPOSITORY_A);

    assertThat(facetValues(attributes, "model")).containsExactly("haiku:1", "sonnet:1");
    assertThat(facetValues(attributes, "session.id")).containsExactly("s2:2");
    assertThat(facetValues(attributes, "vcs.repository.url.full")).containsExactly(REPOSITORY_A + ":2");
    // Unscoped, the same call sees the repository-less streams as well.
    assertThat(facetValues(attributesOf(TOKEN_METRIC, null), "model")).contains("opus:1");
  }

  @Test
  void aKeyTheAttributesEndpointOffersIsAKeyTheSeriesFilterAccepts() {
    // The picker feeds the filter: every key/value it lists must be usable as a filter pair and
    // select exactly the streams it counted.
    for (String modelValue : facetValues(attributesOf(TOKEN_METRIC, null), "model")) {
      String[] valueAndCount = modelValue.split(":");
      MetricSeries filtered = byId(
          metricSeriesService.metricSeries(windowStart(), Instant.now(), null, filterOn("token", "model:" + valueAndCount[0])),
          "token");
      assertThat(filtered.cardinality()).isEqualTo(Long.parseLong(valueAndCount[1]));
    }
  }

  // --- Trend aggregation (GET /api/metrics/series?aggMetricId=&agg=) ---

  @Test
  void avgP95AndCountAggregateOnlyTheNonZeroIncrementsInEachBucket() {
    seedAggregationMetric();

    List<MetricSeries> sum = seriesInFixedWindow(MetricSeriesAggregation.NONE);
    // Fixed 3600s window / 24 buckets = 150s wide, and base is exactly the start of bucket 16.
    // Non-zero increments: bucket 16 = [10, 20, 40] (plus two ghost re-emissions), bucket 17 = [30]
    // (plus two ghosts), bucket 19 = [5, 4]; bucket 18 and everything else is empty.
    assertThat(byId(sum, AGGREGATION_METRIC_ID).trend().subList(16, 20)).containsExactly(70.0, 30.0, 0.0, 9.0);

    List<Double> average = byId(seriesInFixedWindow(aggregation(MetricAggregation.AVG)), AGGREGATION_METRIC_ID).trend();
    assertThat(average).hasSize(24);
    assertThat(average.get(16)).isCloseTo(23.33, within(0.005));
    assertThat(average.get(17)).isEqualTo(30.0);
    assertThat(average.get(18)).isEqualTo(0.0);
    assertThat(average.get(19)).isEqualTo(4.5);
    assertThat(average.stream().filter(value -> value != 0.0)).hasSize(3);

    // percentile_cont(0.95) interpolates: [10, 20, 40] -> 20 + 0.9 * 20 = 38, [4, 5] -> 4.95.
    List<Double> p95 = byId(seriesInFixedWindow(aggregation(MetricAggregation.P95)), AGGREGATION_METRIC_ID).trend();
    assertThat(p95.get(16)).isEqualTo(38.0);
    assertThat(p95.get(17)).isEqualTo(30.0);
    assertThat(p95.get(18)).isEqualTo(0.0);
    assertThat(p95.get(19)).isCloseTo(4.95, within(0.005));

    // A count that included the zero-delta re-emissions would read 5 / 3 / 2 here.
    List<Double> count = byId(seriesInFixedWindow(aggregation(MetricAggregation.COUNT)), AGGREGATION_METRIC_ID).trend();
    assertThat(count.subList(16, 20)).containsExactly(3.0, 1.0, 0.0, 2.0);
    assertThat(count.stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(6.0);
  }

  @Test
  void aggregationChangesOnlyTheTargetTrendAndLeavesEveryOtherFigureUntouched() {
    seedAggregationMetric();
    seedSessionCounter();
    List<MetricSeries> sum = seriesInFixedWindow(MetricSeriesAggregation.NONE);

    for (MetricAggregation aggregation : List.of(MetricAggregation.AVG, MetricAggregation.P95, MetricAggregation.COUNT)) {
      List<MetricSeries> aggregated = seriesInFixedWindow(aggregation(aggregation));

      MetricSeries target = byId(aggregated, AGGREGATION_METRIC_ID);
      MetricSeries targetInSumResponse = byId(sum, AGGREGATION_METRIC_ID);
      assertThat(target.trend()).isNotEqualTo(targetInSumResponse.trend());
      // Sum, rate, peak, delta, splits, cardinality, everything except the trend array is the sum
      // response's, byte for byte -- the peak in particular still reads the sum trend's 70.
      assertThat(target.withTrend(targetInSumResponse.trend())).isEqualTo(targetInSumResponse);
      assertThat(target.peak()).isEqualTo(targetInSumResponse.peak());
      // Every other metric's whole series, trend included, is identical.
      assertThat(aggregated).filteredOn(series -> !AGGREGATION_METRIC_ID.equals(series.id()))
          .containsExactlyElementsOf(
              sum.stream().filter(series -> !AGGREGATION_METRIC_ID.equals(series.id())).toList());
    }
  }

  @Test
  void aggregatingACuratedMetricWithNoIncrementsGivesAnAllZeroTrend() {
    MetricSeries session = byId(
        seriesInFixedWindow(new MetricSeriesAggregation("session", MetricAggregation.AVG)), "session");

    assertThat(session.trend()).hasSize(24).containsOnly(0.0);
    assertThat(session.sum()).isEqualTo("0");
  }

  @Test
  void aggSumAndNoAggregationAreBothIdenticalToTheDefaultSumPath() {
    seedAggregationMetric();
    Instant to = windowEnd();

    List<MetricSeries> defaults = metricSeriesService.metricSeries(windowStart(), to, null);

    assertThat(metricSeriesService.metricSeries(
        windowStart(), to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE)).isEqualTo(defaults);
    assertThat(metricSeriesService.metricSeries(
        windowStart(), to, null, MetricSeriesFilter.NONE,
        new MetricSeriesAggregation(AGGREGATION_METRIC_ID, MetricAggregation.SUM))).isEqualTo(defaults);
    assertThat(metricSeriesService.metricSeries(windowStart(), to, null, MetricSeriesFilter.NONE))
        .isEqualTo(defaults);
  }

  @Test
  void aggregationHonoursTheAttributeFilterOnlyWhenItTargetsTheSameMetric() {
    seedAggregationMetric();

    // Filtering the aggregated metric to model=m1 drops stream two, so bucket 19 empties out.
    List<Double> filteredCount = byId(
        metricSeriesService.metricSeries(
            windowStart(), windowEnd(), null,
            filterOn(AGGREGATION_METRIC_ID, "model:m1"), aggregation(MetricAggregation.COUNT)),
        AGGREGATION_METRIC_ID).trend();
    assertThat(filteredCount.subList(16, 20)).containsExactly(3.0, 1.0, 0.0, 0.0);

    // A filter aimed at a different metric never narrows the aggregated one -- and still narrows its
    // own metric, so both are correct in the same response.
    List<MetricSeries> filteredElsewhere = metricSeriesService.metricSeries(
        windowStart(), windowEnd(), null,
        filterOn("token", "model:sonnet"), aggregation(MetricAggregation.COUNT));
    assertThat(byId(filteredElsewhere, AGGREGATION_METRIC_ID).trend().subList(16, 20))
        .containsExactly(3.0, 1.0, 0.0, 2.0);
    assertThat(byId(filteredElsewhere, "token").sum()).isEqualTo("3.5K");
    assertThat(byId(filteredElsewhere, "token").cardinality()).isEqualTo(2L);
  }

  @Test
  void aFilterAndAnAggregationOnTheSameMetricComposeWhileEveryOtherMetricStaysOnThePlainPath() {
    // Filtered header AND filtered agg trend for the one metric, with the plain path for the rest.
    List<MetricSeries> series = metricSeriesService.metricSeries(
        windowStart(), windowEnd(), null,
        filterOn("token", "model:sonnet", "type:input"), new MetricSeriesAggregation("token", MetricAggregation.COUNT));

    MetricSeries token = byId(series, "token");
    assertThat(token.sum()).isEqualTo("500");
    assertThat(token.trend().get(16)).isEqualTo(2.0);
    assertThat(token.cardinality()).isEqualTo(1L);
    // Decision is a different metric: untouched, still the full accept + reject.
    assertThat(byId(series, "decision").sum()).isEqualTo("11");
  }

  @Test
  void aFilterMatchingEveryRowGivesTheSameSeriesThroughTheFilteredPathAsThroughThePlainPath() {
    Instant to = Instant.now();

    List<MetricSeries> unfiltered = metricSeriesService.metricSeries(windowStart(), to, null);
    // Every seeded token row carries session.id=s1, so this filter is satisfied by all of them and the
    // single-metric ...Filtered queries must reproduce the plain batched queries exactly.
    List<MetricSeries> viaFilteredPath = metricSeriesService.metricSeries(
        windowStart(), to, null, filterOn("token", "session.id:s1"));

    assertThat(byId(viaFilteredPath, "token")).isEqualTo(byId(unfiltered, "token"));
    assertThat(viaFilteredPath).isEqualTo(unfiltered);
  }

  @Test
  void cardinalityCountsOnlyLabelSetsWithNonZeroActivityInTheWindowFilteredOrNot() {
    // A haiku stream that reached 100 long before the window and only RE-EMITTED 100 inside it: its
    // in-window row is a zero-delta ghost. Cardinality is the number of ACTIVE label-sets, so the
    // ghost is left out of the unfiltered figure too (it used to count every stream that emitted,
    // which inflated it ~4x on live data and forced a scan of every ghost row).
    saveToken("haiku", "input", 100, base.minus(3, ChronoUnit.HOURS));
    saveToken("haiku", "input", 100, base.plusSeconds(10));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
    Instant to = Instant.now();

    assertThat(byId(metricSeriesService.metricSeries(windowStart(), to, null), "token").cardinality())
        .isEqualTo(3L);

    // The ghost-only stream is invisible under a filter that would otherwise select it ...
    MetricSeries haiku = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:haiku")), "token");
    assertThat(haiku.cardinality()).isZero();
    assertThat(haiku.sum()).isEqualTo("0");
    // ... and does not inflate a broader one: type=input selects sonnet/input and opus/input (active)
    // plus haiku/input (ghost), so 2 -- where counting the ghost would have said 3.
    MetricSeries input = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "type:input")), "token");
    assertThat(input.cardinality()).isEqualTo(2L);
    assertThat(input.sum()).isEqualTo("2K");
    // A metric the filter does not target is counted the same way (plain path), so the two paths agree.
    assertThat(byId(metricSeriesService.metricSeries(windowStart(), to, null, filterOn("decision", "decision:accept")),
        "token").cardinality()).isEqualTo(3L);
  }

  @Test
  void aLabelWhoseOnlyRowsInTheWindowAreReEmissionsIsNotASplitRow() {
    // haiku reached 100 long before the window and only re-emitted 100 inside it: a zero-delta ghost.
    // The split query reads only rows that moved, so haiku must not show up as a 0% breakdown row
    // (which is also how the filtered path has always behaved), while the metric total is unchanged.
    saveToken("haiku", "input", 100, base.minus(3, ChronoUnit.HOURS));
    saveToken("haiku", "input", 100, base.plusSeconds(10));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);

    MetricSeries token = byId(metricSeriesService.metricSeries(windowStart(), Instant.now(), null), "token");

    assertThat(token.splits().get("Model")).extracting(MetricSplitRow::label).doesNotContain("haiku");
    assertThat(token.sum()).isEqualTo("5K");
  }

  @Test
  void aFilteredTotalCoversThePriorWindowSoTheDeltaIsFilteredToo() {
    // Two streams active in the PRIOR window ([base-100m, base-40m)): sonnet 700 and opus 100.
    save(TOKEN_METRIC, Map.of("session.id", "s9", "model", "sonnet", "type", "input"), 700.0,
        base.minus(70, ChronoUnit.MINUTES));
    save(TOKEN_METRIC, Map.of("session.id", "s9", "model", "opus", "type", "input"), 100.0,
        base.minus(70, ChronoUnit.MINUTES));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
    Instant to = Instant.now();

    // Unfiltered: 5000 now vs 800 before. Filtered to sonnet: 3500 now vs 700 before.
    assertThat(byId(metricSeriesService.metricSeries(windowStart(), to, null), "token").delta())
        .isEqualTo("+525.0%");
    MetricSeries sonnet = byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn("token", "model:sonnet")), "token");
    assertThat(sonnet.sum()).isEqualTo("3.5K");
    assertThat(sonnet.delta()).isEqualTo("+400.0%");
    assertThat(sonnet.dir()).isEqualTo("up");
  }

  @Test
  void aggregationComposesWithTheAttributeFilterAndTheRepositoryScope() {
    seedRepositoryScopedRows();
    Instant to = windowEnd();
    MetricSeriesFilter sonnetOnly = filterOn("token", "model:sonnet");

    // Repository A's sonnet stream moved by 100 then 200; the repository-less base fixture is out
    // of scope, and haiku (a single increment of 40) is filtered away.
    assertThat(tokenBucketSixteen(to, sonnetOnly, MetricAggregation.AVG)).isEqualTo(150.0);
    assertThat(tokenBucketSixteen(to, sonnetOnly, MetricAggregation.COUNT)).isEqualTo(2.0);
    assertThat(tokenBucketSixteen(to, sonnetOnly, MetricAggregation.P95)).isEqualTo(195.0);
    assertThat(tokenBucketSixteen(to, filterOn("token", "model:haiku"), MetricAggregation.AVG))
        .isEqualTo(40.0);
    // Without a filter the same scope sees all three increments: 100, 200 and 40.
    assertThat(tokenBucketSixteen(to, MetricSeriesFilter.NONE, MetricAggregation.COUNT)).isEqualTo(3.0);
    assertThat(tokenBucketSixteen(to, MetricSeriesFilter.NONE, MetricAggregation.AVG))
        .isCloseTo(113.33, within(0.005));
  }

  @Test
  void anAggregationForAnUnknownMetricIdIsRejectedEvenForSum() {
    assertThatThrownBy(() -> metricSeriesService.metricSeries(
        windowStart(), windowEnd(), null, MetricSeriesFilter.NONE,
        new MetricSeriesAggregation("latency", MetricAggregation.SUM)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latency");
  }

  // A discovered metric with two streams and a known increment pattern per 150s bucket. Every
  // stream is re-emitted at an unchanged value between its real increments, exactly as the exporter
  // does each minute, so the zero-delta ghost rows are present for the queries to ignore.
  //   stream one (model=m1), cumulative: base 10, +30s 30, +60s 30 (ghost), +90s 70, +120s 70 (ghost),
  //                                      +160s 70 (ghost), +200s 100, +240s 100 (ghost)
  //   stream two (model=m2), cumulative: +460s 5, +500s 9
  private void seedAggregationMetric() {
    Map<String, Object> streamOne = Map.of("model", "m1");
    Map<String, Object> streamTwo = Map.of("model", "m2");
    save(AGGREGATION_METRIC, streamOne, 10.0, base);
    save(AGGREGATION_METRIC, streamOne, 30.0, base.plusSeconds(30));
    save(AGGREGATION_METRIC, streamOne, 30.0, base.plusSeconds(60));
    save(AGGREGATION_METRIC, streamOne, 70.0, base.plusSeconds(90));
    save(AGGREGATION_METRIC, streamOne, 70.0, base.plusSeconds(120));
    save(AGGREGATION_METRIC, streamOne, 70.0, base.plusSeconds(160));
    save(AGGREGATION_METRIC, streamOne, 100.0, base.plusSeconds(200));
    save(AGGREGATION_METRIC, streamOne, 100.0, base.plusSeconds(240));
    save(AGGREGATION_METRIC, streamTwo, 5.0, base.plusSeconds(460));
    save(AGGREGATION_METRIC, streamTwo, 9.0, base.plusSeconds(500));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  // The window end is fixed (rather than Instant.now()) so the bucket width is exactly 150s and the
  // seeded rows land in predictable buckets.
  private Instant windowEnd() {
    return windowStart().plusSeconds(3_600);
  }

  private List<MetricSeries> seriesInFixedWindow(MetricSeriesAggregation aggregation) {
    return metricSeriesService.metricSeries(
        windowStart(), windowEnd(), null, MetricSeriesFilter.NONE, aggregation);
  }

  private static MetricSeriesAggregation aggregation(MetricAggregation aggregation) {
    return new MetricSeriesAggregation(AGGREGATION_METRIC_ID, aggregation);
  }

  private double tokenBucketSixteen(Instant to, MetricSeriesFilter filter, MetricAggregation aggregation) {
    return byId(
        metricSeriesService.metricSeries(
            windowStart(), to, REPOSITORY_A, filter, new MetricSeriesAggregation("token", aggregation)),
        "token").trend().get(16);
  }

  // One session.count stream, 4 then 6, which telescopes to 6. It carries no model attribute, which
  // is the point: a model filter on tokens must leave it alone.
  private void seedSessionCounter() {
    Map<String, Object> attributes = Map.of("session.id", "s1");
    save(tuningProperties.getSessionCountMetric(), attributes, 4.0, base);
    save(tuningProperties.getSessionCountMetric(), attributes, 6.0, base.plusSeconds(60));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  // Repository A's own token streams (sonnet 100 -> 300, haiku 40) and one session.count point of 2,
  // alongside the repository-less rows the base fixture already seeded.
  private void seedRepositoryScopedRows() {
    Map<String, Object> sonnetAttributes = Map.of(
        "session.id", "s2", "model", "sonnet", "type", "input", REPOSITORY_ATTRIBUTE, REPOSITORY_A);
    Map<String, Object> haikuAttributes = Map.of(
        "session.id", "s2", "model", "haiku", "type", "input", REPOSITORY_ATTRIBUTE, REPOSITORY_A);
    save(TOKEN_METRIC, sonnetAttributes, 100.0, base.plusSeconds(10));
    save(TOKEN_METRIC, sonnetAttributes, 300.0, base.plusSeconds(70));
    save(TOKEN_METRIC, haikuAttributes, 40.0, base.plusSeconds(20));
    save(tuningProperties.getSessionCountMetric(),
        Map.of("session.id", "s2", REPOSITORY_ATTRIBUTE, REPOSITORY_A), 2.0, base.plusSeconds(5));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  // A discovered (uncurated) metric with 30 single-point streams: an id-like key (30 distinct
  // values), a key with exactly 25 distinct values, a two-value key with equal counts, a two-value
  // key with unequal counts, plus an empty-string and a JSON-null value.
  private void seedWideMetric() {
    for (int i = 0; i < 30; i++) {
      Map<String, Object> attributes = new HashMap<>();
      attributes.put("request.id", String.format("r%02d", i));
      attributes.put("bucket", "b" + (i % 25));
      attributes.put("region", i % 2 == 0 ? "eu" : "us");
      attributes.put("tier", i < 20 ? "zeta" : "alpha");
      attributes.put("note", "");
      attributes.put("flag", null);
      save("acme.wide.metric", attributes, 1.0, base.plusSeconds(i));
    }
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  private List<MetricFacet> attributesOf(String metricName, String repositoryUrl) {
    return metricSeriesService.metricAttributes(windowStart(), Instant.now(), repositoryUrl, metricName).attributes();
  }

  // A filter on the given series id from key:value strings, parsed exactly as the endpoint parses them.
  private static MetricSeriesFilter filterOn(String metricId, String... keyValuePairs) {
    return MetricSeriesFilter.of(metricId, List.of(keyValuePairs));
  }

  // A discovered metric whose attributes are typed: status is a JSON number, cached a JSON boolean.
  // One single-point stream each, so a stream that matches contributes exactly 1.0.
  //   S1: status 200, cached true,  region eu, url with colons, label with quotes and a backslash
  //   S2: status 404, cached false, region us
  //   S3: status 200, cached false, region us
  private void seedTypedAttributeMetric() {
    Map<String, Object> firstStream = new HashMap<>();
    firstStream.put("status", 200);
    firstStream.put("cached", true);
    firstStream.put("region", "eu");
    firstStream.put("url", "http://host:8080/a:b");
    firstStream.put("label", "say \"hi\" \\ back");
    Map<String, Object> secondStream = new HashMap<>();
    secondStream.put("status", 404);
    secondStream.put("cached", false);
    secondStream.put("region", "us");
    Map<String, Object> thirdStream = new HashMap<>();
    thirdStream.put("status", 200);
    thirdStream.put("cached", false);
    thirdStream.put("region", "us");
    save(TYPED_METRIC, firstStream, 1.0, base);
    save(TYPED_METRIC, secondStream, 1.0, base.plusSeconds(1));
    save(TYPED_METRIC, thirdStream, 1.0, base.plusSeconds(2));
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  private double typedMetricSum(Instant to, String... keyValuePairs) {
    return byId(
        metricSeriesService.metricSeries(windowStart(), to, null, filterOn(TYPED_METRIC_ID, keyValuePairs)),
        TYPED_METRIC_ID).trend().stream().mapToDouble(Double::doubleValue).sum();
  }

  private static List<String> facetValues(List<MetricFacet> facets, String key) {
    return facets.stream()
        .filter(facet -> key.equals(facet.key()))
        .flatMap(facet -> facet.values().stream())
        .map(facetValue -> facetValue.value() + ":" + facetValue.count())
        .toList();
  }

  private static MetricSeries byId(List<MetricSeries> series, String id) {
    return series.stream().filter(candidate -> id.equals(candidate.id())).findFirst().orElseThrow();
  }

  private MetricSeries seriesById(String id) {
    return metricSeriesService.metricSeries(windowStart(), Instant.now(), null).stream()
        .filter(series -> id.equals(series.id()))
        .findFirst()
        .orElseThrow();
  }

  private Instant windowStart() {
    return base.minus(40, ChronoUnit.MINUTES);
  }

  private void saveToken(String model, String type, double value, Instant timestamp) {
    save(TOKEN_METRIC, Map.of("session.id", "s1", "model", model, "type", type), value, timestamp);
  }

  private void saveDecision(String decision, double value, Instant timestamp) {
    save(DECISION_METRIC, Map.of("session.id", "s1", "decision", decision), value, timestamp);
  }

  private void saveWithUnit(String metricName, String unit, double value, Instant timestamp) {
    save(metricName, Map.of("session.id", "s1"), value, timestamp, unit);
  }

  private void save(String metricName, Map<String, Object> attributes, double value, Instant timestamp) {
    save(metricName, attributes, value, timestamp, null);
  }

  private void save(
      String metricName, Map<String, Object> attributes, double value, Instant timestamp, String unit) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(metricName);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(attributes);
    entity.setUnit(unit);
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }
}
