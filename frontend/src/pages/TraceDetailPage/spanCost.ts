// Per-span cost in USD — a breakdown figure for per-span display, not the
// trace-level total. The header's Cost KPI and the Token composition card's
// cost line read `TraceRow.totalCostUsd` from the `trace-summary` query
// instead (the backend-authoritative total): spans and the cost-bearing
// api_request logs arrive over separate OTLP endpoints, so summing costOfSpan
// across the spans in hand can disagree with — and undercount relative to —
// the trace total (a request logged without a span id still counts toward the
// trace but has no span to attribute it to).

import type { SpanRow } from '../../api';

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
