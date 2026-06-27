// ============================================================================
// Traces page — sample data: a 24h synthetic OTel trace store + an in-memory
// query engine that emulates the endpoints. Used only when VITE_TRACES_SAMPLE=1
// (see tracesApi.ts); the shapes are identical to the live endpoints.
// ============================================================================

import type { SpanRow, TraceRow } from '../../api';
import type {
  FacetKey,
  TraceCursor,
  TraceCursorPage,
  TraceFacets,
  TraceHistogram,
  TraceHistogramBucket,
  TraceSortKey,
  TraceStatus,
  TracesFilters,
  TracesListResult,
} from './traceTypes';
import {
  DURATION_BUCKETS,
  durationBucketOf,
  quantile,
  serviceOf,
} from './traceDerivations';

const MIN = 60_000;
const HOUR = 60 * MIN;

let seed = 90125;
const rnd = () => {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff;
  return seed / 0x7fffffff;
};
const pick = <T,>(a: readonly T[]): T => a[Math.floor(rnd() * a.length)];
const ri = (lo: number, hi: number) => Math.floor(lo + rnd() * (hi - lo + 1));
const HEX = '0123456789abcdef';
const hx = (n: number) => {
  let s = '';
  for (let i = 0; i < n; i += 1) {
    s += HEX[Math.floor(rnd() * 16)];
  }
  return s;
};
const wpick = (pairs: Array<[string, number]>): string => {
  let tot = 0;
  pairs.forEach((p) => {
    tot += p[1];
  });
  let r = rnd() * tot;
  for (const p of pairs) {
    r -= p[1];
    if (r <= 0) {
      return p[0];
    }
  }
  return pairs[0][0];
};

interface OpCfg {
  lo: number;
  hi: number;
  err: number;
  w: number;
}
const OPS: Record<string, OpCfg> = {
  'session.turn': { lo: 1800, hi: 46000, err: 0.05, w: 30 },
  'tool.execute': { lo: 9, hi: 4200, err: 0.08, w: 26 },
  'model.completion': { lo: 380, hi: 12500, err: 0.03, w: 20 },
  'mcp.tool.call': { lo: 45, hi: 5200, err: 0.13, w: 12 },
  'subagent.run': { lo: 5200, hi: 61000, err: 0.06, w: 7 },
  'context.compaction': { lo: 900, hi: 8400, err: 0.02, w: 5 },
};

const NOW = Date.now();
const WINDOW_MS = 24 * HOUR;
const T0 = NOW - WINDOW_MS;
const SESSIONS = Array.from({ length: 14 }, () => `sess_${hx(8)}`);

interface SampleTrace extends TraceRow {
  _ts: number;
  _ms: number;
  _status: TraceStatus;
  _service: string;
  totalTokens: number;
}

const diurnal = (frac: number) => {
  const h = frac * 24;
  const g = (c: number, sd: number) => Math.exp(-((h - c) * (h - c)) / (2 * sd * sd));
  return 0.22 + 0.75 * g(10.5, 3) + 1.0 * g(15, 3.4) + 0.55 * g(21, 2.4);
};

