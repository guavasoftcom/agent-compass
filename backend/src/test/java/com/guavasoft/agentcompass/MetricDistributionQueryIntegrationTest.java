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

import com.guavasoft.agentcompass.entity.LogRecordEntity;
import com.guavasoft.agentcompass.entity.SpanEntity;
import com.guavasoft.agentcompass.model.DistributionPoint;
import com.guavasoft.agentcompass.model.MetricDistribution;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the two native queries and the exemplar selection behind
 * {@code GET /api/metrics/distribution} against a real Postgres: {@code MetricsControllerTest} mocks
 * the service, so only this class can catch a SQL mistake in the value expressions, the newest-N cap,
 * the trace-id normalisation or the repository scoping.
 *
 * <p>The base fixture is six {@code api_request} logs (plus rows that must be ignored), with values
 * chosen so every expected figure can be derived by hand:
 *
 * <pre>
 *   req  offset      tokens     cost   trace  repository  notes
 *   A    +10m        100,000    $0.10  T1     -
 *   B    +50m        200,000    -      T2     -           no cost attribute: counts for tokens only
 *   C    +5h10m      300,000    $0.30  T3     -
 *   D    +5h40m      400,000    $0.40  zeros  -           all-zero trace id == no trace id
 *   E    end - 1s  1,000,000    $3.00  T5     REPO_A      repository in resource_attributes
 *   F    end         500,000    $0.50  T6     REPO_B      logged at exactly the window end (inclusive)
 * </pre>
 *
 * Ignored: an api_request just before the window and a {@code tool_result} inside it that carries the
 * same token attributes.
 */
@SpringBootTest
@Testcontainers
class MetricDistributionQueryIntegrationTest {

  private static final String TOKEN_METRIC = "claude_code.token.usage";
  private static final String COST_METRIC = "claude_code.cost.usage";

  private static final String REPOSITORY_URL_ATTRIBUTE = "vcs.repository.url.full";
  private static final String REPOSITORY_A = "https://github.com/guavasoftcom/agent-compass";
  private static final String REPOSITORY_B = "https://github.com/guavasoftcom/spring-batch-dashboard";
  private static final String UNKNOWN_REPOSITORY = "https://github.com/guavasoftcom/does-not-exist";

  private static final String TRACE_1 = "11111111111111111111111111111111";
  private static final String TRACE_2 = "22222222222222222222222222222222";
  private static final String TRACE_3 = "33333333333333333333333333333333";
  private static final String TRACE_5 = "55555555555555555555555555555555";
  private static final String TRACE_6 = "66666666666666666666666666666666";
  private static final String ALL_ZERO_TRACE = "00000000000000000000000000000000";

  private static final String REQUEST_ID_C = "req_C";
  private static final String REQUEST_ID_E = "req_E";
  private static final String LLM_SPAN_ID_C = "00000000000000c3";

