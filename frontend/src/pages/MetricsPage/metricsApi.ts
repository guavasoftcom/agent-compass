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
// ---------------------------------------------------------------------------
// Metrics page API client
//
// The simplified Metrics page needs one main call: the list of claude_code.* metrics,
// each with a headline value, a windowed trend, and any attribute splits (optionally
// filtered / re-aggregated for one metric), plus a per-metric attributes lookup for the filter
// picker and a per-request points feed for the distribution scatter.
// Defaults to LIVE; set VITE_METRICS_SAMPLE=1 to render the static fixtures
// instead (useful before the backend lands). See BACKEND.md for the contract.
// ---------------------------------------------------------------------------

import { METRICS, type MetricSeries } from './components/metricsSampleData';
import { buildMetricDistributionSample } from './components/metricDistributionSampleData';
import { buildMetricFacetsSample } from './components/metricFacetsSampleData';

const USE_SAMPLE_DATA =
  (import.meta as unknown as { env?: Record<string, string> }).env
    ?.VITE_METRICS_SAMPLE === '1';

/**
 * How the selected metric's trend buckets are aggregated. `sum` is the default (and is never sent
 * over the wire); the others describe the individual non-zero data-point increments in a bucket.
 */
export type MetricAggregation = 'sum' | 'avg' | 'p95' | 'count';

/** A single attribute-equals-value filter. Several are ANDed together, scoped to one metric. */
export interface AttributeFilter {
  key: string;
  value: string;
}

/** One value of a filterable attribute; `count` = active label-sets carrying that value. */
export interface MetricFacetValue {
  value: string;
  count: number;
}

/** One filterable attribute key of a metric, with its values (by count, descending). */
export interface MetricFacet {
  key: string;
  values: MetricFacetValue[];
}

/** Time window derived from the page's WindowSelection. */
export interface MetricsWindowParams {
  /** ISO-8601 start / end of the selected window. */
  from: string;
  to: string;
  /** Restrict to one repository's telemetry; omit (or null) for all repositories. */
  repositoryUrl?: string | null;
}

/**
 * Params of GET /api/metrics/series: the window plus the optional, metric-scoped filters and
 * aggregation. Each group is only serialized when set.
 */
export interface MetricsQueryParams extends MetricsWindowParams {
  /** Filter group (both or none): ANDed attribute filters that restrict ONLY this metric. */
  filterMetricId?: string;
  filters?: AttributeFilter[];
  /** Aggregation group (both or none): changes ONLY this metric's `trend` array. Omit for sum. */
  aggMetricId?: string;
  agg?: MetricAggregation;
}

const toWindowQuery = (params: MetricsWindowParams): URLSearchParams => {
  const query = new URLSearchParams({ from: params.from, to: params.to });
  if (params.repositoryUrl) {
    query.set('repositoryUrl', params.repositoryUrl);
  }
  return query;
};

const toSeriesQuery = (params: MetricsQueryParams): string => {
  const query = toWindowQuery(params);
  if (params.filterMetricId && params.filters && params.filters.length > 0) {
    query.set('filterMetricId', params.filterMetricId);
    // One repeated `filter=key:value` per active chip; append() percent-encodes the value.
    params.filters.forEach((attributeFilter) => {
      query.append('filter', `${attributeFilter.key}:${attributeFilter.value}`);
    });
  }
  // sum is the server's default: never send it.
  if (params.aggMetricId && params.agg && params.agg !== 'sum') {
    query.set('aggMetricId', params.aggMetricId);
    query.set('agg', params.agg);
  }
  return query.toString();
};

const getJSON = async <T>(url: string): Promise<T> => {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} — ${url}`);
  }
  return (await res.json()) as T;
};

/**
 * GET /api/metrics/series — the claude_code.* metrics with trend + splits. In sample mode the
 * static fixture comes back as-is: the filter and aggregation params do not change it.
 */
export const fetchMetrics = async (
  params: MetricsQueryParams,
): Promise<MetricSeries[]> => {
  if (USE_SAMPLE_DATA) {
    return METRICS;
  }
  return getJSON<MetricSeries[]>(`/api/metrics/series?${toSeriesQuery(params)}`);
};

interface MetricAttributesResponse {
  attributes: MetricFacet[];
}

/**
 * GET /api/metrics/attributes — the filterable attribute keys (and their values with label-set
 * counts) of one metric in the window, addressed by the metric's FULL name (not its id). `[]`
 * when nothing is filterable.
 */
export const fetchMetricFacets = async (
  params: MetricsWindowParams & { metricName: string },
): Promise<MetricFacet[]> => {
  if (USE_SAMPLE_DATA) {
    return buildMetricFacetsSample(params.metricName);
  }
  const query = toWindowQuery(params);
  query.set('metric', params.metricName);
  const response = await getJSON<MetricAttributesResponse>(
    `/api/metrics/attributes?${query.toString()}`,
  );
  return response.attributes ?? [];
};

// ---------------------------------------------------------------------------
// Per-request distribution (GET /api/metrics/distribution)
//
// Owned here rather than in metricsSampleData.ts: the shape is the API contract.
// One row per request in the window, ascending by `ts`, capped server-side to the
// newest DISTRIBUTION_POINT_CAP requests. The payload carries no window: the caller
// already knows the request's from/to and plots each point at its real timestamp.
// Percentiles are computed client-side from the values.
// ---------------------------------------------------------------------------

/** The server-side cap on returned requests (the newest are kept). */
export const DISTRIBUTION_POINT_CAP = 2000;

/** One request. `traceId` is non-null only for the handful of server-chosen exemplars. */
export interface DistributionPoint {
  /** ISO-8601 instant of the request. */
  ts: string;
  /** Tokens or USD, by metric. */
  value: number;
  traceId: string | null;
  /**
   * The `llm_request` span this request produced, within `traceId`. Set only on exemplars, and null
   * when its span is not ingested (or absent on an older backend); the trace id alone still opens.
   */
  spanId?: string | null;
}

export interface MetricDistribution {
  points: DistributionPoint[];
}

/**
 * GET /api/metrics/distribution — the per-request points for one metric, addressed by its FULL
 * name (`claude_code.token.usage`, not its id). Only meaningful when that metric's
 * `hasDistribution` flag is true; the caller gates on it.
 */
export const fetchMetricDistribution = async (
  params: MetricsWindowParams & { metricName: string },
): Promise<MetricDistribution> => {
  if (USE_SAMPLE_DATA) {
    return buildMetricDistributionSample(params.metricName, params.from, params.to);
  }
  // The distribution API has no filter/aggregation params, so only the window is sent.
  // toWindowQuery already omits a null repositoryUrl; add the metric on top.
  const query = toWindowQuery(params);
  query.set('metric', params.metricName);
  return getJSON<MetricDistribution>(`/api/metrics/distribution?${query.toString()}`);
};
