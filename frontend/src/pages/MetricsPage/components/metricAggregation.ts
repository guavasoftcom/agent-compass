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
import type { MetricAggregation } from '../metricsApi';

/** The Agg control's options, in display order. `sum` is the default. */
export const AGGREGATION_OPTIONS: MetricAggregation[] = ['sum', 'avg', 'p95', 'count'];

export interface AggregationLabels {
  /** Legend text for the single series. */
  legendLabel: string;
  /** Y-axis label. */
  yLabel: string;
  /** Short suffix appended to the card title, e.g. "avg". */
  titleSuffix: string;
}

/**
 * Copy for a non-sum aggregation. avg / p95 are measured in the metric's own unit but describe one
 * data point, so they say "per data point"; count is a number of points, so its unit is "points".
 * `unit` is the metric's unit with braces stripped (may be empty).
 */
export const describeAggregation = (
  aggregation: Exclude<MetricAggregation, 'sum'>,
  unit: string,
): AggregationLabels => {
  const titleSuffix = aggregation;
  if (aggregation === 'count') {
    return { legendLabel: 'data points per bucket', yLabel: 'points', titleSuffix };
  }
  const perPoint = `${aggregation} per point`;
  return {
    legendLabel: `${aggregation} per data point`,
    yLabel: unit ? `${unit}, ${perPoint}` : perPoint,
    titleSuffix,
  };
};
