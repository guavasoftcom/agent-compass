package com.guavasoft.agentcompass.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.guavasoft.agentcompass.model.HookExecutionSummary;
import com.guavasoft.agentcompass.model.IdentifierUsageCount;
import com.guavasoft.agentcompass.model.McpServerUsage;
import com.guavasoft.agentcompass.model.TimeWindowParams;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolCallTimeseries;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolLatency;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/tool-activity")
@Tag(name = "Tool Activity",
        description = "Tool call counts, latency, failure rates, denials, repeats, skill, subagent, and "
                + "MCP server usage, and hook executions")
public class ToolActivityController {

    private final LogService logService;
    private final TraceService traceService;

    @GetMapping("/calls")
    @Operation(
            summary = "Aggregate tool call counts grouped by tool name over the window",
            description = "Counts log records whose attribute event.name matches the configured "
                    + "tool-event name (default tool_result), grouped by the tool-name attribute. "
                    + "Includes every tool invocation, not just code-edit tool decisions. "
                    + "Rows are sorted descending by call count.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct tool seen in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolCallCount.class)))))
    public List<ToolCallCount> toolCalls(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolCallsInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return logService.aggregateToolCalls(minutes);
    }

    @GetMapping("/calls/timeseries")
    @Operation(
            summary = "Tool call counts bucketed over time, one stacked series per tool",
            description = "Same population as /metrics/tool-calls but split into ~40 equal-width "
                    + "buckets across the window. Tools beyond topN are folded into a synthetic "
                    + "'Other' series so the chart remains readable. Bucket width is chosen by the "
                    + "server; the response carries it so the client can render axes consistently.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Bucketed counts plus the tool column ordering",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ToolCallTimeseries.class))))
    public ToolCallTimeseries toolCallsTimeseries(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Parameter(description = "Maximum number of distinct tool series before overflow folds "
                    + "into 'Other'", example = "8") @RequestParam(defaultValue = "8") int topN,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolCallsTimeseriesInRange(
                    timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp(), topN);
        }
        return logService.aggregateToolCallsTimeseries(minutes, topN);
    }

    @GetMapping("/calls/latency")
    @Operation(
            summary = "Per-tool latency percentiles over the window",
            description = "Computes p50 / p95 of duration_nanos across spans tagged with the "
                    + "configured tool instrumentation scope (default 'claude-code.tools'). Span name "
                    + "is the tool identifier. Rows are sorted by p95 descending.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct tool span name in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolLatency.class)))))
    public List<ToolLatency> toolCallLatency(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return traceService.aggregateToolLatencyInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return traceService.aggregateToolLatency(minutes);
    }

    @GetMapping("/context-footprint")
    @Operation(
            summary = "Per-tool context-window footprint over the window",
            description = "For each tool, the summed and P95 tool_result_size_bytes plus an "
                    + "estimated one-time context-token figure (bytes / 4). Answers 'what is "
                    + "filling my context window', which is why it excludes nothing: externally "
                    + "determined tools (Agent, WebFetch) and image reads are counted here even "
                    + "though the tuning report's oversized-results list skips them, since they "
                    + "occupy the window like anything else. Failed calls count too wherever they "
                    + "reported a size — error output is context. Calls with no size attribute at "
                    + "all are excluded, so `calls` is legitimately lower than the count on "
                    + "/api/tool-activity/calls. estimatedTokens is an ESTIMATE for ranking tools "
                    + "against each other; it is not billed spend, is not comparable to "
                    + "/api/sessions/token-usage, and understates real cost because a result is "
                    + "re-sent with every later request in its session. Sorted by total bytes "
                    + "descending.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct tool that reported at least one result size",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolContextFootprint.class)))))
    public List<ToolContextFootprint> toolContextFootprint(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolContextFootprintInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return logService.aggregateToolContextFootprint(minutes);
    }

    @GetMapping("/failure-rates")
    @Operation(
            summary = "Per-tool execution failure rate over the window",
            description = "For each tool, returns total tool_result events and the subset where the "
                    + "success attribute is 'false', plus the derived failure_rate in [0.0, 1.0]. "
                    + "Population is the same as /metrics/tool-calls — denied-at-hook invocations never "
                    + "emit a tool_result and are not counted. Rows are sorted descending by failures, "
                    + "then calls.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct tool seen in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolFailureRate.class)))))
    public List<ToolFailureRate> toolFailureRates(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolFailureRatesInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return logService.aggregateToolFailureRates(minutes);
    }

    @GetMapping("/denials")
    @Operation(
            summary = "Tool permission denials grouped by tool and source over the window",
            description = "Counts claude_code.tool_decision events where decision='reject', grouped by "
                    + "tool_name and source. Source identifies who denied the tool: 'config' means a "
                    + "settings.json rule blocked it, 'hook' means a hook script returned non-zero, and "
                    + "'user_*' values mean the user declined at the prompt. Rows are sorted descending by "
                    + "count.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct (tool, source) pair seen in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolDenialCount.class)))))
    public List<ToolDenialCount> toolDenials(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolDenialsInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return logService.aggregateToolDenials(minutes);
    }

    @GetMapping("/repeats")
    @Operation(
            summary = "Consecutive same-tool same-scope run-length stats per (tool, scope) over the window",
            description = "For each session, detects consecutive runs of identical (tool, scope) "
                    + "tool_result events ordered by timestamp, then per session takes the longest run for "
                    + "each (tool, scope). Those per-session longest-run values are rolled up into a median "
                    + "and a max per (tool, scope). Sessions whose longest run was just one call are dropped "
                    + "— they are not repeats. Scope is file_path for Edit / Write / Read / MultiEdit; for "
                    + "Bash it's the command with any leading 'cd <path> &&' chain stripped, then its first "
                    + "two whitespace-delimited tokens, so 'cd x && git status' and 'cd y && git commit' "
                    + "resolve to distinct scopes instead of both collapsing to 'cd'. Runs where scope "
                    + "couldn't be determined (any other tool) are dropped rather than reported under a "
                    + "shared '(no scope)' bucket, since that shared value isn't evidence the calls repeated "
                    + "the same action. Long Edit-Edit-Edit chains on the same file mean the agent is "
                    + "hunting; AGENTS.md can encourage 'read the file once, plan all changes, then write in "
                    + "a single pass'.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "Up to 15 (tool, scope) rows sorted by max run length descending",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ToolRepeatStat.class)))))
    public List<ToolRepeatStat> toolRepeats(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateToolRepeatsInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return logService.aggregateToolRepeats(minutes);
    }

    @GetMapping("/skill-usage")
    @Operation(
            summary = "Per-skill invocation counts over the window, split by model",
            description = "Counts skill invocations, grouped by the skill identifier attribute. One "
                    + "invocation is one prompt that entered the skill, NOT one api_request: Claude "
                    + "Code stamps the skill attribute on every model call made while the skill runs, "
                    + "so the population is api_request events carrying that attribute, deduplicated by "
                    + "prompt id, with turns made inside subagents the skill spawned excluded. Counting "
                    + "those rows raw reports how chatty a skill is rather than how often it ran. "
                    + "Skills that never trigger have descriptions that don't match what the agent looks "
                    + "for. The 'tool' field carries the skill identifier; 'byModel' splits the count by "
                    + "the model that ran the skill, read straight off the same log records and taken "
                    + "from each invocation's earliest turn so the split sums to the row total.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct skill observed in the window, sorted by calls desc",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = IdentifierUsageCount.class)))))
    public List<IdentifierUsageCount> skillUsage(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateSkillUsageInRange(timeWindowParams.startTimestamp(), timeWindowParams.endTimestamp());
        }
        return logService.aggregateSkillUsage(minutes);
    }

    @GetMapping("/subagent-usage")
    @Operation(
            summary = "Per-subagent invocation counts over the window, split by dispatching model",
            description = "Counts tool_result events for the configured subagent-dispatch tool "
                    + "(default 'Agent'), grouped by the subagent-type attribute (with fallback to the "
                    + "tool_input field of the same name). Subagents that get called for trivial work "
                    + "belong as inline tool sequences in AGENTS.md instead. The 'tool' field carries "
                    + "the subagent identifier; 'byModel' splits the count by the model that dispatched "
                    + "the call. Those tool_result rows carry no model attribute, so the model comes "
                    + "from the last main-loop api_request in the same session at or before the call — "
                    + "the turn that emitted the tool_use. Calls whose dispatching turn is not in the "
                    + "data bucket under an 'unknown' model. A dispatch that named no subagent type at "
                    + "all is credited to the default agent type (the one such a dispatch actually runs "
                    + "on), not to an 'unknown' identifier.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct subagent observed in the window, sorted by calls desc",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = IdentifierUsageCount.class)))))
    public List<IdentifierUsageCount> subagentUsage(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateSubagentUsageInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return logService.aggregateSubagentUsage(minutes);
    }

    @GetMapping("/mcp-usage")
    @Operation(
            summary = "Per-(server, tool) MCP invocation stats over the window",
            description = "Counts tool_result events whose tool_name equals the shared MCP constant "
                    + "('mcp_tool'), grouped by the real server and tool identity carried inside the "
                    + "tool_parameters JSON attribute (tool_name itself is one constant for every MCP "
                    + "server, so it cannot be the group key). Latency and byte figures come from the "
                    + "log's own duration_ms / tool_result_size_bytes, not span duration — span duration "
                    + "on an MCP call includes time blocked on user approval, which would conflate server "
                    + "slowness with approval latency. estimatedTokens follows the same ranking-only "
                    + "convention as /api/tool-activity/context-footprint. Rows for one server are "
                    + "grouped together, sorted by that server's total calls descending.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct (server, tool) pair observed in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = McpServerUsage.class)))))
    public List<McpServerUsage> mcpUsage(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateMcpServerUsageInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return logService.aggregateMcpServerUsage(minutes);
    }

    @GetMapping("/hook-executions")
    @Operation(
            summary = "Hook execution outcome summary grouped by (hookEvent, hookName) over the window",
            description = "Aggregates claude_code.hook_execution_complete events, summing the num_* "
                    + "counters per (hook_event, hook_name) pair. blockingErrors is the key signal — "
                    + "non-zero rows represent hooks that actively prevented tool execution. Rows are sorted "
                    + "descending by blockingErrors, then total.")
    @ApiResponses(@ApiResponse(
            responseCode = "200",
            description = "One row per distinct (hookEvent, hookName) pair seen in the window",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = HookExecutionSummary.class)))))
    public List<HookExecutionSummary> hookExecutions(
            @Parameter(description = "Window size in minutes", example = "1440") @RequestParam(defaultValue = "1440") int minutes,
            @Valid @ModelAttribute TimeWindowParams timeWindowParams) {
        if (timeWindowParams.startTimestamp() != null && timeWindowParams.endTimestamp() != null) {
            return logService.aggregateHookExecutionsInRange(timeWindowParams.startTimestamp(),
                    timeWindowParams.endTimestamp());
        }
        return logService.aggregateHookExecutions(minutes);
    }
}
