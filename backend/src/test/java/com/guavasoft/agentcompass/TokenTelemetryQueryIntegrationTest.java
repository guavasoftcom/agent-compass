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
import com.guavasoft.agentcompass.model.SessionApiRequest;
import com.guavasoft.agentcompass.model.SessionCacheEfficiency;
import com.guavasoft.agentcompass.model.SessionPrompt;
import com.guavasoft.agentcompass.model.SessionTokenBreakdown;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.MetricPointRepository;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Covers the two Tokens-page aggregations added for the token-telemetry roadmap
 * items: the worst-cache-efficiency ranking (T1) and the per-tool context
 * footprint (T2). Both are native SQL, so only a real Postgres can validate the
 * ratio arithmetic, the noise floor, the percentile, and — most importantly —
 * the resume-heartbeat exclusion, which is structural rather than a filter and
 * would otherwise be invisible until it broke.
 */
@SpringBootTest
@Testcontainers
class TokenTelemetryQueryIntegrationTest {

  private static final String COST_METRIC = "claude_code.cost.usage";
  private static final String ACTIVE_METRIC = "claude_code.active_time.total";
  private static final String SESSION_COUNT_METRIC = "claude_code.session.count";
  private static final String TOKEN_METRIC = "claude_code.token.usage";
  private static final String TOOL_EVENT_NAME = "tool_result";
  private static final String TOOL_ATTRIBUTE = "tool_name";
  private static final String RESULT_SIZE_ATTRIBUTE = "tool_result_size_bytes";
  private static final String USER_PROMPT_EVENT_NAME = "user_prompt";
  private static final String API_REQUEST_EVENT_NAME = "api_request";
  private static final String PROMPT_ID_ATTRIBUTE = "prompt.id";

  private static final int WINDOW_MINUTES = 60;
  private static final int ROW_LIMIT = 10;

  // Every ranked session is seeded with exactly 1,000,000 input-side tokens so the
  // ORDER BY is provably driven by the ratio rather than by volume.
  private static final String SESSION_WORST = "worst";
  private static final String SESSION_MIDDLE = "middle";
  private static final String SESSION_BEST = "best";
  private static final String SESSION_TINY = "tiny";
  private static final String SESSION_HEARTBEAT = "heartbeat";

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  MetricPointRepository metricPointRepository;

  @Autowired
  LogRecordRepository logRecordRepository;

  @Autowired
  MetricService metricService;

  @Autowired
  LogService logService;

  private final List<Long> seededMetricPointIds = new ArrayList<>();

  private Instant seededBase;

  @BeforeEach
  void seed() {
    metricPointRepository.deleteAll();
    logRecordRepository.deleteAll();
    seededMetricPointIds.clear();
    Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
    seededBase = base;

    // worst: 20% of input-side tokens cached. Also the only ranked session seeded
    // with a user prompt, so the cache-efficiency ranking's firstUserPrompt/
    // endTimestamp fields (T1's session-info addition) have exactly one non-null
    // case and one null case (middle/best/tiny below) to distinguish.
    saveSessionActivity(SESSION_WORST, 9.0, base);
    saveTokens(SESSION_WORST, "input", 500_000, base);
    saveTokens(SESSION_WORST, "cacheCreation", 300_000, base);
    saveTokens(SESSION_WORST, "cacheRead", 200_000, base);
    savePrompt(SESSION_WORST, "prompt-1", "Investigate the worst cache session", base);

    // middle: 70%.
    saveSessionActivity(SESSION_MIDDLE, 4.0, base);
    saveTokens(SESSION_MIDDLE, "input", 200_000, base);
    saveTokens(SESSION_MIDDLE, "cacheCreation", 100_000, base);
    saveTokens(SESSION_MIDDLE, "cacheRead", 700_000, base);

    // best: 90%, plus a large output stream. Output is generated rather than
    // sent, so it must not move the ratio at all — if it leaked into the
    // denominator this session would rank as the worst of the three.
    saveSessionActivity(SESSION_BEST, 2.0, base);
    saveTokens(SESSION_BEST, "input", 100_000, base);
    saveTokens(SESSION_BEST, "cacheRead", 900_000, base);
    saveTokens(SESSION_BEST, "output", 5_000_000, base);

    // tiny: a genuinely terrible 10% ratio, but on 1,000 input-side tokens —
    // below the 100k floor, so it is noise rather than a finding.
    saveSessionActivity(SESSION_TINY, 0.01, base);
    saveTokens(SESSION_TINY, "input", 900, base);
    saveTokens(SESSION_TINY, "cacheRead", 100, base);

    // heartbeat: a resume stream. It emits session.count and (implausibly, to
    // make the test strict) token points, but never cost or active time — which
    // is exactly why session_window cannot see it.
    saveSessionCount(SESSION_HEARTBEAT, base);
    saveTokens(SESSION_HEARTBEAT, "input", 400_000, base);
    saveTokens(SESSION_HEARTBEAT, "cacheRead", 600_000, base);

    metricPointRepository.recomputeValueDeltas(seededMetricPointIds);
  }

