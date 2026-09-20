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
import type { DistributionPoint } from '../../metricsApi';
import {
  buildScatterPoints,
  buildTimeAxisLabels,
  buildValueScale,
  computePercentile,
  distributionUnitFor,
  formatAxisValue,
  formatDistributionValue,
  formatPercentileValue,
  scaleKindFor,
  summarizeValues,
  timestampToFraction,
  valueToFraction,
  type ValueScale,
} from './distributionScatter';

const ONE_TO_HUNDRED = Array.from({ length: 100 }, (_, index) => index + 1);

describe('computePercentile', () => {
  it('returns null for an empty list', () => {
    expect(computePercentile([], 0.5)).toBeNull();
  });

  it('is the nearest-rank value: the smallest with that fraction of the list at or below it', () => {
    expect(computePercentile(ONE_TO_HUNDRED, 0.5)).toBe(50);
    expect(computePercentile(ONE_TO_HUNDRED, 0.95)).toBe(95);
    expect(computePercentile(ONE_TO_HUNDRED, 0.99)).toBe(99);
    expect(computePercentile([10, 20, 30], 0.5)).toBe(20);
  });

  it('returns the only value of a one-element list at every percentile', () => {
    expect(computePercentile([42], 0.5)).toBe(42);
    expect(computePercentile([42], 0.99)).toBe(42);
  });

  it('lets a high percentile of a short list land on the maximum', () => {
    expect(computePercentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 0.99)).toBe(10);
  });

  it('clamps out-of-range fractions to the ends of the list', () => {
    expect(computePercentile([5, 6, 7], 0)).toBe(5);
    expect(computePercentile([5, 6, 7], 1)).toBe(7);
    expect(computePercentile([5, 6, 7], -3)).toBe(5);
    expect(computePercentile([5, 6, 7], 9)).toBe(7);
  });
});

describe('summarizeValues', () => {
  it('computes count and p50/p95/p99 from unsorted values', () => {
    const shuffledValues = [...ONE_TO_HUNDRED].reverse();

    expect(summarizeValues(shuffledValues)).toEqual({ requestCount: 100, p50: 50, p95: 95, p99: 99 });
  });

  it('ignores non-finite values and does not mutate its input', () => {
    const values = [30, Number.NaN, 10, Number.POSITIVE_INFINITY, 20];

    const summary = summarizeValues(values);

    expect(summary.requestCount).toBe(3);
    expect(summary.p50).toBe(20);
    expect(values).toEqual([30, Number.NaN, 10, Number.POSITIVE_INFINITY, 20]);
  });

  it('reports nulls for no values', () => {
    expect(summarizeValues([])).toEqual({ requestCount: 0, p50: null, p95: null, p99: null });
  });
});

describe('distributionUnitFor / scaleKindFor', () => {
  it('maps USD to a linear dollar axis and everything else to a log token axis', () => {
    expect(distributionUnitFor('USD')).toBe('USD');
    expect(distributionUnitFor('tokens')).toBe('tokens');
    expect(distributionUnitFor('')).toBe('tokens');
    expect(scaleKindFor('USD')).toBe('linear');
    expect(scaleKindFor('tokens')).toBe('log');
  });
});

describe('buildValueScale (linear)', () => {
  it('runs from zero to a nice round ceiling above the maximum, derived from the data', () => {
    const scale = buildValueScale('linear', [0.004, 0.052, 0.288]);

    expect(scale.kind).toBe('linear');
    expect(scale.minimum).toBe(0);
    expect(scale.maximum).toBe(0.4);
    expect(scale.ticks).toEqual([0, 0.1, 0.2, 0.3, 0.4]);
  });

  it('scales the ceiling with the data instead of hardcoding one', () => {
    expect(buildValueScale('linear', [0.02, 0.31]).maximum).toBe(0.4);
    expect(buildValueScale('linear', [1.2, 8.4]).maximum).toBe(10);
    expect(buildValueScale('linear', [30, 214600]).maximum).toBe(300000);
  });

  it('keeps the largest value strictly below the top edge, even when it lands on a tick', () => {
    const scale = buildValueScale('linear', [0.1, 0.4]);

    expect(scale.maximum).toBeGreaterThan(0.4);
  });

  it('prints ticks without float noise', () => {
    const scale = buildValueScale('linear', [0.29]);

    expect(scale.ticks).toEqual([0, 0.1, 0.2, 0.3, 0.4]);
    scale.ticks.forEach((tick) => {
      expect(String(tick).length).toBeLessThanOrEqual(4);
    });
  });

  it('falls back to a small well-formed 0..1 scale for no data, all zeros, or non-finite values', () => {
    const expectedFallback = { kind: 'linear', minimum: 0, maximum: 1, ticks: [0, 0.25, 0.5, 0.75, 1] };

    expect(buildValueScale('linear', [])).toEqual(expectedFallback);
    expect(buildValueScale('linear', [0, 0])).toEqual(expectedFallback);
    expect(buildValueScale('linear', [Number.NaN])).toEqual(expectedFallback);
  });
});