  private static final int REQUEST_CAP = 2_000;
  private static final int TOKENS_PER_STEP = 20;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestImage.NAME);

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  SpanRepository spanRepository;

  @Autowired
  MetricService metricService;

  private Instant windowStart;
  private Instant windowEnd;

  @BeforeEach
  void seed() {
    logRecordRepository.deleteAll();
    spanRepository.deleteAll();
    windowStart = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    windowEnd = windowStart.plus(24, ChronoUnit.HOURS);

    saveApiRequest(windowStart.plus(10, ChronoUnit.MINUTES), 100_000, 0.10, TRACE_1, null, null, false);
    saveApiRequest(windowStart.plus(50, ChronoUnit.MINUTES), 200_000, null, TRACE_2, null, null, false);
    logRecordRepository.save(withRequestId(buildApiRequest(
        windowStart.plus(5, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES),
        300_000, 0.30, TRACE_3, null, null, false), REQUEST_ID_C));
    saveApiRequest(windowStart.plus(5, ChronoUnit.HOURS).plus(40, ChronoUnit.MINUTES),
        400_000, 0.40, ALL_ZERO_TRACE, null, null, false);
    // Scoped to a repository (E via resource attributes, F via record attributes).
    logRecordRepository.save(withRequestId(buildApiRequest(
        windowEnd.minusSeconds(1), 1_000_000, 3.00, TRACE_5, REPOSITORY_A, null, false), REQUEST_ID_E));
    saveApiRequest(windowEnd, 500_000, 0.50, TRACE_6, null, REPOSITORY_B, false);

    // C's llm_request span is ingested; E's is not, so E's exemplar has a trace id but no span id.
    spanRepository.save(llmRequestSpan(TRACE_3, LLM_SPAN_ID_C, REQUEST_ID_C, windowStart.plus(5, ChronoUnit.HOURS)));

    // Rows that must never be counted.
    saveApiRequest(windowStart.minusSeconds(1), 9_000_000, 9.00, "99999999999999999999999999999999",
        null, null, false);
    saveNonApiRequestEvent(windowStart.plus(3, ChronoUnit.HOURS), 8_000_000);
  }

  @Test
  void tokenPointsAreOneRowPerRequestSummingTheFourTokenKindsAscendingByTimestamp() {
    MetricDistribution distribution = distribution(TOKEN_METRIC, null);

    // Ascending by timestamp; the ignored rows (before the window, wrong event) do not appear, and
    // F, logged exactly at the window end, is included.
    assertThat(distribution.points()).extracting(DistributionPoint::ts).containsExactly(
        windowStart.plus(10, ChronoUnit.MINUTES),
        windowStart.plus(50, ChronoUnit.MINUTES),
        windowStart.plus(5, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES),
        windowStart.plus(5, ChronoUnit.HOURS).plus(40, ChronoUnit.MINUTES),
        windowEnd.minusSeconds(1),
        windowEnd);
    // input + output + cache creation + cache read: 10% + 5% + 15% + 70% of the request's total.
    assertThat(distribution.points()).extracting(DistributionPoint::value)
        .containsExactly(100_000.0, 200_000.0, 300_000.0, 400_000.0, 1_000_000.0, 500_000.0);
  }

  @Test
  void costPointsUseTheCostAttributeAndSkipRequestsWithoutIt() {
    MetricDistribution distribution = distribution(COST_METRIC, null);

    // B carries no cost attribute, so five requests -- not six -- qualify.
    assertThat(distribution.points()).extracting(DistributionPoint::ts).containsExactly(
        windowStart.plus(10, ChronoUnit.MINUTES),
        windowStart.plus(5, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES),
        windowStart.plus(5, ChronoUnit.HOURS).plus(40, ChronoUnit.MINUTES),
        windowEnd.minusSeconds(1),
        windowEnd);
    assertThat(distribution.points()).extracting(DistributionPoint::value)
        .containsExactly(0.10, 0.30, 0.40, 3.00, 0.50);
  }

  @Test
  void onlyRequestsWithARealTraceIdCanBeExemplarsAndTheAllZeroPlaceholderNeverIs() {
    MetricDistribution distribution = distribution(TOKEN_METRIC, null);

    // Sorted values 100K..1M: p50 = 350K, p95 = 875K, p99 = 975K. Traced candidates are A, B, C, E, F
    // (D has only the all-zero placeholder). The maximum is E; p50 is nearest C (300K, |50K|) with D
    // (400K, equally near) ineligible; p95 and p99 are both nearest E (1M) or F (500K): E wins both.
    assertThat(traceIdsByTimestamp(distribution)).containsOnly(
        Map.entry(windowStart.plus(5, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), TRACE_3),
        Map.entry(windowEnd.minusSeconds(1), TRACE_5));
    assertThat(distribution.points()).extracting(DistributionPoint::traceId)
        .doesNotContain(ALL_ZERO_TRACE, "");
    // Every other point stays null, including the traced-but-unremarkable A, B and F.
    assertThat(distribution.points().stream().filter(point -> point.traceId() == null).count()).isEqualTo(4L);
  }

  @Test
  void anExemplarCarriesItsLlmRequestSpanIdWhenThatSpanIsIngestedAndNullWhenItIsNot() {
    MetricDistribution distribution = distribution(TOKEN_METRIC, null);

    // The exemplars are C and E (see the test above). C's request_id matches an ingested llm_request
    // span in its own trace; E's matches nothing. Every non-exemplar point has no span id either.
    Map<Instant, String> spanIdsByTimestamp = new HashMap<>();
    for (DistributionPoint point : distribution.points()) {
      if (point.spanId() != null) {
        spanIdsByTimestamp.put(point.ts(), point.spanId());
      }
    }
    assertThat(spanIdsByTimestamp).containsOnly(
        Map.entry(windowStart.plus(5, ChronoUnit.HOURS).plus(10, ChronoUnit.MINUTES), LLM_SPAN_ID_C));
    assertThat(distribution.points()).filteredOn(point -> point.traceId() == null)
        .extracting(DistributionPoint::spanId).containsOnlyNulls();
  }

  @Test
  void exemplarsAreTheMaximumTheNearestToEachPercentileAndAFailedRequestAndNothingElse() {
    logRecordRepository.deleteAll();
    Instant seriesStart = windowEnd.plus(10, ChronoUnit.DAYS);
    Instant seriesEnd = seriesStart.plus(24, ChronoUnit.HOURS);
    // Twenty requests worth 1K, 2K ... 20K tokens, ten minutes apart, index 0 first.
    //   index 19 (20K)  traced  -> the maximum, and nearest p99 (19,810)
    //   index 18 (19K)  traced  -> nearest p95 (19,050)
    //   index 17,16,14,3 traced -> decoys: real trace ids that must still come back null
    //   index 10 (11K)  all-zero trace id -> as near p50 (10,500) as index 9 but ineligible
    //   index 9  (10K)  traced  -> nearest p50
    //   index 5  (6K)   FAILED, untraced   -> ineligible despite outranking the failed traced one
    //   index 2  (3K)   FAILED, traced     -> the failed exemplar
    List<Integer> tracedIndexes = List.of(2, 3, 9, 14, 16, 17, 18, 19);
    List<LogRecordEntity> requests = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      String traceId = tracedIndexes.contains(index) ? traceIdFor(index) : index == 10 ? ALL_ZERO_TRACE : null;
      boolean failed = index == 2 || index == 5;
      requests.add(buildApiRequest(
          seriesStart.plus(index * 10L, ChronoUnit.MINUTES), 1_000 * (index + 1), null, traceId, null, null, failed));
    }
    logRecordRepository.saveAll(requests);

    List<DistributionPoint> points = metricService
        .aggregateMetricDistribution(seriesStart, seriesEnd, null, TOKEN_METRIC).points();

    assertThat(points).hasSize(20);
    assertThat(points).extracting(DistributionPoint::value)
        .containsExactlyElementsOf(IntStream.rangeClosed(1, 20).mapToObj(step -> 1_000.0 * step).toList());
    List<Integer> exemplarIndexes = new ArrayList<>();
    for (int index = 0; index < points.size(); index++) {
      if (points.get(index).traceId() != null) {
        exemplarIndexes.add(index);
        assertThat(points.get(index).traceId()).isEqualTo(traceIdFor(index));
      }
    }
    assertThat(exemplarIndexes).containsExactly(2, 9, 18, 19);
    assertThat(exemplarIndexes.size()).isLessThanOrEqualTo(6);
  }

  @Test
  void aWindowBiggerThanTheCapReturnsTheNewestRequestsOnlyStillAscending() {
    logRecordRepository.deleteAll();
    Instant capStart = windowEnd.plus(10, ChronoUnit.DAYS);
    Instant capEnd = capStart.plus(24, ChronoUnit.HOURS);
    int requestCount = REQUEST_CAP + 50;
    List<LogRecordEntity> requests = new ArrayList<>(requestCount);
    for (int index = 0; index < requestCount; index++) {
      // One request a second, value = 20 * (index + 1) tokens, so the value identifies the request.
      requests.add(buildApiRequest(
          capStart.plusSeconds(index), TOKENS_PER_STEP * (index + 1), null, null, null, null, false));
    }
    logRecordRepository.saveAll(requests);

    List<DistributionPoint> points = metricService
        .aggregateMetricDistribution(capStart, capEnd, null, TOKEN_METRIC).points();

    // The oldest 50 are dropped; the newest 2,000 come back oldest first.
    assertThat(points).hasSize(REQUEST_CAP);
    assertThat(points.get(0).ts()).isEqualTo(capStart.plusSeconds(50));
    assertThat(points.get(0).value()).isEqualTo(TOKENS_PER_STEP * 51.0);
    assertThat(points.get(REQUEST_CAP - 1).ts()).isEqualTo(capStart.plusSeconds(requestCount - 1));
    assertThat(points.get(REQUEST_CAP - 1).value()).isEqualTo(TOKENS_PER_STEP * (double) requestCount);
    for (int index = 1; index < points.size(); index++) {
      assertThat(points.get(index).ts()).isAfter(points.get(index - 1).ts());
    }
    // No request in this window has a trace id, so there is nothing to click through to.
    assertThat(points).extracting(DistributionPoint::traceId).containsOnlyNulls();
  }

  @Test
  void repositoryFilterExcludesEveryOtherRepositoryAndUnattributedRequest() {
    // Only E belongs to repository A, and it is a lone traced request, so it is every exemplar role.
    MetricDistribution repositoryA = distribution(TOKEN_METRIC, REPOSITORY_A);
    assertThat(repositoryA.points()).hasSize(1);
    assertThat(repositoryA.points().get(0).value()).isEqualTo(1_000_000.0);
    assertThat(repositoryA.points().get(0).traceId()).isEqualTo(TRACE_5);

    // Only F belongs to repository B, and it was logged exactly at the window end.
    MetricDistribution repositoryB = distribution(TOKEN_METRIC, REPOSITORY_B);
    assertThat(repositoryB.points()).extracting(DistributionPoint::ts).containsExactly(windowEnd);
    assertThat(repositoryB.points().get(0).traceId()).isEqualTo(TRACE_6);

    // The cost variant filters the same way.
    MetricDistribution costForRepositoryA = distribution(COST_METRIC, REPOSITORY_A);
    assertThat(costForRepositoryA.points()).extracting(DistributionPoint::value).containsExactly(3.00);
  }

  @Test
  void anEmptyWindowOrUnknownRepositoryYieldsAnEmptyPointListForBothMetrics() {
    Instant emptyStart = windowEnd.plus(10, ChronoUnit.DAYS);
    Instant emptyEnd = emptyStart.plus(24, ChronoUnit.HOURS);

    for (String metric : List.of(TOKEN_METRIC, COST_METRIC)) {
      assertThat(metricService.aggregateMetricDistribution(emptyStart, emptyEnd, null, metric).points()).isEmpty();
      assertThat(distribution(metric, UNKNOWN_REPOSITORY).points()).isEmpty();
    }
  }

  @Test
  void aMetricThatIsNeitherTokenNorCostIsRejectedAsAnIllegalArgument() {
    for (String unsupportedMetric : List.of("claude_code.session.count", "token", "latency", "")) {
      assertThatThrownBy(() -> metricService.aggregateMetricDistribution(windowStart, windowEnd, null, unsupportedMetric))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(TOKEN_METRIC)
          .hasMessageContaining(COST_METRIC);
    }
  }

  private MetricDistribution distribution(String metric, String repositoryUrl) {
    return metricService.aggregateMetricDistribution(windowStart, windowEnd, repositoryUrl, metric);
  }

  // Timestamp -> trace id for every point that carries one, so an assertion names the exemplars.
  private static Map<Instant, String> traceIdsByTimestamp(MetricDistribution distribution) {
    Map<Instant, String> traceIdsByTimestamp = new HashMap<>();
    for (DistributionPoint point : distribution.points()) {
      if (point.traceId() != null) {
        traceIdsByTimestamp.put(point.ts(), point.traceId());
      }
    }
    return traceIdsByTimestamp;
  }

  private static LogRecordEntity withRequestId(LogRecordEntity apiRequest, String requestId) {
    apiRequest.getAttributes().put("request_id", requestId);
    return apiRequest;
  }

  // trace_id is varchar(32) and span_id varchar(16), hence the fixed-width hex constants.
  private static SpanEntity llmRequestSpan(String traceId, String spanId, String requestId, Instant startTimestamp) {
    SpanEntity span = new SpanEntity();
    span.setTraceId(traceId);
    span.setSpanId(spanId);
    span.setName("claude_code.llm_request");
    span.setStartTimestamp(startTimestamp);
    span.setEndTimestamp(startTimestamp.plusSeconds(2));
    span.setReceivedAt(startTimestamp);
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("request_id", requestId);
    span.setAttributes(attributes);
    return span;
  }

  private static String traceIdFor(int index) {
    return String.format("%032x", index + 1);
  }

  private void saveApiRequest(
      Instant timestamp, int totalTokens, Double costUsd, String traceId,
      String resourceRepositoryUrl, String attributeRepositoryUrl, boolean failed) {
    logRecordRepository.save(buildApiRequest(
        timestamp, totalTokens, costUsd, traceId, resourceRepositoryUrl, attributeRepositoryUrl, failed));
  }

  /**
   * One api_request log shaped the way Claude Code emits it. {@code totalTokens} is split across
   * the four token attributes (10% input, 5% output, 15% cache creation, 70% cache read) so the
   * query's four-way sum is what produces the request value. A null cost omits the attribute; an
   * attribute repository lands on the record, a resource repository on the resource attributes; a
   * failed request carries {@code success=false}, which the stored derived severity reads as ERROR.
   */
  private LogRecordEntity buildApiRequest(
      Instant timestamp, int totalTokens, Double costUsd, String traceId,
      String resourceRepositoryUrl, String attributeRepositoryUrl, boolean failed) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", "api_request");
    attributes.put("model", "sonnet");
    attributes.put("input_tokens", totalTokens / 10);
    attributes.put("output_tokens", totalTokens / 20);
    attributes.put("cache_creation_tokens", totalTokens * 15 / 100);
    attributes.put("cache_read_tokens", totalTokens * 70 / 100);
    if (costUsd != null) {
      attributes.put("cost_usd", costUsd);
    }
    if (attributeRepositoryUrl != null) {
      attributes.put(REPOSITORY_URL_ATTRIBUTE, attributeRepositoryUrl);
    }
    if (failed) {
      attributes.put("success", "false");
    }
    Map<String, Object> resourceAttributes = new HashMap<>();
    if (resourceRepositoryUrl != null) {
      resourceAttributes.put(REPOSITORY_URL_ATTRIBUTE, resourceRepositoryUrl);
    }
    return buildLogRecord(timestamp, traceId, attributes, resourceAttributes);
  }

  // A tool_result carrying token attributes: the event filter must keep it out of the point list.
  private void saveNonApiRequestEvent(Instant timestamp, int totalTokens) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", "tool_result");
    attributes.put("input_tokens", totalTokens);
    attributes.put("cost_usd", 8.00);
    logRecordRepository.save(buildLogRecord(timestamp, TRACE_1, attributes, new HashMap<>()));
  }

  private LogRecordEntity buildLogRecord(
      Instant timestamp, String traceId, Map<String, Object> attributes, Map<String, Object> resourceAttributes) {
    LogRecordEntity entity = new LogRecordEntity();
    entity.setTimestamp(timestamp);
    entity.setObservedTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setScopeName("claude_code.tools");
    entity.setBody("api request");
    entity.setTraceId(traceId);
    entity.setAttributes(attributes);
    entity.setResourceAttributes(resourceAttributes);
    return entity;
  }
}