  @Test
  void ranksSessionsByAscendingCacheEfficiencyIgnoringOutputTokens() {
    List<SessionCacheEfficiency> ranked =
        metricService.worstCacheEfficiencySessions(WINDOW_MINUTES, ROW_LIMIT);

    assertThat(ranked).extracting(SessionCacheEfficiency::sessionId)
        .containsExactly(SESSION_WORST, SESSION_MIDDLE, SESSION_BEST);
    assertThat(ranked.get(0).cacheEfficiency()).isEqualTo(0.2);
    assertThat(ranked.get(1).cacheEfficiency()).isEqualTo(0.7);
    assertThat(ranked.get(2).cacheEfficiency()).isEqualTo(0.9);
  }

  @Test
  void reportsTheRatioDenominatorSeparatelyFromTheAllKindsTotal() {
    SessionCacheEfficiency best = rankedSession(SESSION_BEST);

    // inputSideTokens is the ratio's denominator; totalTokens additionally
    // carries output, so the two differ by exactly the output stream — which the
    // row now also reports on its own rather than leaving to that subtraction.
    assertThat(best.inputSideTokens()).isEqualTo(1_000_000L);
    assertThat(best.outputTokens()).isEqualTo(5_000_000L);
    assertThat(best.totalTokens()).isEqualTo(6_000_000L);
    assertThat(best.cacheReadTokens()).isEqualTo(900_000L);
    assertThat(best.costUsd()).isEqualTo(2.0);
  }

  @Test
  void decomposesTheRatioDenominatorIntoItsThreeInputSideKinds() {
    SessionCacheEfficiency worst = rankedSession(SESSION_WORST);

    // The Tokens page's session detail draws three of its four bar segments from
    // these (output is the fourth). The SQL aggregates each kind independently
    // (one FILTERed SUM per kind over the same token-usage metric), so these
    // assert the query actually reads the seeded per-kind values correctly.
    // inputSideTokens() itself is derived from these three by construction, so no
    // separate reconciliation assertion is needed.
    assertThat(worst.inputTokens()).isEqualTo(500_000L);
    assertThat(worst.cacheCreationTokens()).isEqualTo(300_000L);
    assertThat(worst.cacheReadTokens()).isEqualTo(200_000L);

    // A session that emitted no cache-creation and no output stream reports 0 for
    // each rather than dropping out of the decomposition — the bar renders a
    // legend row for a zero kind, so it has to be a number and not a null.
    SessionCacheEfficiency best = rankedSession(SESSION_BEST);
    assertThat(best.cacheCreationTokens()).isZero();
    assertThat(worst.outputTokens()).isZero();
  }

  @Test
  void surfacesLastActivityAndFirstUserPromptPerRankedSession() {
    SessionCacheEfficiency worst = rankedSession(SESSION_WORST);
    assertThat(worst.endTimestamp()).isEqualTo(seededBase);
    assertThat(worst.firstUserPrompt()).isEqualTo("Investigate the worst cache session");

    // middle emitted no user_prompt log at all, so it must read null rather than
    // an empty string or a fabricated placeholder.
    SessionCacheEfficiency middle = rankedSession(SESSION_MIDDLE);
    assertThat(middle.firstUserPrompt()).isNull();
  }

  @Test
  void excludesSessionsBelowTheInputSideTokenFloor() {
    List<SessionCacheEfficiency> ranked =
        metricService.worstCacheEfficiencySessions(WINDOW_MINUTES, ROW_LIMIT);

    // tiny has the worst ratio of anything seeded (10%); it is absent purely
    // because it is too small to judge, not because it ranked poorly.
    assertThat(ranked).extracting(SessionCacheEfficiency::sessionId).doesNotContain(SESSION_TINY);
  }

