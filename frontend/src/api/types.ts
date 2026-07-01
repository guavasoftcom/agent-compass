// Shared DTO types for the dashboard API. Every Row/Summary shape returned by the
// backend lives here, plus the generic `ListResult` envelope and the
// `WindowSelection` discriminated union used by every fetcher. Consumers import
// these as `import type { TraceRow } from '../../api'` (resolved via the barrel).

export interface ToolCallRow {
  tool: string;
  calls: number;
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
  terminalType: 'interactive' | 'non-interactive';
  startType: 'fresh' | 'resume';
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
