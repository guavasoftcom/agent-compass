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
import com.guavasoft.agentcompass.model.McpServerUsage;
import com.guavasoft.agentcompass.model.OversizedToolResult;
import com.guavasoft.agentcompass.model.SlowAndLargeCall;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailure;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolLatency;
import com.guavasoft.agentcompass.repository.LogRecordRepository;
import com.guavasoft.agentcompass.repository.SpanRepository;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the MCP server-visibility split against a real Postgres instance: the new
 * {@code aggregateMcpServerUsageInRange} aggregation, and the 14
 * {@link LogRecordRepository} query methods (plus the 2 {@link SpanRepository} ones,
 * covered separately below) that now split the collapsed {@code mcp_tool} bucket back out
 * by server via {@code MCP_AWARE_TOOL_EXPRESSION}.
 *
 * <p>No existing fixture uses an MCP-shaped tool name, so every row here is seeded fresh.
 * {@code tool_parameters} is stored as a JSON-encoded STRING (matching what Claude Code
 * actually emits and what the {@code ::jsonb} cast in the query depends on), not a nested
 * object.
 */
@SpringBootTest
@Testcontainers
class McpServerUsageQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    LogRecordRepository logRecordRepository;

    @Autowired
    SpanRepository spanRepository;

    @Autowired
    LogService logService;

    @Autowired
    TraceService traceService;

    private static final String ATTR_EVENT_NAME = "event.name";
    private static final String ATTR_TOOL_NAME = "tool_name";
    private static final String ATTR_TOOL_PARAMETERS = "tool_parameters";
    private static final String ATTR_SUCCESS = "success";
    private static final String ATTR_DURATION_MS = "duration_ms";
    private static final String ATTR_RESULT_SIZE_BYTES = "tool_result_size_bytes";
    private static final String ATTR_ERROR_TYPE = "error_type";
    private static final String ATTR_ERROR = "error";
    private static final String ATTR_DECISION = "decision";
    private static final String ATTR_SOURCE = "source";
    private static final String ATTR_TOOL_INPUT = "tool_input";

    private static final String EVENT_TOOL_RESULT = "tool_result";
    private static final String EVENT_TOOL_DECISION = "tool_decision";

    // Mirrors TuningProperties' defaults, the same convention SkillSubagentUsageQueryIntegrationTest uses.
    private static final String MCP_TOOL_NAME = "mcp_tool";
    private static final String TOOL_SPAN_SCOPE = "com.anthropic.claude_code.tracing";
    private static final String TOOL_SPAN_NAME = "claude_code.tool";

    private static final String SERVER_PLAYWRIGHT = "playwright";
    private static final String SERVER_CODE_GRAPH = "CodeGraphContext";
    private static final String TOOL_BROWSER_EVALUATE = "browser_evaluate";
    private static final String TOOL_QUERY = "query";
    private static final String TOOL_BASH = "Bash";

    private static final long PLAYWRIGHT_SUCCESS_ONE_BYTES = 2_000L;
    private static final long PLAYWRIGHT_SUCCESS_TWO_BYTES = 6_000L;
    private static final long PLAYWRIGHT_SUCCESS_TWO_DURATION_MS = 3_000L;
    private static final long CODE_GRAPH_BYTES = 500L;
    private static final long UNKNOWN_ROW_BYTES = 300L;

    private Instant windowStart;
    private Instant windowEnd;

    @BeforeEach
    void seed() {
        logRecordRepository.deleteAll();
        spanRepository.deleteAll();
        windowStart = Instant.now().minus(60, ChronoUnit.MINUTES);
        windowEnd = Instant.now();

        // Two successful playwright calls, one failure, all the same server-side tool.
        saveMcpToolResult(60, SERVER_PLAYWRIGHT, TOOL_BROWSER_EVALUATE, true, 1_000L,
                PLAYWRIGHT_SUCCESS_ONE_BYTES, null, null);
        saveMcpToolResult(120, SERVER_PLAYWRIGHT, TOOL_BROWSER_EVALUATE, true,
                PLAYWRIGHT_SUCCESS_TWO_DURATION_MS, PLAYWRIGHT_SUCCESS_TWO_BYTES, null, null);
        saveMcpToolResult(180, SERVER_PLAYWRIGHT, TOOL_BROWSER_EVALUATE, false, 500L, null,
                "ToolError", "Some evaluate error");

        // A second, unrelated server — confirms grouping is per (server, tool), not just "any MCP".
        saveMcpToolResult(240, SERVER_CODE_GRAPH, TOOL_QUERY, true, 200L, CODE_GRAPH_BYTES, null, null);

        // Blank tool_parameters: must bucket under 'unknown', not be dropped.
        saveToolResult(300, MCP_TOOL_NAME, "", true, 100L, UNKNOWN_ROW_BYTES, null, null, null);

        // Non-MCP control row: must never appear split or affected by the MCP expression.
        saveToolResult(360, TOOL_BASH, null, true, 50L, 100L, null, null, "{\"command\":\"git status\"}");

        // A permission denial for the same MCP server.
        saveMcpToolDecision(420, SERVER_PLAYWRIGHT, TOOL_BROWSER_EVALUATE, "user_reject");

        // Span-side fixture: two raw mcp__playwright__* span names that must collapse to one
        // 'mcp:playwright' row, plus an unrelated control span.
        saveToolSpan(480, "mcp__playwright__browser_evaluate", 1_000_000_000L);
        saveToolSpan(540, "mcp__playwright__browser_click", 2_000_000_000L);
        saveToolSpan(600, TOOL_BASH, 500_000_000L);
    }

    @Test
    void mcpServerUsageSplitsCallsByServerAndTool() {
        List<McpServerUsage> rows = logService.aggregateMcpServerUsageInRange(windowStart, windowEnd);

        assertThat(rows).extracting(McpServerUsage::server)
                .contains(SERVER_PLAYWRIGHT, SERVER_CODE_GRAPH, "unknown");

        McpServerUsage playwright = singleRowFor(rows, SERVER_PLAYWRIGHT);
        assertThat(playwright.tool()).isEqualTo(TOOL_BROWSER_EVALUATE);
        assertThat(playwright.calls()).isEqualTo(3L);
        assertThat(playwright.failures()).isEqualTo(1L);
        assertThat(playwright.failureRate()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(playwright.totalBytes())
                .isEqualTo(PLAYWRIGHT_SUCCESS_ONE_BYTES + PLAYWRIGHT_SUCCESS_TWO_BYTES);
        assertThat(playwright.estimatedTokens()).isEqualTo(playwright.totalBytes() / 4);

        McpServerUsage codeGraph = singleRowFor(rows, SERVER_CODE_GRAPH);
        assertThat(codeGraph.calls()).isEqualTo(1L);
        assertThat(codeGraph.failures()).isZero();
        assertThat(codeGraph.totalBytes()).isEqualTo(CODE_GRAPH_BYTES);
    }

    @Test
    void blankToolParametersBucketsUnderUnknownRatherThanBeingDropped() {
        List<McpServerUsage> rows = logService.aggregateMcpServerUsageInRange(windowStart, windowEnd);

        McpServerUsage unknown = singleRowFor(rows, "unknown");
        assertThat(unknown.tool()).isEqualTo("unknown");
        assertThat(unknown.calls()).isEqualTo(1L);
        assertThat(unknown.totalBytes()).isEqualTo(UNKNOWN_ROW_BYTES);
    }

    @Test
    void aggregateToolCallsSplitsMcpRowsByServerInsteadOfCollapsing() {
        List<ToolCallCount> rows = logService.aggregateToolCallsInRange(windowStart, windowEnd);

        assertThat(rows).extracting(ToolCallCount::getTool)
                .contains("mcp:" + SERVER_PLAYWRIGHT, "mcp:" + SERVER_CODE_GRAPH, "mcp:unknown", TOOL_BASH)
                .doesNotContain(MCP_TOOL_NAME);
        assertThat(callsFor(rows, "mcp:" + SERVER_PLAYWRIGHT)).isEqualTo(3L);
        assertThat(callsFor(rows, "mcp:" + SERVER_CODE_GRAPH)).isEqualTo(1L);
        assertThat(callsFor(rows, TOOL_BASH)).isEqualTo(1L);
    }

    @Test
    void aggregateToolFailureRatesSplitsFailuresByServer() {
        List<ToolFailureRate> rows = logService.aggregateToolFailureRatesInRange(windowStart, windowEnd);

        ToolFailureRate playwright = rows.stream()
                .filter(row -> ("mcp:" + SERVER_PLAYWRIGHT).equals(row.tool()))
                .findFirst().orElseThrow();
        assertThat(playwright.calls()).isEqualTo(3L);
        assertThat(playwright.failures()).isEqualTo(1L);
    }

    @Test
    void aggregateToolFailuresInRangeAttributesRootCauseToTheNamedServer() {
        List<ToolFailure> rows = logService.aggregateToolFailuresInRange(windowStart, windowEnd);

        assertThat(rows).extracting(ToolFailure::tool).contains("mcp:" + SERVER_PLAYWRIGHT);
        ToolFailure playwrightFailure = rows.stream()
                .filter(row -> ("mcp:" + SERVER_PLAYWRIGHT).equals(row.tool()))
                .findFirst().orElseThrow();
        assertThat(playwrightFailure.errorType()).isEqualTo("ToolError");
        assertThat(playwrightFailure.count()).isEqualTo(1L);
    }

    @Test
    void aggregateToolDenialsInRangeSplitsMcpDenialsByServer() {
        List<ToolDenialCount> rows = logService.aggregateToolDenialsInRange(windowStart, windowEnd);

        assertThat(rows).anySatisfy(row -> {
            assertThat(row.tool()).isEqualTo("mcp:" + SERVER_PLAYWRIGHT);
            assertThat(row.source()).isEqualTo("user_reject");
            assertThat(row.count()).isEqualTo(1L);
        });
    }

    @Test
    void aggregateToolContextFootprintInRangeSplitsBytesByServer() {
        List<ToolContextFootprint> rows = logService.aggregateToolContextFootprintInRange(windowStart, windowEnd);

        ToolContextFootprint playwright = rows.stream()
                .filter(row -> ("mcp:" + SERVER_PLAYWRIGHT).equals(row.tool()))
                .findFirst().orElseThrow();
        // Only the two successful calls report a size; the failure is excluded, same as any tool.
        assertThat(playwright.calls()).isEqualTo(2L);
        assertThat(playwright.totalBytes())
                .isEqualTo(PLAYWRIGHT_SUCCESS_ONE_BYTES + PLAYWRIGHT_SUCCESS_TWO_BYTES);
    }

    @Test
    void aggregateOversizedToolResultsInRangeUsesMcpToolNameAsScope() {
        List<OversizedToolResult> rows = logService.aggregateOversizedToolResultsInRange(windowStart, windowEnd);

        // The largest seeded row is the second playwright success call.
        OversizedToolResult largest = rows.get(0);
        assertThat(largest.tool()).isEqualTo("mcp:" + SERVER_PLAYWRIGHT);
        assertThat(largest.scope()).isEqualTo(TOOL_BROWSER_EVALUATE);
        assertThat(largest.bytes()).isEqualTo(PLAYWRIGHT_SUCCESS_TWO_BYTES);
    }

    @Test
    void aggregateSlowAndLargeCallsInRangeUsesMcpToolNameAsScope() {
        List<SlowAndLargeCall> rows = logService.aggregateSlowAndLargeCallsInRange(windowStart, windowEnd);

        assertThat(rows).anySatisfy(call -> {
            assertThat(call.tool()).isEqualTo("mcp:" + SERVER_PLAYWRIGHT);
            assertThat(call.scope()).isEqualTo(TOOL_BROWSER_EVALUATE);
            assertThat(call.durationMs()).isEqualTo(PLAYWRIGHT_SUCCESS_TWO_DURATION_MS);
            assertThat(call.bytes()).isEqualTo(PLAYWRIGHT_SUCCESS_TWO_BYTES);
        });
    }

    @Test
    void spanAggregateToolLatencyInRangeCollapsesRawMcpSpanNamesToOneRowPerServer() {
        List<ToolLatency> rows = traceService.aggregateToolLatencyInRange(windowStart, windowEnd);

        assertThat(rows).extracting(ToolLatency::tool)
                .contains("mcp:" + SERVER_PLAYWRIGHT, TOOL_BASH)
                .doesNotContain("mcp__playwright__browser_evaluate", "mcp__playwright__browser_click");
        ToolLatency playwright = rows.stream()
                .filter(row -> ("mcp:" + SERVER_PLAYWRIGHT).equals(row.tool()))
                .findFirst().orElseThrow();
        assertThat(playwright.calls()).isEqualTo(2L);
    }

    private static McpServerUsage singleRowFor(List<McpServerUsage> rows, String server) {
        return rows.stream().filter(row -> server.equals(row.server())).findFirst().orElseThrow();
    }

    private static long callsFor(List<ToolCallCount> rows, String tool) {
        return rows.stream().filter(row -> tool.equals(row.getTool())).findFirst().orElseThrow().getCalls();
    }

    private static String mcpParametersJson(String server, String tool) {
        return "{\"mcp_server_name\":\"" + server + "\",\"mcp_tool_name\":\"" + tool + "\"}";
    }

    private void saveMcpToolResult(
            int offsetSeconds, String server, String tool, boolean success, Long durationMs, Long resultBytes,
            String errorType, String errorMessage) {
        saveToolResult(offsetSeconds, MCP_TOOL_NAME, mcpParametersJson(server, tool), success, durationMs,
                resultBytes, errorType, errorMessage, null);
    }

    private void saveMcpToolDecision(int offsetSeconds, String server, String tool, String source) {
        Instant timestamp = windowStart.plusSeconds(offsetSeconds);
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool decision");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_DECISION);
        attributes.put(ATTR_TOOL_NAME, MCP_TOOL_NAME);
        attributes.put(ATTR_TOOL_PARAMETERS, mcpParametersJson(server, tool));
        attributes.put(ATTR_DECISION, "reject");
        attributes.put(ATTR_SOURCE, source);
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }

    private void saveToolResult(
            int offsetSeconds, String toolName, String toolParametersJson, boolean success, Long durationMs,
            Long resultBytes, String errorType, String errorMessage, String toolInputJson) {
        Instant timestamp = windowStart.plusSeconds(offsetSeconds);
        LogRecordEntity entity = new LogRecordEntity();
        entity.setTimestamp(timestamp);
        entity.setObservedTimestamp(timestamp);
        entity.setReceivedAt(Instant.now());
        entity.setScopeName("claude_code.tools");
        entity.setBody("tool result");

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(ATTR_EVENT_NAME, EVENT_TOOL_RESULT);
        attributes.put(ATTR_TOOL_NAME, toolName);
        if (toolParametersJson != null) {
            attributes.put(ATTR_TOOL_PARAMETERS, toolParametersJson);
        }
        attributes.put(ATTR_SUCCESS, String.valueOf(success));
        if (durationMs != null) {
            attributes.put(ATTR_DURATION_MS, durationMs);
        }
        if (resultBytes != null) {
            attributes.put(ATTR_RESULT_SIZE_BYTES, resultBytes);
        }
        if (errorType != null) {
            attributes.put(ATTR_ERROR_TYPE, errorType);
        }
        if (errorMessage != null) {
            attributes.put(ATTR_ERROR, errorMessage);
        }
        if (toolInputJson != null) {
            attributes.put(ATTR_TOOL_INPUT, toolInputJson);
        }
        entity.setAttributes(attributes);
        entity.setResourceAttributes(Map.of("service.name", "claude-code"));
        logRecordRepository.save(entity);
    }

    private void saveToolSpan(int offsetSeconds, String rawToolName, long durationNanos) {
        Instant start = windowStart.plusSeconds(offsetSeconds);
        String traceId = String.format("%032x", offsetSeconds);
        String spanId = String.format("%016x", offsetSeconds);

        SpanEntity span = new SpanEntity();
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setName(TOOL_SPAN_NAME);
        span.setKind("internal");
        span.setStartTimestamp(start);
        span.setEndTimestamp(start.plusNanos(durationNanos));
        span.setDurationNanos(durationNanos);
        span.setStatusCode("ok");
        span.setScopeName(TOOL_SPAN_SCOPE);
        span.setAttributes(Map.of(ATTR_TOOL_NAME, rawToolName));
        span.setReceivedAt(Instant.now());
        spanRepository.save(span);
    }
}
