package com.guavasoft.agentcompass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.CostModelShare;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the B4 cost-summary consolidation (MetricPointRepository#aggregateCostCurrentAndPriorTotals
 * and #aggregateCostBreakdown) against a real Postgres. Confirms the merged FILTER
 * query and the merged GROUPING SETS query reconcile with each other and with the
 * end-to-end {@link MetricService#aggregateCostSummary} result, so collapsing five
 * scans into two cannot have silently changed any total — the
 * {@code MetricsControllerTest} mocks the service and can't catch a SQL-level
 * regression here.
 */
@SpringBootTest
@Testcontainers
class CostSummaryQueryIntegrationTest {

  private static final String COST_METRIC = "claude_code.cost.usage";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  MetricService metricService;

  private final List<Long> seededMetricPointIds = new ArrayList<>();

  private Instant from;
  private Instant to;
  private Instant priorFrom;

  @BeforeEach
  void seedCostRows() {
    metricPointRepository.deleteAll();
    seededMetricPointIds.clear();

    to = Instant.now();
    from = to.minus(60, ChronoUnit.MINUTES);
    priorFrom = from.minus(60, ChronoUnit.MINUTES);

    // Current window: two concurrent streams.
    //  opus/session-A:   10 -> 15 (increment)              => 10 + 5  = 15
    //  sonnet/session-B: 20 -> 5  (reset, counted in full)  => 20 + 5 = 25
    // Current total = 40.
    saveCost("A", "opus", 10.0, from.plusSeconds(300));
    saveCost("A", "opus", 15.0, from.plusSeconds(2_100));
    saveCost("B", "sonnet", 20.0, from.plusSeconds(600));
    saveCost("B", "sonnet", 5.0, from.plusSeconds(3_000));

    // Prior window (same length, immediately before "from"): two fresh streams.
    //  opus/session-C:  8  => 8
    //  haiku/session-D: 2  => 2
    // Prior total = 10.
    saveCost("C", "opus", 8.0, priorFrom.plusSeconds(1_200));
    saveCost("D", "haiku", 2.0, priorFrom.plusSeconds(2_400));

    // Fixtures seed rows directly (bypassing OtlpMetricService), so value_delta
    // starts NULL. Replicate the ingest-time computation here — same
    // recomputeValueDeltas call the real ingest path uses after saveAll — so the
    // aggregations under test see the same value_delta they would in production.
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  @Test
  void currentAndPriorTotalsMatchTheOldTwoQuerySemantics() {
    Object[] totalsRow = metricPointRepository.aggregateCostCurrentAndPriorTotals(
        COST_METRIC, from, to, priorFrom).get(0);

    assertThat(((Number) totalsRow[0]).doubleValue()).isEqualTo(40.0);
    assertThat(((Number) totalsRow[1]).doubleValue()).isEqualTo(10.0);
  }

  @Test
  void groupingSetsGrandTotalEqualsSumOfPerModelRowsAndTheFilterQueryCurrentTotal() {
    long bucketSeconds = 257L;
    List<Object[]> breakdownRows = metricPointRepository.aggregateCostBreakdown(
        COST_METRIC, from, to, bucketSeconds);

    double grandTotal = breakdownRows.stream()
        .filter(row -> "total".equals(row[0]))
        .mapToDouble(row -> ((Number) row[3]).doubleValue())
        .sum();
    double modelTotal = breakdownRows.stream()
        .filter(row -> "model".equals(row[0]))
        .mapToDouble(row -> ((Number) row[3]).doubleValue())
        .sum();
    double bucketTotal = breakdownRows.stream()
        .filter(row -> "bucket".equals(row[0]))
        .mapToDouble(row -> ((Number) row[3]).doubleValue())
        .sum();

    Object[] totalsRow = metricPointRepository.aggregateCostCurrentAndPriorTotals(
        COST_METRIC, from, to, priorFrom).get(0);
    double currentTotalFromFilterQuery = ((Number) totalsRow[0]).doubleValue();

    assertThat(grandTotal).isEqualTo(40.0);
    assertThat(modelTotal).isEqualTo(40.0);
    assertThat(bucketTotal).isEqualTo(40.0);
    assertThat(grandTotal).isEqualTo(currentTotalFromFilterQuery);

    // Prior-window rows must not leak into the current-window breakdown: only
    // opus and sonnet (current window) appear, never haiku (prior window only).
    List<String> models = breakdownRows.stream()
        .filter(row -> "model".equals(row[0]))
        .map(row -> (String) row[2])
        .toList();
    assertThat(models).containsExactlyInAnyOrder("opus", "sonnet");
  }

  @Test
  void aggregateCostSummaryReconcilesSpendDeltaAndByModelBreakdown() {
    CostSummary summary = metricService.aggregateCostSummary(from, to);

    assertThat(summary.spend24h()).isEqualTo("$40.00");
    // (40 - 10) / 10 * 100 = +300.0%
    assertThat(summary.deltaPct()).isEqualTo("+300.0%");

    assertThat(summary.trend().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(40.0);

    assertThat(summary.byModel()).extracting(CostModelShare::model).containsExactly("sonnet", "opus");
    assertThat(summary.byModel()).extracting(CostModelShare::usd).containsExactly("$25.00", "$15.00");
    assertThat(summary.byModel().get(0).share()).isEqualTo(63);
    assertThat(summary.byModel().get(1).share()).isEqualTo(38);
    assertThat(summary.byModel().get(0).colorIndex()).isZero();
    assertThat(summary.byModel().get(1).colorIndex()).isEqualTo(1);
  }

  private void saveCost(String sessionId, String model, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(COST_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(Map.of(
        "session.id", sessionId,
        "model", model,
        "query_source", "main"));
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }
}
