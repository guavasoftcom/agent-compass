package com.guavasoft.agentcompass.config;

import java.util.List;
import java.util.Map;

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
   *
   * <p>Also mirrored in SQL by the {@code span_efforts} view ({@code V15}), which
   * restricts the correlation to spans with this name so a stray span that
   * happens to carry a {@link #requestIdAttribute} value can never be matched.
   * Overriding this property means a migration redefining that view.
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
   *
   * <p>Also mirrored in SQL by the {@code span_efforts} view ({@code V15}) and the
   * partial index backing it ({@code idx_log_records_request_id}), which join
   * spans to {@code api_request} logs on this attribute to fill {@code Span.effort}.
   * Overriding this property means a migration redefining both, or the trace
   * detail page's effort correlation silently stops matching any row.
   */
  private String requestIdAttribute = "request_id";

  /**
   * Value of {@code event.name} for the LLM-request log that carries
   * {@link #requestIdAttribute}. Used to pair an {@link #apiRequestBodyEventName}
   * log (which lacks its own request id) to its request via event ordering.
   *
   * <p>This event name and {@link #apiRequestCostAttribute} are also mirrored in
   * SQL by the {@code span_costs} / {@code trace_costs} views ({@code V14}) and
   * by the equivalent {@code LEFT JOIN LATERAL} predicates {@code SpanRepository}
   * runs directly against {@code log_records} for pushdown in the trace-list
   * queries, which together are where every trace-level and span-level cost
   * figure comes from, plus the {@code span_efforts} view ({@code V15}) that
   * correlates {@link #apiRequestEffortAttribute} onto spans — the same
   * SQL-mirrors-configuration arrangement {@code derive_log_severity()}
   * ({@code V6}) has. Overriding this property means a migration that redefines
   * those views plus an update to the lateral predicates, or the Traces pages
   * will read cost and effort from the wrong rows.
   */
  private String apiRequestEventName = "api_request";

  /**
   * Attribute key on an {@link #apiRequestEventName} log carrying that request's
   * cost in USD — the per-request figure that accumulates into
   * {@link #costUsageMetric}. Source of truth for trace and span cost; see the
   * mirroring note above.
   */
  private String apiRequestCostAttribute = "cost_usd";

  /**
   * Attribute key on an {@link #apiRequestEventName} log carrying the reasoning
   * effort the call ran at ({@code high} / {@code medium} / {@code xhigh} /
   * {@code max}). Claude Code never emits this as a span attribute, so the
   * {@code span_efforts} view ({@code V15}) correlates it back onto the
   * {@link #llmRequestSpanName} span through {@link #requestIdAttribute} to fill
   * {@code Span.effort}.
   *
   * <p>Mirrored in SQL by that view, with the same consequence as the cost
   * mirroring above: overriding this means a migration redefining
   * {@code span_efforts}, or the trace detail page reads effort from the wrong
   * attribute. A log that carries no value here is simply absent from the view —
   * the span's effort stays null, and null means not recorded, not a default.
   */
  private String apiRequestEffortAttribute = "effort";

  /**
   * Value of {@code event.name} for the LLM request-payload log. It carries only
   * {@link #promptIdAttribute} (turn-level, not request-level) and no request id, so
   * trace-detail correlation recovers its request id by matching the instant it was
   * logged against the issue instant of an {@link #apiRequestEventName} for the same
   * prompt and model — see {@link #apiRequestDurationAttribute}.
   */
  private String apiRequestBodyEventName = "api_request_body";

  /**
   * Attribute key carrying the per-turn prompt identifier. One prompt spans many
   * LLM requests, so this scopes the body-to-request pairing but cannot identify a
   * single request on its own.
   */
  private String promptIdAttribute = "prompt.id";

  /**
   * Attribute key carrying how long an {@link #apiRequestEventName} call took, in
   * milliseconds. That log is written when the call completes, so subtracting this
   * duration recovers the instant it was issued — which is when the matching
   * {@link #apiRequestBodyEventName} was logged, and reproduces the
   * {@link #llmRequestSpanName} span's start timestamp to the millisecond. It is the
   * key that pairs the two logs when a turn dispatches several calls concurrently.
   */
  private String apiRequestDurationAttribute = "duration_ms";

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
   * Identifier used for a {@link #subagentToolName} dispatch that carries no
   * {@link #subagentTypeAttribute} anywhere. Claude Code omits the parameter when
   * the caller did not name an agent type, and the dispatch then runs on the
   * general-purpose agent — so these rows belong with it rather than in an
   * 'unknown' bucket that reads like missing data.
   */
  private String defaultSubagentType = "general-purpose";

  /**
   * {@link #toolAttribute} value that marks a tool_result or tool_decision emitted for an MCP
   * server call. Claude Code names MCP calls differently on the two signals that carry them, and
   * this property is the log-side one: every MCP server's calls share this single constant here,
   * while the {@link #toolSpanName} span instead carries the prefixed raw form
   * {@code mcp__<server>__<tool>} (see {@link #mcpSpanToolPrefix}). The real server/tool identity
   * on the log side lives in {@link #mcpParametersAttribute}, not in this attribute's value — so
   * log-backed aggregations split MCP calls back out by reading that JSON blob, while the
   * span-backed latency aggregation instead parses the prefixed name. Getting this backwards
   * (treating the log constant as if it were per-server, or the span prefix as if every server
   * used a shared name) is the mistake both frontend docs this feature corrects had made, each
   * about the signal the other author wasn't looking at.
   */
  private String mcpToolName = "mcp_tool";

  /**
   * Attribute key on an MCP {@link #toolAttribute} log record carrying a JSON-encoded string
   * (not a nested object) with that call's server and tool identity. Parsed the same
   * NULLIF/::jsonb way {@code tool_input} is read elsewhere in this codebase. Holds
   * {@link #mcpServerNameAttribute} and {@link #mcpToolNameAttribute} at 100% coverage on live
   * data.
   */
  private String mcpParametersAttribute = "tool_parameters";

  /**
   * Key inside {@link #mcpParametersAttribute} naming the MCP server that handled the call
   * (e.g. {@code playwright}). Missing or blank values bucket under {@code unknown} rather than
   * being dropped.
   */
  private String mcpServerNameAttribute = "mcp_server_name";

  /**
   * Key inside {@link #mcpParametersAttribute} naming the server-side tool that was invoked
   * (e.g. {@code browser_evaluate}).
   */
  private String mcpToolNameAttribute = "mcp_tool_name";

  /**
   * Prefix (including the double-underscore separator) that {@link #toolSpanName} spans use to
   * encode MCP identity as {@code mcp__<server>__<tool>} — the span-side counterpart to
   * {@link #mcpToolName}. Parsed with {@code starts_with()}/{@code split_part()}, never
   * {@code LIKE}: Postgres treats a bare underscore as the LIKE single-character wildcard, so
   * {@code LIKE 'mcp__%'} would match "mcp" plus any two characters plus anything, not literally
   * two underscores.
   */
  private String mcpSpanToolPrefix = "mcp__";

  /**
   * Attribute key on an {@link #apiRequestEventName} log record carrying the
   * model that served the turn. Same values as the {@code model} attribute on
   * the token-usage metric streams, so the per-model breakdowns on
   * /api/tool-activity and /api/sessions/token-usage share a key space.
   */
  private String modelAttribute = "model";

  /**
   * Attribute key on an {@link #apiRequestEventName} log record naming how the
   * request was issued (e.g. {@code sdk}, {@code agent:builtin:Explore},
   * {@code compact}, {@code generate_session_title}). Drives the Cost page's
   * work-category partition: a request whose value starts with
   * {@link #subagentQuerySourcePrefix} is a subagent call, one whose value is
   * in {@link #mainLoopQuerySources} is a main-loop call, and everything else
   * (skill-tagged aside) is auxiliary. Distinct vocabulary from the
   * {@code query_source} attribute on {@link #costUsageMetric} — the two
   * pipelines label the same traffic differently (counters emit {@code main} /
   * {@code auxiliary} / {@code subagent}), so this property is deliberately
   * read only against {@link #apiRequestEventName} logs, never the counter.
   */
  private String querySourceAttribute = "query_source";

  /**
   * Prefix (including the separator) that {@link #querySourceAttribute}
   * carries on every subagent-issued request (e.g. {@code agent:custom},
   * {@code agent:builtin:Explore}). Matched with SQL {@code starts_with()},
   * not {@code LIKE} — same reasoning as {@link #mcpSpanToolPrefix}: an
   * overridden prefix containing {@code _} or {@code %} would otherwise be
   * interpreted as a wildcard rather than a literal character.
   */
  private String subagentQuerySourcePrefix = "agent:";

  /**
   * {@link #querySourceAttribute} values that mark an ordinary main-loop
   * request. A request outside this list and not carrying the
   * {@link #subagentQuerySourcePrefix} is bucketed as auxiliary (compaction,
   * session-title generation, web-fetch apply, etc.) on the Cost page's
   * work-category breakdown.
   */
  private List<String> mainLoopQuerySources = List.of("sdk", "repl_main_thread");

  /**
   * Attribute key present on {@link #apiRequestEventName} log records emitted
   * from inside a subagent run. Its absence is what marks a main-loop turn,
   * which is how the subagent-usage aggregation finds the turn that dispatched
   * an {@link #subagentToolName} tool call. Claude Code collapses project-local
   * agents to {@code custom} here, so the value itself is not a usable subagent
   * identifier — only its presence is meaningful.
   */
  private String agentNameAttribute = "agent.name";

  /**
   * OTLP metric name carrying per-(session, model, query_source) cumulative USD
   * spend. The
   * Sessions aggregation takes MAX per stream then sums across streams.
   */
  private String costUsageMetric = "claude_code.cost.usage";

  /**
   * Noise floor for the worst-cache-efficiency ranking: a session needs at least
   * this many input-side tokens (input + cacheCreation + cacheRead — the ratio's
   * own denominator) before it can be ranked. A session that made two small calls
   * can sit at 0% efficiency without anything being wrong, and would otherwise
   * crowd out the large sessions where a poor ratio actually costs money.
   *
   * <p>Values below 1 are clamped up to 1 by the service, which is also what
   * guarantees the ranking query never divides by zero.
   */
  private long cacheEfficiencyMinimumInputTokens = 100_000L;

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
   * OTLP metric name carrying the cumulative count of git commits created during
   * a session. Drives the Metrics page. Carries only identity attributes
   * (session / user / organization), so it has no meaningful split.
   */
  private String commitCountMetric = "claude_code.commit.count";

  /**
   * OTLP metric name carrying the cumulative count of pull requests opened during
   * a session. Drives the Metrics page. Carries only identity attributes, so it
   * has no meaningful split.
   */
  private String pullRequestCountMetric = "claude_code.pull_request.count";

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
   * configured coding agent, mapped to the replacement to name in the tuning
   * report's anti-pattern suggestions. Matched against each hotspot's
   * commandPrefix. Defaults reflect Claude Code's tool inventory.
   */
  private Map<String, String> bashAntipatternReplacements = Map.of(
      "cat", "Read",
      "head", "Read (with limit)",
      "tail", "Read (with offset)",
      "find", "Glob",
      "sed", "Read + Edit",
      "awk", "Grep",
      "echo", "Write");

  /**
   * Tools whose latency and result size are externally determined — subagents
   * that legitimately run long and return short summaries, and web tools whose
   * payload the remote site controls. Excluded from the tuning report's
   * oversized-result and slow-and-large offender lists because nothing in
   * AGENTS.md can tune them.
   */
  private List<String> externallyDeterminedTools = List.of(
      "Agent", "Task", "WebSearch", "WebFetch");

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