describe('buildValueScale (log)', () => {
  it('floors at the power of ten at or below the smallest value and ceils above the largest', () => {
    const scale = buildValueScale('log', [850, 13180, 214600]);

    expect(scale.kind).toBe('log');
    expect(scale.minimum).toBe(100);
    expect(scale.maximum).toBe(300000);
  });

  it('ticks every decade from the floor, plus the ceiling when it is not itself a decade', () => {
    expect(buildValueScale('log', [850, 214600]).ticks).toEqual([100, 1000, 10000, 100000, 300000]);
    // A ceiling that is a power of ten is not duplicated.
    expect(buildValueScale('log', [1500, 9000]).ticks).toEqual([1000, 10000]);
  });

  it('uses a value that is exactly a power of ten as its own floor', () => {
    const scale = buildValueScale('log', [1000, 1000]);

    expect(scale.minimum).toBe(1000);
    expect(scale.maximum).toBe(2000);
    expect(scale.ticks).toEqual([1000, 2000]);
  });

  it('never degenerates: the ceiling is always above the floor and above the largest value', () => {
    const valueSets = [[5], [1000], [999, 1001], [0.004, 0.9], [1, 1e9], [7, 7, 7], [2e-9, 3e-9]];

    valueSets.forEach((values) => {
      const scale = buildValueScale('log', values);
      expect(scale.maximum).toBeGreaterThan(scale.minimum);
      expect(scale.maximum).toBeGreaterThan(Math.max(...values));
      expect(scale.minimum).toBeLessThanOrEqual(Math.min(...values));
      expect(scale.ticks.length).toBeGreaterThanOrEqual(2);
      expect(scale.ticks[0]).toBe(scale.minimum);
      expect(scale.ticks[scale.ticks.length - 1]).toBe(scale.maximum);
    });
  });

  it('ignores zero and negative values when choosing the floor', () => {
    const scale = buildValueScale('log', [0, -5, 40, 900]);

    expect(scale.minimum).toBe(10);
  });

  it('handles fractional (sub-1) values', () => {
    const scale = buildValueScale('log', [0.004, 0.05]);

    expect(scale.minimum).toBe(0.001);
    expect(scale.maximum).toBe(0.1);
  });

  it('falls back to a small well-formed scale when nothing is positive', () => {
    const expectedFallback = { kind: 'log', minimum: 1, maximum: 10, ticks: [1, 10] };

    expect(buildValueScale('log', [])).toEqual(expectedFallback);
    expect(buildValueScale('log', [0, -1])).toEqual(expectedFallback);
  });
});

describe('valueToFraction', () => {
  it('maps a linear scale from 0 (bottom) to 1 (top) and clamps both ways', () => {
    const scale = buildValueScale('linear', [0.3]);

    expect(valueToFraction(scale, 0)).toBe(0);
    expect(valueToFraction(scale, scale.maximum)).toBe(1);
    expect(valueToFraction(scale, scale.maximum / 2)).toBeCloseTo(0.5);
    expect(valueToFraction(scale, scale.maximum * 5)).toBe(1);
    expect(valueToFraction(scale, -1)).toBe(0);
    expect(valueToFraction(scale, Number.NaN)).toBe(0);
  });

  it('maps a log scale by decades: floor 0, ceiling 1, geometric midpoint one half', () => {
    const scale: ValueScale = { kind: 'log', minimum: 100, maximum: 10000, ticks: [100, 1000, 10000] };

    expect(valueToFraction(scale, 100)).toBe(0);
    expect(valueToFraction(scale, 10000)).toBe(1);
    expect(valueToFraction(scale, 1000)).toBeCloseTo(0.5);
    expect(valueToFraction(scale, 1e7)).toBe(1);
  });

  it('puts zero, negative and below-floor values on the log floor rather than off the chart', () => {
    const scale: ValueScale = { kind: 'log', minimum: 100, maximum: 10000, ticks: [100, 1000, 10000] };

    expect(valueToFraction(scale, 0)).toBe(0);
    expect(valueToFraction(scale, -50)).toBe(0);
    expect(valueToFraction(scale, 3)).toBe(0);
    expect(valueToFraction(scale, Number.NaN)).toBe(0);
  });
});

