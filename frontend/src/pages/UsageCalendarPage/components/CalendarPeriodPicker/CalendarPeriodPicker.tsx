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
import { Box, type SxProps, type Theme } from '@mui/material';
import CalendarMonthOutlinedIcon from '@mui/icons-material/CalendarMonthOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { radii } from '../../../../theme/theme';
import type { CalendarView } from '../../usageCalendarDerivations';

export interface CalendarPeriodPickerProps {
  view: CalendarView;
  /** The visible period, e.g. "September 2026" or "Sep 14 – 20, 2026". */
  label: string;
  onPrevious: () => void;
  onNext: () => void;
}

const BUTTON_SIZE = 40;

const stepButtonSx: SxProps<Theme> = {
  display: 'grid',
  placeItems: 'center',
  width: BUTTON_SIZE,
  height: BUTTON_SIZE,
  flexShrink: 0,
  p: 0,
  border: 1,
  borderColor: 'divider',
  borderRadius: radii.sm,
  bgcolor: 'background.paper',
  color: 'text.secondary',
  cursor: 'pointer',
  '&:hover': { color: 'primary.main' },
  '&:focus-visible': { outline: (t) => `2px solid ${t.palette.primary.main}`, outlineOffset: 2 },
};

/**
 * Previous / period label / next. Replaces the usual WindowSelector on this page: a day-granularity
 * calendar has no use for a preset-or-custom time-window picker — the period *is* the window.
 */
const CalendarPeriodPicker = ({ view, label, onPrevious, onNext }: CalendarPeriodPickerProps) => {
  const noun = view === 'month' ? 'month' : 'week';
  return (
    <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 1.25 }}>
      <Box component="button" type="button" aria-label={`Previous ${noun}`} onClick={onPrevious} sx={stepButtonSx}>
        <ChevronLeftIcon sx={{ fontSize: 20 }} />
      </Box>
      <Box
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 1.1,
          height: BUTTON_SIZE,
          px: 1.75,
          border: 1,
          borderColor: 'divider',
          borderRadius: radii.sm,
          bgcolor: 'background.paper',
          color: 'text.primary',
          fontSize: 13.5,
          fontWeight: 600,
          whiteSpace: 'nowrap',
        }}
      >
        <CalendarMonthOutlinedIcon sx={{ fontSize: 16, color: 'primary.main' }} />
        <span aria-live="polite">{label}</span>
      </Box>
      <Box component="button" type="button" aria-label={`Next ${noun}`} onClick={onNext} sx={stepButtonSx}>
        <ChevronRightIcon sx={{ fontSize: 20 }} />
      </Box>
    </Box>
  );
};

export default CalendarPeriodPicker;
