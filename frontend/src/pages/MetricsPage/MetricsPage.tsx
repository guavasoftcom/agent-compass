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
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, MS_PER_MINUTE, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import MetricsPageView from './MetricsPageView';
import { fetchMetrics, type MetricsQueryParams } from './metricsApi';

/**
 * Metrics page container — sources the shared window selection and fetches the
 * claude_code.* metric series. Mirrors the window/auto-refresh wiring used by
 * the other pages; the simplified view does the rest.
 */
export default function MetricsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } =
    useWindowContext();

  const params = useMemo<MetricsQueryParams>(() => {
    // The metrics window is anchored to current wall-clock time at fetch time.
    // eslint-disable-next-line react-hooks/purity
    const nowMs = Date.now();
    const now = new Date(nowMs).toISOString();
    const from =
      selection.kind === 'custom'
        ? selection.startTimestamp
        : new Date(nowMs - selection.minutes * MS_PER_MINUTE).toISOString();
    const to = selection.kind === 'custom' ? selection.endTimestamp : now;
    return { from, to };
  }, [selection]);

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset'
      ? AUTO_REFRESH_INTERVAL_MS
      : false;

  const metricsQuery = useQuery({
    queryKey: ['metrics/series', params],
    queryFn: () => fetchMetrics(params),
    refetchInterval,
  });

  const isPolling =
    autoRefresh && selection.kind === 'preset' && metricsQuery.isFetching;

  return (
    <MetricsPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      onReload={() => metricsQuery.refetch()}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      metrics={metricsQuery.data}
      isLoading={metricsQuery.isLoading}
      error={metricsQuery.error as Error | null}
    />
  );
}