  @Test
  void excludesResumeHeartbeatSessionsStructurally() {
    List<SessionCacheEfficiency> ranked =
        metricService.worstCacheEfficiencySessions(WINDOW_MINUTES, ROW_LIMIT);

    // The heartbeat session carries a full 1,000,000 input-side tokens and a 60%
    // ratio, so it clears the floor and would rank second if it were visible at
    // all. It is excluded because session_window is keyed off the cost and
    // active-time metrics, which resume streams never emit. This assertion is
    // what stops that exclusion from being refactored away silently.
    assertThat(ranked).extracting(SessionCacheEfficiency::sessionId)
        .doesNotContain(SESSION_HEARTBEAT);
  }

  @Test
  void honoursTheRequestedRowLimit() {
    List<SessionCacheEfficiency> ranked = metricService.worstCacheEfficiencySessions(WINDOW_MINUTES, 1);

    assertThat(ranked).extracting(SessionCacheEfficiency::sessionId).containsExactly(SESSION_WORST);
  }

  @Test
  void windowCacheReadRatioUsesTheSameDenominatorAsThePerSessionRatio() {
    TokenUsageSummary summary = metricService.aggregateTokenUsage(WINDOW_MINUTES);

    long inputSideTokens =
        summary.inputTokens() + summary.cacheCreationTokens() + summary.cacheReadTokens();
    // Asserted as an invariant rather than a hardcoded number: the point is that
    // the window gauge and the Sessions column measure the same thing, and that
    // the large seeded output stream does not enter the denominator.
    assertThat(summary.cacheReadRatio())
        .isEqualTo((double) summary.cacheReadTokens() / (double) inputSideTokens);
    assertThat(summary.outputTokens()).isPositive();
  }

  // ---- T2: per-tool context footprint ---------------------------------------

  @Test
  void sumsResultBytesPerToolAndEstimatesTokensAtFourBytesEach() {
    Instant base = Instant.now().minus(5, ChronoUnit.MINUTES);
    saveToolResult("Bash", 10_000L, true, base);
    saveToolResult("Bash", 20_000L, true, base.plusSeconds(1));
    // A failed call still filled the context with its error output.
    saveToolResult("Bash", 30_000L, false, base.plusSeconds(2));
    saveToolResult("Read", 4_000L, true, base.plusSeconds(3));

    List<ToolContextFootprint> footprint = logService.aggregateToolContextFootprint(WINDOW_MINUTES);

    ToolContextFootprint bash = toolRow(footprint, "Bash");
    assertThat(bash.calls()).isEqualTo(3L);
    assertThat(bash.totalBytes()).isEqualTo(60_000L);
    assertThat(bash.estimatedTokens()).isEqualTo(15_000L);

    ToolContextFootprint read = toolRow(footprint, "Read");
    assertThat(read.totalBytes()).isEqualTo(4_000L);
    assertThat(read.estimatedTokens()).isEqualTo(1_000L);
  }

  @Test
  void ordersToolsByTotalBytesAndIncludesExternallyDeterminedTools() {
    Instant base = Instant.now().minus(5, ChronoUnit.MINUTES);
    saveToolResult("Read", 4_000L, true, base);
    saveToolResult("Bash", 60_000L, true, base.plusSeconds(1));
    // Agent is on TuningProperties.externallyDeterminedTools, so the tuning
    // report's oversized-results list skips it. This card asks a different
    // question — what is filling the window — and must count it.
    saveToolResult("Agent", 100_000L, true, base.plusSeconds(2));

    List<ToolContextFootprint> footprint = logService.aggregateToolContextFootprint(WINDOW_MINUTES);

    assertThat(footprint).extracting(ToolContextFootprint::tool)
        .containsExactly("Agent", "Bash", "Read");
  }

  @Test
  void excludesCallsThatReportedNoResultSize() {
    Instant base = Instant.now().minus(5, ChronoUnit.MINUTES);
    saveToolResult("Bash", 10_000L, true, base);
    saveToolResultWithoutSize("Bash", base.plusSeconds(1));

    List<ToolContextFootprint> footprint = logService.aggregateToolContextFootprint(WINDOW_MINUTES);

    // The sizeless call is not counted as a zero-byte call: `calls` means "calls
    // we can account for", so it stays 1 and the average is not deflated.
    ToolContextFootprint bash = toolRow(footprint, "Bash");
    assertThat(bash.calls()).isEqualTo(1L);
    assertThat(bash.totalBytes()).isEqualTo(10_000L);
  }

