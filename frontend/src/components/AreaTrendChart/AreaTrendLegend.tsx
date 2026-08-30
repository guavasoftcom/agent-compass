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
import { Box } from '@mui/material';
import type { SeriesVisibility } from './useSeriesVisibility';

export interface AreaTrendLegendItem {
  label: string;
  color: string;
}

export interface AreaTrendLegendProps {
  items: AreaTrendLegendItem[];
  visibility: SeriesVisibility;
}

/**
 * Interactive legend for AreaTrendChart: hover a chip to spotlight that series
 * (others dim in the chart), click to toggle it off/on. Mirrors the design
 * preview's top-right legend.
 */
const AreaTrendLegend = ({ items, visibility }: AreaTrendLegendProps) => {
  const { active, toggle, setFocused } = visibility;
  return (
    <Box
      sx={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: '5px 8px',
        justifyContent: 'flex-end',
      }}
    >
      {items.map((item, index) => {
        const isOff = !active[index];
        return (
          <Box
            key={item.label}
            role="button"
            tabIndex={0}
            onClick={() => toggle(index)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                toggle(index);
              }
            }}
            onMouseEnter={() => setFocused(item.label)}
            onMouseLeave={() => setFocused(null)}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 0.75,
              fontSize: 12,
              lineHeight: 1.4,
              color: 'text.primary',
              cursor: 'pointer',
              userSelect: 'none',
              px: 1.125,
              py: 0.375,
              borderRadius: '8px',
              border: '1px solid transparent',
              opacity: isOff ? 0.42 : 1,
              transition: 'background .12s, border-color .12s, opacity .12s',
              '&:hover': { bgcolor: 'action.hover', borderColor: 'divider' },
              '&:focus-visible': {
                outline: '2px solid',
                outlineColor: 'primary.main',
                outlineOffset: 2,
              },
            }}
          >
            <Box
              sx={{
                width: 10,
                height: 10,
                borderRadius: '3px',
                flexShrink: 0,
                bgcolor: isOff ? 'text.disabled' : item.color,
              }}
            />
            {item.label}
          </Box>
        );
      })}
    </Box>
  );
};

export default AreaTrendLegend;
