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
import { useQueries } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import TrendReportPageView from './TrendReportPageView';
import { fetchTrendSection } from './trendReportApi';
import { TREND_SECTIONS, type TrendSectionKey } from './trendReportDerivations';
import type { TrendSectionState } from './TrendReportPageView';

/**
 * Trend Report page container — sources the shared window selection/auto-refresh state and
 * runs one independent query per section (Cost, Token efficiency, Reliability, Activity) via
 * `useQueries`, so a slow or failed section never blocks its siblings from rendering. Mirrors
 * `MetricsPage`'s window/auto-refresh wiring otherwise. `TREND_SECTIONS` is a compile-time
 * constant of fixed length 4, so mapping it into `useQueries`' array doesn't violate
 * rules-of-hooks.
 */
export default function TrendReportPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const sectionQueries = useQueries({
    queries: TREND_SECTIONS.map((section) => ({
      queryKey: ['trend-report', section.key, selectionKey],
      queryFn: () => fetchTrendSection(section.key, selection),
      refetchInterval,
    })),
  });

  const sections = {} as Record<TrendSectionKey, TrendSectionState>;
  TREND_SECTIONS.forEach((section, index) => {
    const query = sectionQueries[index];
    sections[section.key] = {
      data: query.data,
      isLoading: query.isLoading,
      error: query.error as Error | null,
    };
  });

  const isPolling =
    autoRefresh && selection.kind === 'preset' && sectionQueries.some((query) => query.isFetching);

  const handleReload = () => {
    sectionQueries.forEach((query) => {
      query.refetch();
    });
  };

  return (
    <TrendReportPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      sections={sections}
    />
  );
}
