/*
Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.
*/
// The cross-page dashboard fetchers. Each `fetchXxx(selection)` flattens the
// window selection into query params and returns a typed payload. Endpoints that
// serve exactly one page live in a page-local module instead (see
// `pages/LogsPage/logsApi.ts`, `pages/TracesPage/tracesApi.ts`, etc.).

import { getJson, getText, listWithTotalCount, windowQueryParams } from './http';
import type {
  CostBreakdown,
  HookExecutionRow,
  IdentifierUsageRow,
  ListResult,
  LogRow,
  McpServerUsageRow,
  SessionCacheEfficiencyRow,
  SessionKpis,
  SessionPromptRow,
  SessionsPageRequest,
  SessionSummaryRow,
  SpanRow,
  ToolCallRow,
  ToolCallTimeseries,
  ToolContextFootprintRow,
  ToolDenialRow,
  ToolFailureRateRow,
  ToolLatencyRow,
  ToolRepeatStatRow,
  TokenUsageSummary,
  TraceRow,
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
): Promise<IdentifierUsageRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/skill-usage?${params.toString()}`);
};

export const fetchSubagentUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<IdentifierUsageRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/subagent-usage?${params.toString()}`);
};

export const fetchMcpServerUsage = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<McpServerUsageRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/mcp-usage?${params.toString()}`);
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

// Sessions ranked worst-cache-efficiency-first. The server applies the noise
// floor (default 100k input-side tokens) and the ranking, so an empty array
// legitimately means "no session in this window is big enough to judge".
export const fetchSessionCacheEfficiency = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
  limit = 8,
): Promise<SessionCacheEfficiencyRow[]> => {
  const params = windowQueryParams(selection);
  params.set('limit', String(limit));
  return getJson(`/api/sessions/cache-efficiency?${params.toString()}`);
};

// Per-tool context-window footprint, largest total bytes first.
export const fetchToolContextFootprint = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<ToolContextFootprintRow[]> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/tool-activity/context-footprint?${params.toString()}`);
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

// Aggregate row for one trace — same shape as a Traces list row, including
// firstUserPrompt. Not window-scoped, so a permalinked trace always resolves;
// 404s when no spans carry the trace id.
export const fetchTraceSummary = (traceId: string): Promise<TraceRow> =>
  getJson(`/api/traces/${encodeURIComponent(traceId)}/summary`);

export const fetchCostBreakdown = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<CostBreakdown> => {
  const params = windowQueryParams(selection);
  return getJson(`/api/cost/breakdown?${params.toString()}`);
};

export const fetchReportMarkdown = (
  selection: WindowSelection = { kind: 'preset', minutes: 1440 },
): Promise<string> =>
  getText(`/api/report?${windowQueryParams(selection).toString()}`);
