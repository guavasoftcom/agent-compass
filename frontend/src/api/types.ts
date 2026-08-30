// Shared DTO types for the dashboard API. Every Row/Summary shape returned by the
// backend lives here, plus the generic `ListResult` envelope and the
// `WindowSelection` discriminated union used by every fetcher. Consumers import
// these as `import type { TraceRow } from '../../api'` (resolved via the barrel).

export interface ToolCallRow {
  tool: string;
  calls: number;
}

/**
 * One skill or subagent row from `/api/tool-activity/skill-usage` and
 * `/subagent-usage`. `tool` carries the skill or subagent identifier (the
 * endpoints reuse the field name), and `byModel` splits `calls` by the model
 * that made the call — values sum to `calls`, and models with no calls are
 * omitted rather than sent as `0`. Keys are the same model ids used by the
 * Token Usage page's `byModel` rows.
 *
 * `costUsd`/`costByModel` are a separately computed dollar figure, not a
 * derivative of `calls`/`byModel` — they intentionally do not reconcile the
 * same way (skill cost sums every turn a skill's run made, including ones
 * inside a subagent it spawned, while `calls` counts invocations and drops
 * those turns; see the Skills & Subagents page CLAUDE.md). `costByModel`
 * follows the same "omit models with $0, never send an explicit 0" rule as
 * `byModel`.
 */
export interface IdentifierUsageRow {
  tool: string;
  calls: number;
  byModel: Record<string, number>;
  costUsd: number;
  costByModel: Record<string, number>;
}

/**
 * One (server, tool) pair from `GET /api/tool-activity/mcp-usage` — mirrors the backend's
 * `McpServerUsage` record field-for-field. Unlike `IdentifierUsageRow` (skills/subagents), MCP
 * calls carry no model dimension, so this shape reports failure rate, latency, and context
 * bytes instead of a `byModel` split. Rows are keyed by `(server, tool)`, not by `tool` alone —
 * one server (e.g. "playwright") can own many rows, one per tool it exposes (e.g.
 * "browser_evaluate", "browser_click").
 */
export interface McpServerUsageRow {
  server: string;
  tool: string;
  calls: number;
  failures: number;
  /** 0..1 — `failures / calls` for this (server, tool) pair. */
  failureRate: number;
  avgDurationMs: number;
  p95DurationMs: number;
  totalBytes: number;
  /** `totalBytes / 4`, matching `ToolContextFootprintRow.estimatedTokens` — not billed spend. */
  estimatedTokens: number;
}

export interface LogRow {
  id: number;
  timestamp: string;
  severityNumber: number | null;
  severityText: string | null;
  body: string;
  scopeName: string;
  traceId: string | null;
  spanId: string | null;
  attributes: Record<string, unknown> | null;
  resourceAttributes: Record<string, unknown> | null;
}

export interface TraceRow {
  traceId: string;
  startTimestamp: string;
  rootSpanName: string;
  rootSpanId: string | null;
  sessionId: string | null;
  spanCount: number;
  durationNanos: number;
  errorCount: number;
  // Sum of token usage across the trace's spans (input + output + cache-read +
  // cache-creation); 0 when no span carries token attributes.
  totalTokens: number;
  // Model spend for this trace in USD — the summed `cost_usd` of the api_request
  // logs correlated to it by trace id, i.e. the same figure the Sessions prompt
  // timeline shows for the turn that owns this trace. 0 both when the trace
  // issued no model request and when its requests predate trace-id correlation;
  // both render as "—".
  totalCostUsd: number;
  // Portion of totalCostUsd billed AFTER this trace's own claude_code.interaction
  // root span closed — e.g. a fire-and-forget subagent dispatch that kept issuing
  // requests long after the turn that launched it ended. totalCostUsd already
  // includes this amount. 0 when the trace has no root span, or no activity after
  // it closed.
  backgroundCostUsd?: number;
  // The user prompt that initiated this trace, whitespace-collapsed and truncated
  // to 200 chars — mirrors SessionSummaryRow.firstUserPrompt. Null when the trace
  // is not rooted in a conversational turn (tool / model / mcp / compaction-rooted
  // traces have no prompt of their own) or when prompt-body capture was disabled
  // while the trace was recorded.
  firstUserPrompt: string | null;
}

