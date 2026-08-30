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
import {
  fetchToolCallLatency,
  fetchToolCalls,
  fetchToolCallsTimeseries,
} from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import ToolCallsPageView, {
  type ToolCallRowWithShare,
} from './ToolCallsPageView';

export default function ToolCallsPage() {
  const { selection, autoRefresh } = useSectionContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const toolCallsQuery = useQuery({
    queryKey: ['tool-calls', selectionKey],
    queryFn: () => fetchToolCalls(selection),
    refetchInterval,
  });
  const timeseriesQuery = useQuery({
    queryKey: ['tool-calls-timeseries', selectionKey],
    queryFn: () => fetchToolCallsTimeseries(selection),
    refetchInterval,
  });
  const latencyQuery = useQuery({
    queryKey: ['tool-calls-latency', selectionKey],
    queryFn: () => fetchToolCallLatency(selection),
    refetchInterval,
  });

  const { rowsWithShare, total } = useMemo(() => {
    const rows = toolCallsQuery.data ?? [];
    const totalCalls = rows.reduce((sum, row) => sum + row.calls, 0);
    const withShare: ToolCallRowWithShare[] = rows.map((row) => ({
      ...row,
      share: totalCalls === 0 ? 0 : (100 * row.calls) / totalCalls,
    }));
    return { rowsWithShare: withShare, total: totalCalls };
  }, [toolCallsQuery.data]);

  return (
    <ToolCallsPageView
      rowsWithShare={rowsWithShare}
      total={total}
      hasData={rowsWithShare.length > 0}
      isLoading={toolCallsQuery.isLoading}
      error={toolCallsQuery.error as Error | null}
      timeseries={timeseriesQuery.data ?? null}
      isTimeseriesLoading={timeseriesQuery.isLoading}
      latencyRows={latencyQuery.data ?? []}
      isLatencyLoading={latencyQuery.isLoading}
    />
  );
}
