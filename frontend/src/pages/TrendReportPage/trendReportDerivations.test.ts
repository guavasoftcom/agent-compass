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
import { describe, expect, it } from 'vitest';
import {
  buildSummaryCallouts,
  computeBeforeSharePct,
  computeDelta,
  describeWindowSpan,
  formatComparingFromDate,
  formatMetricValue,
  formatPeriod,
  TREND_SECTIONS,
} from './trendReportDerivations';
import type { TrendMetric, TrendMetricKey } from './trendReportApi';
import { WINDOWS } from '../../lib/constants';

const metric = (overrides: Partial<TrendMetric>): TrendMetric => ({
  before: 0,
  after: 0,
  beforeSeries: [],
  afterSeries: [],
  directionIsGoodWhen: 'down',
  ...overrides,
});

describe('TREND_SECTIONS', () => {
  it('covers all eleven contract metric keys exactly once', () => {
    const allKeys: TrendMetricKey[] = [
      'total_cost',
      'cost_per_session',
      'blended_rate_per_1m',
      'cache_read_ratio_pct',
      'tokens_total',
      'tokens_per_session',
      'tool_errors',
      'error_rate_pct',
      'session_failures',
      'sessions',
      'avg_duration_min',
    ];
    const seen = TREND_SECTIONS.flatMap((section) => section.metricKeys);
    expect(seen.sort()).toEqual([...allKeys].sort());
    expect(new Set(seen).size).toBe(allKeys.length);
  });
});

describe('computeDelta', () => {
  it('classifies a percent-change metric that improved as good, direction down', () => {
    const result = computeDelta('total_cost', metric({ before: 612.4, after: 404.18, directionIsGoodWhen: 'down' }));
    expect(result.direction).toBe('down');
    expect(result.state).toBe('good');
    expect(result.label).toBe('34%');
  });

  it('classifies a percent-change metric that regressed as bad', () => {
    const result = computeDelta('tool_errors', metric({ before: 12, after: 15, directionIsGoodWhen: 'down' }));
    expect(result.direction).toBe('up');
    expect(result.state).toBe('bad');
    expect(result.label).toBe('25%');
  });

  it('treats a percentage-point metric as points, not percent-change', () => {
    const result = computeDelta(
      'cache_read_ratio_pct',
      metric({ before: 77.2, after: 86.4, directionIsGoodWhen: 'up' }),
    );
    expect(result.direction).toBe('up');
    expect(result.state).toBe('good');
    expect(result.label).toBe('9.2pt');
  });

  it('classifies a move under the flat threshold as flat regardless of directionIsGoodWhen', () => {
    const result = computeDelta('sessions', metric({ before: 340, after: 342, directionIsGoodWhen: 'up' }));
    expect(result.direction).toBe('flat');
    expect(result.state).toBe('flat');
    expect(result.label).toBe('flat');
  });

  it('treats a zero-before, zero-after metric as flat rather than dividing by zero', () => {
    const result = computeDelta('total_cost', metric({ before: 0, after: 0, directionIsGoodWhen: 'down' }));
    expect(result.direction).toBe('flat');
    expect(result.state).toBe('flat');
  });

  it('treats a zero-before, nonzero-after metric as a full-magnitude move', () => {
    const result = computeDelta('total_cost', metric({ before: 0, after: 50, directionIsGoodWhen: 'down' }));
    expect(result.direction).toBe('up');
    expect(result.state).toBe('bad');
    expect(result.label).toBe('100%');
  });
});

describe('computeBeforeSharePct', () => {
  it('splits proportionally to magnitude', () => {
    expect(computeBeforeSharePct(60, 40)).toBeCloseTo(60);
    expect(computeBeforeSharePct(34, 66)).toBeCloseTo(34);
  });

  it('splits evenly when both sides are zero', () => {
    expect(computeBeforeSharePct(0, 0)).toBe(50);
  });
});

describe('formatMetricValue', () => {
  it('formats currency metrics with USD_FORMATTER', () => {
    expect(formatMetricValue('total_cost', 612.4)).toBe('$612.40');
    expect(formatMetricValue('cost_per_session', 1.8)).toBe('$1.80');
  });

  it('formats percentage metrics to one decimal', () => {
    expect(formatMetricValue('cache_read_ratio_pct', 77.2)).toBe('77.2%');
    expect(formatMetricValue('error_rate_pct', 8.2)).toBe('8.2%');
  });

  it('formats token totals compactly', () => {
    expect(formatMetricValue('tokens_total', 8_200_000)).toBe('8.2M');
  });

  it('formats duration in minutes', () => {
    expect(formatMetricValue('avg_duration_min', 4.2)).toBe('4.2m');
  });

  it('formats plain counters as rounded, locale-formatted integers', () => {
    expect(formatMetricValue('tool_errors', 184)).toBe('184');
    expect(formatMetricValue('sessions', 340)).toBe('340');
  });
});

