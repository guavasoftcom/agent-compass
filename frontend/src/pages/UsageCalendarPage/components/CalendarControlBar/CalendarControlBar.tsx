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
import { useState, type MouseEvent } from 'react';
import { Box, Menu, MenuItem, Paper } from '@mui/material';
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown';
import SegmentedToggle from '../../../../components/SegmentedToggle';
import { colorForIndex, radii } from '../../../../theme/theme';
import {
  COLOR_BY_OPTIONS,
  type CalendarView,
  type ColorByMetric,
} from '../../usageCalendarDerivations';

export interface CalendarControlBarProps {
  view: CalendarView;
  onViewChange: (next: CalendarView) => void;
  colorBy: ColorByMetric;
  onColorByChange: (next: ColorByMetric) => void;
}

const VIEW_OPTIONS: { value: CalendarView; label: string }[] = [
  { value: 'month', label: 'Month' },
  { value: 'week', label: 'Week' },
];

const Swatch = ({ colorIndex }: { colorIndex: number }) => (
  <Box
    component="i"
    sx={{ width: 8, height: 8, borderRadius: '3px', flexShrink: 0, bgcolor: colorForIndex(colorIndex) }}
  />
);

/**
 * Month/Week toggle on the left; on the right a Low-to-High heat legend and the "Color by" picker
 * that decides which metric tints the calendar cells.
 */
const CalendarControlBar = ({ view, onViewChange, colorBy, onColorByChange }: CalendarControlBarProps) => {
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const selected = COLOR_BY_OPTIONS.find((option) => option.value === colorBy) ?? COLOR_BY_OPTIONS[0];
  const selectedColor = colorForIndex(selected.colorIndex);

  return (
    <Paper
      variant="outlined"
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        flexWrap: 'wrap',
        gap: 2,
        px: 1.75,
        py: 1.5,
      }}
    >
      <SegmentedToggle options={VIEW_OPTIONS} value={view} onChange={onViewChange} />
      <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 1.75 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.9, fontSize: 11.5, color: 'text.secondary' }}>
          <span>Low</span>
          <Box
            aria-hidden
            sx={(theme) => ({
              width: 64,
              height: 8,
              borderRadius: '4px',
              border: 1,
              borderColor: 'divider',
              backgroundImage: `linear-gradient(90deg, ${theme.palette.background.paper}, ${selectedColor})`,
            })}
          />
          <span>High</span>
        </Box>
        <Box
          component="button"
          type="button"
          aria-haspopup="listbox"
          aria-expanded={menuAnchor != null}
          onClick={(event: MouseEvent<HTMLElement>) => setMenuAnchor(event.currentTarget)}
          sx={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 1,
            height: 38,
            px: 1.5,
            border: 1,
            borderColor: 'divider',
            borderRadius: radii.sm,
            bgcolor: 'background.paper',
            color: 'text.primary',
            fontFamily: 'inherit',
            fontSize: 13,
            cursor: 'pointer',
          }}
        >
          <Box component="span" sx={{ color: 'text.secondary', fontSize: 12 }}>
            Color by
          </Box>
          <Box component="b" sx={{ color: 'primary.main', fontWeight: 600 }}>
            {selected.label}
          </Box>
          <ArrowDropDownIcon sx={{ fontSize: 18, color: 'text.disabled', mx: -0.5 }} />
        </Box>
        <Menu anchorEl={menuAnchor} open={menuAnchor != null} onClose={() => setMenuAnchor(null)}>
          {COLOR_BY_OPTIONS.map((option) => (
            <MenuItem
              key={option.value}
              selected={option.value === colorBy}
              onClick={() => {
                onColorByChange(option.value);
                setMenuAnchor(null);
              }}
              sx={{ gap: 1, fontSize: 13, minWidth: 150 }}
            >
              <Swatch colorIndex={option.colorIndex} />
              {option.label}
            </MenuItem>
          ))}
        </Menu>
      </Box>
    </Paper>
  );
};

export default CalendarControlBar;
