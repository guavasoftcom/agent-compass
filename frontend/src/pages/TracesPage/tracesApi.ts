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

import type { SpanRow, TraceRow } from '../../api';
import { fetchTraceSpans, fetchTraceSummary } from '../../api';
import { getJson } from '../../api/http';
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
  sampleTraceSummary,
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
  return getJson<TraceHistogram>(`/api/traces/histogram?${p.toString()}`);
};

export const fetchTraceFacets = (f: TracesFilters): Promise<TraceFacets> => {
  if (USE_SAMPLE_DATA) {
    return sampleFacets(f);
  }
  return getJson<TraceFacets>(`/api/traces/facets?${buildTracesQuery(f).toString()}`);
};

export const fetchTracesCursor = (
  f: TracesFilters,
  opts: { sort: TraceSortKey; cursor?: TraceCursor | null; after?: TraceCursor | null; limit?: number },
  signal?: AbortSignal,
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
  return getJson<TraceCursorPage>(`/api/traces?${p.toString()}`, signal);
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
  return getJson<TracesListResult>(`/api/traces?${p.toString()}`);
};

// Inline waterfall reuses the existing per-trace span endpoint (api.ts).
export const fetchSpansForTrace = (traceId: string): Promise<SpanRow[]> => {
  if (USE_SAMPLE_DATA) {
    return sampleSpans(traceId);
  }
  return fetchTraceSpans(traceId);
};

// One trace's aggregate row — the trace detail header's source for
// `firstUserPrompt`, which the spans response can't carry. Resolves to null on a
// 404 (unknown trace id) so the header just omits the prompt row instead of
// failing the page; the waterfall query owns the real not-found state.
export const fetchTraceSummaryOrNull = (traceId: string): Promise<TraceRow | null> => {
  if (USE_SAMPLE_DATA) {
    return sampleTraceSummary(traceId);
  }
  return fetchTraceSummary(traceId).catch(() => null);
};
