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
// Pure derivations for the Trend Report page — delta classification, value/period
// formatting, and the section→metric grouping. Kept out of the container/view so
// they're unit-testable without mounting the page (see trendReportDerivations.test.ts).

import { USD_FORMATTER, formatCompact } from '../../lib/format';
import type { WindowOption } from '../../lib/constants';
import type { WindowSelection } from '../../api';
import type { TrendMetric, TrendMetricKey, TrendPeriod } from './trendReportApi';

// ─── Section grouping ───────────────────────────────────────────────────────

export type TrendSectionKey = 'cost' | 'tokenEfficiency' | 'reliability' | 'activity';

export interface TrendSectionDefinition {
  key: TrendSectionKey;
  label: string;
  metricKeys: TrendMetricKey[];
}

/** Fixed left-to-right / top-to-bottom order for every section-grouped element on this page. */
export const TREND_SECTIONS: TrendSectionDefinition[] = [
  { key: 'cost', label: 'Cost', metricKeys: ['total_cost', 'cost_per_session', 'blended_rate_per_1m'] },
  {
    key: 'tokenEfficiency',
    label: 'Token efficiency',
    metricKeys: ['cache_read_ratio_pct', 'tokens_total', 'tokens_per_session'],
  },
  { key: 'reliability', label: 'Reliability', metricKeys: ['tool_errors', 'error_rate_pct', 'session_failures'] },
  { key: 'activity', label: 'Activity', metricKeys: ['sessions', 'avg_duration_min'] },
];

// ─── Per-metric display config ──────────────────────────────────────────────

export interface TrendMetricLabel {
  name: string;
  sub: string;
}

export const METRIC_LABELS: Record<TrendMetricKey, TrendMetricLabel> = {
  total_cost: { name: 'Total cost', sub: 'total spend' },
  cost_per_session: { name: 'Avg cost / session', sub: 'per session' },
  blended_rate_per_1m: { name: 'Blended rate', sub: 'per 1M tokens' },
  cache_read_ratio_pct: { name: 'Cache read ratio', sub: 'cache read ratio' },
  tokens_total: { name: 'Total tokens', sub: 'total tokens' },
  tokens_per_session: { name: 'Avg tokens / session', sub: 'tokens / session' },
  tool_errors: { name: 'Tool errors', sub: 'tool errors' },
  error_rate_pct: { name: 'Error rate', sub: 'error rate' },
  session_failures: { name: 'Session failures', sub: 'failed sessions' },
  sessions: { name: 'Sessions', sub: 'sessions' },
  avg_duration_min: { name: 'Avg duration', sub: 'avg session length' },
};

/** Formats one metric's raw before/after number for display — currency, percent, compact
 *  token counts (K/M), or plain counts, depending on the metric. */
export const formatMetricValue = (metricKey: TrendMetricKey, value: number): string => {
  switch (metricKey) {
    case 'total_cost':
    case 'cost_per_session':
    case 'blended_rate_per_1m':
      return USD_FORMATTER.format(value);
    case 'cache_read_ratio_pct':
    case 'error_rate_pct':
      return `${value.toFixed(1)}%`;
    case 'tokens_total':
    case 'tokens_per_session':
      return formatCompact(value);
    case 'avg_duration_min':
      return `${value.toFixed(1)}m`;
    case 'tool_errors':
    case 'session_failures':
    case 'sessions':
    default:
      return Math.round(value).toLocaleString();
  }
};

// ─── Delta classification ───────────────────────────────────────────────────

export type DeltaState = 'good' | 'flat' | 'bad';
export type DeltaDirection = 'up' | 'down' | 'flat';

export interface DeltaResult {
  state: DeltaState;
  direction: DeltaDirection;
  /** Formatted magnitude without an arrow, e.g. "34%", "9.2pt", or "flat". */
  label: string;
}

/**
 * Metrics that are already expressed as a percentage, so a before→after move is measured in
 * percentage points (after - before) rather than percent-change — the same convention the
 * backend's design doc uses (`9.2pt` for cache read ratio, `6.1pt` for error rate). Every other
 * metric uses ordinary percent-change.
 */
const PERCENTAGE_POINT_METRICS: ReadonlySet<TrendMetricKey> = new Set([
  'cache_read_ratio_pct',
  'error_rate_pct',
]);

/**
 * Below this magnitude (percent-change for most metrics, percentage points for the two ratio
 * metrics) a delta reads as "flat" rather than a genuine improvement/regression. Picked to
 * absorb ordinary week-to-week noise without hiding a real single-digit swing — e.g. the
 * reference design's "Sessions" row (340 → 342, +0.6%) is flat, but a metric moving 5% is not.
 */
export const FLAT_DELTA_THRESHOLD = 2;

/**
 * Classifies a metric's before→after move into good/flat/bad plus the arrow direction and a
 * formatted magnitude label, from `before`/`after`/`directionIsGoodWhen` alone — the frontend
 * never hardcodes which direction is "good" for a given metric name.
 */
