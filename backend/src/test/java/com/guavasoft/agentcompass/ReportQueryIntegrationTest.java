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

    private void saveToolResult(Instant timestamp, String toolName, String toolInputJson) {
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool result");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, toolName);
        attributes.put(ATTR_SUCCESS, "true");
        attributes.put(ATTR_DURATION_MS, 1200);
        attributes.put(ATTR_RESULT_SIZE_BYTES, 4096);
        if (toolInputJson != null) {
            attributes.put(ATTR_TOOL_INPUT, toolInputJson);
        }
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }
}
