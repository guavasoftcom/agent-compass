import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchMcpServerUsage } from '../../api';
import { useSectionContext } from '../../components/SectionLayout';
import { AUTO_REFRESH_INTERVAL_MS } from '../../lib/constants';
import McpServersPageView from './McpServersPageView';
import {
  buildServerColorIndexes,
  rollupByServer,
  withShare,
  type McpServerRollupWithShare,
} from './mcpDerivations';

export default function McpServersPage() {
  const { selection, autoRefresh } = useSectionContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const mcpUsageQuery = useQuery({
    queryKey: ['mcp-usage', selectionKey],
    queryFn: () => fetchMcpServerUsage(selection),
    refetchInterval,
  });

  const toolRows = useMemo(() => mcpUsageQuery.data ?? [], [mcpUsageQuery.data]);

  const serverRollups = useMemo(() => rollupByServer(toolRows), [toolRows]);
  const { rows: servers, total: totalCalls } = useMemo(
    () => withShare(serverRollups),
    [serverRollups],
  );
  const serverColorIndexes = useMemo(
    () => buildServerColorIndexes(serverRollups),
    [serverRollups],
  );

  const totalFailures = useMemo(
    () => servers.reduce((sum, server) => sum + server.failures, 0),
    [servers],
  );
  const totalContextBytes = useMemo(
    () => servers.reduce((sum, server) => sum + server.totalBytes, 0),
    [servers],
  );
  const totalEstimatedTokens = useMemo(
    () => servers.reduce((sum, server) => sum + server.estimatedTokens, 0),
    [servers],
  );

  // Slowest server by its own rolled-up p95 — the KPI a reader would ask "which server is
  // dragging" for, not a global p95 across every call (which would hide a slow-but-rare
  // server behind a fast-but-frequent one).
  const slowestServer = useMemo(
    () =>
      servers.reduce<McpServerRollupWithShare | null>((slowest, server) => {
        if (!slowest || server.p95DurationMs > slowest.p95DurationMs) {
          return server;
        }
        return slowest;
      }, null),
    [servers],
  );

  return (
    <McpServersPageView
      toolRows={toolRows}
      servers={servers}
      totalCalls={totalCalls}
      totalFailures={totalFailures}
      totalContextBytes={totalContextBytes}
      totalEstimatedTokens={totalEstimatedTokens}
      slowestServer={slowestServer}
      serverColorIndexes={serverColorIndexes}
      isLoading={mcpUsageQuery.isLoading}
      error={mcpUsageQuery.error as Error | null}
    />
  );
}