export interface SpanEvent {
  name: string;
  timestamp: string;
  attributes: Record<string, unknown> | null;
}

export interface SpanRow {
  id: number;
  spanId: string;
  parentSpanId: string | null;
  traceId: string;
  name: string;
  kind: string | null;
  startTimestamp: string;
  endTimestamp: string;
  durationNanos: number;
  statusCode: string | null;
  statusMessage: string | null;
  scopeName: string | null;
  attributes: Record<string, unknown> | null;
  events: SpanEvent[] | null;
  resourceAttributes: Record<string, unknown> | null;
  // Cost attributed to this span in USD: the summed `cost_usd` of the
  // api_request logs Claude Code stamped with this span id (the span active when
  // the request was issued — the interaction root or a tool.execution span, not
  // the llm_request child). 0 for spans no request was logged against.
  costUsd?: number | null;
  // Reasoning effort this call ran at ('high' / 'medium' / 'xhigh' / 'max'),
  // from the span_efforts view. Claude Code emits effort only on the api_request
  // log, never as a span attribute, so the backend correlates it back by
  // request_id — exact, not heuristic, because that id is unique on both sides.
  // Null on non-model spans and on calls whose log recorded no effort (~2% of
  // recent traffic). Null means NOT RECORDED — never render it as a default level.
  effort?: string | null;
}

export interface ListResult<T> {
  items: T[];
  totalCount: number | null;
}

// One facet bucket: a distinct value and how many rows carry it. Shared by the
// Logs and Traces facet rails (LogFacets / TraceFacets) and mirrors the backend
// FacetValue DTO.
export interface FacetValue {
  value: string;
  count: number;
}

export type WindowSelection =
  | { kind: 'preset'; minutes: number }
  | { kind: 'custom'; startTimestamp: string; endTimestamp: string };

export interface ToolCallTimeseriesPoint {
  timestamp: string;
  counts: number[];
}

export interface ToolCallTimeseries {
  bucketSeconds: number;
  tools: string[];
  points: ToolCallTimeseriesPoint[];
}

export interface ToolLatencyRow {
  tool: string;
  calls: number;
  p50Ms: number;
  p95Ms: number;
}

export interface ToolFailureRateRow {
  tool: string;
  calls: number;
  failures: number;
  failureRate: number;
}

export interface ToolRepeatStatRow {
  tool: string;
  scope: string;
  medianRunLength: number;
  maxRunLength: number;
  sessions: number;
}

export interface TokenUsagePoint {
  timestamp: string;
  input: number;
  output: number;
  cacheCreation: number;
  cacheRead: number;
}

export interface TokenModelShare {
  model: string;
  tokens: string;
  share: number;
  colorIndex: number;
}

export interface CostModelShare {
  model: string;
  usd: string;
  share: number;
  colorIndex: number;
}

export interface CostSummary {
  spend24h: string;
  deltaPct: string;
  burnRate: string;
  projected30d: string;
  costPer1k: string;
  trend: number[];
  byModel: CostModelShare[];
  note: string;
}

export interface TokenUsageSummary {
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
  cacheReadRatio: number;
  bucketSeconds: number;
  points: TokenUsagePoint[];
  byModel: TokenModelShare[];
  cost: CostSummary;
}

/**
 * One session in the worst-cache-efficiency ranking
 * (`GET /api/sessions/cache-efficiency`). `cacheEfficiency` is the same ratio
 * `lib/cacheEfficiency.ts` computes for the Sessions grid column — the backend
 * ranks by it so the list and the column can never disagree. Sessions below the
 * server's input-side token floor are absent rather than shown at 0%.
 */
