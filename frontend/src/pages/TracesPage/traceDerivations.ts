// Traces page — shared derivation helpers + query serialization.
// Pulled out of tracesApi.ts so the live fetchers and the sample-data engine
// share one implementation (and the histogram/facets groupings stay in sync).

import type { TraceRow } from '../../api';
import type {
  DurationBucket,
  TraceRowTokens,
  TraceStatus,
  TracesFilters,
} from './traceTypes';

// ---- shared derivation helpers (server mirrors these groupings) ------------
// TraceRow has no `service` field; the operation is `rootSpanName` and the
// service is derived from its dotted prefix. The histogram/facets endpoints
// MUST group by the same mapping so client + server counts agree.
const SERVICE_BY_PREFIX: Array<[string, string]> = [
  ['session.', 'claude_code.session'],
  ['context.', 'claude_code.session'],
  ['tool.', 'claude_code.tools'],
  ['model.', 'claude_code.models'],
  ['mcp.', 'mcp.client'],
  ['subagent.', 'claude_code.subagents'],
];
export const serviceOf = (rootSpanName: string | null | undefined): string => {
  if (!rootSpanName) {
    return 'claude_code';
  }
  const hit = SERVICE_BY_PREFIX.find(([p]) => rootSpanName.startsWith(p));
  return hit ? hit[1] : 'claude_code';
};
export const statusOf = (t: TraceRow): TraceStatus => (t.errorCount > 0 ? 'error' : 'ok');
export const durationMsOf = (t: TraceRow): number => t.durationNanos / 1_000_000;

// Canonical nanos→millis divisor, surfaced via tracesApi and consumed by TraceDetailPage's
// spanTree helpers too.
export const NANOS_PER_MILLI = 1_000_000;

export const tokensOf = (t: TraceRow): number =>
  (t as TraceRow & TraceRowTokens).totalTokens ?? 0;
export const formatTokens = (n: number): string => {
  if (n >= 1e6) {
    return `${(n / 1e6).toFixed(2).replace(/\.?0+$/, '')}M`;
  }
  if (n >= 1e3) {
    return `${(n / 1e3).toFixed(1).replace(/\.0$/, '')}K`;
  }
  return String(n);
};

export const DURATION_BUCKETS: DurationBucket[] = [
  { id: 'd0', label: '< 100 ms', test: (d) => d < 100 },
  { id: 'd1', label: '100 ms – 1 s', test: (d) => d >= 100 && d < 1000 },
  { id: 'd2', label: '1 s – 5 s', test: (d) => d >= 1000 && d < 5000 },
  { id: 'd3', label: '> 5 s', test: (d) => d >= 5000 },
];
export const durationBucketOf = (ms: number): DurationBucket | undefined =>
  DURATION_BUCKETS.find((b) => b.test(ms));

export const formatDuration = (nanos: number | null | undefined): string => {
  if (nanos == null) {
    return '';
  }
  const ms = nanos / 1_000_000;
  if (ms >= 1000) {
    return `${(ms / 1000).toFixed(2)} s`;
  }
  if (ms >= 10) {
    return `${Math.round(ms)} ms`;
  }
  return `${ms.toFixed(1)} ms`;
};

export const quantile = (sortedAsc: number[], q: number): number => {
  if (sortedAsc.length === 0) {
    return 0;
  }
  const pos = (sortedAsc.length - 1) * q;
  const base = Math.floor(pos);
  const rest = pos - base;
  const next = sortedAsc[base + 1];
  return next !== undefined ? sortedAsc[base] + rest * (next - sortedAsc[base]) : sortedAsc[base];
};

// serialize filters → query string (the real fetchers use this verbatim)
export const buildTracesQuery = (f: TracesFilters): URLSearchParams => {
  const p = new URLSearchParams();
  p.set('startTimestamp', f.startTimestamp);
  p.set('endTimestamp', f.endTimestamp);
  (f.status ?? []).forEach((v) => p.append('status', v));
  (f.operation ?? []).forEach((v) => p.append('operation', v));
  (f.service ?? []).forEach((v) => p.append('service', v));
  (f.duration ?? []).forEach((v) => p.append('duration', v));
  (f.session ?? []).forEach((v) => p.append('session', v));
  if (f.q) {
    p.set('q', f.q);
  }
  return p;
};
