// Traces page — shared type definitions (no runtime).
// Split out of tracesApi.ts so both the live network layer and the sample-data
// engine can depend on these without a circular import.

import type { FacetValue, TraceRow } from '../../api';

export type TraceStatus = 'ok' | 'error';
export type FacetKey = 'status' | 'operation' | 'service' | 'duration' | 'session';
export type TraceSortKey =
  | 'new'
  | 'old'
  | 'slow'
  | 'fast'
  | 'spans'
  | 'tokens'
  | 'cost'
  | 'err';

export interface TracesFilters {
  startTimestamp: string;
  endTimestamp: string;
  status?: TraceStatus[];
  operation?: string[];
  service?: string[];
  /** duration-bucket ids (d0–d3) */
  duration?: string[];
  session?: string[];
  /** full-text over traceId / sessionId / rootSpanName */
  q?: string;
}

export interface TraceHistogramBucket {
  t0: string;
  t1: string;
  ok: number;
  error: number;
  /** p95 latency (ms) of traces starting in this bucket — drives the overlay line */
  p95Ms: number;
}
export interface TraceHistogram {
  bucketMs: number;
  buckets: TraceHistogramBucket[];
  /** window-wide aggregates for the histogram header */
  p50Ms: number;
  p95Ms: number;
  total: number;
  errorCount: number;
}

export type { FacetValue };
export interface TraceFacets {
  status: FacetValue[];
  operation: FacetValue[];
  service: FacetValue[];
  duration: FacetValue[];
  session: FacetValue[];
}

export interface TraceCursor {
  ts: string;
  id: string;
}
export interface TraceCursorPage {
  items: TraceRow[];
  nextCursor: TraceCursor | null;
  hasMore: boolean;
  totalCount: number;
}
export interface TracesListResult {
  items: TraceRow[];
  totalCount: number;
}

// `totalTokens` and `totalCostUsd` are plain fields on TraceRow (see
// api/types.ts) — the sum of token usage, and of model spend in USD, across the
// trace's spans. Both list endpoints return them and sample mode synthesizes
// them, so no widening cast is needed to read either.

export interface DurationBucket {
  id: string;
  label: string;
  test: (ms: number) => boolean;
}
