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
 */
export interface IdentifierUsageRow {
  tool: string;
  calls: number;
  byModel: Record<string, number>;
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
}

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

export interface HookExecutionRow {
  hookEvent: string;
  hookName: string;
  total: number;
  successes: number;
  blockingErrors: number;
  nonBlockingErrors: number;
  cancelled: number;
}
