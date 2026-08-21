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
 *
 * <p>The skill side additionally seeds a chatty invocation — several api_request
 * rows under one prompt id, one of them from inside a subagent the skill spawned
 * — because a skill invocation is one prompt that entered the skill, not one
 * api_request. Counting rows there inflated real totals by up to 46x.
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
    private static final String ATTR_PROMPT_ID     = "prompt.id";

    private static final String EVENT_API_REQUEST = "api_request";
    private static final String EVENT_TOOL_RESULT = "tool_result";

    private static final String TOOL_AGENT = "Agent";

    private static final String SESSION_ID = "session-1";

    private static final String MODEL_OPUS   = "claude-opus-4-8";
    private static final String MODEL_SONNET = "claude-sonnet-4-6";
    private static final String MODEL_HAIKU  = "claude-haiku-4-5-20251001";

    private static final String SUBAGENT_EXPLORE         = "Explore";
    private static final String SUBAGENT_GENERAL_PURPOSE = "general-purpose";
    private static final String SKILL_SHIP               = "ship";
    private static final String SKILL_VERIFY             = "verify";

    // Two invocations of the ship skill, plus one of verify. The first ship
    // invocation is deliberately chatty: several turns share PROMPT_FIRST_SHIP.
    private static final String PROMPT_FIRST_SHIP  = "prompt-ship-1";
    private static final String PROMPT_SECOND_SHIP = "prompt-ship-2";
    private static final String PROMPT_VERIFY      = "prompt-verify";

    // Turns inside a spawned subagent get their own prompt id rather than the
    // dispatching prompt's — observed in real data, and the reason deduplicating
    // by prompt id is not on its own enough to keep them out of the count.
    private static final String PROMPT_SHIP_SUBAGENT = "prompt-ship-1-subagent";

    // Row offsets in seconds from windowStart, in dispatch order.
    private static final int OFFSET_MAIN_LOOP_TURN_ON_OPUS      = 60;
    private static final int OFFSET_SUBAGENT_TURN_ON_SONNET     = 120;
    private static final int OFFSET_FIRST_EXPLORE_DISPATCH      = 180;
    private static final int OFFSET_MAIN_LOOP_TURN_ON_SONNET    = 240;
    private static final int OFFSET_SECOND_EXPLORE_DISPATCH     = 300;
    private static final int OFFSET_UNTYPED_AGENT_DISPATCH      = 360;
    private static final int OFFSET_SHIP_SKILL_ON_OPUS          = 420;
    private static final int OFFSET_SHIP_SKILL_LATER_TURN       = 430;
    private static final int OFFSET_SHIP_SKILL_SUBAGENT_TURN    = 440;
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

        // No subagent type anywhere — the dispatch ran on the default agent, so
        // it must land on that identifier rather than an 'unknown' bucket.
        saveLog(OFFSET_UNTYPED_AGENT_DISPATCH, Map.of(
                ATTR_EVENT_NAME, EVENT_TOOL_RESULT,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_TOOL_NAME, TOOL_AGENT));

        // First ship invocation, earliest turn — this is the turn whose model the
        // whole invocation is attributed to.
        saveLog(OFFSET_SHIP_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_FIRST_SHIP));

        // Same invocation, later turn, different model. It must not add a second
        // call, and must not move the invocation into the Sonnet bucket.
        saveLog(OFFSET_SHIP_SKILL_LATER_TURN, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_FIRST_SHIP));

        // A turn from inside a subagent the skill spawned. It inherits skill.name,
        // reports the subagent's model, and carries a prompt id of its own — so
        // only the agent.name check keeps it from counting as a third invocation.
        saveLog(OFFSET_SHIP_SKILL_SUBAGENT_TURN, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_HAIKU,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_SHIP_SUBAGENT,
                ATTR_AGENT_NAME, "custom"));

        // Second ship invocation — a separate prompt, so a separate call.
        saveLog(OFFSET_SHIP_SKILL_ON_SONNET, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_SONNET,
                ATTR_SKILL_NAME, SKILL_SHIP,
                ATTR_PROMPT_ID, PROMPT_SECOND_SHIP));

        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, SKILL_VERIFY,
                ATTR_PROMPT_ID, PROMPT_VERIFY));
    }

    @Test
    void skillUsageSplitsCallsByTheModelThatRanTheSkill() {
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).containsExactly(SKILL_SHIP, SKILL_VERIFY);
        assertThat(rows.get(0).calls()).isEqualTo(2L);
        assertThat(rows.get(0).byModel()).containsOnlyKeys(MODEL_OPUS, MODEL_SONNET);
        assertThat(rows.get(0).byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);
        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_OPUS, 1L));
    }

    @Test
    void skillUsageCountsOnePromptOnceHoweverManyTurnsItTakes() {
        // The ship seed spends four api_request rows on two invocations. Counting
        // rows would report 4 (or 3 after dropping the subagent turn); only the
        // prompt-level dedup gives the 2 the page is supposed to show.
        long shipTurnsInWindow = repository.findAll().stream()
                .filter(row -> SKILL_SHIP.equals(row.getAttributes().get(ATTR_SKILL_NAME)))
                .count();
        assertThat(shipTurnsInWindow).isEqualTo(4L);

        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(2L));
    }

    @Test
    void skillUsageExcludesTurnsMadeInsideSubagentsTheSkillSpawned() {
        // That turn carries its own prompt id, so the prompt-level dedup does not
        // absorb it: without the agent.name check ship gains a third call and a
        // Haiku bucket it never ran on.
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.calls()).isEqualTo(2L);
                    assertThat(row.byModel()).doesNotContainKey(MODEL_HAIKU);
                });
    }

    @Test
    void skillInvocationSpanningModelsIsAttributedToItsEarliestTurn() {
        // PROMPT_FIRST_SHIP starts on Opus and later runs on Sonnet. Attributing
        // it to the latest turn instead would read {Sonnet: 2} and lose Opus.
        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> SKILL_SHIP.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.byModel()).containsEntry(MODEL_OPUS, 1L));
    }

    @Test
    void skillTurnsWithNoPromptIdCountIndividuallyRatherThanCollapsing() {
        // Falling back to a constant would fold every prompt-less turn of one
        // skill into a single invocation; the fallback is the row's primary key.
        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, "unprompted"));
        saveLog(OFFSET_VERIFY_SKILL_ON_OPUS, Map.of(
                ATTR_EVENT_NAME, EVENT_API_REQUEST,
                ATTR_SESSION_ID, SESSION_ID,
                ATTR_MODEL, MODEL_OPUS,
                ATTR_SKILL_NAME, "unprompted"));

        List<IdentifierUsageCount> rows = service.aggregateSkillUsageInRange(windowStart, windowEnd);

        assertThat(rows)
                .filteredOn(row -> "unprompted".equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(2L));
    }

    @Test
    void subagentUsageAttributesCallsToTheDispatchingMainLoopTurnNotTheSubagentsOwnTurns() {
        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool)
                .containsExactly(SUBAGENT_EXPLORE, SUBAGENT_GENERAL_PURPOSE);

        IdentifierUsageCount explore = rows.get(0);
        assertThat(explore.calls()).isEqualTo(2L);
        // The Sonnet turn between the Opus dispatch and its tool_result carries
        // agent.name, so it must not claim the first call.
        assertThat(explore.byModel()).containsEntry(MODEL_OPUS, 1L).containsEntry(MODEL_SONNET, 1L);

        // The dispatch that named no subagent type — it ran on the default agent.
        assertThat(rows.get(1).calls()).isEqualTo(1L);
        assertThat(rows.get(1).byModel()).containsExactlyInAnyOrderEntriesOf(Map.of(MODEL_SONNET, 1L));
    }

    @Test
    void subagentDispatchWithNoTypeIsCreditedToTheDefaultAgentNotAnUnknownBucket() {
        List<IdentifierUsageCount> rows = service.aggregateSubagentUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(IdentifierUsageCount::tool).doesNotContain("unknown");
        assertThat(rows)
                .filteredOn(row -> SUBAGENT_GENERAL_PURPOSE.equals(row.tool()))
                .singleElement()
                .satisfies(row -> assertThat(row.calls()).isEqualTo(1L));
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
