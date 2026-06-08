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
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricQueryService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the native session aggregation against a real Postgres so the
 * dynamic CASE-based
 * ORDER BY, COUNT(*) OVER() total, and percentile_cont KPI query are validated
 * end-to-end —
 * the {@link com.guavasoft.agentcompass.controller.DashboardControllerTest}
 * mocks
 * the service and
 * cannot catch SQL-level mistakes.
 */
@SpringBootTest
@Testcontainers
class SessionsQueryIntegrationTest {

  private static final String COST_METRIC = "claude_code.cost.usage";
  private static final String ACTIVE_METRIC = "claude_code.active_time.total";
  private static final String SESSION_COUNT_METRIC = "claude_code.session.count";
  private static final String TOKEN_METRIC = "claude_code.token.usage";
  private static final int WINDOW_MINUTES = 60;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  MetricQueryService metricQueryService;

  @BeforeEach
  void seedSessions() {
    metricPointRepository.deleteAll();
    Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);

    // Session A: multi-stream cost (12 + 3 = 15) and active time 1500 (MAX of a
    // growing stream).
    saveCost("A", "opus", "main", 5.0, base);
    saveCost("A", "opus", "main", 12.0, base.plusSeconds(60));
    saveCost("A", "haiku", "main", 3.0, base.plusSeconds(90));
    saveActive("A", "opus", "main", 600.0, base);
    saveActive("A", "opus", "main", 1500.0, base.plusSeconds(60));

    // Session B: cost 1.5, active 300.
    saveCost("B", "opus", "main", 1.5, base.plusSeconds(30));
    saveActive("B", "opus", "main", 300.0, base.plusSeconds(30));

    // Session C: cost-only (8.0), no active-time emissions -> active 0, $/min
    // undefined.
    saveCost("C", "opus", "main", 8.0, base.plusSeconds(45));

    // session.count start_type split (re-emitted, so two rows for A still count A
    // once): A & C fresh, B resumed -> fresh 2, resume 1. terminal.type carries
    // through to the row badge (anything != "interactive" -> non-interactive).
    saveSessionStart("A", "fresh", "non-interactive", base);
    saveSessionStart("A", "fresh", "non-interactive", base.plusSeconds(60));
    saveSessionStart("C", "fresh", "interactive", base.plusSeconds(45));
    saveSessionStart("B", "resume", "non-interactive", base.plusSeconds(30));

