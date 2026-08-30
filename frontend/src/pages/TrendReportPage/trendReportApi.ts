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
import { MS_PER_DAY, MS_PER_MINUTE } from '../../lib/constants';

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

const startOfLocalDay = (date: Date): Date =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate(), 0, 0, 0, 0);

const endOfLocalDay = (date: Date): Date =>
  new Date(date.getFullYear(), date.getMonth(), date.getDate(), 23, 59, 59, 999);

/**
 * Trend Report compares the selected window against the immediately preceding window
 * of equal length. Beyond 24 hours that comparison reads better as whole calendar days
 * (in the browser's local timezone, since this app runs on the operator's own
 * workstation) than as an exact rolling instant — "Last 7 days" becomes the last 7
 * full calendar days rather than "7×24h before this exact second", so both the
 * before/after period labels and the 7-point sparklines land on clean day boundaries
 * instead of splitting a day mid-afternoon. A window of 24 hours or less is left as an
 * exact rolling instant, since day-snapping a 1-hour comparison would blow it up into
 * an all-day one. Custom ranges longer than a day are day-snapped the same way, though
 * `WindowSelector`'s calendar (date-only, no time-of-day input) already produces
 * whole-day ranges — this is a defensive no-op for that case, not the primary path.
 */
export const resolveTrendReportSelection = (selection: WindowSelection): WindowSelection => {
  if (selection.kind === 'preset') {
    const spanMs = selection.minutes * MS_PER_MINUTE;
    if (spanMs <= MS_PER_DAY) {
      return selection;
    }
    const wholeDays = Math.round(spanMs / MS_PER_DAY);
    const end = endOfLocalDay(new Date());
    const start = startOfLocalDay(new Date(end.getTime() - (wholeDays - 1) * MS_PER_DAY));
    return { kind: 'custom', startTimestamp: start.toISOString(), endTimestamp: end.toISOString() };
  }

  const start = new Date(selection.startTimestamp);
  const end = new Date(selection.endTimestamp);
  if (end.getTime() - start.getTime() <= MS_PER_DAY) {
    return selection;
  }
  return {
    kind: 'custom',
    startTimestamp: startOfLocalDay(start).toISOString(),
    endTimestamp: endOfLocalDay(end).toISOString(),
  };
};

/** GET /api/trends — before/after metric bundle for the selected window. */
export const fetchTrendReport = (selection: WindowSelection): Promise<TrendReport> => {
  const params = windowQueryParams(resolveTrendReportSelection(selection));
  return getJSON<TrendReport>(`/api/trends?${params.toString()}`);
};
