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
import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { AUTO_REFRESH_INTERVAL_MS, MS_PER_MINUTE, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import { fetchSpansForTrace, fetchTraceSummaryOrNull } from '../TracesPage/tracesApi';
import MetricsPageView from './MetricsPageView';
import {
  fetchMetricDistribution,
  fetchMetricFacets,
  fetchMetrics,
  type AttributeFilter,
  type MetricAggregation,
  type MetricsQueryParams,
  type MetricsWindowParams,
} from './metricsApi';

/**
 * Metrics page container — sources the shared window selection and fetches the
 * claude_code.* metric series, plus the selected metric's per-request distribution
 * points when the backend says it has one, its filterable attributes (facets) once the
 * filter picker is opened, and the trace summary + spans of an exemplar while its drawer is open. Mirrors the window/auto-refresh wiring used by the other
 * pages; the simplified view does the rest.
 */
export default function MetricsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh, repositoryUrl, setRepositoryUrl } =
    useWindowContext();
  const navigate = useNavigate();
  // Lifted out of the view: the container needs the selected metric to decide whether
  // to fetch its distribution / facets and which metric a filter or aggregation is scoped to.
  // The view still owns the split (and resets it on select).
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  // The filters (ANDed chips) and aggregation are scoped to the selected metric and reset whenever
  // it changes (handleSelectedIdChange).
  const [attributeFilters, setAttributeFilters] = useState<AttributeFilter[]>([]);
  const [aggregation, setAggregation] = useState<MetricAggregation>('sum');
  // Facets are only fetched while the filter picker is open.
  const [facetPickerOpen, setFacetPickerOpen] = useState(false);
  // The exemplar whose trace peek drawer is open. Belongs to the selected metric's distribution,
  // so a metric switch closes it (handleSelectedIdChange).
  const [openTraceId, setOpenTraceId] = useState<string | null>(null);

  const windowParams = useMemo<MetricsWindowParams>(() => {
    // The metrics window is anchored to current wall-clock time at fetch time.
    // eslint-disable-next-line react-hooks/purity
    const nowMs = Date.now();
    const now = new Date(nowMs).toISOString();
    const from =
      selection.kind === 'custom'
        ? selection.startTimestamp
        : new Date(nowMs - selection.minutes * MS_PER_MINUTE).toISOString();
    const to = selection.kind === 'custom' ? selection.endTimestamp : now;
    return { from, to, repositoryUrl };
  }, [selection, repositoryUrl]);

  // The series query's params: the window, plus the metric-scoped filters / aggregation ONLY
  // while one is active, so a plain metric switch leaves the key (and the cache) untouched.
  // The filters list rides the key, so adding, replacing or removing a chip refetches.
  // selectedId is always set while either is active (see ensureMetricSelected), so it can be
  // used here without reading the query's own data (which would be circular).
  const params = useMemo<MetricsQueryParams>(() => {
    const seriesParams: MetricsQueryParams = { ...windowParams };
    if (selectedId && attributeFilters.length > 0) {
      seriesParams.filterMetricId = selectedId;
      seriesParams.filters = attributeFilters;
    }
    if (selectedId && aggregation !== 'sum') {
      seriesParams.aggMetricId = selectedId;
      seriesParams.agg = aggregation;
    }
    return seriesParams;
  }, [windowParams, selectedId, attributeFilters, aggregation]);

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset'
      ? AUTO_REFRESH_INTERVAL_MS
      : false;

  // keepPreviousData: a filter / aggregation change is a new key, and without it the view would
  // fall back to its sample catalog while the new series loads. `isPlaceholderData` keeps the
  // chart's Loading state honest meanwhile.
  const metricsQuery = useQuery({
    queryKey: ['metrics/series', params],
    queryFn: () => fetchMetrics(params),
    refetchInterval,
    placeholderData: keepPreviousData,
  });

  const metrics = metricsQuery.data;
  const selectedMetric = metrics?.find((metric) => metric.id === selectedId) ?? metrics?.[0];
  const hasDistribution = Boolean(selectedMetric?.hasDistribution);

  // No placeholderData: switching metrics must not show the previous metric's scatter
  // under the new metric's name while the new one loads. Window params only — the
  // distribution API has no filter / aggregation, so changing them must not refetch it.
  // The endpoint takes the metric's FULL name (`claude_code.token.usage`), not its id.
  const distributionQuery = useQuery({
    queryKey: ['metrics/distribution', selectedMetric?.id, windowParams],
    queryFn: () => fetchMetricDistribution({ ...windowParams, metricName: selectedMetric!.name }),
    enabled: hasDistribution,
    refetchInterval,
  });

  // No placeholderData here either: another metric's attributes must never be offered.
  const facetsQuery = useQuery({
    queryKey: [
      'metrics/facets',
      selectedMetric?.id,
      windowParams.from,
      windowParams.to,
      windowParams.repositoryUrl,
    ],
    queryFn: () => fetchMetricFacets({ ...windowParams, metricName: selectedMetric!.name }),
    enabled: facetPickerOpen && Boolean(selectedMetric),
  });

  // The drawer's trace peek reuses the Trace Detail page's two queries (same keys, so opening the
  // full trace afterwards is served from cache). Neither polls: an exemplar is a finished request.
  const exemplarSummaryQuery = useQuery({
    queryKey: ['trace-summary', openTraceId],
    queryFn: () => fetchTraceSummaryOrNull(openTraceId!),
    enabled: openTraceId !== null,
  });
  const exemplarSpansQuery = useQuery({
    queryKey: ['trace-spans', openTraceId],
    queryFn: () => fetchSpansForTrace(openTraceId!),
    enabled: openTraceId !== null,
  });

  const isPolling =
    autoRefresh &&
    selection.kind === 'preset' &&
    (metricsQuery.isFetching || distributionQuery.isFetching);

  const handleReload = () => {
    metricsQuery.refetch();
    // refetch() ignores `enabled`, so guard it: a metric with no distribution must not
    // fire a request the backend would reject.
    if (hasDistribution) {
      distributionQuery.refetch();
    }
    if (facetPickerOpen && selectedMetric) {
      facetsQuery.refetch();
    }
  };

  const handleSelectedIdChange = (nextId: string) => {
    setSelectedId(nextId);
    // The filters / aggregation belong to one metric: a real switch clears them all and closes the
    // picker. Re-clicking the already-selected row is not a switch.
    if (nextId !== selectedMetric?.id) {
      setAttributeFilters([]);
      setAggregation('sum');
      setFacetPickerOpen(false);
      setOpenTraceId(null);
    }
  };

  // The metric-scoped params are keyed by selectedId, which starts undefined (the page falls
  // back to the first metric). Pin it to the effective selection before a filter / aggregation
  // is applied so the params never depend on the query's own data.
  const ensureMetricSelected = () => {
    if (selectedId === undefined && selectedMetric) {
      setSelectedId(selectedMetric.id);
    }
  };

  const handleFiltersChange = (next: AttributeFilter[]) => {
    if (next.length > 0) {
      ensureMetricSelected();
    }
    setAttributeFilters(next);
  };

  const handleAggregationChange = (next: MetricAggregation) => {
    if (next !== 'sum') {
      ensureMetricSelected();
    }
    setAggregation(next);
  };

  return (
    <MetricsPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      metrics={metrics}
      isLoading={metricsQuery.isLoading || metricsQuery.isPlaceholderData}
      error={metricsQuery.error as Error | null}
      selectedId={selectedId}
      onSelectedIdChange={handleSelectedIdChange}
      distribution={distributionQuery.data}
      isDistributionLoading={distributionQuery.isLoading}
      distributionError={(distributionQuery.error as Error | null)?.message ?? null}
      windowFrom={windowParams.from}
      windowTo={windowParams.to}
      onOpenTrace={setOpenTraceId}
      openTraceId={openTraceId}
      onCloseTrace={() => setOpenTraceId(null)}
      onOpenTraceInTraces={(traceId, spanId) => {
        const spanQuery = spanId ? `?span=${encodeURIComponent(spanId)}` : '';
        navigate(`/traces/${encodeURIComponent(traceId)}${spanQuery}`);
      }}
      exemplarSummary={exemplarSummaryQuery.data}
      exemplarSpans={exemplarSpansQuery.data}
      isExemplarLoading={exemplarSummaryQuery.isLoading || exemplarSpansQuery.isLoading}
      exemplarErrorMessage={(exemplarSpansQuery.error as Error | null)?.message ?? null}
      repositoryUrl={repositoryUrl}
      onRepositoryUrlChange={setRepositoryUrl}
      filters={attributeFilters}
      onFiltersChange={handleFiltersChange}
      facets={facetsQuery.data}
      isFacetsLoading={facetsQuery.isLoading}
      facetsErrorMessage={(facetsQuery.error as Error | null)?.message ?? null}
      onFacetPickerOpenChange={setFacetPickerOpen}
      aggregation={aggregation}
      onAggregationChange={handleAggregationChange}
    />
  );
}
