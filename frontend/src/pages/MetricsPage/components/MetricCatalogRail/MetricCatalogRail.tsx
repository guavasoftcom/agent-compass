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
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { alpha, Box, Paper, Typography, useTheme } from '@mui/material';
import LineSparkline from '../../../../components/LineSparkline';
import SearchInput from '../../../../components/SearchInput';
import { fontFamilies } from '../../../../theme/typography';
import { formatCompact } from '../../../../lib/format';
import type { MetricSeries } from '../metricsSampleData';
import { HEALTH_LABEL, healthColor } from '../metricHealth';
import { metricTypeColor } from '../metricTypeColor';

export interface MetricCatalogRailProps {
  metrics: MetricSeries[];
  selectedId: string;
  onSelect: (id: string) => void;
  search: string;
  onSearchChange: (next: string) => void;
}

const CLAUDE_CODE_PREFIX = 'claude_code.';

/**
 * Every metric currently lands in one group. Kept as a function of the metric
 * (rather than a bare constant) so a second namespace later only changes this
 * lookup, not the grouping/rendering below.
 */
// The parameter is the extension point, unused until a second namespace exists.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const groupLabelOf = (_metric: MetricSeries): string => 'Claude Code';

// Discovered metrics may not carry the claude_code. prefix, so strip it only when present.
const displayNameOf = (metric: MetricSeries): string =>
  metric.name.startsWith(CLAUDE_CODE_PREFIX) ? metric.name.slice(CLAUDE_CODE_PREFIX.length) : metric.name;

interface CatalogRowProps {
  metric: MetricSeries;
  isSelected: boolean;
  onSelect: (id: string) => void;
}

const CatalogRow = ({ metric, isSelected, onSelect }: CatalogRowProps) => {
  const theme = useTheme();
  const typeColor = metricTypeColor(metric.type, theme);
  const statusColor = healthColor(metric.health, theme);
  const isCardinalityFlagged = metric.health !== 'ok';

  return (
    <Box
      role="button"
      tabIndex={0}
      aria-pressed={isSelected}
      onClick={() => onSelect(metric.id)}
      onKeyDown={(event: ReactKeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect(metric.id);
        }
      }}
      sx={{
        p: '9px 10px',
        borderRadius: '11px',
        cursor: 'pointer',
        border: 1,
        transition: 'background-color .13s, border-color .13s, box-shadow .13s',
        borderColor: isSelected ? 'primary.main' : 'transparent',
        boxShadow: isSelected ? `0 0 0 1px ${theme.palette.primary.main}` : 'none',
        bgcolor: isSelected ? 'action.selected' : 'transparent',
        '&:hover': {
          bgcolor: isSelected ? 'action.selected' : 'action.hover',
        },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '7px' }}>
        <Box
          component="span"
          sx={{
            flexShrink: 0,
            fontFamily: fontFamilies.display,
            fontSize: 9.5,
            fontWeight: 700,
            letterSpacing: '0.4px',
            textTransform: 'uppercase',
            px: '6px',
            py: '2px',
            borderRadius: '5px',
            color: typeColor,
            bgcolor: alpha(typeColor, 0.18),
          }}
        >
          {metric.type.slice(0, 4)}
        </Box>
        <Box
          component="span"
          sx={{
            minWidth: 0,
            fontFamily: fontFamilies.body,
            fontSize: 13,
            fontWeight: 600,
            letterSpacing: '-0.1px',
            color: 'text.primary',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {displayNameOf(metric)}
        </Box>
        <Box
          component="span"
          role="img"
          aria-label={HEALTH_LABEL[metric.health]}
          title={HEALTH_LABEL[metric.health]}
          sx={{
            ml: 'auto',
            width: 8,
            height: 8,
            borderRadius: '50%',
            flexShrink: 0,
            bgcolor: statusColor,
            boxShadow: `0 0 0 3px ${alpha(statusColor, 0.16)}`,
          }}
        />
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: '7px' }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <LineSparkline values={metric.trend} height={22} />
        </Box>
        <Box
          component="span"
          title="active series · cardinality"
          sx={{
            fontSize: 11,
            fontWeight: 600,
            fontVariantNumeric: 'tabular-nums',
            whiteSpace: 'nowrap',
            color: isCardinalityFlagged ? statusColor : 'text.disabled',
          }}
        >
          {`${formatCompact(metric.cardinality)} ser`}
        </Box>
      </Box>
    </Box>
  );
};

/**
 * Left-hand metric picker: a search box over a scrolling, grouped list of every
 * metric the series endpoint returned. Nothing here assumes a fixed metric count —
 * curated and discovered metrics render as the same row, and the list scrolls
 * instead of growing the page.
 */
const MetricCatalogRail = ({
  metrics,
  selectedId,
  onSelect,
  search,
  onSearchChange,
}: MetricCatalogRailProps) => {
  const groups = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const visibleMetrics = needle
      ? metrics.filter((metric) => metric.name.toLowerCase().includes(needle))
      : metrics;
    const metricsByGroupLabel = new Map<string, MetricSeries[]>();
    for (const metric of visibleMetrics) {
      const groupLabel = groupLabelOf(metric);
      const groupMetrics = metricsByGroupLabel.get(groupLabel);
      if (groupMetrics) {
        groupMetrics.push(metric);
      } else {
        metricsByGroupLabel.set(groupLabel, [metric]);
      }
    }
    return Array.from(metricsByGroupLabel, ([label, groupMetrics]) => ({
      label,
      metrics: groupMetrics,
    }));
  }, [metrics, search]);

  return (
    <Paper
      variant="outlined"
      sx={{ p: 1.75, display: 'flex', flexDirection: 'column', maxHeight: 780, minWidth: 0 }}
    >
      <SearchInput
        value={search}
        onChange={onSearchChange}
        placeholder="Search metrics…"
        sx={{ mb: 0.75 }}
      />
      <Box sx={{ overflow: 'auto', minHeight: 0, mx: '-6px', px: '6px' }}>
        {groups.length === 0 && (
          <Typography variant="caption" color="text.disabled" sx={{ display: 'block', px: 1, py: 1.5 }}>
            {search.trim() ? 'No metrics match' : 'No metrics'}
          </Typography>
        )}
        {groups.map((group) => (
          <Box key={group.label} sx={{ mt: 1.5 }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                px: 1,
                py: '6px',
                fontFamily: fontFamilies.display,
                fontSize: 10.5,
                fontWeight: 700,
                letterSpacing: '1.1px',
                textTransform: 'uppercase',
                color: 'text.disabled',
              }}
            >
              {group.label}
              <Box
                component="span"
                sx={{
                  ml: 'auto',
                  px: '7px',
                  py: '1px',
                  borderRadius: '6px',
                  fontSize: 10,
                  letterSpacing: '0.5px',
                  color: 'text.secondary',
                  bgcolor: 'action.hover',
                }}
              >
                {group.metrics.length}
              </Box>
            </Box>
            <Box sx={{ display: 'flex', flexDirection: 'column', gap: '2px' }}>
              {group.metrics.map((metric) => (
                <CatalogRow
                  key={metric.id}
                  metric={metric}
                  isSelected={metric.id === selectedId}
                  onSelect={onSelect}
                />
              ))}
            </Box>
          </Box>
        ))}
      </Box>
    </Paper>
  );
};

export default MetricCatalogRail;
