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
import com.guavasoft.agentcompass.model.IdentifierUsageCount;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.service.LogService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the skill-usage and subagent-usage aggregations against a real
 * Postgres instance, because both now carry a per-model split that only SQL can
 * get wrong: the skill side reads the model straight off the api_request row,
 * while the subagent side resolves it through a lateral walk back to the last
 * main-loop api_request in the same session. The controller-layer
 * {@code ToolActivityControllerTest} mocks {@link LogService} and cannot catch
 * either.
 *
 * <p>The seed models one session: a main-loop turn on model A dispatches an
 * Explore subagent, the subagent's own turns run on model B, then a later
 * main-loop turn on model B dispatches another Explore. Attribution must follow
 * the dispatching turn, not the subagent's turns.
 */
@SpringBootTest
@Testcontainers
class SkillSubagentUsageQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository repository;

    @Autowired
    LogService service;

    /** Window spanning 60 minutes; rows are seeded at fixed offsets inside it. */
    private Instant windowStart;
    private Instant windowEnd;

    private static final String ATTR_EVENT_NAME    = "event.name";
    private static final String ATTR_TOOL_NAME     = "tool_name";
    private static final String ATTR_TOOL_INPUT    = "tool_input";
    private static final String ATTR_SESSION_ID    = "session.id";
    private static final String ATTR_MODEL         = "model";
    private static final String ATTR_AGENT_NAME    = "agent.name";
    private static final String ATTR_SKILL_NAME    = "skill.name";
    private static final String ATTR_SUBAGENT_TYPE = "subagent_type";

    private static final String EVENT_API_REQUEST = "api_request";
    private static final String EVENT_TOOL_RESULT = "tool_result";

    private static final String TOOL_AGENT = "Agent";

    private static final String SESSION_ID = "session-1";

    private static final String MODEL_OPUS   = "claude-opus-4-8";
    private static final String MODEL_SONNET = "claude-sonnet-4-6";

    private static final String SUBAGENT_EXPLORE = "Explore";
    private static final String SKILL_SHIP       = "ship";
    private static final String SKILL_VERIFY     = "verify";

    // Row offsets in seconds from windowStart, in dispatch order.
    private static final int OFFSET_MAIN_LOOP_TURN_ON_OPUS      = 60;
    private static final int OFFSET_SUBAGENT_TURN_ON_SONNET     = 120;
    private static final int OFFSET_FIRST_EXPLORE_DISPATCH      = 180;
    private static final int OFFSET_MAIN_LOOP_TURN_ON_SONNET    = 240;
    private static final int OFFSET_SECOND_EXPLORE_DISPATCH     = 300;
    private static final int OFFSET_UNTYPED_AGENT_DISPATCH      = 360;
    private static final int OFFSET_SHIP_SKILL_ON_OPUS          = 420;
    private static final int OFFSET_SHIP_SKILL_ON_SONNET        = 480;
    private static final int OFFSET_VERIFY_SKILL_ON_OPUS        = 540;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        // Main-loop turn on Opus — the turn that dispatches the first Explore.
        saveLog(OFFSET_MAIN_LOOP_TURN_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS));

        // A turn from inside the subagent run, on a different model. It is nearer
        // in time to the tool_result below than the dispatching turn is, so it
        // would win a naive "last api_request" lookup — agent.name must exclude it.
        saveLog(OFFSET_SUBAGENT_TURN_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_AGENT_NAME, "custom"));

        saveLog(OFFSET_FIRST_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, SUBAGENT_EXPLORE));

        saveLog(OFFSET_MAIN_LOOP_TURN_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET));

        // Identifier carried inside tool_input rather than as a flat attribute —
        // the COALESCE fallback has to find it.
        saveLog(OFFSET_SECOND_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_TOOL_INPUT, "{\"" + ATTR_SUBAGENT_TYPE + "\": \"" + SUBAGENT_EXPLORE + "\"}"));

        // No subagent type anywhere — buckets under 'unknown'.
        saveLog(OFFSET_UNTYPED_AGENT_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT));

        saveLog(OFFSET_SHIP_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_SHIP));

        saveLog(OFFSET_SHIP_SKILL_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_SKILL_NAME, SKILL_SHIP));

        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_VERIFY));
    }

    @Test
    void skillUsageSplitsCallsByTheModelThatServedTheTurn() {
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).containsExactly(SKILL_SHIP, SKILL_VERIFY);
        assertThat(rows.get(0).calls()).isEqualTo(2L);
        assertThat(rows.get(0).byModel()).containsOnlyKeys(MODEL_OPUS, MODEL_SONNET);
        assertThat(rows.get(0).byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);
        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_OPUS, 1L));
    }

    @Test
    void subagentUsageAttributesCallsToTheDispatchingMainLoopTurnNotTheSubagentsOwnTurns() {
        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).containsExactly(SUBAGENT_EXPLORE, "unknown");

        IdentifierUsageCount explore = rows.get(0);
        assertThat(explore.calls()).isEqualTo(2L);
        // The Sonnet turn between the Opus dispatch and its tool_result carries
        // agent.name, so it must not claim the first call.
        assertThat(explore.byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);

        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_SONNET, 1L));
    }

    @Test
    void perModelCallsAlwaysSumToTheRowTotal() {
        List<IdentifierUsageCount> rows = new ArrayList<>(service.aggregateSkillUsageInRange(windowStart, windowEnd));
        rows.addAll(service.aggregateSubagentUsageInRange(windowStart, windowEnd));

        assertThat(rows).isNotEmpty().allSatisfy(row ->
                assertThat(row.byModel().values().stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(row.calls()));
    }

    @Test
    void subagentCallsWithNoPrecedingMainLoopTurnBucketUnderUnknownModel() {
        // A second session whose only rows are the dispatch itself — no api_request
        // exists at or before it, so the lateral lookup finds nothing.
        saveLog(OFFSET_FIRST_EXPLORE_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, "session-with-no-turns",
                ATTR_TOOL_NAME, TOOL_AGENT,
                ATTR_SUBAGENT_TYPE, "orphaned"));

        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> "orphaned".equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.byModel())
                        .containsExactlyInAnyOrderEntriesOf(Map.of("unknown", 1L)));
    }

    @Test
    void emptyWindowReturnsNoRowsRatherThanAnUnknownBucket() {
        Instant beforeAnySeededRow = windowStart.minus(10, ChronoUnit.MINUTES);

        assertThat(service.aggregateSkillUsageInRange(beforeAnySeededRow, windowStart)).isEmpty();
        assertThat(service.aggregateSubagentUsageInRange(beforeAnySeededRow, windowStart)).isEmpty();
    }

    private void saveLog(int offsetSeconds, Map<String, Object> attributes) {
        Instant timestamp = windowStart.plusSeconds(offsetSeconds);
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("anthropic.api");
        entity.setBody("seeded row");
        entity.setAttributes(new HashMap<>(attributes));
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        repository.save(entity);
    }
}
