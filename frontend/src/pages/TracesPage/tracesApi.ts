// Traces page — network layer.
//
// Mirrors the recommended backend split (see BACKEND.md, "Traces page"):
//   • fetchTraceHistogram → GET /api/traces/histogram   (server-aggregated throughput + p95)
//   • fetchTraceFacets    → GET /api/traces/facets       (server-aggregated counts)
//   • fetchTracesCursor   → GET /api/traces?before/after (keyset paging — Stream + live tail)
//   • fetchTracesPage     → GET /api/traces?page&size    (offset paging — Table)
//   • fetchSpansForTrace  → GET /api/traces/:id          (existing — inline waterfall)
//
// Defaults to the LIVE endpoints. Set VITE_TRACES_SAMPLE=1 to force the synthetic
// fixtures (offline UI work); the components bind to the same shapes either way.
//
// Types live in ./traceTypes, derivation helpers + buildTracesQuery in
// ./traceDerivations, and the synthetic store in ./tracesSampleData. All three
// are re-exported here so existing `from '…/tracesApi'` imports keep working.

import type { SpanRow } from '../../api';
import { fetchTraceSpans } from '../../api';
import type {
  TraceCursor,
  TraceCursorPage,
  TraceFacets,
  TraceHistogram,
  TraceSortKey,
  TracesFilters,
  TracesListResult,
} from './traceTypes';
import { buildTracesQuery } from './traceDerivations';
import {
  sampleCursor,
  sampleFacets,
  sampleHistogram,
  samplePage,
  sampleSpans,
} from './tracesSampleData';

export * from './traceTypes';
export * from './traceDerivations';

const env = (import.meta as unknown as { env?: Record<string, string> }).env ?? {};
const USE_SAMPLE_DATA = env.VITE_TRACES_SAMPLE === '1';

// ============================================================================
// public API — swap the sample branch for real fetches when the endpoints exist
// ============================================================================

export const fetchTraceHistogram = (f: TracesFilters, buckets = 48): Promise<TraceHistogram> => {
  if (USE_SAMPLE_DATA) {
    return sampleHistogram(f, buckets);
  }
  const p = buildTracesQuery(f);
  p.set('buckets', String(buckets));
  return fetch(`/api/traces/histogram?${p.toString()}`).then((r) => r.json() as Promise<TraceHistogram>);
};

export const fetchTraceFacets = (f: TracesFilters): Promise<TraceFacets> => {
  if (USE_SAMPLE_DATA) {
    return sampleFacets(f);
  }
  return fetch(`/api/traces/facets?${buildTracesQuery(f).toString()}`).then((r) => r.json() as Promise<TraceFacets>);
};

export const fetchTracesCursor = (
  f: TracesFilters,
  opts: { sort: TraceSortKey; cursor?: TraceCursor | null; after?: TraceCursor | null; limit?: number },
): Promise<TraceCursorPage> => {
  const limit = opts.limit ?? 60;
  if (USE_SAMPLE_DATA) {
    if (opts.after) {
      return sampleCursor(f, opts.sort, null, limit);
    }
    return sampleCursor(f, opts.sort, opts.cursor ?? null, limit);
  }
  const p = buildTracesQuery(f);
  p.set('sort', opts.sort);
  p.set('limit', String(limit));
  if (opts.cursor) {
    p.set('before', `${opts.cursor.ts},${opts.cursor.id}`);
  }
  if (opts.after) {
    p.set('after', `${opts.after.ts},${opts.after.id}`);
  }
  return fetch(`/api/traces?${p.toString()}`).then((r) => r.json() as Promise<TraceCursorPage>);
};

export const fetchTracesPage = (
  f: TracesFilters,
  sort: TraceSortKey,
  page: number,
  size: number,
): Promise<TracesListResult> => {
  if (USE_SAMPLE_DATA) {
    return samplePage(f, sort, page, size);
  }
  const p = buildTracesQuery(f);
  p.set('sort', sort);
  p.set('page', String(page));
  p.set('size', String(size));
  return fetch(`/api/traces?${p.toString()}`).then((r) => r.json() as Promise<TracesListResult>);
};

// Inline waterfall reuses the existing per-trace span endpoint (api.ts).
export const fetchSpansForTrace = (traceId: string): Promise<SpanRow[]> => {
  if (USE_SAMPLE_DATA) {
    return sampleSpans(traceId);
  }
  return fetchTraceSpans(traceId);
};