  @Test
  void reportsP95OfSingleResultSizes() {
    Instant base = Instant.now().minus(5, ChronoUnit.MINUTES);
    // A steady drip with one blowout: the total is unremarkable but p95 is not.
    for (int i = 0; i < 19; i++) {
      saveToolResult("Bash", 1_000L, true, base.plusSeconds(i));
    }
    saveToolResult("Bash", 500_000L, true, base.plusSeconds(20));

    ToolContextFootprint bash =
        toolRow(logService.aggregateToolContextFootprint(WINDOW_MINUTES), "Bash");

    assertThat(bash.calls()).isEqualTo(20L);
    assertThat(bash.p95Bytes()).isGreaterThan(1_000L);
  }

  @Test
  void returnsNothingWhenNoToolResultCarriedASize() {
    List<ToolContextFootprint> footprint = logService.aggregateToolContextFootprint(WINDOW_MINUTES);

    assertThat(footprint).isEmpty();
  }

  // ---- T3: per-API-request token attribution --------------------------------

  @Test
  void attributesTurnTokensAndCostFromTheTurnsOwnRequestsWhenPromptIdCorrelates() {
    Instant base = Instant.now().minus(4, ChronoUnit.MINUTES);
    String firstTurn = "prompt-1";
    String secondTurn = "prompt-2";
    savePrompt("S1", firstTurn, "Investigate the flaky test", base);
    savePrompt("S1", secondTurn, "Now fix it", base.plusSeconds(120));

    saveApiRequest("S1", firstTurn, "req-1", "claude-opus-5", 10, 100, 1_000, 50_000, 0.10, 900L, base.plusSeconds(1));
    saveApiRequest("S1", firstTurn, "req-2", "claude-opus-5", 20, 200, 2_000, 60_000, 0.20, 800L, base.plusSeconds(2));
    // Issued by turn 1 but logged AFTER turn 2 started. Interval bucketing would
    // bill it to turn 2; the prompt-id join bills it to the turn that asked.
    saveApiRequest("S1", firstTurn, "req-3", "claude-opus-5", 30, 300, 3_000, 70_000, 0.30, 700L,
        base.plusSeconds(130));
    saveApiRequest("S1", secondTurn, "req-4", "claude-fable-5", 40, 400, 4_000, 80_000, 0.40, 600L,
        base.plusSeconds(140));

    List<SessionPrompt> prompts = logService.promptsForSession("S1");

    assertThat(prompts).hasSize(2);
    SessionPrompt turnOne = prompts.get(0);
    assertThat(turnOne.attribution()).isEqualTo(SessionPrompt.TurnAttribution.REQUEST);
    assertThat(turnOne.requestCount()).isEqualTo(3L);
    assertThat(turnOne.model()).isEqualTo("claude-opus-5");
    assertThat(turnOne.costUsd()).isEqualTo(0.60);
    assertThat(turnOne.tokens()).isEqualTo(new SessionTokenBreakdown(60L, 600L, 6_000L, 180_000L));

    SessionPrompt turnTwo = prompts.get(1);
    assertThat(turnTwo.requestCount()).isEqualTo(1L);
    assertThat(turnTwo.model()).isEqualTo("claude-fable-5");
    assertThat(turnTwo.costUsd()).isEqualTo(0.40);
  }

  @Test
  void perTurnRollupsSumExactlyToThePerRequestDrillDown() {
    Instant base = Instant.now().minus(4, ChronoUnit.MINUTES);
    String turn = "prompt-1";
    savePrompt("S1", turn, "Investigate the flaky test", base);
    saveApiRequest("S1", turn, "req-1", "claude-opus-5", 10, 100, 1_000, 50_000, 0.10, 900L, base.plusSeconds(1));
    saveApiRequest("S1", turn, "req-2", "claude-opus-5", 20, 200, 2_000, 60_000, 0.20, 800L, base.plusSeconds(2));

    SessionPrompt rolledUp = logService.promptsForSession("S1").get(0);
    List<SessionApiRequest> requests = logService.requestsForSession("S1").stream()
        .filter(request -> turn.equals(request.promptId()))
        .toList();

    // This is the reconciliation that actually holds: the turn card and its
    // drill-down are the same rows summed two ways, so they can never disagree.
    // (The counter-derived pipeline is a DIFFERENT measurement and is not
    // asserted equal to this one — see the class doc on TokenUsageSummary and the
    // dashboard roadmap for why the two are reported separately, never summed.)
    assertThat(requests).hasSize((int) rolledUp.requestCount());
    assertThat(requests.stream().mapToLong(request -> request.tokens().cacheRead()).sum())
        .isEqualTo(rolledUp.tokens().cacheRead());
    // Tokens are integers and must match exactly; cost is a double summed by
    // Postgres on one side and by the JVM on the other, so the two agree only to
    // within floating-point rounding (0.1 + 0.2 famously lands on
    // 0.30000000000000004 one way and 0.3 the other). Clients must not compare
    // these two costs for exact equality either.
    assertThat(requests.stream().mapToDouble(SessionApiRequest::costUsd).sum())
        .isCloseTo(rolledUp.costUsd(), within(1e-9));
  }

