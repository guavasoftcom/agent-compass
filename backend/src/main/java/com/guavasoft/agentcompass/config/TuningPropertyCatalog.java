package com.guavasoft.agentcompass.config;

import org.springframework.stereotype.Component;

import com.guavasoft.agentcompass.model.ConfigurationEntry;
import com.guavasoft.agentcompass.model.ConfigurationGroup;
import com.guavasoft.agentcompass.model.EffectiveConfiguration;
import com.guavasoft.agentcompass.model.SqlMirroring;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Describes every {@link TuningProperties} field for {@code GET /api/system/configuration}: which
 * group it belongs to, what it drives, and whether overriding it also requires a Flyway migration.
 *
 * <p><b>Why this is authored by hand.</b> Reflection can read the 52 values but not the three things
 * that make the endpoint worth having — the grouping, the one-line descriptions, and above all the
 * {@link SqlMirroring} flag, which is a fact about the migration history and cannot be derived from
 * the field. A hand-written list rots silently, so {@code TuningPropertyCatalogTest} reflects over
 * {@code TuningProperties}' declared fields and fails the build unless every one appears here
 * exactly once. Adding a property to {@code TuningProperties} therefore forces classifying it.
 *
 * <p><b>Why only {@code TuningProperties}.</b> This deliberately does not walk the Spring
 * {@code Environment} or its property sources. That would render {@code SPRING_DATASOURCE_PASSWORD}
 * on an endpoint with no authentication in front of it.
 *
 * <p><b>On the mirrored set.</b> {@code AGENTS.md} names six mirrored properties; the real count is
 * 17 across six migrations, split three ways. {@link SqlMirroring#MIRRORED} properties have their
 * value duplicated as a literal in migration SQL — a generated column ({@code V16}, {@code V17}), a
 * view ({@code V14}, {@code V15}, {@code V19}), the {@code derive_log_severity()} function
 * ({@code V6}), or a partial index predicate ({@code V19}). {@link SqlMirroring#SHARED_LITERAL}
 * properties merely happen to equal a literal that {@code V6} hardcodes for its own reasons: the
 * property is not that literal's source, so overriding it does not invalidate the function, but the
 * two then describe different events.
 */
@Component
public class TuningPropertyCatalog {

  private static final String PROPERTY_PREFIX = "tuning.";
  private static final String VALUE_SEPARATOR = ", ";
  private static final String MAP_ENTRY_FORMAT = "%s=%s";
  private static final String CAMEL_CASE_BOUNDARY = "([a-z0-9])([A-Z])";
  private static final String KEBAB_CASE_REPLACEMENT = "$1-$2";

  /** Compiled-in defaults, used only to decide whether a live value was overridden. */
  private static final TuningProperties DEFAULTS = new TuningProperties();

  /**
   * One property's catalog metadata. {@code fieldName} is the reflection key the completeness test
   * matches on; the kebab-case property name is derived from it rather than authored twice.
   */
  private record CatalogEntry(
      String fieldName,
      String description,
      SqlMirroring sqlMirroring,
      List<String> mirroredIn,
      Function<TuningProperties, Object> accessor) {

    static CatalogEntry plain(
        String fieldName, String description, Function<TuningProperties, Object> accessor) {
      return new CatalogEntry(fieldName, description, SqlMirroring.NOT_MIRRORED, List.of(), accessor);
    }

    static CatalogEntry mirrored(
        String fieldName,
        String description,
        List<String> mirroredIn,
        Function<TuningProperties, Object> accessor) {
      return new CatalogEntry(fieldName, description, SqlMirroring.MIRRORED, mirroredIn, accessor);
    }

    static CatalogEntry sharedLiteral(
        String fieldName,
        String description,
        List<String> mirroredIn,
        Function<TuningProperties, Object> accessor) {
      return new CatalogEntry(fieldName, description, SqlMirroring.SHARED_LITERAL, mirroredIn, accessor);
    }
  }

  /** A group's metadata plus its properties, in the order the page should render them. */
  private record CatalogGroup(String name, String description, List<CatalogEntry> entries) {
  }

  private static final List<String> SEVERITY_FUNCTION = List.of("V6");

  private static final List<CatalogGroup> GROUPS = List.of(
      new CatalogGroup(
          "Tool activity",
          "Identifying which tool ran, whether it was permitted, and what its hooks did",
          List.of(
              CatalogEntry.plain("toolEventName",
                  "event.name of the log emitted once per tool result",
                  TuningProperties::getToolEventName),
              CatalogEntry.mirrored("toolAttribute",
                  "Attribute naming the tool that was called. Materialized as the generated column "
                      + "log_records.tool_name, which the Logs page's tool facet, filter, and "
                      + "histogram all read.",
                  List.of("V17", "V19"),
                  TuningProperties::getToolAttribute),
              CatalogEntry.sharedLiteral("toolDecisionEventName",
                  "event.name of the permission-check log carrying accept or reject",
                  SEVERITY_FUNCTION,
                  TuningProperties::getToolDecisionEventName),
              CatalogEntry.sharedLiteral("hookExecutionEventName",
                  "event.name emitted when a hook batch finishes, carrying its outcome counters",
                  SEVERITY_FUNCTION,
                  TuningProperties::getHookExecutionEventName),
              CatalogEntry.mirrored("toolCallIdAttribute",
                  "Key shared by tool logs and the tool-execution span, used to re-point logs from "
                      + "the interaction root onto the exact span. Also the key of the partial "
                      + "index idx_spans_tool_execution_call_id (V20).",
                  List.of("V20"),
                  TuningProperties::getToolCallIdAttribute))),

      new CatalogGroup(
          "Spans & correlation",
          "Which spans represent what, and the keys that join logs to them",
          List.of(
              CatalogEntry.plain("toolSpanScope",
                  "Instrumentation scope of spans representing a tool invocation",
                  TuningProperties::getToolSpanScope),
              CatalogEntry.plain("toolSpanName",
                  "Span name wrapping a single tool invocation",
                  TuningProperties::getToolSpanName),
              CatalogEntry.mirrored("toolExecutionSpanName",
                  "Leaf span timing one tool execution — the correlation target, distinct from the "
                      + "wrapper span it shares a tool-call id with. Also the predicate of the "
                      + "partial index idx_spans_tool_execution_call_id (V20).",
                  List.of("V20"),
                  TuningProperties::getToolExecutionSpanName),
              CatalogEntry.mirrored("llmRequestSpanName",
                  "Leaf span timing one LLM request. The span_efforts view restricts its correlation "
                      + "to this name so a stray span carrying a request id can never be matched.",
                  List.of("V15", "V19"),
                  TuningProperties::getLlmRequestSpanName),
              CatalogEntry.mirrored("requestIdAttribute",
                  "Key joining api_request logs to their LLM-request span. Also the key of the "
                      + "partial index backing span_efforts — and that index's predicate is written "
                      + "against event_name, which is why changing it needs a migration.",
                  List.of("V15", "V19"),
                  TuningProperties::getRequestIdAttribute))),

      new CatalogGroup(
          "LLM requests & cost",
          "The api_request log family — the source of every exact per-call cost, effort, and model "
              + "figure in the dashboard",
          List.of(
              CatalogEntry.mirrored("apiRequestEventName",
                  "event.name of the per-request log. Mirrored by the span_costs, trace_costs, and "
                      + "span_efforts views and by the lateral predicates SpanRepository runs against "
                      + "log_records for pushdown.",
                  List.of("V14", "V15", "V19"),
                  TuningProperties::getApiRequestEventName),
              CatalogEntry.mirrored("apiRequestCostAttribute",
                  "Attribute carrying a request's cost in USD — the source of every trace-level and "
                      + "span-level cost figure",
                  List.of("V14", "V19"),
                  TuningProperties::getApiRequestCostAttribute),
              CatalogEntry.mirrored("apiRequestEffortAttribute",
                  "Attribute carrying the reasoning effort a call ran at. Never emitted on a span, "
                      + "so span_efforts correlates it back through the request id.",
                  List.of("V15", "V19"),
                  TuningProperties::getApiRequestEffortAttribute),
              CatalogEntry.sharedLiteral("apiRequestBodyEventName",
                  "event.name of the request-payload log, which carries no request id of its own and "
                      + "is paired to its request by issue instant",
                  SEVERITY_FUNCTION,
                  TuningProperties::getApiRequestBodyEventName),
              CatalogEntry.plain("apiRequestDurationAttribute",
                  "Call duration in milliseconds. Subtracting it from the completion log recovers the "
                      + "instant the call was issued, which is what pairs it to its payload log.",
                  TuningProperties::getApiRequestDurationAttribute),
              CatalogEntry.plain("modelAttribute",
                  "Attribute naming the model that served a turn. Only api_request logs carry it — "
                      + "tool_result rows have none, so per-model tool splits are correlated.",
                  TuningProperties::getModelAttribute),
              CatalogEntry.plain("agentNameAttribute",
                  "Present only on turns from inside a subagent run. Its absence marks a main-loop "
                      + "turn; the value itself is not a usable agent identifier.",
                  TuningProperties::getAgentNameAttribute))),

      new CatalogGroup(
          "Cost breakdown",
          "Partitions api_request spend into work categories for the Cost page. Not SQL-mirrored: "
              + "the category query is already gated by the indexed event_name column.",
          List.of(
              CatalogEntry.plain("querySourceAttribute",
                  "Attribute naming how a request was issued (sdk, agent:*, compact, ...). Distinct "
                      + "vocabulary from the query_source attribute on the cost counter — the two "
                      + "pipelines label the same traffic differently.",
                  TuningProperties::getQuerySourceAttribute),
              CatalogEntry.plain("subagentQuerySourcePrefix",
                  "Prefix marking a subagent-issued request on querySourceAttribute",
                  TuningProperties::getSubagentQuerySourcePrefix),
              CatalogEntry.plain("mainLoopQuerySources",
                  "querySourceAttribute values classified as ordinary main-loop requests; anything "
                      + "else not carrying the subagent prefix is auxiliary",
                  TuningProperties::getMainLoopQuerySources))),

      new CatalogGroup(
          "Sessions & prompts",
          "Turn-level identity and the prompt text the Sessions page renders",
          List.of(
              CatalogEntry.plain("userPromptEventName",
                  "event.name emitted once per user turn, carrying the raw prompt text",
                  TuningProperties::getUserPromptEventName),
              CatalogEntry.plain("promptAttribute",
                  "Attribute carrying the full prompt text. Truncated to a preview in SQL so services "
                      + "never hold untruncated bodies.",
                  TuningProperties::getPromptAttribute),
              CatalogEntry.plain("promptIdAttribute",
                  "Per-turn identifier shared by user_prompt and api_request logs — the exact join "
                      + "key that makes per-turn rollups a GROUP BY rather than a time bucketing",
                  TuningProperties::getPromptIdAttribute),
              CatalogEntry.plain("sessionStartTypeAttribute",
                  "How a session began, fresh or resume. Drives the Sessions page split; resume "
                      + "streams emit only the session counter, never cost or tokens.",
                  TuningProperties::getSessionStartTypeAttribute),
              CatalogEntry.plain("cacheEfficiencyMinimumInputTokens",
                  "Noise floor for the worst-cache-efficiency ranking, so two small calls at 0% "
                      + "cannot crowd out the large sessions where the ratio costs money",
                  TuningProperties::getCacheEfficiencyMinimumInputTokens))),

      new CatalogGroup(
          "Metric names",
          "OTLP counter names. All are cumulative and re-emitted per stream, so every rollup reads "
              + "the reset-aware value_delta precomputed at ingest.",
          List.of(
              CatalogEntry.plain("tokenUsageMetric",
                  "Per-turn token counts, one data point per session and token type",
                  TuningProperties::getTokenUsageMetric),
              CatalogEntry.plain("tokenTypeAttribute",
                  "Attribute on the token metric identifying the token bucket",
                  TuningProperties::getTokenTypeAttribute),
              CatalogEntry.plain("costUsageMetric",
                  "Cumulative USD spend per session, model, and query source",
                  TuningProperties::getCostUsageMetric),
              CatalogEntry.plain("activeTimeMetric",
                  "Cumulative active seconds, same stream identity as the cost counter",
                  TuningProperties::getActiveTimeMetric),
              CatalogEntry.plain("sessionCountMetric",
                  "Per-session counter, always 1 and re-emitted; summed reset-aware it yields the "
                      + "window's session count",
                  TuningProperties::getSessionCountMetric),
              CatalogEntry.plain("commitCountMetric",
                  "Cumulative git commits created during a session",
                  TuningProperties::getCommitCountMetric),
              CatalogEntry.plain("pullRequestCountMetric",
                  "Cumulative pull requests opened during a session",
                  TuningProperties::getPullRequestCountMetric),
              CatalogEntry.plain("linesOfCodeMetric",
                  "Cumulative lines added and removed by edit tools, split on type",
                  TuningProperties::getLinesOfCodeMetric),
              CatalogEntry.plain("codeEditDecisionMetric",
                  "Cumulative edit-tool permission decisions, split on decision",
                  TuningProperties::getCodeEditDecisionMetric))),

      new CatalogGroup(
          "Skills & subagents",
          "Identifying skill invocations and subagent dispatches, both of which need de-duplication "
              + "rather than a row count",
          List.of(
              CatalogEntry.plain("skillToolName",
                  "Tool name marking a result emitted by the skill dispatcher",
                  TuningProperties::getSkillToolName),
              CatalogEntry.plain("skillNameAttribute",
                  "Key carrying the skill identifier, looked up as a flat attribute then inside "
                      + "tool_input. Stamped on every model call while a skill runs, so invocations "
                      + "are counted per prompt, not per row.",
                  TuningProperties::getSkillNameAttribute),
              CatalogEntry.plain("skillEventName",
                  "event.name emitted when a skill is invoked — api_request, not tool_result, since a "
                      + "skill invoked as a slash command never goes through the dispatcher tool",
                  TuningProperties::getSkillEventName),
              CatalogEntry.plain("subagentToolName",
                  "Tool name marking a subagent dispatch",
                  TuningProperties::getSubagentToolName),
              CatalogEntry.plain("subagentTypeAttribute",
                  "Key carrying the subagent identifier, same lookup strategy as the skill name",
                  TuningProperties::getSubagentTypeAttribute),
              CatalogEntry.plain("defaultSubagentType",
                  "Identifier credited to a dispatch that names no agent type — that is the agent it "
                      + "actually runs on, so an unknown bucket would under-report a real one",
                  TuningProperties::getDefaultSubagentType))),

      new CatalogGroup(
          "MCP tools",
          "MCP calls are named differently on the two signals that carry them — logs collapse "
              + "every server to one constant, spans carry a per-server prefixed name — so this "
              + "group's properties parse identity back out of each side rather than reading it "
              + "off tool_name directly. Not SQL-mirrored: every query here is already gated by "
              + "the indexed tool_name column, so no generated column is warranted.",
          List.of(
              CatalogEntry.plain("mcpToolName",
                  "toolAttribute value marking an MCP dispatch on the LOG side (tool_result / "
                      + "tool_decision) — one constant shared by every server. The span side uses "
                      + "a per-server prefixed name instead; see mcpSpanToolPrefix.",
                  TuningProperties::getMcpToolName),
              CatalogEntry.plain("mcpParametersAttribute",
                  "JSON-string attribute on an MCP log record holding the real server/tool "
                      + "identity, parsed the same NULLIF/::jsonb way tool_input is read elsewhere",
                  TuningProperties::getMcpParametersAttribute),
              CatalogEntry.plain("mcpServerNameAttribute",
                  "Key inside mcpParametersAttribute naming the MCP server that handled the call",
                  TuningProperties::getMcpServerNameAttribute),
              CatalogEntry.plain("mcpToolNameAttribute",
                  "Key inside mcpParametersAttribute naming the server-side tool that was invoked",
                  TuningProperties::getMcpToolNameAttribute),
              CatalogEntry.plain("mcpSpanToolPrefix",
                  "Prefix encoding MCP identity on the SPAN side as mcp__<server>__<tool>, parsed "
                      + "with starts_with()/split_part() rather than LIKE (which would treat the "
                      + "double underscore as two wildcard characters)",
                  TuningProperties::getMcpSpanToolPrefix))),

      new CatalogGroup(
          "Severity classification",
          "Inputs to derive_log_severity(), the Postgres function behind the stored derived_severity "
              + "column. Every property here is duplicated as a literal inside that function, so "
              + "changing any of them means a migration that redefines it and rebuilds the column.",
          List.of(
              CatalogEntry.mirrored("errorEventNames",
                  "event.name values classified ERROR",
                  SEVERITY_FUNCTION,
                  TuningProperties::getErrorEventNames),
              CatalogEntry.mirrored("warnEventNames",
                  "event.name values classified WARN",
                  SEVERITY_FUNCTION,
                  TuningProperties::getWarnEventNames),
              CatalogEntry.mirrored("debugEventNames",
                  "event.name values classified DEBUG unconditionally",
                  SEVERITY_FUNCTION,
                  TuningProperties::getDebugEventNames),
              CatalogEntry.mirrored("successAttribute",
                  "Attribute whose false value signals ERROR",
                  SEVERITY_FUNCTION,
                  TuningProperties::getSuccessAttribute),
              CatalogEntry.mirrored("decisionAttribute",
                  "Attribute whose reject value signals ERROR and whose accept value signals DEBUG",
                  SEVERITY_FUNCTION,
                  TuningProperties::getDecisionAttribute),
              CatalogEntry.mirrored("statusAttribute",
                  "Attribute whose disconnected value signals WARN",
                  SEVERITY_FUNCTION,
                  TuningProperties::getStatusAttribute),
              CatalogEntry.mirrored("numNonBlockingErrorAttribute",
                  "Attribute whose non-zero value signals WARN",
                  SEVERITY_FUNCTION,
                  TuningProperties::getNumNonBlockingErrorAttribute),
              CatalogEntry.mirrored("eventNameAttribute",
                  "The attribute every other event name is read from. Materialized as the generated "
                      + "column log_records.event_name — always filter on that column, never on the "
                      + "raw extraction, which has had no index since V16 dropped it.",
                  List.of("V6", "V7", "V14", "V15", "V16", "V19"),
                  TuningProperties::getEventNameAttribute))),

      new CatalogGroup(
          "Report tuning",
          "Shapes the markdown tuning report's suggestions rather than any query",
          List.of(
              CatalogEntry.plain("bashAntipatternReplacements",
                  "Bash command prefixes mapped to the dedicated tool the report should suggest instead",
                  TuningProperties::getBashAntipatternReplacements),
              CatalogEntry.plain("externallyDeterminedTools",
                  "Tools whose latency and result size the agent cannot tune, excluded from the "
                      + "report's oversized and slow-and-large offender lists",
                  TuningProperties::getExternallyDeterminedTools))));

  /** Renders the live values of every catalogued property, grouped and annotated. */
  public EffectiveConfiguration describe(TuningProperties activeProperties) {
    List<ConfigurationGroup> groups = GROUPS.stream()
        .map(group -> new ConfigurationGroup(
            group.name(),
            group.description(),
            group.entries().stream().map(entry -> describeEntry(entry, activeProperties)).toList()))
        .toList();
    int propertyCount = groups.stream().mapToInt(group -> group.entries().size()).sum();
    int overriddenCount = (int) groups.stream()
        .flatMap(group -> group.entries().stream())
        .filter(ConfigurationEntry::overridden)
        .count();
    return new EffectiveConfiguration(groups, propertyCount, overriddenCount);
  }

  /** Field names of every catalogued property. Exists for the completeness test. */
  public List<String> catalogedFieldNames() {
    return GROUPS.stream()
        .flatMap(group -> group.entries().stream())
        .map(CatalogEntry::fieldName)
        .toList();
  }

  /** Migration versions referenced by any entry, so the test can check each file exists. */
  public List<String> referencedMigrationVersions() {
    return GROUPS.stream()
        .flatMap(group -> group.entries().stream())
        .flatMap(entry -> entry.mirroredIn().stream())
        .distinct()
        .toList();
  }

  private ConfigurationEntry describeEntry(CatalogEntry entry, TuningProperties activeProperties) {
    Object activeValue = entry.accessor().apply(activeProperties);
    Object defaultValue = entry.accessor().apply(DEFAULTS);
    return new ConfigurationEntry(
        PROPERTY_PREFIX + toKebabCase(entry.fieldName()),
        renderValue(activeValue),
        !Objects.equals(activeValue, defaultValue),
        entry.sqlMirroring(),
        entry.mirroredIn(),
        entry.description());
  }

  private static String toKebabCase(String fieldName) {
    return fieldName.replaceAll(CAMEL_CASE_BOUNDARY, KEBAB_CASE_REPLACEMENT).toLowerCase();
  }

  private static String renderValue(Object value) {
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(String::valueOf).collect(Collectors.joining(VALUE_SEPARATOR));
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(mapEntry -> MAP_ENTRY_FORMAT.formatted(mapEntry.getKey(), mapEntry.getValue()))
          .sorted()
          .collect(Collectors.joining(VALUE_SEPARATOR));
    }
    return String.valueOf(value);
  }
}
