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
import SegmentedToggle from '../../../../components/SegmentedToggle';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { formatCompact, isSparseCounter } from '../../../../lib/format';
import type { MetricSeries } from '../metricsSampleData';

export interface MetricTrendCardProps {
  metric: MetricSeries;
  split: string;
  splitKeys: string[];
  onSplitChange: (next: string) => void;
  isLoading?: boolean;
}

const SPLIT_NONE = 'None';

/**
 * Trend chart card for the selected metric. Renders a titled Paper with an
 * optional "Split by" SegmentedToggle (visible only when the metric has attribute
 * splits), a legend strip, and an AreaTrendChart stacked by the active split —
 * or a single total series when no split is selected.
 */
const MetricTrendCard = ({
  metric,
  split,
  splitKeys,
  onSplitChange,
  isLoading = false,
}: MetricTrendCardProps) => {
  const hasSplits = splitKeys.length > 1;

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

  // Chart series: a single total line, or the split proportions stacked.
  const series = useMemo(() => {
    const splitRows = metric.splits[split];
    if (split === SPLIT_NONE || !splitRows) {
      return [{ label: 'total', data: metric.trend, color: colorForIndex(0) }];
    }
    return splitRows.map((row) => ({
      label: row.label,
      data: metric.trend.map((value) => (value * row.pct) / 100),
      color: colorForIndex(row.colorIndex),
    }));
  }, [metric, split]);

  /**
   * A sparse whole-number counter draws as bars rather than an interpolated
   * area: a line between buckets claims a rate that rose and fell between them,
   * but a commit either happened in that hour or it didn't. This is a threshold
   * rather than a per-metric flag so an uncurated counter the backend discovers
   * gets the same treatment without a spec.
   */
  const isDiscrete = useMemo(() => isSparseCounter(metric.trend), [metric.trend]);

  const yUnit = metric.unit.replace(/[{}]/g, '');
  const yLabel = `${yUnit}${split === SPLIT_NONE ? '' : ' (stacked)'}`;

  return (
    <Paper variant="outlined" sx={{ p: '20px 24px', minWidth: 0 }}>
      <Box
        sx={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: 1.5,
          mb: 1.25,
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
          <Typography sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}>
            {metric.name.replace('claude_code.', '')} over time
          </Typography>
          {hasSplits && (
            <Tooltip
              title="With **Split by** set, the areas stack — the top edge is the metric's total and each band's thickness is that slice's own value. With **None** the chart is a single total series; the y-axis label says which you're looking at."
              arrow
            >
              <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
            </Tooltip>
          )}
        </Box>
        {hasSplits && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.125 }}>
            <Box
              sx={{
                typography: 'eyebrowSm',
                color: 'text.disabled',
              }}
            >
              Split by
            </Box>
            <SegmentedToggle
              options={splitKeys.map((key) => ({ value: key, label: key }))}
              value={split}
              onChange={onSplitChange}
            />
          </Box>
        )}
      </Box>

      <Box sx={{ display: 'flex', gap: 1.75, flexWrap: 'wrap', mb: 0.5 }}>
        {series.map((seriesItem) => (
          <Box key={seriesItem.label} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            <Box sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: seriesItem.color }} />
            <Typography variant="caption" color="text.secondary">
              {split === SPLIT_NONE
                ? `total · ${metric.unit.replace(/[{}]/g, '')}`
                : seriesItem.label}
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