export interface SessionCacheEfficiencyRow {
  sessionId: string;
  /** cacheRead / (input + cacheCreation + cacheRead), 0..1. Never null. */
  cacheEfficiency: number;
  cacheReadTokens: number;
  /** The ratio's denominator: input + cacheCreation + cacheRead. */
  inputSideTokens: number;
  /**
   * `inputSideTokens` decomposed. `inputTokens + cacheCreationTokens +
   * cacheReadTokens` always equals `inputSideTokens` — they are the same
   * aggregation one level less collapsed, not a second measurement, which is
   * what lets the session detail draw its token bar without a second fetch.
   */
  inputTokens: number;
  cacheCreationTokens: number;
  /**
   * The one kind outside the ratio — generated rather than sent, so the cache
   * could never have served it. Sent explicitly rather than left to a
   * `totalTokens - inputSideTokens` subtraction here, so the row stays
   * self-describing; it is the fourth segment of the session detail's bar.
   */
  outputTokens: number;
  /** All four kinds including output — a scale hint, not the denominator. */
  totalTokens: number;
  /** Whole-session spend, matching the Sessions grid's Cost column. */
  costUsd: number;
  /**
   * Timestamp of the session's latest cost/active-time emission, matching
   * `SessionSummaryRow.endTimestamp`. Null on the rare session whose every
   * cost/active-time point in the window is a zero-delta re-export of an
   * already-finished session.
   */
  endTimestamp: string | null;
  /**
   * Session's first meaningful user prompt, whitespace-collapsed and truncated
   * to <=200 chars server-side, matching `SessionSummaryRow.firstUserPrompt`.
   * Null when prompt capture was disabled (OTEL_LOG_USER_PROMPTS) or the
   * session has no user-authored prompt.
   */
  firstUserPrompt: string | null;
}

/**
 * One tool's context-window footprint (`GET /api/tool-activity/context-footprint`).
 *
 * `estimatedTokens` is `totalBytes / 4` — an estimate of one-time injection size
 * for ranking tools against each other. It is NOT billed spend: never add it to,
 * or display it alongside, the exact figures on `TokenUsageSummary`, and note
 * that it understates real cost because a tool result is re-sent with every
 * later request in its session.
 */
export interface ToolContextFootprintRow {
  tool: string;
  /** Calls that reported a size, including failures; excludes sizeless calls. */
  calls: number;
  totalBytes: number;
  estimatedTokens: number;
  p95Bytes: number;
}

/**
 * Four-way token split (reset-aware sums of claude_code.token.usage by the
 * `type` attribute). Missing kinds are 0, never null.
 */
export interface SessionTokenBreakdown {
  input: number;
  output: number;
  cacheCreation: number;
  cacheRead: number;
}

export interface SessionSummaryRow {
  sessionId: string;
  costUsd: number;
  activeTimeSeconds: number;
  startTimestamp: string;
  endTimestamp: string;
  wallSeconds: number;
  toolCallCount: number;
  denialCount: number;
  /** Reset-aware total tokens for the session (raw; the grid formats M/K). */
  tokens: number;
  /** Window-scoped four-way split; the backend guarantees it sums to `tokens`. */
  tokenBreakdown: SessionTokenBreakdown;
  terminalType: 'interactive' | 'non-interactive';
  startType: 'fresh' | 'resume';
  /**
   * Session's first meaningful user prompt, whitespace-collapsed and truncated
   * to <=200 chars server-side. Null when prompt capture was disabled
   * (OTEL_LOG_USER_PROMPTS) or the session has no user-authored prompt — this
   * can be true even when `userPromptCount` is greater than zero.
   */
  firstUserPrompt: string | null;
  userPromptCount: number;
}