  @Test
  void fallsBackToIntervalAttributionForTurnsWithNoRequestLogs() {
    Instant base = Instant.now().minus(4, ChronoUnit.MINUTES);
    savePrompt("S2", "prompt-untracked", "A turn recorded without event logging", base);

    SessionPrompt turn = logService.promptsForSession("S2").get(0);

    // Degrades rather than reporting zeros: a session with no api_request logs is
    // a normal outcome (event logging off, older CLI), and its counter-derived
    // figures are still the best available answer.
    assertThat(turn.attribution()).isEqualTo(SessionPrompt.TurnAttribution.INTERVAL);
    assertThat(turn.requestCount()).isZero();
  }

  @Test
  void mixesAttributionPerTurnWithinOneSession() {
    Instant base = Instant.now().minus(4, ChronoUnit.MINUTES);
    savePrompt("S3", null, "A turn from before prompt ids were stamped", base);
    savePrompt("S3", "prompt-new", "A turn from after", base.plusSeconds(60));
    saveApiRequest("S3", "prompt-new", "req-1", "claude-opus-5", 10, 100, 1_000, 50_000, 0.10, 900L,
        base.plusSeconds(61));

    List<SessionPrompt> prompts = logService.promptsForSession("S3");

    // The fallback is per-turn, not per-session: a session that gained
    // api_request logs partway through reports each turn however it can be
    // measured, instead of downgrading the whole timeline to the weaker source.
    assertThat(prompts.get(0).attribution()).isEqualTo(SessionPrompt.TurnAttribution.INTERVAL);
    assertThat(prompts.get(1).attribution()).isEqualTo(SessionPrompt.TurnAttribution.REQUEST);
  }

  @Test
  void requestDrillDownCarriesEffortAndSpeedAndToleratesMissingEffort() {
    Instant base = Instant.now().minus(4, ChronoUnit.MINUTES);
    savePrompt("S1", "prompt-1", "Investigate", base);
    saveApiRequest("S1", "prompt-1", "req-1", "claude-opus-5", 10, 100, 1_000, 50_000, 0.10, 900L,
        base.plusSeconds(1), "high", "normal");
    saveApiRequest("S1", "prompt-1", "req-2", "claude-opus-5", 20, 200, 2_000, 60_000, 0.20, 800L,
        base.plusSeconds(2), null, "normal");

    List<SessionApiRequest> requests = logService.requestsForSession("S1");

    assertThat(requests).extracting(SessionApiRequest::requestId).containsExactly("req-1", "req-2");
    assertThat(requests.get(0).effort()).isEqualTo("high");
    assertThat(requests.get(0).durationMs()).isEqualTo(900L);
    // Absent on a minority of real rows — must stay null rather than defaulting.
    assertThat(requests.get(1).effort()).isNull();
    assertThat(requests.get(1).speed()).isEqualTo("normal");
  }

  @Test
  void requestDrillDownIsEmptyForSessionsWithoutRequestLogs() {
    assertThat(logService.requestsForSession("no-such-session")).isEmpty();
  }

  // ---- fixtures -------------------------------------------------------------

  private SessionCacheEfficiency rankedSession(String sessionId) {
    return metricService.worstCacheEfficiencySessions(WINDOW_MINUTES, ROW_LIMIT).stream()
        .filter(session -> sessionId.equals(session.sessionId()))
        .findFirst()
        .orElseThrow();
  }

  private static ToolContextFootprint toolRow(List<ToolContextFootprint> footprint, String tool) {
    return footprint.stream()
        .filter(row -> tool.equals(row.tool()))
        .findFirst()
        .orElseThrow();
  }

