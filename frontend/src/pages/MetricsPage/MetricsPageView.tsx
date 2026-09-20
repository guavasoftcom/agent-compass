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
import { Box, Stack } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import type { WindowOption } from '../../lib/constants';
import type { SpanRow, TraceRow, WindowSelection } from '../../api';
import MetricCatalogRail from './components/MetricCatalogRail';
import MetricFacetBar from './components/MetricFacetBar';
import MetricHeader from './components/MetricHeader';
import MetricBreakdown from './components/MetricBreakdown';
import MetricDistributionCard from './components/MetricDistributionCard';
import { distributionUnitFor } from './components/MetricDistributionCard/distributionScatter';
import MetricExemplarDrawer, { type MetricExemplar } from './components/MetricExemplarDrawer';
import MetricTrendCard from './components/MetricTrendCard';
import { METRICS, type MetricSeries } from './components/metricsSampleData';
import type {
  AttributeFilter,
  MetricAggregation,
  MetricDistribution,
  MetricFacet,
} from './metricsApi';

export interface MetricsPageViewProps {
  /** Window-selection chrome (same contract as every other page). */
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
  /** The claude_code.* metrics. Optional so the page renders on sample data. */
  metrics?: MetricSeries[];
  isLoading?: boolean;
  error?: Error | null;
  /** Controlled selection (owned by the container, which needs it to gate the distribution fetch). */
  selectedId?: string;
  onSelectedIdChange: (next: string) => void;
  /** Per-request distribution of the selected metric; undefined while loading or when it has none. */
  distribution?: MetricDistribution;
  isDistributionLoading?: boolean;
  distributionError?: string | null;
  /** ISO-8601 bounds of the request window: the distribution's x axis is real time within them. */
  windowFrom: string;
  windowTo: string;
  /** Called with an exemplar's trace id; the container opens that trace's quick-peek drawer. */
  onOpenTrace: (traceId: string) => void;
  /** Trace id of the exemplar whose drawer is open; null when it is closed. */
  openTraceId: string | null;
  onCloseTrace: () => void;
  /** The drawer's hand-off to the full Trace Detail page; the container navigates. */
  onOpenTraceInTraces: (traceId: string, spanId: string | null) => void;
  /** The open exemplar's trace summary: undefined while loading, null when the backend has none. */
  exemplarSummary?: TraceRow | null;
  exemplarSpans?: SpanRow[];
  isExemplarLoading?: boolean;
  exemplarErrorMessage?: string | null;
  repositoryUrl: string | null;
  onRepositoryUrlChange: (next: string | null) => void;
  /**
   * The selected metric's attribute filters (ANDed, at most one per key), owned by the container,
   * which also clears them when the selected metric changes.
   */
  filters: AttributeFilter[];
  onFiltersChange: (next: AttributeFilter[]) => void;
  /** Filterable attributes of the selected metric; undefined until the picker has been opened. */
  facets?: MetricFacet[];
  isFacetsLoading?: boolean;
  facetsErrorMessage?: string | null;
  /** Reported so the container can fetch facets only while the picker is open. */
  onFacetPickerOpenChange: (isOpen: boolean) => void;
  /** How the selected metric's trend is aggregated; the container resets it on a metric switch. */
  aggregation: MetricAggregation;
  onAggregationChange: (next: MetricAggregation) => void;
}

const SPLIT_NONE = 'None';

/**
 * Metrics page — a simplified master-detail over the claude_code.* counters.
 *
 * Left: the searchable metric catalog rail (sparkline + cardinality per row).
 * Right: the selected metric's header stats, a trend chart, and a breakdown
 * card. A full-width facet bar above both hosts the attribute filters (ANDed chips /
 * "+ Add filter" picker), the "Split by" control (None / Model / Type / …, only
 * for metrics with an attribute breakdown — splitting stacks the chart and fills
 * the breakdown card), and the Agg control (sum / avg / p95 / count). A non-sum Agg
 * forces the split to None for rendering (the stored split is kept, so switching
 * back to sum restores it). Below them, a full-width per-request distribution card
 * (scatter of every request, percentile lines, exemplar dots that open the request's trace) — a
 * placeholder for metrics without a per-request value.
 *
 * Presentational: the container supplies window chrome + live data (and owns the
 * selected metric id, the filters and the aggregation); defaults to sample data in
 * components/metricsSampleData.ts. See BACKEND.md.
 */