describe('timestampToFraction', () => {
  const fromMs = Date.parse('2026-09-19T00:00:00Z');
  const toMs = Date.parse('2026-09-19T10:00:00Z');

  it('places an instant proportionally within the window', () => {
    expect(timestampToFraction(fromMs, fromMs, toMs)).toBe(0);
    expect(timestampToFraction(toMs, fromMs, toMs)).toBe(1);
    expect(timestampToFraction(Date.parse('2026-09-19T03:00:00Z'), fromMs, toMs)).toBeCloseTo(0.3);
  });

  it('clamps an instant outside the window onto its edge', () => {
    expect(timestampToFraction(fromMs - 1000, fromMs, toMs)).toBe(0);
    expect(timestampToFraction(toMs + 1000, fromMs, toMs)).toBe(1);
  });

  it('returns 0 for an empty or inverted window', () => {
    expect(timestampToFraction(fromMs, fromMs, fromMs)).toBe(0);
    expect(timestampToFraction(fromMs, toMs, fromMs)).toBe(0);
    expect(timestampToFraction(fromMs, Number.NaN, toMs)).toBe(0);
  });
});

describe('buildTimeAxisLabels', () => {
  // Built from local-time components so the expected HH:mm labels do not depend on the runner's zone.
  const localIso = (day: number, hour: number, minute = 0): string =>
    new Date(2026, 8, day, hour, minute).toISOString();

  it('labels a short window with five evenly spaced local HH:mm times', () => {
    const labels = buildTimeAxisLabels(localIso(19, 0), localIso(19, 12), Date.parse(localIso(25, 0)));

    expect(labels).toEqual(['00:00', '03:00', '06:00', '09:00', '12:00']);
  });

  it('reads "now" for the last label only when the window ends within five minutes of now', () => {
    const to = localIso(19, 12);
    const toMs = Date.parse(to);

    const lastLabelAt = (nowMs: number): string => {
      const labels = buildTimeAxisLabels(localIso(19, 0), to, nowMs);
      return labels[labels.length - 1];
    };

    expect(lastLabelAt(toMs + 2 * 60 * 1000)).toBe('now');
    expect(lastLabelAt(toMs - 4 * 60 * 1000)).toBe('now');
    expect(lastLabelAt(toMs + 6 * 60 * 1000)).toBe('12:00');
    // Only the last label is ever replaced.
    expect(buildTimeAxisLabels(localIso(19, 0), to, toMs)[0]).toBe('00:00');
  });

  it('switches to short dates once the window is longer than 48 hours', () => {
    const labels = buildTimeAxisLabels(localIso(12, 0), localIso(19, 0), Date.parse(localIso(30, 0)));

    expect(labels[0]).toBe('Sep 12');
    expect(labels[labels.length - 1]).toBe('Sep 19');
    expect(labels).toHaveLength(5);
  });

  it('keeps clock labels at exactly 48 hours and uses dates just past it', () => {
    const farFuture = Date.parse(localIso(30, 0));

    expect(buildTimeAxisLabels(localIso(17, 0), localIso(19, 0), farFuture)[0]).toBe('00:00');
    expect(buildTimeAxisLabels(localIso(17, 0), localIso(19, 1), farFuture)[0]).toBe('Sep 17');
  });

  it('honors a custom label count and returns [] for an unparseable window', () => {
    expect(buildTimeAxisLabels(localIso(19, 0), localIso(19, 12), 0, 3)).toEqual(['00:00', '06:00', '12:00']);
    expect(buildTimeAxisLabels('not a date', localIso(19, 12), 0)).toEqual([]);
    expect(buildTimeAxisLabels(localIso(19, 0), 'nope', 0)).toEqual([]);
  });
});