  /** Cost + active time — the pair that puts a session into session_window at all. */
  private void saveSessionActivity(String sessionId, double costUsd, Instant timestamp) {
    saveMetric(COST_METRIC, Map.of("session.id", sessionId, "model", "opus"), costUsd, timestamp);
    saveMetric(ACTIVE_METRIC, Map.of("session.id", sessionId, "model", "opus"), 60.0, timestamp);
  }

  /** A resume heartbeat: session.count only, never cost or active time. */
  private void saveSessionCount(String sessionId, Instant timestamp) {
    saveMetric(
        SESSION_COUNT_METRIC,
        Map.of("session.id", sessionId, "start_type", "resume", "terminal.type", "interactive"),
        1.0,
        timestamp);
  }

  private void saveTokens(String sessionId, String tokenType, double value, Instant timestamp) {
    saveMetric(TOKEN_METRIC, Map.of("session.id", sessionId, "type", tokenType), value, timestamp);
  }

  private void saveMetric(
      String metricName, Map<String, Object> attributes, double value, Instant timestamp) {
    MetricPointEntity entity = new MetricPointEntity();
    entity.setMetricName(metricName);
    entity.setTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setValueDouble(value);
    entity.setValueKind("double");
    entity.setAttributes(attributes);
    seededMetricPointIds.add(metricPointRepository.save(entity).getId());
  }

  private void saveToolResult(String toolName, long resultBytes, boolean success, Instant timestamp) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", TOOL_EVENT_NAME);
    attributes.put(TOOL_ATTRIBUTE, toolName);
    attributes.put(RESULT_SIZE_ATTRIBUTE, resultBytes);
    attributes.put("success", success);
    saveLogRecord(attributes, timestamp);
  }

  private void saveToolResultWithoutSize(String toolName, Instant timestamp) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", TOOL_EVENT_NAME);
    attributes.put(TOOL_ATTRIBUTE, toolName);
    attributes.put("success", true);
    saveLogRecord(attributes, timestamp);
  }

  /** A user_prompt turn. A null promptId models a row predating prompt-id stamping. */
  private void savePrompt(String sessionId, String promptId, String promptText, Instant timestamp) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", USER_PROMPT_EVENT_NAME);
    attributes.put("session.id", sessionId);
    attributes.put("prompt", promptText);
    if (promptId != null) {
      attributes.put(PROMPT_ID_ATTRIBUTE, promptId);
    }
    saveLogRecord(attributes, timestamp);
  }

  private void saveApiRequest(
      String sessionId, String promptId, String requestId, String model,
      int inputTokens, int outputTokens, int cacheCreationTokens, int cacheReadTokens,
      double costUsd, long durationMs, Instant timestamp) {
    saveApiRequest(sessionId, promptId, requestId, model, inputTokens, outputTokens,
        cacheCreationTokens, cacheReadTokens, costUsd, durationMs, timestamp, "high", "normal");
  }

  /** One api_request log, shaped the way Claude Code emits it. A null effort models the ~7% of rows missing it. */
  private void saveApiRequest(
      String sessionId, String promptId, String requestId, String model,
      int inputTokens, int outputTokens, int cacheCreationTokens, int cacheReadTokens,
      double costUsd, long durationMs, Instant timestamp, String effort, String speed) {
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("event.name", API_REQUEST_EVENT_NAME);
    attributes.put("session.id", sessionId);
    attributes.put(PROMPT_ID_ATTRIBUTE, promptId);
    attributes.put("request_id", requestId);
    attributes.put("model", model);
    attributes.put("input_tokens", inputTokens);
    attributes.put("output_tokens", outputTokens);
    attributes.put("cache_creation_tokens", cacheCreationTokens);
    attributes.put("cache_read_tokens", cacheReadTokens);
    attributes.put("cost_usd", costUsd);
    attributes.put("duration_ms", durationMs);
    if (effort != null) {
      attributes.put("effort", effort);
    }
    attributes.put("speed", speed);
    saveLogRecord(attributes, timestamp);
  }

  private void saveLogRecord(Map<String, Object> attributes, Instant timestamp) {
    LogRecordEntity entity = new LogRecordEntity();
    entity.setTimestamp(timestamp);
    entity.setObservedTimestamp(timestamp);
    entity.setReceivedAt(Instant.now());
    entity.setScopeName("claude_code.tools");
    entity.setBody("tool result");
    entity.setAttributes(attributes);
    logRecordRepository.save(entity);
  }
}
