export interface ToolCallRow {
  tool: string;
  calls: number;
}

export interface MetricRow {
  id: number;
  metricName: string;
  description: string | null;
  unit: string | null;
  scopeName: string;
  timestamp: string;
  valueDouble: number | null;
  valueLong: number | null;
  valueKind: string;
  attributes: Record<string, unknown> | null;
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

const getJson = async <T>(path: string): Promise<T> => {
  const res = await fetch(path);
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<T>;
};

const getJsonWithHeaders = async <T>(
  path: string,
): Promise<{ body: T; headers: Headers }> => {
  const res = await fetch(path);
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  const body = (await res.json()) as T;
  return { body, headers: res.headers };
};

const getText = async (path: string): Promise<string> => {
  const res = await fetch(path);
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  return res.text();
};

const listWithTotalCount = async <T>(path: string): Promise<ListResult<T>> => {
  const { body, headers } = await getJsonWithHeaders<T[]>(path);
  const totalHeader = headers.get('X-Total-Count');
  const totalCount = totalHeader != null ? Number(totalHeader) : null;
  return { items: body, totalCount };
};

export type WindowSelection =
  | { kind: 'preset'; minutes: number }
  | { kind: 'custom'; startTimestamp: string; endTimestamp: string };

const windowQueryParams = (selection: WindowSelection): URLSearchParams => {
  const params = new URLSearchParams();
  if (selection.kind === 'preset') {
    params.set('minutes', String(selection.minutes));
  } else {
    params.set('startTimestamp', selection.startTimestamp);
    params.set('endTimestamp', selection.endTimestamp);
  }
  return params;
};

export const fetchToolCalls = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolCallRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/calls?${params.toString()}`);
};

export interface ToolCallTimeseriesPoint {
  timestamp: string;
  counts: number[];
}

export interface ToolCallTimeseries {
  bucketSeconds: number;
  tools: string[];
  points: ToolCallTimeseriesPoint[];
}

export const fetchToolCallsTimeseries = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
  topN = 8,
): Promise<ToolCallTimeseries> => {
  const params = windowQueryParams(selection);
  params.set('topN', String(topN));
  return getJson(`/api/tool-activity/calls/timeseries?${params.toString()}`);
};

export interface ToolLatencyRow {
  tool: string;
  calls: number;
  p50Ms: number;
  p95Ms: number;
}

export const fetchSkillUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolCallRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/skill-usage?${params.toString()}`);
};

export const fetchSubagentUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolCallRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/subagent-usage?${params.toString()}`);
};

export interface ToolFailureRateRow {
  tool: string;
  calls: number;
  failures: number;
  failureRate: number;
}

export const fetchToolFailureRates = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolFailureRateRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/failure-rates?${params.toString()}`);
};

export interface ToolRepeatStatRow {
  tool: string;
  scope: string;
  medianRunLength: number;
  maxRunLength: number;
  sessions: number;
}

export const fetchToolRepeats = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolRepeatStatRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/repeats?${params.toString()}`);
};

export const fetchToolCallLatency = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolLatencyRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/calls/latency?${params.toString()}`);
};

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

export const fetchTokenUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<TokenUsageSummary> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/sessions/token-usage?${params.toString()}`);
};

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

export const fetchSessions = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
  request: SessionsPageRequest,
): Promise<ListResult<SessionSummaryRow>> => {
  const params = windowQueryParams(selection);
  params.set('page', String(request.page));
  params.set('size', String(request.pageSize));
  params.set('sort', request.sort.field);
  params.set('direction', request.sort.direction);
  return listWithTotalCount<SessionSummaryRow>(`/api/sessions?${params.toString()}`);
};

export interface SessionKpis {
  totalSessions: number;
  medianCostUsd: number;
  p95CostUsd: number;
  medianCostPerActiveMinuteUsd: number;
  /** New-session count per window bucket — the Total-sessions card sparkline. */
  sessionsTrend: number[];
}

export const fetchSessionsSummary = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<SessionKpis> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/sessions/summary?${params.toString()}`);
};

export interface ToolDenialRow {
  tool: string;
  source: string;
  count: number;
}

export const fetchToolDenials = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolDenialRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/denials?${params.toString()}`);
};

export interface HookExecutionRow {
  hookEvent: string;
  hookName: string;
  total: number;
  successes: number;
  blockingErrors: number;
  nonBlockingErrors: number;
  cancelled: number;
}

export const fetchHookExecutions = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<HookExecutionRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/hook-executions?${params.toString()}`);
};

export const fetchMetrics = (
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<ListResult<MetricRow>> => {
  const params = new URLSearchParams();
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  const query = params.toString();
  return listWithTotalCount<MetricRow>(`/api/metrics${query ? `?${query}` : ''}`);
};

export const fetchMetricAttributes = (
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<string[]> => {
  const params = new URLSearchParams();
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  const query = params.toString();
  return getJson(`/api/metrics/attributes${query ? `?${query}` : ''}`);
};

export interface TimeWindow {
  startTimestamp?: string | null;
  endTimestamp?: string | null;
}

const appendTimeWindowParams = (
  params: URLSearchParams,
  window: TimeWindow | null | undefined,
): void => {
  if (!window) {
    return;
  }
  if (window.startTimestamp) {
    params.append('startTimestamp', window.startTimestamp);
  }
  if (window.endTimestamp) {
    params.append('endTimestamp', window.endTimestamp);
  }
};

export const fetchLogs = (
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<ListResult<LogRow>> => {
  const params = new URLSearchParams();
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  const query = params.toString();
  return listWithTotalCount<LogRow>(`/api/logs${query ? `?${query}` : ''}`);
};

export const fetchLogAttributes = (
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<string[]> => {
  const params = new URLSearchParams();
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  const query = params.toString();
  return getJson(`/api/logs/attributes${query ? `?${query}` : ''}`);
};

export const fetchLogAttributeKeys = (
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<string[]> => {
  const params = new URLSearchParams();
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  const query = params.toString();
  return getJson(`/api/logs/attribute-keys${query ? `?${query}` : ''}`);
};

export const fetchLogAttributeValues = (
  key: string,
  filters: string[] = [],
  window?: TimeWindow | null,
): Promise<string[]> => {
  const params = new URLSearchParams({ key });
  for (const filter of filters) {
    params.append('filter', filter);
  }
  appendTimeWindowParams(params, window);
  return getJson(`/api/logs/attribute-values?${params.toString()}`);
};

export const fetchTraces = (
  selection?: WindowSelection | null,
): Promise<ListResult<TraceRow>> => {
  const suffix = selection == null ? '' : `?${windowQueryParams(selection).toString()}`;
  return listWithTotalCount<TraceRow>(`/api/traces${suffix}`);
};

export const fetchTraceSpans = (traceId: string): Promise<SpanRow[]> =>
  getJson(`/api/traces/${encodeURIComponent(traceId)}`);

export const fetchTraceLogs = (traceId: string): Promise<LogRow[]> =>
  getJson(`/api/traces/${encodeURIComponent(traceId)}/logs`);

export const fetchReportMarkdown = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<string> =>
  getText(`/api/report?${windowQueryParams(selection).toString()}`);
