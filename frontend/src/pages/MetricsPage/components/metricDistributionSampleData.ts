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
// Sample data for the Metrics page's per-request distribution scatter
// (`GET /api/metrics/distribution`): `{ points: [{ ts, value, traceId }] }`, ascending by `ts`.
//
// Like the real feed, the requests arrive in bursts of activity separated by real idle gaps
// (Claude Code is a personal CLI tool, not a steady API drip), values are heavy-tailed (most
// requests are small, a few are large), and only a handful of points — the exemplars — carry a
// trace id. Percentiles are NOT part of the payload; the card computes them from the values.
//
// Deterministic: each metric has its own fixed seed via createSampleRng, so the fixture is
// identical across reloads and across calls. The trace ids are fake — the sample mode never
// opens a real trace.

import { createSampleRng } from '../../../lib/sampleData';
import type { DistributionPoint, MetricDistribution } from '../metricsApi';

const TRACE_ID_LENGTH = 32;

/** [windowStartFraction, windowEndFraction, requestCount] — bursts with idle gaps between them. */
const ACTIVITY_CLUSTERS: ReadonlyArray<readonly [number, number, number]> = [
  [0.02, 0.1, 16],
  [0.28, 0.4, 24],
  [0.52, 0.64, 20],
  [0.76, 0.99, 30],
];

/** Where in the window each exemplar sits (fractions of the window), left to right. */
const EXEMPLAR_FRACTIONS = [8, 13, 16, 19, 21, 22].map((column) => column / 23);

interface DistributionShape {
  seed: number;
  /** Upper bound of the heavy-tailed draw. */
  maximumValue: number;
  /** Smallest value a request can have (keeps a log axis off a vanishing floor). */
  minimumValue: number;
  roundValue: (value: number) => number;
  exemplarValues: number[];
}

const TOKEN_SHAPE: DistributionShape = {
  seed: 4242,
  maximumValue: 256000,
  minimumValue: 180,
  roundValue: Math.round,
  exemplarValues: [13180, 27340, 61200, 118400, 214600, 53900],
};

const COST_SHAPE: DistributionShape = {
  seed: 7331,
  maximumValue: 0.32,
  minimumValue: 0.0006,
  roundValue: (value) => Math.round(value * 1e6) / 1e6,
  exemplarValues: [0.052, 0.129, 0.186, 0.238, 0.288, 0.146],
};

/**
 * Builds a deterministic MetricDistribution fixture for the token or cost metric (any name not
 * containing "cost" falls back to the token shape) over the given window.
 */
export const buildMetricDistributionSample = (
  metricName: string,
  from: string,
  to: string,
): MetricDistribution => {
  const shape = metricName.includes('cost') ? COST_SHAPE : TOKEN_SHAPE;
  const rng = createSampleRng(shape.seed);
  const windowStartMs = new Date(from).getTime();
  const windowSpanMs = new Date(to).getTime() - windowStartMs;
  const isoAt = (fraction: number): string => new Date(windowStartMs + fraction * windowSpanMs).toISOString();

  const points: DistributionPoint[] = [];
  ACTIVITY_CLUSTERS.forEach(([startFraction, endFraction, requestCount]) => {
    for (let requestIndex = 0; requestIndex < requestCount; requestIndex += 1) {
      const fraction = startFraction + rng.rnd() * (endFraction - startFraction);
      // Raising the uniform draw to a power above 1 skews the values low, with a long tail up to the maximum.
      const drawnValue = shape.maximumValue * Math.pow(rng.rnd(), 2.3) * (0.35 + 0.65 * rng.rnd());
      points.push({
        ts: isoAt(fraction),
        value: shape.roundValue(Math.max(shape.minimumValue, drawnValue)),
        traceId: null,
      });
    }
  });
  EXEMPLAR_FRACTIONS.forEach((fraction, exemplarIndex) => {
    points.push({
      ts: isoAt(fraction),
      value: shape.exemplarValues[exemplarIndex],
      traceId: rng.hx(TRACE_ID_LENGTH),
    });
  });

  points.sort((left, right) => Date.parse(left.ts) - Date.parse(right.ts));
  return { points };
};
