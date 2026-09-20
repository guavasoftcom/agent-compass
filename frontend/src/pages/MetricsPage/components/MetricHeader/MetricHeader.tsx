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
import { alpha, Box, Paper, Tooltip, Typography, useTheme } from '@mui/material';
import type { ReactNode } from 'react';
import { auroraColors, gradients } from '../../../../theme/colors';
import type { MetricSeries } from '../metricsSampleData';
import { fontFamilies } from '../../../../theme/typography';
import { formatCompact } from '../../../../lib/format';
import { HEALTH_LABEL, healthColor } from '../metricHealth';

export interface MetricHeaderProps {
  metric: MetricSeries;
  /** Number of series the chart currently draws — 1 with no split active, or the active split's row count. */
  seriesCount: number;
  /** Pre-pluralized unit word for the Series stat, e.g. "series" or "models". */
  seriesUnitLabel: string;
}

/** The health label's full copy reads "<short reason> · <detail>" / "<short reason> — <detail>"; the
 * compact stat only shows the part before that separator, with the full copy in a Tooltip. */
const healthShortLabel = (health: MetricSeries['health']): string =>
  HEALTH_LABEL[health].split(' · ')[0].split(' — ')[0];

const TYPE_BADGE = (type: string) => type.charAt(0).toUpperCase() + type.slice(1);

const ArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={13} height={13}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);
const ArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={13} height={13}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

const Stat = ({ label, children, mid = false }: { label: string; children: ReactNode; mid?: boolean }) => (
  <Box>
    <Box sx={{ fontSize: 11, fontWeight: 600, letterSpacing: 0.5, textTransform: 'uppercase', color: 'text.disabled' }}>
      {label}
    </Box>
    <Box
      sx={{
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: mid ? 20 : 30,
        letterSpacing: '-0.8px',
        mt: mid ? '11px' : '6px',
        color: 'text.primary',
      }}
    >
      {children}
    </Box>
  </Box>
);

/**
 * Detail-pane header for the selected metric: fully-qualified name, type + unit
 * badges, a one-line description, and the headline stats (sum, rate, peak, delta).
 */
const MetricHeader = ({ metric, seriesCount, seriesUnitLabel }: MetricHeaderProps) => {
  const theme = useTheme();

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.375, flexWrap: 'wrap' }}>
        <Typography sx={{ fontFamily: fontFamilies.body, fontSize: 19, fontWeight: 700, letterSpacing: '-0.3px' }}>
          {metric.name}
        </Typography>
        <Box
          sx={(t) => ({
            typography: 'eyebrowSm',
            px: 0.75,
            py: '2px',
            borderRadius: '5px',
            color: t.palette.info?.main ?? t.palette.primary.main,
            bgcolor: alpha(t.palette.info?.main ?? auroraColors.cyan, 0.18),
          })}
        >
          {TYPE_BADGE(metric.type)}
        </Box>
        <Box
          sx={{
            typography: 'eyebrowSm',
            px: 0.75,
            py: '2px',
            borderRadius: '5px',
            color: 'text.secondary',
            bgcolor: 'action.hover',
          }}
        >
          {metric.unit || '—'}
        </Box>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mt: 1.25, maxWidth: '72ch', lineHeight: 1.5 }}>
        {metric.description}
      </Typography>

      <Box sx={{ display: 'flex', gap: { xs: 3, sm: 4.25 }, flexWrap: 'wrap', mt: 2.25 }}>
        <Stat label={metric.sumLabel}>
          <Box
            component="span"
            sx={{
              backgroundImage: gradients.auroraActionSoft,
              WebkitBackgroundClip: 'text',
              backgroundClip: 'text',
              color: 'transparent',
            }}
          >
            {metric.sum}
          </Box>
        </Stat>
        <Stat label="Rate" mid>
          {metric.rate}
          <Box component="span" sx={{ fontSize: 14, color: 'text.secondary', fontWeight: 600 }}>{metric.rateUnit}</Box>
        </Stat>
        <Stat label="Peak / h" mid>{metric.peak}</Stat>
        <Stat label="Series" mid>
          {seriesCount} <Box component="span" sx={{ fontSize: 14, color: 'text.secondary', fontWeight: 600 }}>{seriesUnitLabel}</Box>
        </Stat>
        <Stat label="Cardinality" mid>{formatCompact(metric.cardinality)}</Stat>
        <Stat label="Health" mid>
          <Tooltip title={HEALTH_LABEL[metric.health]} arrow>
            <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1, cursor: 'help' }}>
              <Box
                sx={{
                  width: 9,
                  height: 9,
                  borderRadius: '50%',
                  bgcolor: healthColor(metric.health, theme),
                  flexShrink: 0,
                }}
              />
              <Box component="span" sx={{ fontSize: 16 }}>{healthShortLabel(metric.health)}</Box>
            </Box>
          </Tooltip>
        </Stat>
        <Stat label="vs. prev 24h" mid>
          <Box
            component="span"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontSize: 18, color: 'text.secondary', fontWeight: 600 }}
          >
            <Box component="span" sx={{ color: 'text.primary', fontWeight: 700 }}>{metric.delta}</Box>
            {metric.dir === 'down' ? <ArrowDown /> : <ArrowUp />}
          </Box>
        </Stat>
      </Box>
    </Paper>
  );
};

export default MetricHeader;