export const computeDelta = (metricKey: TrendMetricKey, trend: TrendMetric): DeltaResult => {
  const isPercentagePoint = PERCENTAGE_POINT_METRICS.has(metricKey);
  const rawDelta = isPercentagePoint
    ? trend.after - trend.before
    : trend.before === 0
      ? trend.after === 0
        ? 0
        : 100
      : ((trend.after - trend.before) / Math.abs(trend.before)) * 100;

  const magnitude = Math.abs(rawDelta);
  const direction: DeltaDirection = magnitude < FLAT_DELTA_THRESHOLD ? 'flat' : rawDelta > 0 ? 'up' : 'down';

  const state: DeltaState =
    direction === 'flat' ? 'flat' : direction === trend.directionIsGoodWhen ? 'good' : 'bad';

  const label =
    direction === 'flat' ? 'flat' : isPercentagePoint ? `${magnitude.toFixed(1)}pt` : `${magnitude.toFixed(0)}%`;

  return { state, direction, label };
};

/**
 * Share (0-100) of the metric row's two-tone ratio bar the "before" segment should occupy,
 * proportional to `|before|` against `|before| + |after|`. Both sides are treated as
 * magnitudes (every metric on this page is non-negative in practice) so the bar always fills
 * end to end; an all-zero row splits the bar evenly rather than collapsing to zero width.
 */
export const computeBeforeSharePct = (before: number, after: number): number => {
  const total = Math.abs(before) + Math.abs(after);
  if (total === 0) {
    return 50;
  }
  return (Math.abs(before) / total) * 100;
};

// ─── Period / window formatting ─────────────────────────────────────────────

// UTC explicitly: the backend's `current`/`previous` timestamps are day-boundary-aligned in UTC
// (or the org's configured tz, per BACKEND_API.md's `tz` param), and formatting in the browser's
// local zone could shift a boundary instant onto the wrong calendar day (e.g. midnight UTC
// reading as the previous evening in a negative-UTC-offset zone) — same reasoning as every other
// page's timestamp handling staying in the zone the server meant.
const PERIOD_DATE_FORMATTER = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });

/** "Aug 15 – Aug 21" — `period.end` is treated as exclusive, so the label shows the last
 *  covered instant rather than the boundary timestamp itself. */
export const formatPeriodRange = (period: TrendPeriod): string => {
  const start = new Date(period.start);
  const end = new Date(new Date(period.end).getTime() - 1);
  return `${PERIOD_DATE_FORMATTER.format(start)} – ${PERIOD_DATE_FORMATTER.format(end)}`;
};

/** "Aug 22" — the date the current ("after") period starts, for the header's "Comparing from" pill. */
export const formatComparingFromDate = (period: TrendPeriod): string => PERIOD_DATE_FORMATTER.format(new Date(period.start));

/** "7 days" / "24 hours" / "the selected custom range" — the phrase used in the page subtitle. */
export const describeWindowSpan = (selection: WindowSelection, windows: readonly WindowOption[]): string => {
  if (selection.kind === 'custom') {
    return 'the selected custom range';
  }
  const option = windows.find((candidate) => candidate.value === selection.minutes);
  return option ? option.label.toLowerCase() : `${selection.minutes} minutes`;
};

// ─── Summary strip ───────────────────────────────────────────────────────────

export interface SummaryCallout {
  label: string;
  text: string;
}

/**
 * Derives up to three short prose callouts (cost / reliability / volume) from the fetched
 * metrics, in the style of the reference design's summary strip — but generated from real
 * before/after figures rather than the design's hardcoded example copy. A metric missing from
 * the response (e.g. a partial/degraded backend payload) simply drops its callout instead of
 * throwing.
 */
export const buildSummaryCallouts = (metrics: Partial<Record<TrendMetricKey, TrendMetric>>): SummaryCallout[] => {
  const callouts: SummaryCallout[] = [];

  const cost = metrics.total_cost;
  if (cost) {
    const delta = computeDelta('total_cost', cost);
    const costDiff = USD_FORMATTER.format(Math.abs(cost.after - cost.before));
    const afterValue = formatMetricValue('total_cost', cost.after);
    const text =
      delta.direction === 'flat'
        ? `Spend held roughly flat at ${afterValue}.`
        : delta.direction === 'down'
          ? `Spend dropped ${costDiff} (${delta.label}) to ${afterValue}.`
          : `Spend rose ${costDiff} (${delta.label}) to ${afterValue}.`;
    callouts.push({ label: 'Cost', text });
  }

  const errors = metrics.tool_errors;
  if (errors) {
    const delta = computeDelta('tool_errors', errors);
    const beforeValue = formatMetricValue('tool_errors', errors.before);
    const afterValue = formatMetricValue('tool_errors', errors.after);
    const text =
      delta.direction === 'flat'
        ? `Tool errors held roughly flat at ${afterValue}.`
        : delta.direction === 'down'
          ? `Tool errors fell ${delta.label}, from ${beforeValue} to ${afterValue}.`
          : `Tool errors rose ${delta.label}, from ${beforeValue} to ${afterValue}.`;
    callouts.push({ label: 'Reliability', text });
  }

  const sessions = metrics.sessions;
  if (sessions) {
    const delta = computeDelta('sessions', sessions);
    const afterValue = formatMetricValue('sessions', sessions.after);
    const text =
      delta.direction === 'flat'
        ? `Session count held flat at ~${afterValue}.`
        : `Session count ${delta.direction === 'up' ? 'grew' : 'fell'} ${delta.label} to ${afterValue}.`;
    callouts.push({ label: 'Volume', text });
  }

  return callouts;
};
