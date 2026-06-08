import { useQuery } from '@tanstack/react-query';
import { fetchTokenUsage, type TokenUsageSummary } from '../../api';
import { WINDOWS } from '../../constants';
import { useWindowContext } from '../../windowContext';
import TokensPageView from './TokensPageView';

const AUTO_REFRESH_INTERVAL_MS = 60_000;

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

  const summary = summaryQuery.data ?? emptySummary;
  const isPolling =
    autoRefresh && selection.kind === 'preset' && summaryQuery.isFetching;

  return (
    <TokensPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      summary={summary}
      isLoading={summaryQuery.isLoading}
      error={summaryQuery.error as Error | null}
      onReload={() => summaryQuery.refetch()}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={(next) => setAutoRefresh(next)}
      isPolling={isPolling}
    />
  );
}
