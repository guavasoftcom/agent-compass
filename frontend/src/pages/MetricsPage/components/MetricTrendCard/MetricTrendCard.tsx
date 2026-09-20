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
import { useMemo } from 'react';
import { Box, Paper, Tooltip, Typography } from '@mui/material';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import AreaTrendChart from '../../../../components/AreaTrendChart';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { formatCompact, isSparseCounter } from '../../../../lib/format';
import type { MetricAggregation } from '../../metricsApi';
import { describeAggregation } from '../metricAggregation';
import type { MetricSeries } from '../metricsSampleData';

export interface MetricTrendCardProps {
  metric: MetricSeries;
  /** The split to draw; the view passes 'None' while a non-sum aggregation is active. */
  split: string;
  /** Defaults to sum, the only aggregation that can be stacked by a split. */
  aggregation?: MetricAggregation;
  isLoading?: boolean;
}

const SPLIT_NONE = 'None';

const NON_SUM_AGGREGATION_TOOLTIP =
  'Averages, percentiles and counts describe the individual data-point increments in each bucket ' +
  '(non-zero ones only), not the bucket total. The header stats above stay sum-based.';

/**
 * Trend chart card for the selected metric. Renders a titled Paper with a legend
 * strip and an AreaTrendChart stacked by the active split — or a single total
 * series when no split is selected. The "Split by" and "Agg" controls live in
 * MetricFacetBar; this card only reads the resulting `split` and `aggregation`.
 *
 * With a non-sum aggregation `metric.trend` already holds the per-bucket aggregate (the
 * backend computed it), so the card draws it as one unstacked series named for the aggregate
 * — never `(stacked)`, and never split (per-group averages don't add up to anything).
 */
const MetricTrendCard = ({
  metric,
  split,
  aggregation = 'sum',
  isLoading = false,
}: MetricTrendCardProps) => {
  const isSumAggregation = aggregation === 'sum';
  const yUnit = metric.unit.replace(/[{}]/g, '');
  const aggregationLabels = isSumAggregation ? undefined : describeAggregation(aggregation, yUnit);
  // A split only applies to sums; guard here too so this card can never stack an average.
  const isSplitActive = isSumAggregation && split !== SPLIT_NONE && Boolean(metric.splits[split]);

  // x-axis: one bucket per trend point across the window, ending now.
  const axisDates = useMemo(() => {
    const numberOfPoints = metric.trend.length;
    // x-axis is anchored to current wall-clock time.
    // eslint-disable-next-line react-hooks/purity
    const end = Date.now();
    const stepMs = (24 * 60 * 60 * 1000) / Math.max(1, numberOfPoints - 1);
    return Array.from(
      { length: numberOfPoints },
      (_, index) => new Date(end - (numberOfPoints - 1 - index) * stepMs),
    );
  }, [metric]);

  // Chart series: a single line (the total, or the aggregate), or the split proportions stacked.
  const series = useMemo(() => {
    const splitRows = metric.splits[split];
    if (!isSplitActive || !splitRows) {
      return [{ label: 'total', data: metric.trend, color: colorForIndex(0) }];
    }
    return splitRows.map((row) => ({
      label: row.label,
      data: metric.trend.map((value) => (value * row.pct) / 100),
      color: colorForIndex(row.colorIndex),
    }));
  }, [metric, split, isSplitActive]);

  /**
   * A sparse whole-number counter draws as bars rather than an interpolated
   * area: a line between buckets claims a rate that rose and fell between them,
   * but a commit either happened in that hour or it didn't. This is a threshold
   * rather than a per-metric flag so an uncurated counter the backend discovers
   * gets the same treatment without a spec.
   */
  const isDiscrete = useMemo(() => isSparseCounter(metric.trend), [metric.trend]);

  // The y label is derived from the aggregation and split state, never pinned: `(stacked)` only
  // when a split is active, and a non-sum aggregate names itself instead.
  let yLabel = `${yUnit}${isSplitActive ? ' (stacked)' : ''}`;
  if (aggregationLabels) {
    yLabel = aggregationLabels.yLabel;
  }

  const legendLabelFor = (seriesLabel: string): string => {
    if (aggregationLabels) {
      return aggregationLabels.legendLabel;
    }
    return isSplitActive ? seriesLabel : `total · ${yUnit}`;
  };

  return (
    <Paper variant="outlined" sx={{ p: '20px 24px', minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 1.25 }}>
        <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>
          {metric.name.replace('claude_code.', '')} over time
          {aggregationLabels && ` (${aggregationLabels.titleSuffix})`}
        </Typography>
        {aggregationLabels && (
          <Tooltip title={NON_SUM_AGGREGATION_TOOLTIP} arrow>
            <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
          </Tooltip>
        )}
      </Box>

      <Box sx={{ display: 'flex', gap: 1.75, flexWrap: 'wrap', mb: 0.5 }}>
        {series.map((seriesItem) => (
          <Box key={seriesItem.label} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: seriesItem.color }} />
            <Typography variant="caption" color="text.secondary">
              {legendLabelFor(seriesItem.label)}
            </Typography>
          </Box>
        ))}
      </Box>

      {isLoading ? (
        <Box
          sx={{ height: 290, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
        >
          <Typography color="text.secondary">Loading…</Typography>
        </Box>
      ) : (
        <AreaTrendChart
          axisDates={axisDates}
          series={series}
          yLabel={yLabel}
          formatY={formatCompact}
          height={290}
          isDiscrete={isDiscrete}
        />
      )}
    </Paper>
  );
};

export default MetricTrendCard;
