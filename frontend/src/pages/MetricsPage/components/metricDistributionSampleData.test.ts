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
import { DISTRIBUTION_POINT_CAP } from '../metricsApi';
import { summarizeValues } from './MetricDistributionCard/distributionScatter';
import { buildMetricDistributionSample } from './metricDistributionSampleData';

const FROM = '2026-09-18T12:00:00.000Z';
const TO = '2026-09-19T12:00:00.000Z';

describe('buildMetricDistributionSample', () => {
  it('is deterministic: the same metric and window always give the same points', () => {
    expect(buildMetricDistributionSample('claude_code.token.usage', FROM, TO)).toEqual(
      buildMetricDistributionSample('claude_code.token.usage', FROM, TO),
    );
    expect(buildMetricDistributionSample('claude_code.cost.usage', FROM, TO)).toEqual(
      buildMetricDistributionSample('claude_code.cost.usage', FROM, TO),
    );
  });

  it('returns points ascending by timestamp, all inside the request window, under the cap', () => {
    const { points } = buildMetricDistributionSample('claude_code.token.usage', FROM, TO);
    const timestamps = points.map((point) => Date.parse(point.ts));

    expect(points.length).toBeGreaterThan(20);
    expect(points.length).toBeLessThanOrEqual(DISTRIBUTION_POINT_CAP);
    expect(timestamps).toEqual([...timestamps].sort((left, right) => left - right));
    expect(Math.min(...timestamps)).toBeGreaterThanOrEqual(Date.parse(FROM));
    expect(Math.max(...timestamps)).toBeLessThanOrEqual(Date.parse(TO));
  });

  it('carries a trace id on about six exemplars only, each unique', () => {
    const { points } = buildMetricDistributionSample('claude_code.token.usage', FROM, TO);
    const exemplarTraceIds = points.flatMap((point) => (point.traceId === null ? [] : [point.traceId]));

    expect(exemplarTraceIds).toHaveLength(6);
    expect(new Set(exemplarTraceIds).size).toBe(6);
    exemplarTraceIds.forEach((traceId) => {
      expect(traceId).toMatch(/^[0-9a-f]{32}$/);
    });
  });

  it('clusters requests into bursts with real idle gaps between them', () => {
    const { points } = buildMetricDistributionSample('claude_code.token.usage', FROM, TO);
    const windowSpanMs = Date.parse(TO) - Date.parse(FROM);
    const gapsMs = points.slice(1).map((point, index) => Date.parse(point.ts) - Date.parse(points[index].ts));

    // A steady drip would have no gap larger than a few percent of the window; bursts do.
    expect(Math.max(...gapsMs)).toBeGreaterThan(0.08 * windowSpanMs);
  });

  it('draws heavy-tailed whole-number token values', () => {
    const { points } = buildMetricDistributionSample('claude_code.token.usage', FROM, TO);
    const values = points.map((point) => point.value);
    const summary = summarizeValues(values);

    values.forEach((value) => {
      expect(Number.isInteger(value)).toBe(true);
      expect(value).toBeGreaterThan(0);
    });
    // Heavy tail: the p99 request is several times the median one.
    expect(summary.p99 as number).toBeGreaterThan(3 * (summary.p50 as number));
  });

  it('draws small-dollar cost values', () => {
    const { points } = buildMetricDistributionSample('claude_code.cost.usage', FROM, TO);

    points.forEach((point) => {
      expect(point.value).toBeGreaterThan(0);
      expect(point.value).toBeLessThan(1);
    });
  });
});
