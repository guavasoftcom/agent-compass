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
// Logs page — network layer.
//
//   • fetchLogHistogram → GET /api/logs/histogram   (server-aggregated buckets)
//   • fetchLogFacets    → GET /api/logs/facets       (server-aggregated counts)
//   • fetchLogsCursor   → GET /api/logs?before/after (keyset paging — Stream)
//   • fetchLogsPage     → GET /api/logs?page&size    (offset paging — Table)
//
// Defaults to the LIVE endpoints. Set VITE_LOGS_SAMPLE=1 to force the synthetic
// store in ./logsSampleData (offline UI work); the components bind to the same
// shapes either way. Types live in ./logsTypes and the derivations (query
// serialization, severityOf/eventNameOf/toolNameOf) in ./logsDerivations — both
// re-exported here so the view components keep importing everything from one place.

import { getJson } from '../../api/http';
import type {
  LogCursor,
  LogCursorPage,
  LogFacets,
  LogHistogram,
  LogsFilters,
  LogsListResult,
} from './logsTypes';
import { buildLogsQuery } from './logsDerivations';
import {
  sampleCursor,
  sampleFacets,
  sampleHistogram,
  samplePage,
  sampleTail,
} from './logsSampleData';

export * from './logsTypes';
export * from './logsDerivations';

const env = (import.meta as unknown as { env?: Record<string, string> }).env ?? {};
// Live endpoints are the default; set VITE_LOGS_SAMPLE=1 to force the synthetic
// fixtures (useful for offline UI work). The fetchers below hit /api/logs/*.
const USE_SAMPLE_DATA = env.VITE_LOGS_SAMPLE === '1';

export const fetchLogHistogram = (f: LogsFilters, buckets = 50): Promise<LogHistogram> => {
  if (USE_SAMPLE_DATA) {
    return sampleHistogram(f, buckets);
  }
  // histogram always returns all four severity series (legend mutes client-side),
  // so severity is never sent as a filter — matches GET /api/logs/histogram.
  const p = buildLogsQuery({ ...f, severity: [] });
  p.set('buckets', String(buckets));
  return getJson<LogHistogram>(`/api/logs/histogram?${p.toString()}`);
};

export const fetchLogFacets = (f: LogsFilters): Promise<LogFacets> => {
  if (USE_SAMPLE_DATA) {
    return sampleFacets(f);
  }
  return getJson<LogFacets>(`/api/logs/facets?${buildLogsQuery(f).toString()}`);
};

export const fetchLogsCursor = (
  f: LogsFilters,
  opts: { cursor?: LogCursor | null; after?: LogCursor | null; limit?: number },
): Promise<LogCursorPage> => {
  const limit = opts.limit ?? 60;
  if (USE_SAMPLE_DATA) {
    if (opts.after) {
      return sampleTail(f);
    }
    return sampleCursor(f, opts.cursor ?? null, limit);
  }
  const p = buildLogsQuery(f);
  p.set('limit', String(limit));
  if (opts.cursor) {
    p.set('before', `${opts.cursor.ts},${opts.cursor.id}`);
  }
  if (opts.after) {
    p.set('after', `${opts.after.ts},${opts.after.id}`);
  }
  return getJson<LogCursorPage>(`/api/logs?${p.toString()}`);
};

export const fetchLogsPage = (f: LogsFilters, page: number, size: number): Promise<LogsListResult> => {
  if (USE_SAMPLE_DATA) {
    return samplePage(f, page, size);
  }
  const p = buildLogsQuery(f);
  p.set('page', String(page));
  p.set('size', String(size));
  return getJson<LogsListResult>(`/api/logs?${p.toString()}`);
};
