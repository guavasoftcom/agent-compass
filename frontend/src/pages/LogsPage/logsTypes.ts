// Logs page — shared DTO types. The shapes returned by the /api/logs/* endpoints
// (and emulated by the sample-data store in ./logsSampleData), plus the
// LogsFilters request shape and the Severity / FacetKey enums that the histogram,
// facet rail, stream, and table all share. Re-exported from ./logsApi so consumers
// import everything from one place.

import type { FacetValue, LogRow } from '../../api';

export const SEVERITIES = ['ERROR', 'WARN', 'INFO', 'DEBUG'] as const;
export type Severity = (typeof SEVERITIES)[number];

export type FacetKey = 'severity' | 'event' | 'tool';

export interface LogsFilters {
  startTimestamp: string;
  endTimestamp: string;
  /** existing key=value attribute chips (unchanged contract) */
  filter?: string[];
  severity?: Severity[];
  event?: string[];
  tool?: string[];
  /** full-text over body + serialized attributes */
  q?: string;
  /** legend-muted severities — applied to histogram + stream, never to facet counts */
  hiddenSeverity?: Severity[];
}

export interface HistogramBucket {
  t0: string;
  t1: string;
  ERROR: number;
  WARN: number;
  INFO: number;
  DEBUG: number;
}
export interface LogHistogram {
  bucketMs: number;
  buckets: HistogramBucket[];
}

export type { FacetValue };
export interface LogFacets {
  severity: FacetValue[];
  event: FacetValue[];
  tool: FacetValue[];
}

export interface LogCursor {
  ts: string;
  id: number;
}
export interface LogCursorPage {
  items: LogRow[];
  nextCursor: LogCursor | null;
  hasMore: boolean;
  totalCount: number;
}

export interface LogsListResult {
  items: LogRow[];
  totalCount: number;
}