describe('formatPeriod / formatComparingFromDate', () => {
  it('formats a sub-day, same-calendar-day period as one date plus a time range', () => {
    // A short window that (in most timezones) stays within one calendar day.
    const result = formatPeriod({ start: '2026-08-30T20:00:00Z', end: '2026-08-30T21:00:00Z' });
    expect(result.primary).toMatch(/^Aug 3[01]$/);
    expect(result.secondary).toMatch(/^\d{1,2}:\d{2} [AP]M – \d{1,2}:\d{2} [AP]M$/);
  });

  it('omits the time line for a period that runs start-of-day to end-of-day on one calendar day', () => {
    // A whole-day window is only unambiguous in UTC-anchored tests when the local
    // timezone doesn't shift the day boundary — assert the shape rather than exact text.
    const local = new Date(2026, 7, 30, 0, 0, 0, 0);
    const localEnd = new Date(2026, 7, 31, 0, 0, 0, 0);
    const result = formatPeriod({ start: local.toISOString(), end: localEnd.toISOString() });
    expect(result.primary).toMatch(/^Aug 30$/);
    expect(result.secondary).toBeNull();
  });

  it('shows both dates and omits the time line for a whole multi-day span', () => {
    const start = new Date(2026, 7, 24, 0, 0, 0, 0);
    const end = new Date(2026, 7, 31, 0, 0, 0, 0);
    const result = formatPeriod({ start: start.toISOString(), end: end.toISOString() });
    expect(result.primary).toBe('Aug 24 – Aug 30');
    expect(result.secondary).toBeNull();
  });

  it('shows both dates and a time range for a multi-day span that is not day-aligned', () => {
    const start = new Date(2026, 7, 29, 15, 30, 0, 0);
    const end = new Date(2026, 7, 30, 15, 30, 0, 0);
    const result = formatPeriod({ start: start.toISOString(), end: end.toISOString() });
    expect(result.primary).toBe('Aug 29 – Aug 30');
    expect(result.secondary).toMatch(/^\d{1,2}:\d{2} [AP]M – \d{1,2}:\d{2} [AP]M$/);
  });

  it('formats the comparing-from date as the current period start', () => {
    const formatted = formatComparingFromDate({ start: '2026-08-22T00:00:00Z', end: '2026-08-29T00:00:00Z' });
    // Should be just the month and day in local timezone (exact date depends on timezone)
    expect(formatted).toMatch(/Aug \d{1,2}/);
  });
});

describe('describeWindowSpan', () => {
  it('describes a preset window using the matching WINDOWS label, lowercased', () => {
    expect(describeWindowSpan({ kind: 'preset', minutes: 60 * 24 * 7 }, WINDOWS)).toBe('7 days');
  });

  it('describes a custom range generically', () => {
    expect(
      describeWindowSpan(
        { kind: 'custom', startTimestamp: '2026-08-01T00:00:00Z', endTimestamp: '2026-08-02T00:00:00Z' },
        WINDOWS,
      ),
    ).toBe('the selected custom range');
  });
});

describe('buildSummaryCallouts', () => {
  it('derives cost/reliability/volume callouts from before/after figures', () => {
    const callouts = buildSummaryCallouts({
      total_cost: metric({ before: 612.4, after: 404.18, directionIsGoodWhen: 'down' }),
      tool_errors: metric({ before: 184, after: 47, directionIsGoodWhen: 'down' }),
      sessions: metric({ before: 340, after: 342, directionIsGoodWhen: 'up' }),
    });

    expect(callouts).toHaveLength(3);
    expect(callouts[0].label).toBe('Cost');
    expect(callouts[0].text).toContain('dropped');
    expect(callouts[1].label).toBe('Reliability');
    expect(callouts[1].text).toContain('fell');
    expect(callouts[2].label).toBe('Volume');
    expect(callouts[2].text).toContain('flat');
  });

  it('omits a callout whose backing metric is missing from the response', () => {
    const callouts = buildSummaryCallouts({
      total_cost: metric({ before: 100, after: 90, directionIsGoodWhen: 'down' }),
    });
    expect(callouts).toHaveLength(1);
    expect(callouts[0].label).toBe('Cost');
  });
});
