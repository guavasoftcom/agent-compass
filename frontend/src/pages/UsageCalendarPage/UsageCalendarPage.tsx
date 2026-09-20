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
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  fetchSkillUsage,
  fetchSubagentUsage,
  fetchTokenUsage,
  type WindowSelection,
} from '../../api';
import { MS_PER_MINUTE } from '../../lib/constants';
import { buildWindowSelectionKey } from '../../lib/queryKeys';
import { useNowTick } from '../../lib/useNowTick';
import { useWindowContext } from '../../lib/windowContext';
import { fetchUsageCalendarDaily } from './usageCalendarApi';
import {
  buildHeatPercents,
  buildKpiCards,
  buildModelRows,
  endOfLocalDay,
  formatPeriodLabel,
  fromDateKey,
  indexDaysByDateKey,
  monthGridDates,
  periodRange,
  previousPeriodRange,
  shiftAnchor,
  startOfLocalDay,
  toDateKey,
  type CalendarView,
  type ColorByMetric,
} from './usageCalendarDerivations';
import UsageCalendarPageView from './UsageCalendarPageView';

// The rollup's own clock: how often "today" is re-read so a page left open across midnight moves its
// today marker and stops treating the new day as a future one.
const TODAY_TICK_INTERVAL_MS = MS_PER_MINUTE;

