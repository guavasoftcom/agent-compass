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
import com.guavasoft.agentcompass.entity.MetricPointEntity;
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
  private static final String USER_PROMPT_EVENT_NAME = "user_prompt";
  private static final String PROMPT_ATTRIBUTE = "prompt";
  private static final String TOOL_EVENT_NAME = "tool_result";
  private static final String TOOL_DECISION_EVENT_NAME = "tool_decision";
  private static final int WINDOW_MINUTES = 60;

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  MetricService metricService;

  private final List<Long> seededMetricPointIds = new ArrayList<>();

  // Multi-line prompt seeded for session A. regexp_replace collapses the
  // embedded newline/tab whitespace run to a single space, so the collapsed text
  // is fiftyAs + " " + twoHundredFiftyBs; truncated to 200 chars that is
  // fiftyAs + " " + 149 Bs (50 + 1 + 149 = 200).
  private static final String MULTI_LINE_PROMPT = "A".repeat(50) + "\n \t \n" + "B".repeat(250);
  private static final String EXPECTED_COLLAPSED_TRUNCATED_PROMPT = "A".repeat(50) + " " + "B".repeat(149);

  // Real trace ids are 32 hex chars; the all-zero placeholder is what pre-tracing
  // Claude Code versions stamp onto user_prompt rows instead of leaving trace_id
  // NULL. Both this sentinel and the empty string must normalize to a null
  // traceId in the API response.
  private static final String REAL_TRACE_ID = "0102030405060708090a0b0c0d0e0f10";
  private static final String ALL_ZERO_TRACE_ID = "0".repeat(32);

  @BeforeEach
  void seedSessions() {
    metricPointRepository.deleteAll();
    logRecordRepository.deleteAll();
    seededMetricPointIds.clear();
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

    // Prompt context: A has a single multi-line prompt (whitespace-collapse +
    // 200-char truncation coverage); B has a bare slash command followed by a
    // real prompt (the real prompt must win as firstUserPrompt); C has no
    // user_prompt events at all (firstUserPrompt null, count 0).
    // B's prompts also cover trace-id normalization: the bare slash command
    // predates tracing (all-zero placeholder -> null in the API), the real
    // prompt carries the claude_code.interaction root span's real trace id.
    saveUserPrompt("A", MULTI_LINE_PROMPT, base, null);
    saveUserPrompt("B", "/ship", base.plusSeconds(30), ALL_ZERO_TRACE_ID);
    saveUserPrompt("B", "Add authentication support to the login flow", base.plusSeconds(31), REAL_TRACE_ID);

    // Fixtures seed rows directly (bypassing OtlpMetricService), so value_delta
    // starts NULL. Replicate the ingest-time computation here — same
    // recomputeValueDeltas call the real ingest path uses after saveAll — so the
    // session aggregations under test see the same value_delta they would in
    // production.
    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  @Test
  void defaultSortRanksSessionsByCostDescendingWithTotalCount() {
    SessionSummaryPage page = metricService.sessionsSummary(WINDOW_MINUTES, null, null, 0, 25);

    assertThat(page.totalCount()).isEqualTo(3);
    assertThat(page.items()).extracting(SessionSummary::sessionId).containsExactly("A", "C", "B");
    SessionSummary sessionA = page.items().get(0);
    assertThat(sessionA.costUsd()).isEqualTo(15.0);
    assertThat(sessionA.activeTimeSeconds()).isEqualTo(1500.0);
  }

  @Test
  void ascendingActiveTimeSortPutsTheIdleOnlySessionFirst() {
    SessionSummaryPage page = metricService.sessionsSummary(WINDOW_MINUTES, "activeTimeSeconds", "asc", 0, 25);

    assertThat(page.items()).extracting(SessionSummary::sessionId).containsExactly("C", "B", "A");
  }

  @Test
  void paginationReturnsRequestedSliceWhileTotalCountStaysWholeWindow() {
    SessionSummaryPage firstPage = metricService.sessionsSummary(WINDOW_MINUTES, "costUsd", "desc", 0, 2);
    SessionSummaryPage secondPage = metricService.sessionsSummary(WINDOW_MINUTES, "costUsd", "desc", 1, 2);

    assertThat(firstPage.totalCount()).isEqualTo(3);
    assertThat(firstPage.items()).extracting(SessionSummary::sessionId).containsExactly("A", "C");
    assertThat(secondPage.totalCount()).isEqualTo(3);
    assertThat(secondPage.items()).extracting(SessionSummary::sessionId).containsExactly("B");
  }

  @Test
  void rowsCarryTokensTerminalAndStartTypeAndSortByTokens() {
    SessionSummaryPage page = metricService.sessionsSummary(WINDOW_MINUTES, "tokens", "desc", 0, 25);

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
    SessionKpis kpis = metricService.sessionsKpis(WINDOW_MINUTES);

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

  @Test
  void promptContextEnrichesFirstPromptAndCountPerSession() {
    SessionSummaryPage page = metricService.sessionsSummary(WINDOW_MINUTES, null, null, 0, 25);

    SessionSummary sessionA = page.items().stream()
        .filter(item -> "A".equals(item.sessionId())).findFirst().orElseThrow();
    assertThat(sessionA.userPromptCount()).isEqualTo(1L);
    assertThat(sessionA.firstUserPrompt()).isEqualTo(EXPECTED_COLLAPSED_TRUNCATED_PROMPT);
    assertThat(sessionA.firstUserPrompt()).hasSize(200);

    SessionSummary sessionB = page.items().stream()
        .filter(item -> "B".equals(item.sessionId())).findFirst().orElseThrow();
    assertThat(sessionB.userPromptCount()).isEqualTo(2L);
    assertThat(sessionB.firstUserPrompt()).isEqualTo("Add authentication support to the login flow");

    SessionSummary sessionC = page.items().stream()
        .filter(item -> "C".equals(item.sessionId())).findFirst().orElseThrow();
    assertThat(sessionC.userPromptCount()).isEqualTo(0L);
    assertThat(sessionC.firstUserPrompt()).isNull();
  }

  @Test
  void promptInfoFallsBackToTheLiteralFirstPromptWhenEveryPromptIsASlashCommand() {
    Instant base = Instant.now().minus(5, ChronoUnit.MINUTES);
    saveUserPrompt("D", "/compact", base, null);
    saveUserPrompt("D", "/clear", base.plusSeconds(30), null);

    List<Object[]> rows = logRecordRepository.aggregateSessionCounts(
        List.of("D"), TOOL_EVENT_NAME, TOOL_DECISION_EVENT_NAME, USER_PROMPT_EVENT_NAME, PROMPT_ATTRIBUTE);

    assertThat(rows).hasSize(1);
    Object[] row = rows.get(0);
    assertThat(row[0]).isEqualTo("D");
    assertThat(((Number) row[3]).longValue()).isEqualTo(2L);
    assertThat(row[4]).isEqualTo("/compact");
  }

  @Test
  void promptsForSessionReturnsFullTimelineOrderedAscendingWithNormalizedTraceIds() {
    List<Object[]> rows = logRecordRepository.findPromptsForSession(
        "B", USER_PROMPT_EVENT_NAME, PROMPT_ATTRIBUTE, 500);

    assertThat(rows).hasSize(2);
    // The all-zero placeholder trace id (pre-tracing sessions) normalizes to
    // SQL NULL.
    assertThat(rows.get(0)[1]).isEqualTo("/ship");
    assertThat(rows.get(0)[2]).isNull();
    // The real trace id passes through unchanged.
    assertThat(rows.get(1)[1]).isEqualTo("Add authentication support to the login flow");
    assertThat(rows.get(1)[2]).isEqualTo(REAL_TRACE_ID);
  }

  @Test
  void promptsForSessionMapsEmptyStringTraceIdToNull() {
    Instant timestamp = Instant.now().minus(5, ChronoUnit.MINUTES);
    saveUserPrompt("E", "Investigate the intermittent CI failure", timestamp, "");

    List<Object[]> rows = logRecordRepository.findPromptsForSession(
        "E", USER_PROMPT_EVENT_NAME, PROMPT_ATTRIBUTE, 500);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[2]).isNull();
  }

  private void saveUserPrompt(String sessionId, String promptText, Instant timestamp, String traceId) {
    LogRecordEntity entity = new LogRecordEntity();
    entity.setTimestamp(timestamp);
    entity.setObservedTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setTraceId(traceId);
    entity.setAttributes(Map.of(
        "event.name", USER_PROMPT_EVENT_NAME,
        "session.id", sessionId,
        PROMPT_ATTRIBUTE, promptText));
    logRecordRepository.save(entity);
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
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }

  private void saveTokenUsage(String sessionId, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(TOKEN_METRIC);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(Map.of("session.id", sessionId, "type", "input"));
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
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
    MetricPointEntity savedEntity = metricPointRepository.save(entity);
    seededMetricPointIds.add(savedEntity.getId());
  }
}
