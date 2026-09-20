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
import type { MetricFacet } from '../metricsApi';

// VITE_METRICS_SAMPLE fixture for GET /api/metrics/attributes (the `attributes` array, already
// unwrapped). Keyed by the metric's FULL name, like the real endpoint's `metric` param. Shaped
// like the real response: keys alphabetical, values by label-set count descending. Metrics
// without a fixture (every other curated metric and every discovered one) have nothing
// filterable, i.e. `[]`.

const MODEL_FACET: MetricFacet = {
  key: 'model',
  values: [
    { value: 'claude-sonnet-4', count: 14 },
    { value: 'claude-opus-4', count: 9 },
    { value: 'claude-haiku-3.5', count: 4 },
  ],
};

const TERMINAL_TYPE_FACET: MetricFacet = {
  key: 'terminal.type',
  values: [
    { value: 'vscode', count: 15 },
    { value: 'iTerm.app', count: 7 },
  ],
};

const FACETS_BY_METRIC_NAME: Record<string, MetricFacet[]> = {
  'claude_code.token.usage': [MODEL_FACET, TERMINAL_TYPE_FACET],
  'claude_code.cost.usage': [MODEL_FACET, TERMINAL_TYPE_FACET],
  'claude_code.lines_of_code.count': [
    {
      key: 'type',
      values: [
        { value: 'added', count: 22 },
        { value: 'removed', count: 17 },
      ],
    },
  ],
  'claude_code.code_edit_tool.decision': [
    {
      key: 'decision',
      values: [
        { value: 'accepted', count: 31 },
        { value: 'rejected', count: 12 },
      ],
    },
  ],
};

export const buildMetricFacetsSample = (metricName: string): MetricFacet[] =>
  FACETS_BY_METRIC_NAME[metricName] ?? [];
