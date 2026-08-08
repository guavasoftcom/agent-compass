import { useQuery } from '@tanstack/react-query';
import {
  fetchSessionCacheEfficiency,
  fetchToolContextFootprint,
  fetchTokenUsage,
  type TokenUsageSummary,
} from '../../api';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import TokensPageView from './TokensPageView';

/**
 * Sessions shown in the worst-cache-efficiency list. The server clamps this and
 * applies its own token noise floor, so a short list is a real answer ("nothing
 * in this window is big enough to judge"), not a truncation.
 */
const CACHE_EFFICIENCY_ROW_LIMIT = 8;

const emptySummary: TokenUsageSummary = {
  inputTokens: 0,
  outputTokens: 0,
  cacheCreationTokens: 0,
  cacheReadTokens: 0,
  cacheReadRatio: 0,
  bucketSeconds: 0,
  points: [],
  byModel: [],
  cost: {
    spend24h: '$0',
    deltaPct: '+0.0%',
    burnRate: '$0/h',
    projected30d: '$0',
    costPer1k: '$0.000',
    trend: [],
    byModel: [],
    note: '',
  },
};

export default function TokensPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const summaryQuery = useQuery({
    queryKey: ['token-usage', selectionKey],
    queryFn: () => fetchTokenUsage(selection),
    refetchInterval,
  });

  const cacheEfficiencyQuery = useQuery({
    queryKey: ['session-cache-efficiency', selectionKey, CACHE_EFFICIENCY_ROW_LIMIT],
    queryFn: () => fetchSessionCacheEfficiency(selection, CACHE_EFFICIENCY_ROW_LIMIT),
    refetchInterval,
  });

  const contextFootprintQuery = useQuery({
    queryKey: ['tool-context-footprint', selectionKey],
    queryFn: () => fetchToolContextFootprint(selection),
    refetchInterval,
  });

  const summary = summaryQuery.data ?? emptySummary;
  const isPolling =
    autoRefresh
    && selection.kind === 'preset'
    && (summaryQuery.isFetching || cacheEfficiencyQuery.isFetching || contextFootprintQuery.isFetching);

  // The token summary is the page's spine — if it fails there is nothing to
  // show, so its error wins the PageLayout error slot. The two ranked lists are
  // supplementary: when only one of them fails the page still renders, and that
  // card falls back to its own empty state rather than blanking the page.
  const error = (summaryQuery.error
    ?? cacheEfficiencyQuery.error
    ?? contextFootprintQuery.error) as Error | null;

  const handleReload = () => {
    summaryQuery.refetch();
    cacheEfficiencyQuery.refetch();
    contextFootprintQuery.refetch();
  };

  return (
    <TokensPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      summary={summary}
      cacheEfficiencyRows={cacheEfficiencyQuery.data ?? []}
      contextFootprintRows={contextFootprintQuery.data ?? []}
      isLoading={summaryQuery.isLoading}
      error={error}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={(next) => setAutoRefresh(next)}
      isPolling={isPolling}
    />
  );
}
