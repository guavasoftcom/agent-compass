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
// ---------------------------------------------------------------------------
// Metrics page API client
//
// The simplified Metrics page needs one call: the list of claude_code.* metrics,
// each with a headline value, a windowed trend, and any attribute splits.
// Defaults to LIVE; set VITE_METRICS_SAMPLE=1 to render the static fixtures
// instead (useful before the backend lands). See BACKEND.md for the contract.
// ---------------------------------------------------------------------------

import { METRICS, type MetricSeries } from './components/metricsSampleData';

const USE_SAMPLE_DATA =
  (import.meta as unknown as { env?: Record<string, string> }).env
    ?.VITE_METRICS_SAMPLE === '1';

/** Time window derived from the page's WindowSelection. */
export interface MetricsQueryParams {
  /** ISO-8601 start / end of the selected window. */
  from: string;
  to: string;
}

const toQuery = (params: MetricsQueryParams): string =>
  new URLSearchParams({ from: params.from, to: params.to }).toString();

const getJSON = async <T>(url: string): Promise<T> => {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
};

/** GET /api/metrics/series — the claude_code.* metrics with trend + splits. */
export const fetchMetrics = async (
  params: MetricsQueryParams,
): Promise<MetricSeries[]> => {
  if (USE_SAMPLE_DATA) {
    return METRICS;
  }
  return getJSON<MetricSeries[]>(`/api/metrics/series?${toQuery(params)}`);
};
