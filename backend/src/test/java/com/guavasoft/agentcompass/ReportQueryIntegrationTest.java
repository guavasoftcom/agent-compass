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
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.service.ReportService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ReportService} against a real Postgres instance so every native
 * aggregation behind {@code GET /api/report} runs for real. The report renders by
 * chaining ~10 repository queries; a named-parameter mismatch in any of them (the
 * query text saying {@code :since} while the method binds {@code :start}/{@code :end},
 * as regressed once in {@code aggregateBashCommandHotspotsInRange}) only surfaces at
 * execution time, which no controller-slice test with a mocked service can catch.
 */
@SpringBootTest
@Testcontainers
class ReportQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    ReportService reportService;

    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_TOOL_NAME = "tool_name";
    private static final String ATTR_TOOL_INPUT = "tool_input";
    private static final String ATTR_DURATION_MS = "duration_ms";
    private static final String ATTR_RESULT_SIZE_BYTES = "tool_result_size_bytes";
    private static final String ATTR_SUCCESS = "success";
    private static final String ATTR_ERROR = "error";
    private static final String ATTR_SESSION_ID = "session.id";
    private static final String ATTR_TOOL_PARAMETERS = "tool_parameters";

    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String TOOL_BASH = "Bash";
    private static final String TOOL_READ = "Read";
    private static final String MCP_TOOL_NAME = "mcp_tool";

    /** MCP section fixtures: two servers, one (server, tool) row each, both successful. */
    private static final String MCP_SERVER_PLAYWRIGHT = "playwright";
    private static final String MCP_SERVER_CODE_GRAPH = "CodeGraphContext";
    private static final String MCP_TOOL_BROWSER_EVALUATE = "browser_evaluate";
    private static final String MCP_TOOL_SEARCH = "search";
    private static final long MCP_PLAYWRIGHT_DURATION_MS = 1200L;
    private static final long MCP_PLAYWRIGHT_RESULT_BYTES = 4096L;
    private static final long MCP_CODE_GRAPH_DURATION_MS = 800L;
    private static final long MCP_CODE_GRAPH_RESULT_BYTES = 1024L;

    /** MCP suggestion-rule fixtures: a server crossing the failure-rate threshold vs. a control. */
    private static final String MCP_SERVER_FLAKY = "flaky-server";
    private static final String MCP_SERVER_RELIABLE = "reliable-server";
    private static final String MCP_TOOL_BROWSE = "browse";
    private static final int MCP_FLAKY_CALL_COUNT = 10;
    private static final int MCP_FLAKY_FAILURE_COUNT = 3;
    private static final int MCP_RELIABLE_CALL_COUNT = 10;
    private static final long MCP_SUGGESTION_FIXTURE_DURATION_MS = 500L;
    private static final long MCP_SUGGESTION_FIXTURE_RESULT_BYTES = 100L;

    private static final String GIT_STATUS_TOOL_INPUT = "{\"command\":\"git status\"}";
    private static final String GIT_DIFF_TOOL_INPUT = "{\"command\":\"git diff\"}";

    private static final int WINDOW_MINUTES = 60;
    private static final int OFFSET_FIRST_GIT_CALL = 0;
    private static final int OFFSET_SECOND_GIT_CALL = 300;
    private static final int OFFSET_READ_CALL = 600;
    private static final int OFFSET_AFTER_WINDOW_END = 3_600;

    private static final String SESSION_A = "session-a";
    private static final long AGENT_RESULT_BYTES = 555_444L;
    private static final long IMAGE_RESULT_BYTES = 444_333L;
    private static final long CD_PREFIXED_RESULT_BYTES = 60_000L;
    private static final long GROUPED_RESULT_BYTES = 30_000L;
    private static final long SPREAD_GAP_SECONDS = 45L * 60L;

    /** Tail-shape fixtures: many small results plus a couple of blowouts (p95 >> mean). */
    private static final long TAIL_SMALL_RESULT_BYTES = 1_000L;
    private static final long TAIL_BLOWOUT_RESULT_BYTES = 400_000L;
    private static final int TAIL_SMALL_CALL_COUNT = 20;
    private static final int TAIL_BLOWOUT_CALL_COUNT = 2;
    /** Control fixture: a comparable total spread evenly across calls (p95 ≈ mean). */
    private static final long UNIFORMLY_LARGE_RESULT_BYTES = 50_000L;
    private static final int UNIFORMLY_LARGE_CALL_COUNT = 12;

    /** Window spanning 60 minutes; the after-window row sits one hour past its end. */
    private Instant windowStart;
    private Instant windowEnd;

    @BeforeEach
    void seed() {
        logRecordRepository.deleteAll();
        windowEnd = Instant.now().minus(2, ChronoUnit.HOURS);
        windowStart = windowEnd.minus(WINDOW_MINUTES, ChronoUnit.MINUTES);

        saveToolResult(windowStart.plusSeconds(OFFSET_FIRST_GIT_CALL), TOOL_BASH, GIT_STATUS_TOOL_INPUT);
        saveToolResult(windowStart.plusSeconds(OFFSET_SECOND_GIT_CALL), TOOL_BASH, GIT_DIFF_TOOL_INPUT);
        saveToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_READ, null);

        // Sits AFTER windowEnd: must be excluded by the range form's upper bound.
        saveToolResult(windowEnd.plusSeconds(OFFSET_AFTER_WINDOW_END), TOOL_BASH, GIT_STATUS_TOOL_INPUT);
    }

    @Test
    void renderMarkdownInRangeRendersBashHotspotsForTheWindow() {
        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Two in-window git calls collapse onto the 'git' command prefix; the row after
        // windowEnd must not bump the count to 3 (the upper bound is real, not cosmetic).
        assertThat(markdown)
                .contains("## Bash command hotspots")
                .contains("| `git` | 2 |");
    }

    @Test
    void renderMarkdownInRangeCountsOnlyInWindowCallsInTheToolMix() {
        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        assertThat(markdown)
                .contains("| `Bash` | 2 |")
                .contains("| `Read` | 1 |");
    }

    @Test
    void renderMarkdownForMinutesWindowIncludesAllSeededCalls() {
        // The ?minutes= form ends at now, so all four seeded rows fall inside it.
        String markdown = reportService.renderMarkdown(WINDOW_MINUTES * 24);

        assertThat(markdown)
                .contains("## Bash command hotspots")
                .contains("| `git` | 3 |");
    }

    @Test
    void renderMarkdownStripsCdPrefixesFromBashHotspotBuckets() {
        saveToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_BASH,
                "{\"command\":\"cd backend && ./mvnw verify\"}");
        // Newline-chained form: `cd frontend` on its own line, real command on the next.
        saveToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), TOOL_BASH,
                "{\"command\":\"cd frontend\\nyarn build\"}");

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Both chained forms must bucket as the real command, not a 'cd' row, and the
        // coverage blurb must surface how many commands carried the stripped prefix.
        assertThat(markdown)
                .contains("| `./mvnw` | 1 |")
                .contains("| `yarn` | 1 |")
                .doesNotContain("| `cd` |")
                .contains("**2** of them carried a `cd` prefix");
    }

    @Test
    void renderMarkdownStripsCdPrefixesFromSlowAndLargeAndOversizedScopes() {
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_BASH,
                "{\"command\":\"cd backend && ./mvnw verify\"}", CD_PREFIXED_RESULT_BYTES);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // The oversized row and the slow-and-large suggestion must both show the command
        // that actually ran; the raw cd-prefixed form must not appear anywhere.
        assertThat(markdown)
                .contains("| `Bash` | `./mvnw verify` | " + CD_PREFIXED_RESULT_BYTES + " | 1 |")
                .contains("(scope `./mvnw verify`)")
                .doesNotContain("cd backend && ./mvnw verify");
    }

    @Test
    void renderMarkdownGroupsIdenticalOversizedRowsAndDedupesSuggestions() {
        String dumpPath = "/repo/big-dump.txt";
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_READ,
                "{\"file_path\":\"" + dumpPath + "\"}", GROUPED_RESULT_BYTES);
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), TOOL_READ,
                "{\"file_path\":\"" + dumpPath + "\"}", GROUPED_RESULT_BYTES);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Two identical reads collapse into one oversized row with an occurrence count,
        // and the suggestions section carries exactly one bullet for the path.
        assertThat(markdown)
                .contains("| `Read` | `" + dumpPath + "` | " + GROUPED_RESULT_BYTES + " | 2 |")
                .containsOnlyOnce("via `" + dumpPath + "`")
                .contains("returned " + GROUPED_RESULT_BYTES + " bytes × 2 calls via `" + dumpPath + "`");
    }

    @Test
    void renderMarkdownSplitsFailuresByRootCauseSignature() {
        saveFailedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_BASH,
                "{\"command\":\"gradle build\"}", "zsh: command not found: gradle", SESSION_A);
        saveFailedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), TOOL_READ,
                "{\"file_path\":\"/repo/missing.txt\"}", "File does not exist.", SESSION_A);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        assertThat(markdown)
                .contains("## Failures")
                .contains("`command-not-found`")
                .contains("`missing-path`");
    }

    @Test
    void renderMarkdownReportsPathNearMissesForTypoedReadPaths() {
        String typoedPath = "/scratch/ee81e89a-fecbb/review-diff.patch";
        String realPath = "/scratch/ee81e89a-fcdbb/review-diff.patch";
        saveFailedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), TOOL_READ,
                "{\"file_path\":\"" + typoedPath + "\"}", "File does not exist.", SESSION_A);
        saveToolResultForSession(windowStart.plusSeconds(OFFSET_READ_CALL + 5), TOOL_READ,
                "{\"file_path\":\"" + realPath + "\"}", SESSION_A);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        assertThat(markdown)
                .contains("## Path near-misses (likely typos)")
                .contains(typoedPath)
                .contains(realPath);
    }

    @Test
    void renderMarkdownExcludesExternallyDeterminedToolsAndImageReadsFromOversized() {
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), "Agent", null,
                AGENT_RESULT_BYTES);
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), TOOL_READ,
                "{\"file_path\":\"/repo/screenshot.png\"}", IMAGE_RESULT_BYTES);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // The tools still appear in the performance/mix tables (that's correct); only
        // their oversized-table rows must be gone. Match the exact row shapes.
        assertThat(markdown)
                .doesNotContain("| `Agent` | `` | " + AGENT_RESULT_BYTES + " |")
                .doesNotContain("`/repo/screenshot.png`");
    }

    @Test
    void renderMarkdownRanksContextFootprintOverTunableToolsOnly() {
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), "Agent", null,
                AGENT_RESULT_BYTES);
        saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), TOOL_READ,
                "{\"file_path\":\"/repo/screenshot.png\"}", IMAGE_RESULT_BYTES);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Seeded window: two Bash calls and one Read at SEEDED_RESULT_BYTES each. The Agent
        // result and the image read must not reach the ranking, which the total proves —
        // either one leaking in would dwarf it. Both tools still appear elsewhere in the
        // report (performance, mix), so the assertion is on the footprint's own arithmetic.
        assertThat(markdown)
                .contains("## Context footprint")
                .contains("| `Bash` | 2 | 8192 | 66.7% | 4096 | 4096 |")
                .contains("| `Read` | 1 | 4096 | 33.3% | 4096 | 4096 |")
                .contains("Total: **12288** bytes (~3072 tokens");
    }

    @Test
    void renderMarkdownRendersMcpServersSectionRolledUpPerServer() {
        saveMcpToolResult(windowStart.plusSeconds(OFFSET_READ_CALL), MCP_SERVER_PLAYWRIGHT,
                MCP_TOOL_BROWSER_EVALUATE, true, MCP_PLAYWRIGHT_DURATION_MS, MCP_PLAYWRIGHT_RESULT_BYTES);
        saveMcpToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 1), MCP_SERVER_CODE_GRAPH,
                MCP_TOOL_SEARCH, true, MCP_CODE_GRAPH_DURATION_MS, MCP_CODE_GRAPH_RESULT_BYTES);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // One row per (server, tool) collapses to one row per server here since each server has
        // exactly one tool in this fixture; p95 of a single-row group is that row's own duration.
        // This exercises the exact ReportService context-map keys against report.mustache's
        // placeholders end to end — a renamed key (e.g. p95Ms) would render this section blank
        // rather than fail, which no other test in this suite would catch.
        assertThat(markdown)
                .contains("## MCP servers")
                .contains("| `" + MCP_SERVER_PLAYWRIGHT + "` | 1 | 0.0% | " + MCP_PLAYWRIGHT_DURATION_MS
                        + " | " + MCP_PLAYWRIGHT_RESULT_BYTES + " | 80.0% |")
                .contains("| `" + MCP_SERVER_CODE_GRAPH + "` | 1 | 0.0% | " + MCP_CODE_GRAPH_DURATION_MS
                        + " | " + MCP_CODE_GRAPH_RESULT_BYTES + " | 20.0% |")
                .contains("Total: **5120** bytes (~1280 tokens");
    }

    @Test
    void renderMarkdownSuggestsNarrowingAnMcpServerWithHighFailureRate() {
        for (int callIndex = 0; callIndex < MCP_FLAKY_CALL_COUNT; callIndex++) {
            boolean failed = callIndex < MCP_FLAKY_FAILURE_COUNT;
            saveMcpToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + callIndex), MCP_SERVER_FLAKY,
                    MCP_TOOL_BROWSE, !failed, MCP_SUGGESTION_FIXTURE_DURATION_MS, MCP_SUGGESTION_FIXTURE_RESULT_BYTES);
        }
        for (int callIndex = 0; callIndex < MCP_RELIABLE_CALL_COUNT; callIndex++) {
            saveMcpToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 100 + callIndex), MCP_SERVER_RELIABLE,
                    MCP_TOOL_BROWSE, true, MCP_SUGGESTION_FIXTURE_DURATION_MS, MCP_SUGGESTION_FIXTURE_RESULT_BYTES);
        }

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // 30% failures trips MCP_SERVER_HIGH_FAILURE_RATE_THRESHOLD (10%); the all-success
        // control server, seeded with the same call volume and byte size, must not trip either
        // condition of the rule, isolating the failure-rate path from the context-share one.
        assertThat(markdown)
                .contains("MCP server `" + MCP_SERVER_FLAKY + "`")
                .contains("check whether it earns its cost")
                .doesNotContain("MCP server `" + MCP_SERVER_RELIABLE + "`");
    }

    @Test
    void renderMarkdownSuggestsCappingTheTailOnlyWhenP95OutrunsTheMean() {
        for (int callIndex = 0; callIndex < TAIL_SMALL_CALL_COUNT; callIndex++) {
            saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + callIndex), TOOL_READ,
                    null, TAIL_SMALL_RESULT_BYTES);
        }
        for (int callIndex = 0; callIndex < TAIL_BLOWOUT_CALL_COUNT; callIndex++) {
            saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 100 + callIndex), TOOL_READ,
                    null, TAIL_BLOWOUT_RESULT_BYTES);
        }
        for (int callIndex = 0; callIndex < UNIFORMLY_LARGE_CALL_COUNT; callIndex++) {
            saveOversizedToolResult(windowStart.plusSeconds(OFFSET_READ_CALL + 200 + callIndex), TOOL_BASH,
                    GIT_STATUS_TOOL_INPUT, UNIFORMLY_LARGE_RESULT_BYTES);
        }

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Read's total is carried by two blowouts among many small calls, so its p95 runs
        // an order of magnitude past its mean and the tail rule fires. Bash returns roughly
        // the same large payload every call — same order of total bytes, no tail to cap —
        // so it must not produce the bullet, or the rule would just be "big tool is big".
        assertThat(markdown)
                .contains("`Read` results average")
                .contains("cap the tail")
                .doesNotContain("`Bash` results average");
    }

    @Test
    void renderMarkdownSuggestsOnlyHuntingLoopRedundantReads() {
        String huntedPath = "/repo/hunted.ts";
        String spreadPath = "/repo/spread.ts";
        for (int readIndex = 0; readIndex < 3; readIndex++) {
            saveToolResultForSession(windowStart.plusSeconds(OFFSET_READ_CALL + readIndex * 30L), TOOL_READ,
                    "{\"file_path\":\"" + huntedPath + "\"}", SESSION_A);
        }
        saveToolResultForSession(windowStart, TOOL_READ,
                "{\"file_path\":\"" + spreadPath + "\"}", SESSION_A);
        saveToolResultForSession(windowStart.plusSeconds(SPREAD_GAP_SECONDS), TOOL_READ,
                "{\"file_path\":\"" + spreadPath + "\"}", SESSION_A);

        String markdown = reportService.renderMarkdownInRange(windowStart, windowEnd);

        // Both files appear in the redundant-reads table, but only the hunting loop may
        // generate a suggestion bullet.
        assertThat(markdown)
                .contains("| `" + spreadPath + "` |")
                .contains("`" + huntedPath + "` was re-read 3 times")
                .doesNotContain("`" + spreadPath + "` was re-read");
    }

    private void saveToolResult(Instant timestamp, String toolName, String toolInputJson) {
        saveToolResultRow(timestamp, toolName, toolInputJson, true, null, null, 4096);
    }

    private void saveToolResultForSession(Instant timestamp, String toolName, String toolInputJson, String sessionId) {
        saveToolResultRow(timestamp, toolName, toolInputJson, true, null, sessionId, 4096);
    }

    private void saveFailedToolResult(Instant timestamp, String toolName, String toolInputJson, String errorMessage,
            String sessionId) {
        saveToolResultRow(timestamp, toolName, toolInputJson, false, errorMessage, sessionId, 4096);
    }

    private void saveOversizedToolResult(Instant timestamp, String toolName, String toolInputJson, long resultBytes) {
        saveToolResultRow(timestamp, toolName, toolInputJson, true, null, null, resultBytes);
    }

    /**
     * Seeds an MCP-shaped {@code tool_result} row: {@code tool_name} is the generic
     * {@link #MCP_TOOL_NAME} constant every server shares, with real server/tool identity carried
     * in the {@link #ATTR_TOOL_PARAMETERS} attribute as a JSON *string* (matching the stringified
     * blob {@code aggregateMcpServerUsageInRange}'s {@code ::jsonb} cast depends on) rather than a
     * nested object. Duration and result size are bound directly, unlike {@link #saveToolResultRow}
     * which hardcodes both, since the MCP suggestion-rule test needs both varied per call.
     */
    private void saveMcpToolResult(Instant timestamp, String server, String tool, boolean success,
            long durationMs, long resultBytes) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool result");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, MCP_TOOL_NAME);
        attributes.put(ATTR_TOOL_PARAMETERS,
                "{\"mcp_server_name\":\"" + server + "\",\"mcp_tool_name\":\"" + tool + "\"}");
        attributes.put(ATTR_SUCCESS, String.valueOf(success));
        attributes.put(ATTR_DURATION_MS, durationMs);
        attributes.put(ATTR_RESULT_SIZE_BYTES, resultBytes);
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }

    private void saveToolResultRow(Instant timestamp, String toolName, String toolInputJson, boolean success,
            String errorMessage, String sessionId, long resultBytes) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool result");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, toolName);
        attributes.put(ATTR_SUCCESS, String.valueOf(success));
        attributes.put(ATTR_DURATION_MS, 1200);
        attributes.put(ATTR_RESULT_SIZE_BYTES, resultBytes);
        if (toolInputJson != null) {
            attributes.put(ATTR_TOOL_INPUT, toolInputJson);
        }
        if (errorMessage != null) {
            attributes.put(ATTR_ERROR, errorMessage);
        }
        if (sessionId != null) {
            attributes.put(ATTR_SESSION_ID, sessionId);
        }
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }
}