/** One row of a session's full prompt timeline (`GET /api/sessions/{id}/prompts`). */
export interface SessionPromptRow {
  timestamp: string;
  /**
   * Null for pre-capture events (prompt_text wasn't recorded). Keep these rows
   * — don't filter them out — and render a placeholder instead of passing null
   * into a component that would stringify it (e.g. `AttributeValue` renders the
   * literal text "null").
   */
  prompt: string | null;
  /**
   * Trace whose root span is this prompt's claude_code.interaction. Null for
   * prompts from sessions that predate tracing (~35% of existing data) — don't
   * render a disabled placeholder for those, just omit the link.
   */
  traceId: string | null;
  /** Model that served the turn (dominant by tokens). Null → chip omitted. */
  model?: string | null;
  /** Cost attributed to the turn (reset-aware sum). Null → cost omitted. */
  costUsd?: number | null;
  /** The turn's four-way token split. Null when the turn has no token points. */
  tokens?: SessionTokenBreakdown | null;
  /** Tool calls the turn triggered, count desc. Empty/null → "No tool calls". */
  tools?: { name: string; count: number }[] | null;
  /**
   * Turn identifier, shared with the api_request logs it issued. Null on turns
   * predating prompt-id stamping.
   */
  promptId?: string | null;
  /** api_request logs correlated to this turn. 0 whenever attribution is INTERVAL. */
  requestCount?: number;
  /**
   * How `model` / `costUsd` / `tokens` were derived. `REQUEST` means exact
   * per-call figures summed over the turn's own api_request logs. `INTERVAL`
   * means no such logs exist and the values were bucketed from cumulative metric
   * counters by timestamp — present the latter as approximate.
   *
   * The two are different measurements, not two views of one number: the
   * counter-derived totals run materially lower than the per-request sums on
   * cache-read-heavy sessions. Never add or compare them across turns.
   */
  attribution?: TurnAttribution;
  /**
   * Portion of `costUsd` billed to this turn's trace AFTER the trace's own
   * claude_code.interaction root span closed — e.g. a fire-and-forget subagent
   * dispatch (an Agent tool call whose own span closes immediately) that kept
   * issuing requests long after this turn ended and the next prompt was typed.
   * `costUsd` already includes this amount (it is the turn's trace total,
   * matching what the trace detail page reports for the same trace) — this
   * field explains why a turn cost more than what happened while it was
   * active, not an amount to add on top. Null when the turn has no trace, or
   * its trace has no activity after the root span closed.
   */
  backgroundCostUsd?: number | null;
  /**
   * Tool calls attributed to this turn's trace but occurring AFTER the trace's
   * root span closed — the background counterpart to `backgroundCostUsd`. The
   * `tools` list above already includes these calls (it too is the turn's
   * trace total); this is the subset that ran as background/detached work.
   * Empty/null when none.
   */
  backgroundTools?: { name: string; count: number }[] | null;
}

export type TurnAttribution = 'REQUEST' | 'INTERVAL';

export interface SessionsSortModel {
  field: string;
  direction: 'asc' | 'desc';
}

export interface SessionsPageRequest {
  page: number;
  pageSize: number;
  sort: SessionsSortModel;
}

export interface SessionKpis {
  totalSessions: number;
  medianCostUsd: number;
  p95CostUsd: number;
  medianCostPerActiveMinuteUsd: number;
  /** New-session count per window bucket — the Total-sessions card sparkline. */
  sessionsTrend: number[];
}

export interface ToolDenialRow {
  tool: string;
  source: string;
  count: number;
}

/** MAIN_LOOP, SUBAGENT, SKILL, or AUXILIARY — see CostCategoryShare. */
export type CostCategory = 'MAIN_LOOP' | 'SUBAGENT' | 'SKILL' | 'AUXILIARY';

/**
 * One named identifier's share of its parent `CostCategoryShare`'s drilldown (a skill or
 * subagent identifier). `share` is percent of the category's `identifiedCostUsd`, not of the
 * page total.
 */
export interface CostIdentifierShare {
  identifier: string;
  costUsd: number;
  share: number;
}

/**
 * One slice of the Cost page's work-category money map (`GET /api/cost/breakdown`). Every
 * `api_request` row in the window belongs to exactly one category — SUBAGENT beats SKILL beats
 * MAIN_LOOP beats AUXILIARY, so a request tagged as both a subagent call and a skill invocation
 * (a skill running inside a subagent) is credited to SUBAGENT only. `costUsd` across all
 * categories always sums exactly to `CostBreakdown.totalCostUsd`.
 *
 * `drilldown`/`identifiedCostUsd` are populated only for SUBAGENT and SKILL (resolved by the
 * separate span-correlated / skill-tagged cost queries the Skills & Subagents page also uses).
 * `identifiedCostUsd` — the sum of `drilldown`'s own `costUsd` — can be LESS than this row's
 * `costUsd`: a subagent dispatch with no matching execution span, or nested inside another
 * subagent's own dispatch, still counts toward the category total but has no resolvable
 * identifier. Never force these to reconcile — render the drilldown as "of which, identified".
 */