const STORE: SampleTrace[] = (() => {
  const rows: SampleTrace[] = [];
  for (let i = 0; i < 540; i += 1) {
    const op = wpick(Object.entries(OPS).map(([k, v]) => [k, v.w] as [string, number]));
    const cfg = OPS[op];
    const u = rnd() * rnd();
    const ms = Math.round(cfg.lo + u * (cfg.hi - cfg.lo));
    let frac = rnd();
    while (rnd() > diurnal(frac) / 1.85) {
      frac = rnd();
    }
    const startMs = T0 + frac * WINDOW_MS;
    const errorCount = rnd() < cfg.err ? ri(1, 3) : 0;
    const spanCount = op === 'session.turn' || op === 'subagent.run' ? ri(8, 32) : ri(3, 9);
    // Model-bearing operations accrue tokens (cache-read dominant in practice);
    // pure tool / mcp ops carry none, so their Tokens cell reads “—”.
    const heavy = op === 'session.turn' || op === 'subagent.run';
    const modelBearing = heavy || op === 'model.completion' || op === 'context.compaction';
    const totalTokens = modelBearing ? ri(heavy ? 40000 : 6000, heavy ? 540000 : 95000) : 0;
    rows.push({
      traceId: hx(32),
      rootSpanId: hx(16),
      sessionId: pick(SESSIONS),
      startTimestamp: new Date(startMs).toISOString(),
      rootSpanName: op,
      spanCount,
      durationNanos: ms * 1e6,
      errorCount,
      totalTokens,
      _ts: startMs,
      _ms: ms,
      _status: errorCount > 0 ? 'error' : 'ok',
      _service: serviceOf(op),
    });
  }
  rows.sort((a, b) => b._ts - a._ts);
  return rows;
})();

const inWindow = (r: SampleTrace, f: TracesFilters) =>
  r._ts >= Date.parse(f.startTimestamp) && r._ts <= Date.parse(f.endTimestamp);

const matches = (r: SampleTrace, f: TracesFilters, exclude: FacetKey | 'q' | null): boolean => {
  if (!inWindow(r, f)) {
    return false;
  }
  if (exclude !== 'q' && f.q) {
    const hay = `${r.traceId} ${r.sessionId ?? ''} ${r.rootSpanName}`.toLowerCase();
    if (!hay.includes(f.q.toLowerCase())) {
      return false;
    }
  }
  if (exclude !== 'status' && f.status?.length && !f.status.includes(r._status)) {
    return false;
  }
  if (exclude !== 'operation' && f.operation?.length && !f.operation.includes(r.rootSpanName)) {
    return false;
  }
  if (exclude !== 'service' && f.service?.length && !f.service.includes(r._service)) {
    return false;
  }
  if (exclude !== 'session' && f.session?.length && (r.sessionId == null || !f.session.includes(r.sessionId))) {
    return false;
  }
  if (exclude !== 'duration' && f.duration?.length) {
    const b = durationBucketOf(r._ms);
    if (!b || !f.duration.includes(b.id)) {
      return false;
    }
  }
  return true;
};

const NICE = [MIN, 2 * MIN, 5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR];
const niceBucket = (spanMs: number, target: number) => {
  const raw = spanMs / target;
  return NICE.find((n) => n >= raw) ?? NICE[NICE.length - 1];
};
const latency = (ms: number) => new Promise<void>((res) => setTimeout(res, ms));

const SORTS: Record<TraceSortKey, (a: SampleTrace, b: SampleTrace) => number> = {
  new: (a, b) => b._ts - a._ts,
  old: (a, b) => a._ts - b._ts,
  slow: (a, b) => b._ms - a._ms,
  fast: (a, b) => a._ms - b._ms,
  spans: (a, b) => b.spanCount - a.spanCount,
  tokens: (a, b) => b.totalTokens - a.totalTokens || b._ts - a._ts,
  err: (a, b) => b.errorCount - a.errorCount || b._ts - a._ts,
};

const stripInternal = (r: SampleTrace): TraceRow => {
  const { _ts, _ms, _status, _service, ...row } = r;
  void _ts;
  void _ms;
  void _status;
  void _service;
  return row;
};

