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
import { useQuery } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import TrendReportPageView from './TrendReportPageView';
import { fetchTrendReport } from './trendReportApi';

/**
 * Trend Report page container — sources the shared window selection/auto-refresh state and
 * runs the single `GET /api/trends` query. Mirrors `MetricsPage`'s wiring: the window/auto-refresh
 * pattern, no page-local state beyond what `useWindowContext` already provides.
 */
export default function TrendReportPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const trendReportQuery = useQuery({
    queryKey: ['trend-report', selectionKey],
    queryFn: () => fetchTrendReport(selection),
    refetchInterval,
  });

  const isPolling = autoRefresh && selection.kind === 'preset' && trendReportQuery.isFetching;

  return (
    <TrendReportPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      onReload={() => trendReportQuery.refetch()}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      report={trendReportQuery.data}
      isLoading={trendReportQuery.isLoading}
      error={trendReportQuery.error as Error | null}
    />
  );
}