export interface CostCategoryShare {
  category: CostCategory;
  costUsd: number;
  requests: number;
  share: number;
  drilldown: CostIdentifierShare[];
  identifiedCostUsd: number | null;
}

/**
 * One bucket of the Cost page's stacked spend-over-time chart. `costByCategory` keys are
 * `CostCategory` values; a category with zero spend in this bucket is omitted rather than sent
 * as `0` — fill missing categories with 0 when building chart series.
 */
export interface CostTrendPoint {
  timestamp: string;
  costByCategory: Partial<Record<CostCategory, number>>;
}

/**
 * One (model, effort) cell of the Cost page's cost-drivers grid, plus the token composition
 * behind its spend. `effort` is `null` when NOT RECORDED (~7% of `api_request` rows carry no
 * effort attribute) — never render `null` as a default level.
 */
export interface CostModelEffortCell {
  model: string;
  effort: string | null;
  costUsd: number;
  requests: number;
  inputTokens: number;
  outputTokens: number;
  cacheCreationTokens: number;
  cacheReadTokens: number;
}

/**
 * One session in the Cost page's biggest-line-items ranking ("Most expensive sessions"),
 * sorted by spend descending. The four `*CostUsd` fields are the same work-category
 * partition as `CostCategoryShare`, scoped to this one session — they always sum exactly
 * to `costUsd` (same SUBAGENT-beats-SKILL-beats-MAIN_LOOP-beats-AUXILIARY precedence).
 */
export interface CostSessionShare {
  sessionId: string;
  costUsd: number;
  requests: number;
  /**
   * Session's first meaningful user prompt, whitespace-collapsed and truncated
   * server-side, matching `SessionSummaryRow.firstUserPrompt`. Null when prompt
   * capture was disabled (OTEL_LOG_USER_PROMPTS) or the session has no
   * user-authored prompt.
   */
  firstUserPrompt: string | null;
  /** This session's share of `CostCategoryShare` MAIN_LOOP. */
  mainLoopCostUsd: number;
  /** This session's share of `CostCategoryShare` SUBAGENT. */
  subagentCostUsd: number;
  /** This session's share of `CostCategoryShare` SKILL. */
  skillCostUsd: number;
  /** This session's share of `CostCategoryShare` AUXILIARY. */
  auxiliaryCostUsd: number;
}

/**
 * Full response for the Cost page (`GET /api/cost/breakdown`). Measured exclusively from
 * `api_request` log records' exact per-call `cost_usd` — never the `claude_code.cost.usage`
 * cumulative counter that backs the Tokens and Sessions pages' cost KPIs. The two pipelines do
 * not reconcile (see `AGENTS.md`'s two-pipelines note): `totalCostUsd` here reads a few percent
 * below the counter-derived totals shown elsewhere for the same window, and that gap is not a
 * bug in either number. Chosen because it's the only side where the dollars and the
 * skill/subagent/model/effort tags sit on the same row, which is what lets every category and
 * every cell below sum exactly to `totalCostUsd`.
 */
export interface CostBreakdown {
  totalCostUsd: number;
  priorCostUsd: number;
  deltaPct: number;
  burnRatePerHour: number;
  projected30dUsd: number;
  totalRequests: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCacheCreationTokens: number;
  totalCacheReadTokens: number;
  /** The work-category partition, sorted by cost descending. */
  categories: CostCategoryShare[];
  /** Stacked spend-over-time trend, one point per bucket, oldest first. */
  trend: CostTrendPoint[];
  /** Cost drivers grid, sorted by cost descending. */
  modelEffort: CostModelEffortCell[];
  /** Biggest line items: top sessions by spend, sorted descending. */
  topSessions: CostSessionShare[];
  bucketSeconds: number;
}

export interface HookExecutionRow {
  hookEvent: string;
  hookName: string;
  total: number;
  successes: number;
  blockingErrors: number;
  nonBlockingErrors: number;
  cancelled: number;
}
