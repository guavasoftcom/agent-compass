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
import { Box, Paper, Stack } from '@mui/material';
import type { IdentifierUsageRow } from '../../api';
import type { BreakdownRow } from '../../components/BreakdownList';
import PageActionsView from '../../components/PageActions/PageActionsView';
import PageLayout from '../../components/PageLayout';
import RepositorySelector from '../../components/RepositorySelector';
import { colorForIndex } from '../../theme/theme';
import CalendarControlBar from './components/CalendarControlBar';
import CalendarPeriodPicker from './components/CalendarPeriodPicker';
import MonthCalendarGrid from './components/MonthCalendarGrid';
import UsageDayDrawer from './components/UsageDayDrawer';
import UsageKpiStrip from './components/UsageKpiStrip';
import WeekCalendarRow from './components/WeekCalendarRow';
import type { UsageCalendarDay } from './usageCalendarApi';
import {
  COLOR_BY_OPTIONS,
  fromDateKey,
  type CalendarView,
  type ColorByMetric,
  type KpiCardModel,
} from './usageCalendarDerivations';

export interface UsageCalendarPageViewProps {
  view: CalendarView;
  onViewChange: (next: CalendarView) => void;
  periodLabel: string;
  onPreviousPeriod: () => void;
  onNextPeriod: () => void;
  colorBy: ColorByMetric;
  onColorByChange: (next: ColorByMetric) => void;
  repositoryUrl: string | null;
  onRepositoryUrlChange: (next: string | null) => void;
  onReload: () => void;
  today: Date;
  /** The dates the grid draws: the 42 of a month grid, or the 7 of the week. */
  gridDates: Date[];
  /** Month index (0-11) of the anchor, so the month grid knows which cells are in the period. */
  anchorMonth: number;
  daysByDateKey: Map<string, UsageCalendarDay>;
  heatPercents: Map<string, number>;
  kpiCards: KpiCardModel[];
  isLoading: boolean;
  error: Error | null;
  /** The clicked day's `YYYY-MM-DD` key; stays set through the drawer's slide-out. */
  selectedDateKey: string | null;
  isDrawerOpen: boolean;
  onSelectDay: (dateKey: string) => void;
  onCloseDrawer: () => void;
  onOpenInMetrics: () => void;
  isDetailLoading: boolean;
  modelRows: BreakdownRow[];
  skillUsage: IdentifierUsageRow[];
  subagentUsage: IdentifierUsageRow[];
}

const Emphasis = ({ children }: { children: string }) => (
  <Box component="b" sx={{ color: 'text.primary', fontWeight: 600 }}>
    {children}
  </Box>
);

// The counter-pipeline caveat is stated up front: cost here is counter-derived, so it can read slightly
// off the Cost page (which sums per-request api_request figures). See AGENTS.md's two-pipelines note.
const SUBTITLE = (
  <>
    Day-by-day usage across the CLI — <Emphasis>cost</Emphasis>, <Emphasis>token usage</Emphasis>, and{' '}
    <Emphasis>skill &amp; agent invocations</Emphasis> at a glance. Click any day to open its full breakdown.
    Figures come from the metric counters, so cost can read slightly off the Cost page.
  </>
);

const UsageCalendarPageView = ({
  view,
  onViewChange,
  periodLabel,
  onPreviousPeriod,
  onNextPeriod,
  colorBy,
  onColorByChange,
  repositoryUrl,
  onRepositoryUrlChange,
  onReload,
  today,
  gridDates,
  anchorMonth,
  daysByDateKey,
  heatPercents,
  kpiCards,
  isLoading,
  error,
  selectedDateKey,
  isDrawerOpen,
  onSelectDay,
  onCloseDrawer,
  onOpenInMetrics,
  isDetailLoading,
  modelRows,
  skillUsage,
  subagentUsage,
}: UsageCalendarPageViewProps) => {
  const colorByOption = COLOR_BY_OPTIONS.find((option) => option.value === colorBy) ?? COLOR_BY_OPTIONS[0];
  const heatColor = colorForIndex(colorByOption.colorIndex);
  const selectedDate = selectedDateKey ? fromDateKey(selectedDateKey) : null;

  // A failed rollup leaves daysByDateKey empty exactly as loading does, and an empty map must not be read
  // as a run of "No activity" days; the PageLayout error Alert is what reports the failure.
  const isDayDataUnavailable = isLoading || error != null;
  const gridProps = {
    today,
    daysByDateKey,
    heatPercents,
    heatColor,
    isDayDataUnavailable,
    selectedDateKey,
    onSelectDay,
  };

  return (
    <>
      <PageLayout
        eyebrow="Activity"
        title="Usage Calendar"
        subtitle={SUBTITLE}
        error={error}
        actions={
          // No WindowSelector here: a day-granularity calendar has no use for a preset-or-custom
          // window picker, so the period pill stands in for it. Auto-refresh is omitted for the same
          // reason as on the Settings page — there is no rolling window to keep fresh.
          <PageActionsView
            windowSelector={
              <CalendarPeriodPicker
                view={view}
                label={periodLabel}
                onPrevious={onPreviousPeriod}
                onNext={onNextPeriod}
              />
            }
            repositorySelector={<RepositorySelector value={repositoryUrl} onValueChange={onRepositoryUrlChange} />}
            onReload={onReload}
            reloadDisabled={false}
            autoRefreshActive={false}
            autoRefreshDisabled
            isPolling={false}
            onToggleAutoRefresh={() => undefined}
            hideAutoRefresh
          />
        }
      >
        <Stack spacing={2.25}>
          <CalendarControlBar
            view={view}
            onViewChange={onViewChange}
            colorBy={colorBy}
            onColorByChange={onColorByChange}
          />
          <UsageKpiStrip cards={kpiCards} showBars={view === 'week'} />
          <Paper variant="outlined" sx={{ p: 2 }}>
            {view === 'month' ? (
              <MonthCalendarGrid gridDates={gridDates} anchorMonth={anchorMonth} {...gridProps} />
            ) : (
              <WeekCalendarRow weekDates={gridDates} {...gridProps} />
            )}
          </Paper>
        </Stack>
      </PageLayout>
      <UsageDayDrawer
        open={isDrawerOpen}
        date={selectedDate}
        day={selectedDateKey ? daysByDateKey.get(selectedDateKey) : undefined}
        isDetailLoading={isDetailLoading}
        modelRows={modelRows}
        skillUsage={skillUsage}
        subagentUsage={subagentUsage}
        onClose={onCloseDrawer}
        onOpenInMetrics={onOpenInMetrics}
      />
    </>
  );
};

export default UsageCalendarPageView;
