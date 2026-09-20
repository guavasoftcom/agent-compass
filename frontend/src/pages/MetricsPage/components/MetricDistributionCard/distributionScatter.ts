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
// Pure scale / percentile / axis helpers for MetricDistributionCard's per-request scatter (no
// React), split out so the axis rules can be unit-tested without rendering the SVG.
//
// Everything is derived from the points themselves, never from hardcoded ceilings: the y domain
// is a nice round ceiling above the largest value (log floor: the power of ten at or below the
// smallest positive value), and the percentiles are computed client-side from the sorted values.
// x is the request WINDOW (the payload carries none), so a burst of activity sits at its real
// time and an idle stretch is simply empty.

import { formatCompact } from '../../../../lib/format';
import type { DistributionPoint } from '../../metricsApi';

export type DistributionUnit = 'tokens' | 'USD';
export type ScaleKind = 'log' | 'linear';

export const EM_DASH = '—';
export const TIME_LABEL_COUNT = 5;

/** A window this short or shorter labels the x-axis with clock times; longer uses dates. */
const CLOCK_LABEL_MAX_SPAN_MS = 48 * 60 * 60 * 1000;
/** The last x-axis label reads "now" when the window ends within this of the current time. */
const NOW_LABEL_TOLERANCE_MS = 5 * 60 * 1000;

/** Aim for roughly this many intervals on a linear axis. */
const LINEAR_TARGET_INTERVALS = 4;
/** Human-friendly step mantissas for a linear axis (1, 2, 5 x 10^n). */
const LINEAR_STEP_MANTISSAS = [1, 2, 5, 10];
/** Human-friendly ceiling mantissas for a log axis (1, 2, 3, 5 x 10^n). */
const LOG_CEILING_MANTISSAS = [1, 2, 3, 5, 10];
/** The ceiling always clears the largest value by at least this factor, so it never sits on the edge. */
const CEILING_HEADROOM_FACTOR = 1.05;
/** Smallest power of ten a log floor may take; keeps a denormal-ish value from degenerating the axis. */
const LOG_FLOOR_MIN_EXPONENT = -12;

/** Which unit a metric's per-request value is in, from the metric's own `unit` field. */
export const distributionUnitFor = (metricUnit: string): DistributionUnit =>
  metricUnit === 'USD' ? 'USD' : 'tokens';

/** Tokens span orders of magnitude (log); cost is a small-dollar figure read from zero (linear). */
export const scaleKindFor = (unit: DistributionUnit): ScaleKind => (unit === 'USD' ? 'linear' : 'log');

/** Strips float noise (0.30000000000000004 -> 0.3) from a computed tick. */
const removeFloatNoise = (value: number): number => Number(value.toPrecision(12));

const finiteValuesOf = (values: readonly number[]): number[] =>
  values.filter((value) => Number.isFinite(value));

// ---------------------------------------------------------------------------
// Percentiles
// ---------------------------------------------------------------------------

/**
 * Nearest-rank percentile of an ASCENDING-sorted list: the smallest value with at least
 * `fraction` of the list at or below it. Null for an empty list.
 */
export const computePercentile = (
  sortedAscendingValues: readonly number[],
  fraction: number,
): number | null => {
  if (sortedAscendingValues.length === 0) {
    return null;
  }
  const clampedFraction = Math.min(1, Math.max(0, fraction));
  const rankIndex = Math.ceil(clampedFraction * sortedAscendingValues.length) - 1;
  return sortedAscendingValues[Math.max(0, rankIndex)];
};

export interface DistributionSummary {
  requestCount: number;
  p50: number | null;
  p95: number | null;
  p99: number | null;
}

/** Whole-window figures from the raw (unsorted) values; non-finite values are ignored. */
export const summarizeValues = (values: readonly number[]): DistributionSummary => {
  const sortedValues = finiteValuesOf(values).sort((left, right) => left - right);
  return {
    requestCount: sortedValues.length,
    p50: computePercentile(sortedValues, 0.5),
    p95: computePercentile(sortedValues, 0.95),
    p99: computePercentile(sortedValues, 0.99),
  };
};

// ---------------------------------------------------------------------------
// Value (y) scale
// ---------------------------------------------------------------------------

export interface ValueScale {
  kind: ScaleKind;
  /** Bottom of the axis: 0 for linear, the log floor (a power of ten) for log. */
  minimum: number;
  /** Top of the axis: a nice round number above the largest value. */
  maximum: number;
  /** Ascending tick values, minimum first. */
  ticks: number[];
}

