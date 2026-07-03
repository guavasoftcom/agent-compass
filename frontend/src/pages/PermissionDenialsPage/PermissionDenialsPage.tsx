import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  fetchToolDenials,
  fetchHookExecutions,
  type ToolDenialRow,
  type HookExecutionRow,
} from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import PermissionDenialsPageView from './PermissionDenialsPageView';

export default function PermissionDenialsPage() {
  const { selection, autoRefresh } = useSectionContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const denialsQuery = useQuery({
    queryKey: ['tool-denials', selectionKey],
    queryFn: () => fetchToolDenials(selection),
    refetchInterval,
  });

  const hooksQuery = useQuery({
    queryKey: ['hook-executions', selectionKey],
    queryFn: () => fetchHookExecutions(selection),
    refetchInterval,
  });

  const denialRows = denialsQuery.data ?? [];
  const hookRows = hooksQuery.data ?? [];

  const { totalDenials, distinctDeniedTools, totalBlockingErrors, mostDeniedTool } =
    useMemo(() => {
      const total = denialRows.reduce((sum: number, row: ToolDenialRow) => sum + row.count, 0);
      const uniqueTools = new Set(denialRows.map((row: ToolDenialRow) => row.tool));
      const blockingErrors = hookRows.reduce(
        (sum: number, row: HookExecutionRow) => sum + row.blockingErrors,
        0,
      );
      const toolTotals = denialRows.reduce<Record<string, number>>((acc, row: ToolDenialRow) => {
        acc[row.tool] = (acc[row.tool] ?? 0) + row.count;
        return acc;
      }, {});
      const topTool = Object.entries(toolTotals).sort(([, a], [, b]) => b - a)[0]?.[0] ?? '—';
      return {
        totalDenials: total,
        distinctDeniedTools: uniqueTools.size,
        totalBlockingErrors: blockingErrors,
        mostDeniedTool: topTool,
      };
    }, [denialRows, hookRows]);

  return (
    <PermissionDenialsPageView
      denialRows={denialRows}
      hookRows={hookRows}
      totalDenials={totalDenials}
      distinctDeniedTools={distinctDeniedTools}
      totalBlockingErrors={totalBlockingErrors}
      mostDeniedTool={mostDeniedTool}
      isDenialsLoading={denialsQuery.isLoading}
      isHooksLoading={hooksQuery.isLoading}
      error={(denialsQuery.error ?? hooksQuery.error) as Error | null}
    />
  );
}
