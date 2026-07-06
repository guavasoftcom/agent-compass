// The cross-page dashboard fetchers. Each `fetchXxx(selection)` flattens the
// window selection into query params and returns a typed payload. Endpoints that
// serve exactly one page live in a page-local module instead (see
// `pages/LogsPage/logsApi.ts`, `pages/TracesPage/tracesApi.ts`, etc.).

import { getJson, getText, listWithTotalCount, windowQueryParams } from './http';
import type {
  HookExecutionRow,
  ListResult,
  LogRow,
  SessionKpis,
  SessionPromptRow,
  SessionsPageRequest,
  SessionSummaryRow,
  SpanRow,
  ToolCallRow,
  ToolCallTimeseries,
  ToolDenialRow,
  ToolFailureRateRow,
  ToolLatencyRow,
  ToolRepeatStatRow,
  TokenUsageSummary,
  WindowSelection,
} from './types';

export const fetchToolCalls = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolCallRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/calls?${params.toString()}`);
};

export const fetchToolCallsTimeseries = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
  topN = 8,
): Promise<ToolCallTimeseries> => {
  const params = windowQueryParams(selection);
  params.set('topN', String(topN));
  return getJson(`/api/tool-activity/calls/timeseries?${params.toString()}`);
};

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

export const fetchToolFailureRates = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolFailureRateRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/failure-rates?${params.toString()}`);
};

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

export const fetchTokenUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<TokenUsageSummary> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/sessions/token-usage?${params.toString()}`);
};

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

export const fetchSessionsSummary = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<SessionKpis> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/sessions/summary?${params.toString()}`);
};

// Full, untruncated prompt timeline for one session — not window-scoped, no
// query params, max 500 rows, ascending by time.
export const fetchSessionPrompts = (
  sessionId: string,
): Promise<SessionPromptRow[]> =>
  getJson(`/api/sessions/${encodeURIComponent(sessionId)}/prompts`);

export const fetchToolDenials = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolDenialRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/denials?${params.toString()}`);
};

export const fetchHookExecutions = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<HookExecutionRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/hook-executions?${params.toString()}`);
};

export const fetchTraceSpans = (traceId: string): Promise<SpanRow[]> =>
  getJson(`/api/traces/${encodeURIComponent(traceId)}`);

export const fetchTraceLogs = (traceId: string): Promise<LogRow[]> =>
  getJson(`/api/traces/${encodeURIComponent(traceId)}/logs`);

export const fetchReportMarkdown = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<string> =>
  getText(`/api/report?${windowQueryParams(selection).toString()}`);