/** Smallest 1/2/5/10 x 10^n that is at least `rawStep`. */
const niceLinearStep = (rawStep: number): number => {
  const exponent = Math.floor(Math.log10(rawStep));
  const magnitude = 10 ** exponent;
  const mantissa = LINEAR_STEP_MANTISSAS.find((candidate) => rawStep / magnitude <= candidate + 1e-9) ?? 10;
  return mantissa * magnitude;
};

const buildLinearScale = (largestValue: number): ValueScale => {
  if (largestValue <= 0) {
    return { kind: 'linear', minimum: 0, maximum: 1, ticks: [0, 0.25, 0.5, 0.75, 1] };
  }
  const step = niceLinearStep(largestValue / LINEAR_TARGET_INTERVALS);
  let intervalCount = Math.ceil(largestValue / step);
  // A value that lands exactly on a tick would sit on the top edge: add one more interval.
  if (intervalCount * step < largestValue * CEILING_HEADROOM_FACTOR) {
    intervalCount += 1;
  }
  const ticks = Array.from({ length: intervalCount + 1 }, (_, index) => removeFloatNoise(index * step));
  return { kind: 'linear', minimum: 0, maximum: ticks[ticks.length - 1], ticks };
};

const buildLogScale = (positiveValues: number[]): ValueScale => {
  if (positiveValues.length === 0) {
    return { kind: 'log', minimum: 1, maximum: 10, ticks: [1, 10] };
  }
  const smallestValue = Math.min(...positiveValues);
  const largestValue = Math.max(...positiveValues);

  let floorExponent = Math.max(LOG_FLOOR_MIN_EXPONENT, Math.floor(Math.log10(smallestValue)));
  if (10 ** floorExponent > smallestValue) {
    floorExponent = Math.max(LOG_FLOOR_MIN_EXPONENT, floorExponent - 1);
  }
  const minimum = removeFloatNoise(10 ** floorExponent);

  const ceilingTarget = largestValue * CEILING_HEADROOM_FACTOR;
  const ceilingExponent = Math.floor(Math.log10(ceilingTarget));
  const ceilingMagnitude = 10 ** ceilingExponent;
  const ceilingMantissa =
    LOG_CEILING_MANTISSAS.find((candidate) => candidate * ceilingMagnitude >= ceilingTarget) ?? 10;
  // The floor never reaches the ceiling: the smallest value is <= the largest, the ceiling clears
  // the largest by the headroom factor, and 2x the floor is the next mantissa above 1.
  const maximum = Math.max(removeFloatNoise(ceilingMantissa * ceilingMagnitude), minimum * 2);

  const ticks: number[] = [];
  for (let exponent = floorExponent; 10 ** exponent <= maximum * (1 + 1e-9); exponent += 1) {
    ticks.push(removeFloatNoise(10 ** exponent));
  }
  if (maximum > ticks[ticks.length - 1] * 1.01) {
    ticks.push(maximum);
  }
  return { kind: 'log', minimum, maximum, ticks };
};

/**
 * Builds the y scale from the plotted values. Linear: from 0 to a nice ceiling above the maximum,
 * ticked at 1/2/5 steps. Log: from the power of ten at or below the smallest POSITIVE value to a
 * nice ceiling above the maximum, ticked at each decade plus the ceiling. Non-finite values are
 * ignored; an empty or all-non-positive input yields a small well-formed default scale.
 */
export const buildValueScale = (kind: ScaleKind, values: readonly number[]): ValueScale => {
  const finiteValues = finiteValuesOf(values);
  if (kind === 'linear') {
    return buildLinearScale(finiteValues.length === 0 ? 0 : Math.max(...finiteValues));
  }
  return buildLogScale(finiteValues.filter((value) => value > 0));
};

/**
 * Fractional height of a value on the scale: 0 = bottom, 1 = top, clamped both ways. On a log
 * scale, non-positive (and non-finite) values sit on the floor rather than at minus infinity.
 */
export const valueToFraction = (scale: ValueScale, value: number): number => {
  if (scale.kind === 'linear') {
    if (!Number.isFinite(value)) {
      return 0;
    }
    return Math.min(1, Math.max(0, value / scale.maximum));
  }
  if (!Number.isFinite(value) || value <= scale.minimum) {
    return 0;
  }
  const logMinimum = Math.log10(scale.minimum);
  const fraction = (Math.log10(value) - logMinimum) / (Math.log10(scale.maximum) - logMinimum);
  return Math.min(1, Math.max(0, fraction));
};

// ---------------------------------------------------------------------------
// Time (x) axis
// ---------------------------------------------------------------------------

