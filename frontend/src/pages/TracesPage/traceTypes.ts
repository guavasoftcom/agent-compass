// Traces page — shared type definitions (no runtime).
// Split out of tracesApi.ts so both the live network layer and the sample-data
// engine can depend on these without a circular import.

import type { FacetValue, TraceRow } from '../../api';

export type TraceStatus = 'ok' | 'error';
export type FacetKey = 'status' | 'operation' | 'service' | 'duration' | 'session';
export type TraceSortKey = 'new' | 'old' | 'slow' | 'fast' | 'spans' | 'tokens' | 'err';

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

// `totalTokens` is an additive field the Traces list endpoints should return
// (sum of token usage across the trace's spans). The base TraceRow type doesn't
// carry it yet, so we read it through a widening cast; sample mode synthesizes
// it. See BACKEND.md ("Traces page · token total").
export interface TraceRowTokens {
  totalTokens?: number;
}

export interface DurationBucket {
  id: string;
  label: string;
  test: (ms: number) => boolean;
}