export const sampleHistogram = async (f: TracesFilters, target: number): Promise<TraceHistogram> => {
  await latency(70);
  const start = Date.parse(f.startTimestamp);
  const end = Date.parse(f.endTimestamp);
  const bw = niceBucket(end - start, target);
  const nb = Math.max(1, Math.ceil((end - start) / bw));
  const buckets = Array.from({ length: nb }, (_, i) => ({
    t0: new Date(start + i * bw).toISOString(),
    t1: new Date(start + (i + 1) * bw).toISOString(),
    ok: 0,
    error: 0,
    p95Ms: 0,
    _lat: [] as number[],
  }));
  const all: number[] = [];
  let errs = 0;
  STORE.forEach((r) => {
    if (!matches(r, f, null)) {
      return;
    }
    const idx = Math.floor((r._ts - start) / bw);
    if (idx < 0 || idx >= nb) {
      return;
    }
    buckets[idx][r._status] += 1;
    buckets[idx]._lat.push(r._ms);
    all.push(r._ms);
    if (r._status === 'error') {
      errs += 1;
    }
  });
  all.sort((a, b) => a - b);
  const out: TraceHistogramBucket[] = buckets.map((b) => {
    const lat = b._lat.sort((a, c) => a - c);
    return { t0: b.t0, t1: b.t1, ok: b.ok, error: b.error, p95Ms: quantile(lat, 0.95) };
  });
  return { bucketMs: bw, buckets: out, p50Ms: quantile(all, 0.5), p95Ms: quantile(all, 0.95), total: all.length, errorCount: errs };
};

export const sampleFacets = async (f: TracesFilters): Promise<TraceFacets> => {
  await latency(60);
  const count = (key: FacetKey, field: (r: SampleTrace) => string | null) => {
    const m = new Map<string, number>();
    STORE.forEach((r) => {
      if (!matches(r, f, key)) {
        return;
      }
      const v = field(r);
      if (v == null) {
        return;
      }
      m.set(v, (m.get(v) ?? 0) + 1);
    });
    return m;
  };
  const toRows = (m: Map<string, number>) =>
    [...m.entries()].map(([value, c]) => ({ value, count: c })).sort((a, b) => b.count - a.count);
  const statusMap = count('status', (r) => r._status);
  const durMap = count('duration', (r) => durationBucketOf(r._ms)?.id ?? null);
  return {
    status: (['ok', 'error'] as const).map((s) => ({ value: s, count: statusMap.get(s) ?? 0 })),
    operation: toRows(count('operation', (r) => r.rootSpanName)),
    service: toRows(count('service', (r) => r._service)),
    duration: DURATION_BUCKETS.map((b) => ({ value: b.id, count: durMap.get(b.id) ?? 0 })),
    session: toRows(count('session', (r) => r.sessionId)).slice(0, 8),
  };
};

const sortStore = (f: TracesFilters, sort: TraceSortKey) =>
  STORE.filter((r) => matches(r, f, null)).sort(SORTS[sort]);

export const sampleCursor = async (
  f: TracesFilters,
  sort: TraceSortKey,
  cursor: TraceCursor | null,
  limit: number,
): Promise<TraceCursorPage> => {
  await latency(220);
  const all = sortStore(f, sort);
  let startIdx = 0;
  if (cursor) {
    const at = all.findIndex((r) => r.traceId === cursor.id);
    startIdx = at >= 0 ? at + 1 : 0;
  }
  const page = all.slice(startIdx, startIdx + limit);
  const last = page[page.length - 1];
  return {
    items: page.map(stripInternal),
    nextCursor: last ? { ts: last.startTimestamp, id: last.traceId } : null,
    hasMore: startIdx + limit < all.length,
    totalCount: all.length,
  };
};

export const samplePage = async (
  f: TracesFilters,
  sort: TraceSortKey,
  page: number,
  size: number,
): Promise<TracesListResult> => {
  await latency(180);
  const all = sortStore(f, sort);
  const start = page * size;
  return { items: all.slice(start, start + size).map(stripInternal), totalCount: all.length };
};

