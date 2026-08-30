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
// Trend Report page — network layer.
//
// One call: GET /api/trends returns a before/after comparison bundle — the
// selected window ("current") against the immediately preceding window of
// equal length ("previous") — for an eleven-metric bundle spanning cost,
// token efficiency, reliability, and activity. See
// design_handoff_trend_report/BACKEND_API.md for the original proposal;
// the shape below is the contract this page was built against (camelCase
// fields, `directionIsGoodWhen` per metric rather than a hardcoded
// good-direction table on the frontend).

import { windowQueryParams } from '../../api/http';
import type { WindowSelection } from '../../api';

export interface TrendPeriod {
  start: string;
  end: string;
}

export type TrendMetricKey =
  | 'total_cost'
  | 'cost_per_session'
  | 'blended_rate_per_1m'
  | 'cache_read_ratio_pct'
  | 'tokens_total'
  | 'tokens_per_session'
  | 'tool_errors'
  | 'error_rate_pct'
  | 'session_failures'
  | 'sessions'
  | 'avg_duration_min';

/**
 * Before/after aggregate for one metric, plus a short (~7-point) trend series per side for the
 * row's sparklines. `directionIsGoodWhen` tells the frontend which direction counts as an
 * improvement ("down" for cost/errors, "up" for cache ratio) so the good/bad classification
 * never hardcodes a per-metric table client-side — see `computeDelta` in
 * `trendReportDerivations.ts`.
 */
export interface TrendMetric {
  before: number;
  after: number;
  beforeSeries: number[];
  afterSeries: number[];
  directionIsGoodWhen: 'up' | 'down';
}

export interface TrendReport {
  current: TrendPeriod;
  previous: TrendPeriod;
  metrics: Record<TrendMetricKey, TrendMetric>;
}

// Mirrors MetricsPage's own page-local `getJSON` wrapper rather than the shared
// `api/http.ts` `getJson` — see this page's CLAUDE.md gotcha for why.
const getJSON = async <T>(url: string): Promise<T> => {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
};

/** GET /api/trends — before/after metric bundle for the selected window. */
export const fetchTrendReport = (selection: WindowSelection): Promise<TrendReport> => {
  const params = windowQueryParams(selection);
  return getJSON<TrendReport>(`/api/trends?${params.toString()}`);
};
