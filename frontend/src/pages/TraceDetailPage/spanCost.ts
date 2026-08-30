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
// Per-span cost in USD — a breakdown figure for per-span display, not the
// trace-level total. The header's Cost KPI and the Token composition card's
// cost line read `TraceRow.totalCostUsd` from the `trace-summary` query
// instead (the backend-authoritative total): spans and the cost-bearing
// api_request logs arrive over separate OTLP endpoints, so summing costOfSpan
// across the spans in hand can disagree with — and undercount relative to —
// the trace total (a request logged without a span id still counts toward the
// trace but has no span to attribute it to).

import type { LogRow, SpanRow } from '../../api';

// The event whose logs carry `cost_usd`. Mirrors the backend's
// `tuning.api-request-event-name` (TuningProperties), the same literal the
// span_costs / trace_costs views (V14) filter on — overriding that property
// means changing it here too.
const API_REQUEST_EVENT_NAME = 'api_request';

// The conversational-turn root span. Normalized the way `serviceOf` /
// `isToolCallSpan` (../TracesPage/traceDerivations) do it — strip the
// `claude_code.` prefix, lowercase — so the check reads the same as every other
// span-name rule on these pages.
const INTERACTION_OPERATION = 'interaction';
const isInteractionSpan = (span: SpanRow): boolean =>
  (span.name ?? '').toLowerCase().replace(/^claude_code\./, '') === INTERACTION_OPERATION;

// A span's cost, as reported by the backend's span_costs view: the summed
// cost_usd of the api_request logs stamped with this span's id. There is
// deliberately no client-side estimate behind this — pricing a span's tokens at
// published rates ran 2-3x off real spend and, because the Sessions page reports
// what Claude Code actually billed, made a prompt's cost disagree with the cost
// on the trace it links to. A span with no logged request contributes 0.
export const costOfSpan = (span: SpanRow): number => {
  if (typeof span.costUsd === 'number' && Number.isFinite(span.costUsd)) {
    return span.costUsd;
  }
  return 0;
};

// The cost of the API calls this span itself made, summed from the api_request
// logs bucketed onto it. Distinct from costOfSpan, which reads the span_costs
// view: Claude Code stamps those logs with the span that was *active* when the
// request went out (the interaction root, or a tool.execution span), never the
// llm_request child that represents the call, so span_costs attributes a whole
// turn's spend to its root and leaves every llm_request row at 0. LogService's
// resolveLeafSpans re-points each root-stranded log onto its true span by
// request_id before the frontend ever sees it, so the logs bucketed onto an
// llm_request span are exactly that call's own — which is where this number
// comes from. Summed rather than first-match: nothing guarantees a span issued
// only one request. Returns 0 when the span has no cost-bearing request log.
export const costOfSpanRequests = (logs: LogRow[] | undefined): number => {
  if (!logs || logs.length === 0) {
    return 0;
  }
  let total = 0;
  for (const log of logs) {
    if (log.attributes?.['event.name'] !== API_REQUEST_EVENT_NAME) {
      continue;
    }
    const cost = log.attributes?.['cost_usd'];
    if (typeof cost === 'number' && Number.isFinite(cost)) {
      total += cost;
    }
  }
  return total;
};

// What the waterfall row and the inspector show for a span, preferring
// whichever of the two figures above actually describes it.
//
// costOfSpan wins when it is non-zero, because a span carrying a span_costs row
// is the span the requests were stamped against and its figure covers all of
// them — including any the log bucketing didn't reach. Only when it is 0 does
// the span's own request logs answer, which is the llm_request case: the row
// that made the call but was never stamped with the cost. The two are never
// added, so a turn's spend can't be counted twice on one span.
//
// This is display-only and deliberately not pushed into span_costs (V14): that
// view's per-span rollup is what answers "what did this Agent call cost" on a
// tool.execution span, and re-keying it to request_id would disperse that
// figure across the subagent's children and move 37 cross-trace requests out of
// the trace their logs were recorded under.
//
// A `claude_code.interaction` span reports 0 here regardless of what it was
// stamped with: its rollup covers the whole turn, which is the trace-level
// figure the header's Cost KPI already states — repeating it on the root row
// and in that row's drawer only invited reading it as a per-span cost and
// summing it with the llm_request rows below. Suppressed at display time on
// purpose: `costOfSpan` still reports the span_costs figure for anything that
// needs the real number.
export const costOfSelectedSpan = (span: SpanRow, logs: LogRow[] | undefined): number => {
  if (isInteractionSpan(span)) {
    return 0;
  }
  const stampedCost = costOfSpan(span);
  if (stampedCost > 0) {
    return stampedCost;
  }
  return costOfSpanRequests(logs);
};