const MetricsPageView = ({
  selection,
  onSelectionChange,
  windows,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  metrics = METRICS,
  isLoading = false,
  error = null,
  selectedId,
  onSelectedIdChange,
  distribution,
  isDistributionLoading = false,
  distributionError = null,
  windowFrom,
  windowTo,
  onOpenTrace,
  openTraceId,
  onCloseTrace,
  onOpenTraceInTraces,
  exemplarSummary,
  exemplarSpans,
  isExemplarLoading = false,
  exemplarErrorMessage = null,
  repositoryUrl,
  onRepositoryUrlChange,
  filters,
  onFiltersChange,
  facets,
  isFacetsLoading = false,
  facetsErrorMessage = null,
  onFacetPickerOpenChange,
  aggregation,
  onAggregationChange,
}: MetricsPageViewProps) => {
  // The stored split survives a non-sum aggregation untouched; only what is rendered changes.
  const [storedSplit, setSplit] = useState<string>(SPLIT_NONE);
  const [search, setSearch] = useState('');

  const selected = useMemo(
    () => metrics.find((m) => m.id === selectedId) ?? metrics[0],
    [metrics, selectedId],
  );

  // The distribution payload has only a time and a value per request, so the drawer's context
  // line and headline figure come from the point the dot was drawn for. Memoized because the
  // drawer keeps the last non-null exemplar through its slide-out by comparing identity.
  const exemplar = useMemo<MetricExemplar | null>(() => {
    if (openTraceId === null || !selected) {
      return null;
    }
    const point = distribution?.points.find((candidate) => candidate.traceId === openTraceId);
    return {
      traceId: openTraceId,
      spanId: point?.spanId ?? null,
      metricName: selected.name,
      unit: distributionUnitFor(selected.unit),
      value: point?.value ?? null,
      timestamp: point?.ts ?? null,
      summary: exemplarSummary,
      spans: exemplarSpans,
      isLoading: isExemplarLoading,
      errorMessage: exemplarErrorMessage,
    };
  }, [
    openTraceId,
    selected,
    distribution,
    exemplarSummary,
    exemplarSpans,
    isExemplarLoading,
    exemplarErrorMessage,
  ]);

  const selectMetric = (id: string) => {
    onSelectedIdChange(id);
    setSplit(SPLIT_NONE);
  };

  const splitKeys = selected
    ? [SPLIT_NONE, ...Object.keys(selected.splits)]
    : [SPLIT_NONE];

  // Averages / percentiles / counts are not additive across groups, so a non-sum aggregation
  // renders as if the split were None (the Split-by control is disabled to match).
  const split = aggregation === 'sum' ? storedSplit : SPLIT_NONE;

  // Series/health stats: with no split active the chart draws the metric's own single
  // total series; with a split active it draws one series per row in that split.
  const activeSplitRows = selected && split !== SPLIT_NONE ? selected.splits[split] : undefined;
  const seriesCount = activeSplitRows?.length ?? 1;
  const seriesUnitLabel = seriesCount === 1 ? 'series' : `${split.toLowerCase()}s`;

  return (
    <PageLayout
      eyebrow="Observability"
      title="Metrics"
      subtitle={
        'Every counter received in the claude_code.* namespace. Pick a metric to see its trend ' +
        'over the selected window; split token & cost by model when you need the breakdown.'
      }
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
          repositorySelector={{ value: repositoryUrl, onChange: onRepositoryUrlChange }}
        />
      }
    >
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.25 }}>
        {selected && (
          <MetricFacetBar
            // Keyed by metric so the filter picker's local popover state starts closed on a switch.
            key={selected.id}
            splitKeys={splitKeys}
            split={split}
            onSplitChange={setSplit}
            aggregation={aggregation}
            onAggregationChange={onAggregationChange}
            filters={filters}
            onFiltersChange={onFiltersChange}
            facets={facets}
            isFacetsLoading={isFacetsLoading}
            facetsErrorMessage={facetsErrorMessage}
            onFacetPickerOpenChange={onFacetPickerOpenChange}
          />
        )}
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '286px minmax(0, 1fr)' },
            gap: 2.25,
            alignItems: 'start',
          }}
        >
          <MetricCatalogRail
            metrics={metrics}
            selectedId={selected?.id ?? ''}
            onSelect={selectMetric}
            search={search}
            onSearchChange={setSearch}
          />
          {selected && (
            <Stack sx={{ gap: 2.25, minWidth: 0 }}>
              <MetricHeader metric={selected} seriesCount={seriesCount} seriesUnitLabel={seriesUnitLabel} />
              {/* The rail now takes a column, so trend + breakdown only sit side by side
                  once the detail column is wide enough for both (xl, not md). */}
              <Box
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', xl: 'minmax(0, 1fr) 300px' },
                  gap: 2.25,
                  alignItems: 'stretch',
                }}
              >
                <MetricTrendCard
                  metric={selected}
                  split={split}
                  aggregation={aggregation}
                  isLoading={isLoading}
                />
                <MetricBreakdown metric={selected} split={split} aggregation={aggregation} />
              </Box>
              <MetricDistributionCard
                metric={selected}
                distribution={distribution}
                windowFrom={windowFrom}
                windowTo={windowTo}
                isLoading={isDistributionLoading}
                errorMessage={distributionError}
                onOpenTrace={onOpenTrace}
                ignoresAttributeFilter={filters.length > 0}
              />
            </Stack>
          )}
        </Box>
      </Box>
      <MetricExemplarDrawer exemplar={exemplar} onClose={onCloseTrace} onOpenInTraces={onOpenTraceInTraces} />
    </PageLayout>
  );
};

export default MetricsPageView;
