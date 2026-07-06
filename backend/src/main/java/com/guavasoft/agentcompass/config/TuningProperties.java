package com.guavasoft.agentcompass.config;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tuning")
public class TuningProperties {

  /**
   * Value of the OTLP log attribute {@code event.name} that marks a single tool
   * invocation.
   * Claude Code emits one such log per tool result.
   */
  private String toolEventName = "tool_result";

  /** Attribute key on the log record that identifies which tool was called. */
  private String toolAttribute = "tool_name";

  /**
   * OTLP instrumentation scope name for spans that represent a tool invocation.
   * Used to
   * compute per-tool latency from span durations on the Tool Calls page.
   */
  private String toolSpanScope = "com.anthropic.claude_code.tracing";

  /**
   * Span name (within {@link #toolSpanScope}) that wraps a single tool
   * invocation.
   */
  private String toolSpanName = "claude_code.tool";

  /**
   * Span name for the leaf span that times a single tool execution. Distinct from
   * {@link #toolSpanName}: the {@code claude_code.tool} wrapper and this execution
   * child share a {@link #toolCallIdAttribute}, so trace-detail log correlation
   * indexes only this leaf to avoid attaching tool logs to the wrapper span.
   */
  private String toolExecutionSpanName = "claude_code.tool.execution";

  /**
   * Span name for the leaf span that times a single LLM request. Carries
   * {@link #requestIdAttribute}, which {@code api_request*} logs share for
   * trace-detail correlation.
   */
  private String llmRequestSpanName = "claude_code.llm_request";

  /**
   * Attribute key shared by tool logs ({@code tool_result} / {@code tool_decision})
   * and the {@link #toolExecutionSpanName} span. Claude Code stamps most tool logs
   * with the coarse interaction-root span id; trace-detail correlation re-points
   * them onto the exact execution span by matching this key.
   */
  private String toolCallIdAttribute = "tool_use_id";

  /**
   * Attribute key shared by {@code api_request*} logs and the
   * {@link #llmRequestSpanName} span. Used the same way as
   * {@link #toolCallIdAttribute} to re-point LLM logs onto the exact request span.
   */
  private String requestIdAttribute = "request_id";

  /**
   * Value of {@code event.name} for the LLM-request log that carries
   * {@link #requestIdAttribute}. Used to pair an {@link #apiRequestBodyEventName}
   * log (which lacks its own request id) to its request via event ordering.
   */
  private String apiRequestEventName = "api_request";

  /**
   * Value of {@code event.name} for the LLM request-payload log. It carries only
   * {@link #promptIdAttribute} (turn-level, not request-level) and no request id,
   * so trace-detail correlation recovers its request id from the
   * {@link #apiRequestEventName} that immediately follows it in
   * {@link #eventSequenceAttribute} order within the same prompt.
   */
  private String apiRequestBodyEventName = "api_request_body";

  /**
   * Attribute key carrying the per-turn prompt identifier. One prompt spans many
   * LLM requests, so this scopes the body-to-request pairing but cannot identify a
   * single request on its own.
   */
  private String promptIdAttribute = "prompt.id";

  /**
   * Attribute key carrying Claude Code's monotonic per-session event counter. Gives
   * the authoritative ordering used to pair an {@link #apiRequestBodyEventName} log
   * with the {@link #apiRequestEventName} that follows it.
   */
  private String eventSequenceAttribute = "event.sequence";

  /**
   * OTLP metric name carrying per-turn token counts. Claude Code emits one data
   * point per
   * (session, type) on every turn — {@link #tokenTypeAttribute} carries the type.
   */
  private String tokenUsageMetric = "claude_code.token.usage";

  /**
   * Attribute key on the token-usage metric whose value identifies the token
   * bucket.
   */
  private String tokenTypeAttribute = "type";

  /**
   * {@link #toolAttribute} value that marks a tool_result emitted by the Skill
   * dispatcher.
   */
  private String skillToolName = "Skill";

  /**
   * Attribute key (or tool_input JSON field) carrying the skill identifier. The
   * repository
   * looks for it as a flat attribute first, then falls back to
   * {@code tool_input.<key>}.
   */
  private String skillNameAttribute = "skill.name";

  /**
   * Value of the OTLP log attribute {@code event.name} emitted when a skill is
   * invoked. Claude Code emits {@code api_request} (not {@code tool_result}) for
   * skill calls; the skill identifier is carried in {@link #skillNameAttribute}.
   */
  private String skillEventName = "api_request";

  /**
   * {@link #toolAttribute} value that marks a tool_result emitted by a subagent
   * dispatch.
   */
  private String subagentToolName = "Agent";

  /**
   * Attribute key (or tool_input JSON field) carrying the subagent identifier.
   * Same lookup
   * strategy as {@link #skillNameAttribute}.
   */
  private String subagentTypeAttribute = "subagent_type";

  /**
   * OTLP metric name carrying per-(session, model, query_source) cumulative USD
   * spend. The
   * Sessions aggregation takes MAX per stream then sums across streams.
   */
  private String costUsageMetric = "claude_code.cost.usage";

