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
import { Box, Typography, useTheme } from '@mui/material';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import type { PaperProps } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { auroraColors } from '../../../../theme/colors';
import type { MetricSeries } from '../metricsSampleData';
import StatCard from '../../../../components/StatCard/StatCard';
import LineSparkline from '../../../../components/LineSparkline';

export interface MetricKpiStripProps {
  metrics: MetricSeries[];
  selectedId: string;
  onSelect: (id: string) => void;
}

const typeColor = (type: MetricSeries['type'], t: Theme): string => {
  if (type === 'gauge') {
    return t.palette.primary.main;
  }
  if (type === 'histogram') {
    return t.palette.secondary?.main ?? auroraColors.pink;
  }
  return t.palette.info?.main ?? auroraColors.cyan;
};

// The design's own breakpoints, which don't line up with MUI's sm/md/lg keys
// (620 sits inside sm, 1080 between md and lg), so they're written as raw
// queries rather than snapped to the nearest key.
const TWO_COLUMNS_ABOVE = '@media (min-width:621px)';
const THREE_COLUMNS_ABOVE = '@media (min-width:901px)';
const FOUR_COLUMNS_ABOVE = '@media (min-width:1081px)';

const TrendArrowUp = () => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={3}
    width={11}
    height={11}
  >
    <path d="M7 14l5-5 5 5" />
  </svg>
);

const TrendArrowDown = () => (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={3}
    width={11}
    height={11}
  >
    <path d="M7 10l5 5 5-5" />
  </svg>
);

/**
 * Metric picker as a KPI strip (replaces the dropdown/rail): one uniform grid,
 * every metric the same full card with a sparkline. The layout doesn't encode
 * importance, so a metric the series endpoint discovers needs no allow-list
 * entry and no layout decision — it lands in the next cell with a trend like
 * everything else.
 */
const MetricKpiStrip = ({
  metrics,
  selectedId,
  onSelect,
}: MetricKpiStripProps) => {
  const theme = useTheme();

  const selectableCardSx = (isSelected: boolean) => ({
    minWidth: 0,
    height: '100%',
    cursor: 'pointer',
    transition: 'transform .14s, border-color .14s, box-shadow .14s',
    borderColor: isSelected ? 'primary.main' : 'divider',
    boxShadow: isSelected ? `0 0 0 1px ${theme.palette.primary.main}` : 'none',
    bgcolor: isSelected ? 'action.selected' : 'background.paper',
    '&:hover': { borderColor: 'primary.main', transform: 'translateY(-1px)' },
  });

  const selectionProps = (metricId: string): Partial<PaperProps> => ({
    role: 'button',
    tabIndex: 0,
    onClick: () => onSelect(metricId),
    onKeyDown: (event: ReactKeyboardEvent<HTMLDivElement>) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        onSelect(metricId);
      }
    },
  });

  const deltaChip = (metric: MetricSeries) => {
    const isTrendingUp = metric.dir !== 'down';
    return (
      <Box
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '2px',
          fontSize: 11,
          fontWeight: 700,
          fontVariantNumeric: 'tabular-nums',
          flexShrink: 0,
          color: isTrendingUp ? 'success.main' : 'error.main',
        }}
      >
        {metric.delta}
        {isTrendingUp ? <TrendArrowUp /> : <TrendArrowDown />}
      </Box>
    );
  };

  // Unit-less metrics (e.g. pull_request.count) skip this element entirely
  // rather than rendering an empty Box — an empty node still contributes ml: 2px.
  const unitSuffix = (metric: MetricSeries) => {
    if (!metric.unit) {
      return null;
    }
    return (
      <Box
        component="span"
        sx={{
          fontSize: 11,
          color: 'text.disabled',
          fontWeight: 600,
          ml: '2px',
        }}
      >
        {metric.unit.replace(/[{}]/g, '')}
      </Box>
    );
  };

  const typeDot = (metric: MetricSeries, diameter: number) => (
    <Box
      sx={{
        width: diameter,
        height: diameter,
        borderRadius: '50%',
        bgcolor: typeColor(metric.type, theme),
      }}
    />
  );

  const primaryCard = (metric: MetricSeries) => (
    <StatCard
      key={metric.id}
      label={metric.name.replace('claude_code.', '')}
      value={
        <>
          {metric.sum}
          {unitSuffix(metric)}
        </>
      }
      displayFont
      displayFontSize={23}
      labelUppercase={false}
      adornment={typeDot(metric, 7)}
      PaperProps={{
        ...selectionProps(metric.id),
        sx: { p: '13px 14px', ...selectableCardSx(metric.id === selectedId) },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'space-between',
          gap: 1,
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <LineSparkline values={metric.trend} height={24} />
        </Box>
        {deltaChip(metric)}
      </Box>
    </StatCard>
  );

  return (
    <Box>
      <Typography
        sx={{
          typography: 'eyebrowSm',
          color: 'text.disabled',
          mb: 1.25,
        }}
      >
        Metrics · claude_code
      </Typography>

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: '1fr',
          gap: 1.5,
          mb: 2.5,
          [TWO_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(2, 1fr)' },
          [THREE_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(3, 1fr)' },
          [FOUR_COLUMNS_ABOVE]: { gridTemplateColumns: 'repeat(4, 1fr)' },
        }}
      >
        {metrics.map((metric) => primaryCard(metric))}
      </Box>
    </Box>
  );
};

export default MetricKpiStrip;
