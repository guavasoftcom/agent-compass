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

}
