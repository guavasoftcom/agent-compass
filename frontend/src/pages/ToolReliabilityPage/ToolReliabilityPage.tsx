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
  fetchToolFailureRates,
  fetchToolRepeats,
  type ToolFailureRateRow,
} from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import ToolReliabilityPageView from './ToolReliabilityPageView';

const MIN_CALLS_FOR_RANKING = 5;

export default function ToolReliabilityPage() {
  const { selection, autoRefresh } = useSectionContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const failureRatesQuery = useQuery({
    queryKey: ['tool-failure-rates', selectionKey],
    queryFn: () => fetchToolFailureRates(selection),
    refetchInterval,
  });
  const repeatsQuery = useQuery({
    queryKey: ['tool-repeats', selectionKey],
    queryFn: () => fetchToolRepeats(selection),
    refetchInterval,
  });

  const rows = failureRatesQuery.data ?? [];

  // Most-failing tool is interesting only when there's enough volume to trust the rate.
  // A 1/1 = 100% spike from a single call is noise, not signal.
  const { totalCalls, totalFailures, overallRate, worstTool } = useMemo(() => {
    const calls = rows.reduce((sum: number, row: ToolFailureRateRow) => sum + row.calls, 0);
    const failures = rows.reduce(
      (sum: number, row: ToolFailureRateRow) => sum + row.failures,
      0,
    );
    const eligible = rows.filter(
      (row: ToolFailureRateRow) => row.calls >= MIN_CALLS_FOR_RANKING,
    );
    const worst = eligible.reduce<ToolFailureRateRow | null>((acc, row) => {
      if (!acc || row.failureRate > acc.failureRate) {
        return row;
      }
      return acc;
    }, null);
    return {
      totalCalls: calls,
      totalFailures: failures,
      overallRate: calls === 0 ? 0 : failures / calls,
      worstTool: worst,
    };
  }, [rows]);

  return (
    <ToolReliabilityPageView
      rows={rows}
      totalCalls={totalCalls}
      totalFailures={totalFailures}
      overallRate={overallRate}
      worstTool={worstTool}
      minCallsForRanking={MIN_CALLS_FOR_RANKING}
      isLoading={failureRatesQuery.isLoading}
      error={(failureRatesQuery.error ?? repeatsQuery.error) as Error | null}
      repeatRows={repeatsQuery.data ?? []}
      isRepeatsLoading={repeatsQuery.isLoading}
    />
  );
}