describe('value formatting', () => {
  it('formats tokens compactly and dollars to the mill below $1, to the cent above', () => {
    expect(formatDistributionValue(13180, 'tokens')).toBe('13.2K');
    expect(formatDistributionValue(214600, 'tokens')).toBe('214.6K');
    expect(formatDistributionValue(0.052, 'USD')).toBe('$0.052');
    expect(formatDistributionValue(1.5, 'USD')).toBe('$1.50');
  });

  it('prints an em dash for a missing percentile', () => {
    expect(formatPercentileValue(null, 'tokens')).toBe('—');
    expect(formatPercentileValue(undefined, 'USD')).toBe('—');
    expect(formatPercentileValue(27340, 'tokens')).toBe('27.3K');
  });

  it('picks axis-tick precision from the axis maximum for USD', () => {
    expect(formatAxisValue(0.3, 'USD', 0.4)).toBe('$0.30');
    expect(formatAxisValue(0.01, 'USD', 0.05)).toBe('$0.010');
    expect(formatAxisValue(100, 'USD', 250)).toBe('$100');
    expect(formatAxisValue(100000, 'tokens', 300000)).toBe('100K');
  });
});

describe('buildScatterPoints', () => {
  const from = '2026-09-19T00:00:00.000Z';
  const to = '2026-09-19T10:00:00.000Z';
  const scale: ValueScale = { kind: 'linear', minimum: 0, maximum: 100, ticks: [0, 100] };

  it('places each point by its real timestamp and value, and keeps exemplar trace ids', () => {
    const points: DistributionPoint[] = [
      { ts: '2026-09-19T02:00:00.000Z', value: 25, traceId: null },
      { ts: '2026-09-19T08:00:00.000Z', value: 100, traceId: 'trace-abc' },
    ];

    const scatterPoints = buildScatterPoints(points, scale, from, to);

    expect(scatterPoints).toHaveLength(2);
    expect(scatterPoints[0]).toMatchObject({ key: 0, traceId: null, fractionY: 0.25 });
    expect(scatterPoints[0].fractionX).toBeCloseTo(0.2);
    expect(scatterPoints[1]).toMatchObject({ key: 1, traceId: 'trace-abc', fractionY: 1 });
    expect(scatterPoints[1].fractionX).toBeCloseTo(0.8);
  });

  it('leaves idle gaps as gaps: x follows the timestamp, not the request index', () => {
    const points: DistributionPoint[] = [
      { ts: '2026-09-19T00:30:00.000Z', value: 10, traceId: null },
      { ts: '2026-09-19T00:40:00.000Z', value: 10, traceId: null },
      { ts: '2026-09-19T09:30:00.000Z', value: 10, traceId: null },
    ];

    const [first, second, third] = buildScatterPoints(points, scale, from, to);

    expect(second.fractionX - first.fractionX).toBeCloseTo(1 / 60);
    expect(third.fractionX - second.fractionX).toBeGreaterThan(0.8);
  });

  it('drops points with an unparseable timestamp or a non-finite value, keeping the others keyed by index', () => {
    const points: DistributionPoint[] = [
      { ts: 'garbage', value: 5, traceId: null },
      { ts: '2026-09-19T05:00:00.000Z', value: Number.NaN, traceId: null },
      { ts: '2026-09-19T05:00:00.000Z', value: 50, traceId: null },
    ];

    const scatterPoints = buildScatterPoints(points, scale, from, to);

    expect(scatterPoints).toHaveLength(1);
    expect(scatterPoints[0].key).toBe(2);
  });

  it('clamps a point outside the request window onto its edge and treats a missing traceId as null', () => {
    const points = [{ ts: '2026-09-18T00:00:00.000Z', value: 1 }] as unknown as DistributionPoint[];

    const [scatterPoint] = buildScatterPoints(points, scale, from, to);

    expect(scatterPoint.fractionX).toBe(0);
    expect(scatterPoint.traceId).toBeNull();
  });

  it('returns nothing for no points', () => {
    expect(buildScatterPoints([], scale, from, to)).toEqual([]);
  });
});