/** Fractional position of an instant within [fromMs, toMs]: 0 = left edge, clamped to the window. */
export const timestampToFraction = (timestampMs: number, fromMs: number, toMs: number): number => {
  const spanMs = toMs - fromMs;
  if (!(spanMs > 0)) {
    return 0;
  }
  return Math.min(1, Math.max(0, (timestampMs - fromMs) / spanMs));
};

const padTwoDigits = (value: number): string => String(value).padStart(2, '0');

/**
 * Evenly spaced x-axis labels across [from, to]. Windows up to 48h read as local `HH:mm`;
 * longer ones as a short date. The last label is "now" only when the window actually ends
 * within a few minutes of `nowMs` (a custom, historical range should show its real end).
 */
export const buildTimeAxisLabels = (
  from: string,
  to: string,
  nowMs: number,
  labelCount: number = TIME_LABEL_COUNT,
): string[] => {
  const startMs = Date.parse(from);
  const endMs = Date.parse(to);
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) {
    return [];
  }
  const spanMs = endMs - startMs;
  const formatInstant = (instantMs: number): string => {
    const instant = new Date(instantMs);
    if (spanMs <= CLOCK_LABEL_MAX_SPAN_MS) {
      return `${padTwoDigits(instant.getHours())}:${padTwoDigits(instant.getMinutes())}`;
    }
    return instant.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  };
  const labels = Array.from({ length: labelCount }, (_, index) =>
    formatInstant(startMs + (spanMs * index) / Math.max(1, labelCount - 1)),
  );
  if (Math.abs(nowMs - endMs) <= NOW_LABEL_TOLERANCE_MS) {
    labels[labels.length - 1] = 'now';
  }
  return labels;
};

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

const createUsdFormatter = (decimals: number): Intl.NumberFormat =>
  new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
const USD_WHOLE = createUsdFormatter(0);
const USD_CENTS = createUsdFormatter(2);
const USD_MILLS = createUsdFormatter(3);

/** A single per-request value: compact tokens, or dollars to the mill below $1 and to the cent above. */
export const formatDistributionValue = (value: number, unit: DistributionUnit): string => {
  if (unit !== 'USD') {
    return formatCompact(value);
  }
  return (Math.abs(value) < 1 ? USD_MILLS : USD_CENTS).format(value);
};

/** A percentile chip figure: unit-aware, and an em dash for null. */
export const formatPercentileValue = (
  value: number | null | undefined,
  unit: DistributionUnit,
): string => {
  if (value === null || value === undefined) {
    return EM_DASH;
  }
  return formatDistributionValue(value, unit);
};

/**
 * A y-axis tick label. Tokens use the shared compact form. USD picks its precision from the
 * axis maximum so ticks stay distinct without float noise: whole dollars once the axis reaches
 * $100, cents down to a $0.10 maximum, three decimals below that.
 */
export const formatAxisValue = (value: number, unit: DistributionUnit, axisMaximum: number): string => {
  if (unit !== 'USD') {
    return formatCompact(value);
  }
  if (axisMaximum >= 100) {
    return USD_WHOLE.format(value);
  }
  return (axisMaximum >= 0.1 ? USD_CENTS : USD_MILLS).format(value);
};

// ---------------------------------------------------------------------------
// Points
// ---------------------------------------------------------------------------

export interface ScatterPoint {
  /** Stable React key: index within the payload. */
  key: number;
  timestamp: string;
  value: number;
  /** Non-null only for an exemplar. */
  traceId: string | null;
  /** 0 = left edge of the request window, 1 = right edge. */
  fractionX: number;
  /** 0 = bottom of the axis, 1 = top. */
  fractionY: number;
}

/**
 * Places every usable point on the scale and the window. A point with an unparseable
 * timestamp or a non-finite value is dropped, not drawn at a fake position.
 */
export const buildScatterPoints = (
  points: readonly DistributionPoint[],
  scale: ValueScale,
  from: string,
  to: string,
): ScatterPoint[] => {
  const fromMs = Date.parse(from);
  const toMs = Date.parse(to);
  const scatterPoints: ScatterPoint[] = [];
  points.forEach((point, index) => {
    const timestampMs = Date.parse(point.ts);
    if (Number.isNaN(timestampMs) || !Number.isFinite(point.value)) {
      return;
    }
    scatterPoints.push({
      key: index,
      timestamp: point.ts,
      value: point.value,
      traceId: point.traceId ?? null,
      fractionX: timestampToFraction(timestampMs, fromMs, toMs),
      fractionY: valueToFraction(scale, point.value),
    });
  });
  return scatterPoints;
};
