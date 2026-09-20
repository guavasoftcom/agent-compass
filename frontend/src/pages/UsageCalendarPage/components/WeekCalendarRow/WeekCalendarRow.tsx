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

export interface WeekCalendarRowProps extends CalendarGridProps {
  /** The seven dates of the Sunday-to-Saturday week. */
  weekDates: Date[];
}

/**
 * The week view: one row of seven tall day cells. Unlike the month grid every date is in the
 * period, so only future days are inert; each cell also carries sessions and active time, a
 * spend-by-model bar and commit / pull-request badges, which the month cell has no room for.
 */
const WeekCalendarRow = ({
  weekDates,
  today,
  daysByDateKey,
  heatPercents,
  heatColor,
  isDayDataUnavailable,
  selectedDateKey,
  onSelectDay,
}: WeekCalendarRowProps) => {
  const todayKey = toDateKey(today);
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: '14px' }}>
      {weekDates.map((date) => {
        const dateKey = toDateKey(date);
        return (
          <CalendarDayCell
            key={dateKey}
            variant="week"
            date={date}
            isInPeriod
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
  );
};

export default WeekCalendarRow;
