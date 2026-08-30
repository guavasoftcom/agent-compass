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
import { Box, Tooltip } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import StatCard from '../../components/StatCard';
import DonutCard from '../../components/DonutCard';
import { colorForIndex } from '../../theme/theme';
import type { HookExecutionRow, ToolDenialRow } from '../../api';
import ToolDenialsCard from './ToolDenialsCard';
import HookExecutionsCard from './HookExecutionsCard';

export interface PermissionDenialsPageViewProps {
  denialRows: ToolDenialRow[];
  hookRows: HookExecutionRow[];
  totalDenials: number;
  distinctDeniedTools: number;
  totalBlockingErrors: number;
  mostDeniedTool: string;
  isDenialsLoading: boolean;
  isHooksLoading: boolean;
  error: Error | null;
}

const PermissionDenialsPageView = ({
  denialRows,
  hookRows,
  totalDenials,
  distinctDeniedTools,
  totalBlockingErrors,
  mostDeniedTool,
  isDenialsLoading,
  isHooksLoading,
  error,
}: PermissionDenialsPageViewProps) => {
  // Per-tool denial totals (sorted desc) drive both the donut and the summary cards.
  const denialSlices = useMemo(() => {
    const byTool = new Map<string, number>();
    for (const row of denialRows) {
      byTool.set(row.tool, (byTool.get(row.tool) ?? 0) + row.count);
    }
    return [...byTool.entries()]
      .sort((left, right) => right[1] - left[1])
      .map(([tool, count], index) => ({
        label: tool,
        value: count,
        color: colorForIndex(index),
      }));
  }, [denialRows]);

  const topSlice = denialSlices[0];
  const topShare =
    topSlice && totalDenials > 0
      ? `${((topSlice.value / totalDenials) * 100).toFixed(1)}%`
      : null;

  // Distinct-tools card subtitle: the leading tool names. When the list is
  // truncated with an ellipsis, a tooltip reveals the full set of denied tools.
  const toolListSub = useMemo(() => {
    if (denialSlices.length === 0) {
      return undefined;
    }
    const allNames = denialSlices.map((slice) => slice.label);
    if (allNames.length <= 3) {
      return allNames.join(' · ');
    }
    return (
      <Tooltip title={allNames.join(' · ')} arrow>
        <Box component="span" sx={{ cursor: 'help' }}>
          {`${allNames.slice(0, 3).join(' · ')} · …`}
        </Box>
      </Tooltip>
    );
  }, [denialSlices]);

  // How many distinct hooks actually blocked a tool (for the blocking-errors card).
  const blockingHookCount = useMemo(
    () => hookRows.filter((row) => row.blockingErrors > 0).length,
    [hookRows],
  );

  return (
    <PageLayout
      subtitle={
        'Tool permission denials and hook execution outcomes — which tools the agent was blocked ' +
        'from using and why, plus whether configured hooks blocked or errored during execution.'
      }
      error={error}
    >
      {/* Summary KPIs */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr',
            sm: 'repeat(2, 1fr)',
            md: 'repeat(4, 1fr)',
          },
          gap: 2,
        }}
      >
        <StatCard
          label="Total denials"
          value={totalDenials.toLocaleString()}
          sub={
            distinctDeniedTools > 0
              ? `across ${distinctDeniedTools} distinct tools`
              : undefined
          }
          accent
        />
        <StatCard
          label="Distinct tools denied"
          value={
            distinctDeniedTools === 0
              ? '—'
              : distinctDeniedTools.toLocaleString()
          }
          sub={toolListSub}
        />
        <StatCard
          label="Hook blocking errors"
          value={
            totalBlockingErrors === 0 ? (
              '—'
            ) : (
              <Box component="span" sx={{ color: 'error.main' }}>
                {totalBlockingErrors.toLocaleString()}
              </Box>
            )
          }
          sub={
            blockingHookCount > 0 ? (
              <Box component="span">
                <Box
                  component="span"
                  sx={{ color: 'error.main', fontWeight: 700 }}
                >
                  {blockingHookCount}
                </Box>
                {` ${blockingHookCount === 1 ? 'hook' : 'hooks'} actively blocked a tool`}
              </Box>
            ) : undefined
          }
        />
        <StatCard
          label="Most denied tool"
          value={
            <Box component="span" sx={{ fontSize: 26 }}>
              {mostDeniedTool}
            </Box>
          }
          sub={
            topSlice && topShare
              ? `${topSlice.value.toLocaleString()} denials · ${topShare}`
              : undefined
          }
        />
      </Box>

      {/* Denials by tool & source (breakdown) beside the by-tool donut */}
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1.6fr 1fr' },
          gap: 2,
          alignItems: 'stretch',
        }}
      >
        <ToolDenialsCard
          denialRows={denialRows}
          isDenialsLoading={isDenialsLoading}
        />
        <DonutCard
          title="Denials by tool"
          slices={denialSlices}
          centerValue={totalDenials.toLocaleString()}
          centerLabel="denials"
          ranked
          hasData={denialSlices.length > 0}
          isLoading={isDenialsLoading}
          emptyLabel="No tool denials in this window."
        />
      </Box>

      {/* Hook execution outcomes */}
      <HookExecutionsCard hookRows={hookRows} isHooksLoading={isHooksLoading} />
    </PageLayout>
  );
};

export default PermissionDenialsPageView;
