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
