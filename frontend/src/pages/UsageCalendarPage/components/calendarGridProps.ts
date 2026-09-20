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
import type { UsageCalendarDay } from '../usageCalendarApi';

/** What MonthCalendarGrid and WeekCalendarRow both need to render their cells. */
export interface CalendarGridProps {
  today: Date;
  daysByDateKey: Map<string, UsageCalendarDay>;
  /** Heat tint share per date key (see buildHeatPercents); a missing key renders the plain surface. */
  heatPercents: Map<string, number>;
  /** CSS color the heat tint mixes in — the "Color by" metric's palette color. */
  heatColor: string;
  /** Loading or failed: cells render nothing and stay inert rather than claiming "No activity". */
  isDayDataUnavailable: boolean;
  selectedDateKey: string | null;
  onSelectDay: (dateKey: string) => void;
}
