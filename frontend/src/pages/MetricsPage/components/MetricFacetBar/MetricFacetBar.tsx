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
import { Box, Paper, Tooltip } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import SegmentedToggle from '../../../../components/SegmentedToggle';
import type { AttributeFilter, MetricAggregation, MetricFacet } from '../../metricsApi';
import { AGGREGATION_OPTIONS } from '../metricAggregation';
import AttributeFilterControl from './AttributeFilterControl';

export interface MetricFacetBarProps {
  splitKeys: string[];
  /** The split to display. The view passes 'None' while a non-sum aggregation is active. */
  split: string;
  onSplitChange: (next: string) => void;
  aggregation: MetricAggregation;
  onAggregationChange: (next: MetricAggregation) => void;
  /** The active attribute filters, ANDed together (at most one per key). */
  filters: AttributeFilter[];
  onFiltersChange: (next: AttributeFilter[]) => void;
  /** Undefined until the filter picker has been opened (facets are fetched lazily). */
  facets?: MetricFacet[];
  isFacetsLoading?: boolean;
  facetsErrorMessage?: string | null;
  onFacetPickerOpenChange: (isOpen: boolean) => void;
}

const GROUP_BY_DISABLED_TOOLTIP =
  'Averages and percentiles are not additive across groups - switch Agg back to sum to group';

const BarLabel = ({ children }: { children: string }) => (
  <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>{children}</Box>
);

/**
 * Control bar above the explorer, scoped to the selected metric. Left: the attribute filters (one
 * removable chip each, ANDed, followed by the always-present "+ Add filter" picker). Right:
 * "Split by" (only when the metric has at least one attribute split) and the Agg control that
 * re-aggregates the trend chart. The bar itself is never hidden.
 *
 * Split by is disabled while Agg is not sum: averages and percentiles are not additive across
 * groups, so a stacked split of them would lie. The stored split is the view's business — this
 * bar just shows what it is given.
 *
 * Pure props: filters, aggregation, split and the facets all live in the view / container. Parents
 * should `key` the bar by metric id so the filter picker's local popover state resets on a switch.
 */
const MetricFacetBar = ({
  splitKeys,
  split,
  onSplitChange,
  aggregation,
  onAggregationChange,
  filters,
  onFiltersChange,
  facets,
  isFacetsLoading = false,
  facetsErrorMessage = null,
  onFacetPickerOpenChange,
}: MetricFacetBarProps) => {
  const hasSplits = splitKeys.length > 1;
  const isSplitDisabled = aggregation !== 'sum';

  return (
    <Paper
      variant="outlined"
      sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.25, p: '12px 14px' }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.125, minWidth: 0 }}>
        <BarLabel>Filter</BarLabel>
        <AttributeFilterControl
          filters={filters}
          onFiltersChange={onFiltersChange}
          facets={facets}
          isFacetsLoading={isFacetsLoading}
          facetsErrorMessage={facetsErrorMessage}
          onFacetPickerOpenChange={onFacetPickerOpenChange}
        />
      </Box>

      <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 2.25 }}>
        {hasSplits && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.125 }}>
            <BarLabel>Split by</BarLabel>
            {/* SegmentedToggle has no disabled state, so a disabled bar is an inert, dimmed group;
                the tooltip hangs off the outer span because inert content takes no pointer events. */}
            <Tooltip title={isSplitDisabled ? GROUP_BY_DISABLED_TOOLTIP : ''} arrow>
              <Box component="span" sx={{ display: 'inline-flex' }}>
                <Box
                  role="group"
                  aria-label="Group by"
                  aria-disabled={isSplitDisabled || undefined}
                  inert={isSplitDisabled}
                  sx={{
                    display: 'inline-flex',
                    opacity: isSplitDisabled ? 0.45 : 1,
                    cursor: isSplitDisabled ? 'not-allowed' : undefined,
                  }}
                >
                  <SegmentedToggle
                    options={splitKeys.map((key) => ({ value: key, label: key }))}
                    value={split}
                    onChange={onSplitChange}
                  />
                </Box>
              </Box>
            </Tooltip>
            <Tooltip
              title="With **Split by** set, the areas stack — the top edge is the metric's total and each band's thickness is that slice's own value. With **None** the chart is a single total series; the y-axis label says which you're looking at."
              arrow
            >
              <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
            </Tooltip>
          </Box>
        )}

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.125 }}>
          <BarLabel>Agg</BarLabel>
          <SegmentedToggle<MetricAggregation>
            options={AGGREGATION_OPTIONS.map((option) => ({ value: option, label: option }))}
            value={aggregation}
            onChange={onAggregationChange}
          />
        </Box>
      </Box>
    </Paper>
  );
};

export default MetricFacetBar;