export default function UsageCalendarPage() {
  const { setSelection, repositoryUrl, setRepositoryUrl } = useWindowContext();
  const navigate = useNavigate();

  const [view, setView] = useState<CalendarView>('month');
  const [anchor, setAnchor] = useState<Date>(() => new Date());
  const [colorBy, setColorBy] = useState<ColorByMetric>('cost');
  // The drawer's selection outlives its open flag: closing only clears `isDrawerOpen`, so the
  // day's queries keep their keys and the content stays put through the slide-out.
  const [selectedDateKey, setSelectedDateKey] = useState<string | null>(null);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);

  const nowMs = useNowTick(TODAY_TICK_INTERVAL_MS);
  const todayKey = toDateKey(new Date(nowMs));
  const today = useMemo(() => fromDateKey(todayKey), [todayKey]);

  // The zone that defines "a day": the browser's own, sent to the backend so its bucketing agrees
  // with the local midnights this page computes the range from.
  const timeZone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone, []);

  const range = useMemo(() => periodRange(view, anchor), [view, anchor]);
  const priorRange = useMemo(() => previousPeriodRange(view, anchor), [view, anchor]);

  const dailyQueryFor = (periodRangeToFetch: typeof range, granularity: 'daily' | 'hourly') => ({
    queryKey: [
      'usage-calendar-daily',
      periodRangeToFetch.start.toISOString(),
      periodRangeToFetch.endExclusive.toISOString(),
      timeZone,
      repositoryUrl ?? 'all',
      granularity,
    ],
    queryFn: () =>
      fetchUsageCalendarDaily({
        from: periodRangeToFetch.start.toISOString(),
        to: periodRangeToFetch.endExclusive.toISOString(),
        timeZone,
        repositoryUrl,
        granularity,
      }),
  });

  // Only the week view draws hourly sparklines, so only it pays for the hourly buckets (and only over
  // its own seven days). Granularity is in the key, so flipping Month <-> Week never serves one
  // view's cached rows to the other.
  const currentQuery = useQuery(dailyQueryFor(range, view === 'week' ? 'hourly' : 'daily'));
  // Only the KPI deltas read the prior period, and they need day totals alone; a failure there drops
  // the deltas, not the page.
  const priorQuery = useQuery(dailyQueryFor(priorRange, 'daily'));

  const selectedDate = useMemo(
    () => (selectedDateKey ? fromDateKey(selectedDateKey) : null),
    [selectedDateKey],
  );
  // One local day as the inclusive custom window the existing per-window endpoints take — the same
  // 00:00:00.000 .. 23:59:59.999 shape WindowSelector emits for a whole-day range.
  const daySelection = useMemo<WindowSelection | null>(
    () =>
      selectedDate
        ? {
            kind: 'custom',
            startTimestamp: startOfLocalDay(selectedDate).toISOString(),
            endTimestamp: endOfLocalDay(selectedDate).toISOString(),
            repositoryUrl,
          }
        : null,
    [selectedDate, repositoryUrl],
  );
  const daySelectionKey = daySelection ? buildWindowSelectionKey(daySelection, repositoryUrl) : null;

  const skillUsageQuery = useQuery({
    queryKey: ['usage-calendar-day-skills', daySelectionKey],
    queryFn: () => fetchSkillUsage(daySelection as WindowSelection),
    enabled: daySelection != null,
  });
  const subagentUsageQuery = useQuery({
    queryKey: ['usage-calendar-day-subagents', daySelectionKey],
    queryFn: () => fetchSubagentUsage(daySelection as WindowSelection),
    enabled: daySelection != null,
  });
  const tokenUsageQuery = useQuery({
    queryKey: ['usage-calendar-day-models', daySelectionKey],
    queryFn: () => fetchTokenUsage(daySelection as WindowSelection),
    enabled: daySelection != null,
  });

  const daysByDateKey = useMemo(() => indexDaysByDateKey(currentQuery.data ?? []), [currentQuery.data]);
  const priorDaysByDateKey = useMemo(() => indexDaysByDateKey(priorQuery.data ?? []), [priorQuery.data]);

  const heatPercents = useMemo(
    () => buildHeatPercents(range.days, daysByDateKey, colorBy, today),
    [range, daysByDateKey, colorBy, today],
  );
  const kpiCards = useMemo(
    () => buildKpiCards(view, range.days, priorRange.days, daysByDateKey, priorDaysByDateKey, today),
    [view, range, priorRange, daysByDateKey, priorDaysByDateKey, today],
  );
  const gridDates = useMemo(
    () => (view === 'month' ? monthGridDates(anchor) : range.days),
    [view, anchor, range],
  );
  const modelRows = useMemo(() => buildModelRows(tokenUsageQuery.data), [tokenUsageQuery.data]);

  const handleSelectDay = (dateKey: string): void => {
    setSelectedDateKey(dateKey);
    setIsDrawerOpen(true);
  };

  // The Metrics page reads the global window, so the hand-off is: set it to this day, then navigate.
  // `repositoryUrl` is deliberately left off the selection — it lives beside it in the window
  // context and is already what the calendar was filtered by.
  const handleOpenInMetrics = (): void => {
    if (selectedDate == null) {
      return;
    }
    setSelection({
      kind: 'custom',
      startTimestamp: startOfLocalDay(selectedDate).toISOString(),
      endTimestamp: endOfLocalDay(selectedDate).toISOString(),
    });
    navigate('/metrics');
  };

  const handleReload = (): void => {
    currentQuery.refetch();
    priorQuery.refetch();
    if (daySelection != null) {
      skillUsageQuery.refetch();
      subagentUsageQuery.refetch();
      tokenUsageQuery.refetch();
    }
  };

  return (
    <UsageCalendarPageView
      view={view}
      onViewChange={setView}
      periodLabel={formatPeriodLabel(view, anchor)}
      onPreviousPeriod={() => setAnchor(shiftAnchor(view, anchor, -1))}
      onNextPeriod={() => setAnchor(shiftAnchor(view, anchor, 1))}
      colorBy={colorBy}
      onColorByChange={setColorBy}
      repositoryUrl={repositoryUrl}
      onRepositoryUrlChange={setRepositoryUrl}
      onReload={handleReload}
      today={today}
      gridDates={gridDates}
      anchorMonth={anchor.getMonth()}
      daysByDateKey={daysByDateKey}
      heatPercents={heatPercents}
      kpiCards={kpiCards}
      isLoading={currentQuery.isLoading}
      error={currentQuery.error as Error | null}
      selectedDateKey={selectedDateKey}
      isDrawerOpen={isDrawerOpen}
      onSelectDay={handleSelectDay}
      onCloseDrawer={() => setIsDrawerOpen(false)}
      onOpenInMetrics={handleOpenInMetrics}
      isDetailLoading={
        skillUsageQuery.isLoading || subagentUsageQuery.isLoading || tokenUsageQuery.isLoading
      }
      modelRows={modelRows}
      skillUsage={skillUsageQuery.data ?? []}
      subagentUsage={subagentUsageQuery.data ?? []}
    />
  );
}
