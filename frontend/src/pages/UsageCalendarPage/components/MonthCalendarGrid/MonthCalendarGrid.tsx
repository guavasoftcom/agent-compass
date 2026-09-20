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
import { toDateKey } from '../../usageCalendarDerivations';
import CalendarDayCell from '../CalendarDayCell';
import type { CalendarGridProps } from '../calendarGridProps';

export interface MonthCalendarGridProps extends CalendarGridProps {
  /** The 42 dates of the six-week Sunday-first grid, neighbouring months' days included. */
  gridDates: Date[];
  /** Month index (0-11) the grid is showing; days of any other month render dimmed and inert. */
  anchorMonth: number;
}

const DAY_HEADERS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const GRID_COLUMNS = 'repeat(7, minmax(0, 1fr))';

/**
 * The month view: a Sunday-first weekday header over a six-row grid of day cells. Only days of the
 * anchor month that are not in the future are interactive; the neighbours' days fill the grid dimmed.
 */
const MonthCalendarGrid = ({
  gridDates,
  anchorMonth,
  today,
  daysByDateKey,
  heatPercents,
  heatColor,
  isDayDataUnavailable,
  selectedDateKey,
  onSelectDay,
}: MonthCalendarGridProps) => {
  const todayKey = toDateKey(today);
  return (
    <Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: GRID_COLUMNS, gap: '10px', px: '2px', pb: 1.25 }}>
        {DAY_HEADERS.map((header) => (
          <Box key={header} sx={{ typography: 'eyebrowSm', color: 'text.disabled', textAlign: 'center' }}>
            {header}
          </Box>
        ))}
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: GRID_COLUMNS, gap: '10px' }}>
        {gridDates.map((date) => {
          const dateKey = toDateKey(date);
          return (
            <CalendarDayCell
              key={dateKey}
              variant="month"
              date={date}
              isInPeriod={date.getMonth() === anchorMonth}
              isFuture={dateKey > todayKey}
              isToday={dateKey === todayKey}
              day={daysByDateKey.get(dateKey)}
              heatPercent={heatPercents.get(dateKey)}
              heatColor={heatColor}
              isDayDataUnavailable={isDayDataUnavailable}
              isSelected={dateKey === selectedDateKey}
              onSelect={onSelectDay}
            />
          );
        })}
      </Box>
    </Box>
  );
};

export default MonthCalendarGrid;