// On-demand synthetic span tree for a trace (inline waterfall in the Stream).
// Seeded by traceId so the same trace always expands to the same shape.
const TOOLS = ['Read', 'Edit', 'Write', 'Bash', 'Grep', 'Glob', 'WebFetch', 'Task'];
const MCP = ['github.search_issues', 'github.create_pr', 'linear.list_issues', 'postgres.query'];
export const sampleSpans = async (traceId: string): Promise<SpanRow[]> => {
  await latency(120);
  const t = STORE.find((r) => r.traceId === traceId);
  if (!t) {
    return [];
  }
  let s = 0;
  for (let i = 0; i < traceId.length; i += 1) {
    s = (s * 31 + traceId.charCodeAt(i)) & 0x7fffffff;
  }
  const r2 = () => {
    s = (s * 1103515245 + 12345) & 0x7fffffff;
    return s / 0x7fffffff;
  };
  const totalMs = t._ms;
  const startMs = t._ts;
  const op = t.rootSpanName;
  const spans: SpanRow[] = [];
  const mk = (
    spanId: string,
    parentSpanId: string | null,
    name: string,
    kind: string,
    offMs: number,
    durMs: number,
    error: boolean,
    extraAttrs?: Record<string, unknown>,
  ): SpanRow => ({
    id: spans.length + 1,
    spanId,
    parentSpanId,
    traceId,
    name,
    kind,
    startTimestamp: new Date(startMs + offMs).toISOString(),
    endTimestamp: new Date(startMs + offMs + durMs).toISOString(),
    durationNanos: durMs * 1e6,
    statusCode: error ? 'error' : 'ok',
    statusMessage: error ? 'span reported a non-OK status' : null,
    scopeName: serviceOf(name),
    attributes: { 'session.id': t.sessionId, ...extraAttrs },
    events: null,
    resourceAttributes: { 'service.name': 'claude-code' },
  });
  const rootId = t.rootSpanId ?? hx(16);
  spans.push(mk(rootId, null, op, 'server', 0, totalMs, false));
  const childCount = Math.min(t.spanCount - 1, op === 'session.turn' || op === 'subagent.run' ? ri(5, 9) : ri(2, 4));
  const head = totalMs * 0.02;
  const span = (totalMs * 0.94) / Math.max(1, childCount);
  let errorsLeft = t.errorCount;
  for (let i = 0; i < childCount; i += 1) {
    const off = head + i * span + (r2() - 0.5) * span * 0.2;
    const dur = span * (0.45 + r2() * 0.5);
    let name = 'tool.execute';
    let kind = 'internal';
    if (op === 'session.turn' || op === 'subagent.run') {
      const kp = r2();
      if (kp < 0.45) {
        name = 'model.completion';
        kind = 'client';
      } else if (kp < 0.6) {
        name = `mcp.${MCP[Math.floor(r2() * MCP.length)]}`;
        kind = 'client';
      } else {
        name = `tool.${TOOLS[Math.floor(r2() * TOOLS.length)]}`;
      }
    } else if (op === 'model.completion') {
      name = ['model.tokenize', 'model.sample', 'model.stream'][Math.floor(r2() * 3)];
    } else if (op === 'mcp.tool.call') {
      name = ['mcp.connect', 'mcp.request', 'mcp.parse_result'][Math.floor(r2() * 3)];
    }
    const isErr = errorsLeft > 0 && i >= childCount - errorsLeft;
    if (isErr) {
      errorsLeft -= 1;
    }
    // model.completion spans carry per-span token usage (cache-read dominant),
    // mirroring real gen_ai.usage.* attributes so the inline summary's token
    // composition and the detail page agree.
    let extraAttrs: Record<string, unknown> | undefined;
    if (name === 'model.completion') {
      const out = ri(180, 1400);
      extraAttrs = {
        'gen_ai.usage.input_tokens': ri(900, 4200),
        'gen_ai.usage.output_tokens': out,
        'gen_ai.usage.cache_read_input_tokens': ri(12000, 90000),
        'gen_ai.usage.cache_creation_input_tokens': ri(0, 3500),
      };
    }
    spans.push(mk(hx(16), rootId, name, kind, Math.max(0, off), Math.max(2, dur), isErr, extraAttrs));
  }
  return spans;
};