    // Token usage for A only (cumulative stream 400000 -> 1000000) -> reset-aware
    // total 1,000,000; B and C have no token rows -> 0.
    saveTokenUsage("A", 400_000.0, base);
    saveTokenUsage("A", 1_000_000.0, base.plusSeconds(60));
  }

  @Test
  void defaultSortRanksSessionsByCostDescendingWithTotalCount() {
    SessionSummaryPage page = metricQueryService.sessionsSummary(WINDOW_MINUTES, null, null, 0, 25);

    assertThat(page.totalCount()).isEqualTo(3);
    assertThat(page.items()).extracting(SessionSummary::sessionId).containsExactly("A", "C", "B");
    SessionSummary sessionA = page.items().get(0);
    assertThat(sessionA.costUsd()).isEqualTo(15.0);
    assertThat(sessionA.activeTimeSeconds()).isEqualTo(1500.0);
  }

  @Test
  void ascendingActiveTimeSortPutsTheIdleOnlySessionFirst() {
    SessionSummaryPage page = metricQueryService.sessionsSummary(WINDOW_MINUTES, "activeTimeSeconds", "asc", 0, 25);

    assertThat(page.items()).extracting(SessionSummary::sessionId).containsExactly("C", "B", "A");
  }

  @Test
  void paginationReturnsRequestedSliceWhileTotalCountStaysWholeWindow() {
    SessionSummaryPage firstPage = metricQueryService.sessionsSummary(WINDOW_MINUTES, "costUsd", "desc", 0, 2);
    SessionSummaryPage secondPage = metricQueryService.sessionsSummary(WINDOW_MINUTES, "costUsd", "desc", 1, 2);

    assertThat(firstPage.totalCount()).isEqualTo(3);
    assertThat(firstPage.items()).extracting(SessionSummary::sessionId).containsExactly("A", "C");
    assertThat(secondPage.totalCount()).isEqualTo(3);
    assertThat(secondPage.items()).extracting(SessionSummary::sessionId).containsExactly("B");
  }

  @Test
  void rowsCarryTokensTerminalAndStartTypeAndSortByTokens() {
    SessionSummaryPage page = metricQueryService.sessionsSummary(WINDOW_MINUTES, "tokens", "desc", 0, 25);

    // A is the only session with token rows, so it sorts first on tokens.
    SessionSummary sessionA = page.items().get(0);
    assertThat(sessionA.sessionId()).isEqualTo("A");
    assertThat(sessionA.tokens()).isEqualTo(1_000_000L);
    assertThat(sessionA.terminalType()).isEqualTo("non-interactive");
    assertThat(sessionA.startType()).isEqualTo("fresh");

    SessionSummary sessionC = page.items().stream()
        .filter(item -> "C".equals(item.sessionId())).findFirst().orElseThrow();
    assertThat(sessionC.tokens()).isEqualTo(0L);
    assertThat(sessionC.terminalType()).isEqualTo("interactive");

    SessionSummary sessionB = page.items().stream()
        .filter(item -> "B".equals(item.sessionId())).findFirst().orElseThrow();
    assertThat(sessionB.startType()).isEqualTo("resume");
  }

  @Test
  void kpisComputePercentilesOverTheWholeWindow() {
    SessionKpis kpis = metricQueryService.sessionsKpis(WINDOW_MINUTES);

    assertThat(kpis.totalSessions()).isEqualTo(3);
    // Costs sorted: [1.5, 8.0, 15.0] -> P50 = 8.0, P95 interpolates to 14.3.
    assertThat(kpis.medianCostUsd()).isEqualTo(8.0);
    assertThat(kpis.p95CostUsd()).isCloseTo(14.3, org.assertj.core.data.Offset.offset(1e-9));
    // $/active-min only over sessions with active time: A=0.6, B=0.3 -> median
    // 0.45. C excluded.
    assertThat(kpis.medianCostPerActiveMinuteUsd())
        .isCloseTo(0.45, org.assertj.core.data.Offset.offset(1e-9));
    // New-session sparkline: each session counted in the bucket it first appeared,
    // so the buckets sum to totalSessions (A, B, C -> 3).
    assertThat(kpis.sessionsTrend()).isNotEmpty();
    assertThat(kpis.sessionsTrend().stream().mapToLong(Long::longValue).sum())
        .isEqualTo(kpis.totalSessions());
  }

  private void saveCost(String sessionId, String model, String querySource, double value, Instant timestamp) {
    save(COST_METRIC, sessionId, model, querySource, value, timestamp);
  }

  private void saveSessionStart(String sessionId, String startType, String terminalType, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(SESSION_COUNT_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(1.0);
    entity.setValueKind("double");
    entity.setAttributes(Map.of(
        "session.id", sessionId, "start_type", startType, "terminal.type", terminalType));
    metricPointRepository.save(entity);
  }

  private void saveTokenUsage(String sessionId, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(TOKEN_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(Map.of("session.id", sessionId, "type", "input"));
    metricPointRepository.save(entity);
  }

  private void saveActive(String sessionId, String model, String querySource, double value, Instant timestamp) {
    save(ACTIVE_METRIC, sessionId, model, querySource, value, timestamp);
  }

  private void save(
      String metricName, String sessionId, String model, String querySource, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(metricName);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(Map.of(
        "session.id", sessionId,
        "model", model,
        "query_source", querySource));
    metricPointRepository.save(entity);
  }
}
