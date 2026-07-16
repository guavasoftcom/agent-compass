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

    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String TOOL_BASH = "Bash";
    private static final String TOOL_READ = "Read";

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
