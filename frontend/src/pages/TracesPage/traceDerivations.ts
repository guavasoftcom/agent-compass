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
// service is derived from its prefix. Real Claude Code root spans are
// `claude_code.`-prefixed (`claude_code.tool.execution`, `claude_code.llm_request`,
// `claude_code.interaction`); the sample store uses the bare operation form
// (`tool.execute`, `model.completion`). Strip the prefix, then match. The
// histogram/facets CASE in SpanRepository.java MUST mirror this so client +
// server counts agree.
const SERVICE_BY_PREFIX: Array<[string, string]> = [
  ['interaction', 'claude_code.session'],
  ['session', 'claude_code.session'],
  ['context', 'claude_code.session'],
  ['tool', 'claude_code.tools'],
  ['llm', 'claude_code.models'],
  ['model', 'claude_code.models'],
];
export const serviceOf = (rootSpanName: string | null | undefined): string => {
  if (!rootSpanName) {
    return 'claude_code';
  }
  const operation = rootSpanName.toLowerCase().replace(/^claude_code\./, '');
  const hit = SERVICE_BY_PREFIX.find(([prefix]) => operation.startsWith(prefix));
  return hit ? hit[1] : 'claude_code';
};
// Sub-spans the SDK nests *under* a tool-call span rather than emitting per call:
// `claude_code.tool.execution` (the run itself) and `claude_code.tool.blocked_on_user`
// (the permission wait). Both are ~1:1 with their parent, so counting them as calls
// triples the real figure.
const TOOL_SUBSPAN_OPERATIONS = new Set(['tool.execution', 'tool.blocked_on_user']);

// One entry per actual tool/MCP invocation. Real Claude Code names the call span
// `claude_code.tool` and hangs the sub-spans above off it; the sample store uses the
// bare operation form (`tool.execute`, `tool.Read`, `mcp.connect`). Strip the
// `claude_code.` prefix first — same normalization `serviceOf` does — so both shapes
// go through one rule.
export const isToolCallSpan = (spanName: string | null | undefined): boolean => {
  if (!spanName) {
    return false;
  }
  const operation = spanName.toLowerCase().replace(/^claude_code\./, '');
  if (TOOL_SUBSPAN_OPERATIONS.has(operation)) {
    return false;
  }
  return operation === 'tool' || /^(tool|mcp)\./.test(operation);
};

export const statusOf = (t: TraceRow): TraceStatus => (t.errorCount > 0 ? 'error' : 'ok');
export const durationMsOf = (t: TraceRow): number => t.durationNanos / 1_000_000;

// Canonical nanos→millis divisor, surfaced via tracesApi and consumed by TraceDetailPage's
// spanTree helpers too.
export const NANOS_PER_MILLI = 1_000_000;

export const tokensOf = (t: TraceRow): number =>
  (t as TraceRow & TraceRowTokens).totalTokens ?? 0;

// The trace's initiating user prompt. Null for traces rooted in a tool / model /
// mcp / compaction span (no prompt of their own) and for traces recorded with
// prompt-body capture disabled; both render as "—".
export const promptOf = (t: TraceRow): string | null => t.firstUserPrompt ?? null;

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