  /**
   * OTLP metric name carrying per-(session, model, query_source) cumulative
   * active-time
   * seconds. Same MAX-per-stream-then-sum aggregation as
   * {@link #costUsageMetric}.
   */
  private String activeTimeMetric = "claude_code.active_time.total";

  /**
   * OTLP metric name carrying the per-session session counter (always value 1,
   * re-emitted). Summed reset-aware across full-attribute streams it yields the
   * session count for the window. Drives the Metrics page series endpoint.
   */
  private String sessionCountMetric = "claude_code.session.count";

  /**
   * Attribute on {@link #sessionCountMetric} carrying how a session began —
   * {@code fresh} or {@code resume}. Drives the Sessions page fresh/resume split.
   */
  private String sessionStartTypeAttribute = "start_type";

  /**
   * OTLP metric name carrying cumulative lines added/removed by edit tools, split
   * on the {@code type} attribute (added | removed). Drives the Metrics page.
   */
  private String linesOfCodeMetric = "claude_code.lines_of_code.count";

  /**
   * OTLP metric name carrying cumulative edit-tool permission decisions, split on
   * the {@code decision} attribute (accept | reject). Drives the Metrics page.
   */
  private String codeEditDecisionMetric = "claude_code.code_edit_tool.decision";

  /**
   * Value of the OTLP log attribute {@code event.name} for tool permission check
   * events.
   * Claude Code emits one per tool invocation with a {@code decision} of 'accept'
   * or 'reject'.
   */
  private String toolDecisionEventName = "tool_decision";

  /**
   * Value of the OTLP log attribute {@code event.name} emitted when a hook batch
   * finishes.
   * Carries {@code num_success}, {@code num_blocking},
   * {@code num_non_blocking_error}, and
   * {@code num_cancelled} counters for the hook run.
   */
  private String hookExecutionEventName = "hook_execution_complete";

  /**
   * First tokens of Bash commands that have a dedicated tool replacement in the
   * configured coding agent. Matched against each hotspot's commandPrefix to
   * generate anti-pattern suggestions in the tuning report.
   * Defaults reflect Claude Code's tool inventory (Read, Write, Edit).
   */
  private List<String> bashAntipatternPrefixes = List.of(
      "cat", "head", "tail", "find", "sed", "awk", "echo");

  // -------------------------------------------------------------------------
  // Derived-severity classification defaults
  //
  // These lists document the event.name values and signal attribute keys used
  // by the derive_log_severity() Postgres function (V6__log_severity_function.sql).
  // They are the canonical source of truth for what the defaults are; the
  // function itself contains the literals because @Query native SQL cannot read
  // Spring properties at query-parse time. If you change these lists, create a
  // new Flyway migration to update the function accordingly.
  // -------------------------------------------------------------------------

  /**
   * event.name values that map to ERROR severity in the event-based fallback.
   * Mirrors the IN-list in {@code derive_log_severity()}.
   */
  private List<String> errorEventNames = List.of(
      "api_error", "internal_error", "api_retries_exhausted");

  /**
   * event.name values that map to WARN severity in the event-based fallback.
   * Mirrors the IN-list in {@code derive_log_severity()}.
   */
  private List<String> warnEventNames = List.of("compaction");

  /**
   * event.name values that map to DEBUG severity in the event-based fallback
   * (unconditionally, regardless of other attributes).
   * Mirrors the IN-list in {@code derive_log_severity()}.
   */
  private List<String> debugEventNames = List.of(
      "hook_execution_start", "hook_execution_complete", "hook_registered",
      "api_request_body", "api_response_body");

  /**
   * Attribute key whose value {@code 'false'} signals an ERROR outcome.
   * Checked in the event-based severity fallback.
   */
  private String successAttribute = "success";

  /**
   * Attribute key whose value {@code 'reject'} signals an ERROR outcome, and
   * whose value {@code 'accept'} signals DEBUG (for tool_decision events).
   */
  private String decisionAttribute = "decision";

  /**
   * Attribute key whose value {@code 'disconnected'} signals a WARN outcome.
   */
  private String statusAttribute = "status";

  /**
   * Attribute key whose non-zero value signals a WARN outcome (non-blocking
   * hook errors). The function checks {@code <> '0'} on the text representation.
   */
  private String numNonBlockingErrorAttribute = "num_non_blocking_error";

  /**
   * Attribute key on log records that identifies the instrumentation scope (library).
   * Kept here in case a future query needs to filter on scope at the attribute level.
   */
  private String eventNameAttribute = "event.name";

  /**
   * Value of the OTLP log attribute {@code event.name} emitted once per user turn,
   * carrying the raw prompt text in {@link #promptAttribute}. Drives the Sessions
   * grid's prompt-context enrichment and the per-session prompt timeline.
   */
  private String userPromptEventName = "user_prompt";

  /**
   * Attribute key on a {@link #userPromptEventName} log carrying the full prompt
   * text submitted by the user.
   */
  private String promptAttribute = "prompt";

}
