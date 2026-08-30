import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  fetchCostBreakdown,
  type CostBreakdown,
  type CostSessionShare,
} from '../../api';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import CostPageView, { type CostPageTab } from './CostPageView';

const emptyBreakdown: CostBreakdown = {
  totalCostUsd: 0,
  priorCostUsd: 0,
  deltaPct: 0,
  burnRatePerHour: 0,
  projected30dUsd: 0,
  totalRequests: 0,
  totalInputTokens: 0,
  totalOutputTokens: 0,
  totalCacheCreationTokens: 0,
  totalCacheReadTokens: 0,
  categories: [],
  trend: [],
  modelEffort: [],
  topSessions: [],
  bucketSeconds: 0,
};

export default function CostPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const [activeTab, setActiveTab] = useState<CostPageTab>('overview');
  // The clicked "Most expensive sessions" row itself, not its id: the dialog
  // shows only fields the row already carries, so opening it costs no fetch —
  // same idiom as TokensPage's selectedCacheEfficiencyRow.
  const [selectedSession, setSelectedSession] = useState<CostSessionShare | null>(null);

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const breakdownQuery = useQuery({
    queryKey: ['cost-breakdown', selectionKey],
    queryFn: () => fetchCostBreakdown(selection),
    refetchInterval,
  });

  const isPolling = autoRefresh && selection.kind === 'preset' && breakdownQuery.isFetching;

  const error = breakdownQuery.error as Error | null;

  // Leaving the "What drove it" tab unmounts the dialog; drop the selection
  // with it so coming back doesn't re-open a dialog the user had left behind.
  const handleActiveTabChange = (next: CostPageTab): void => {
    setSelectedSession(null);
    setActiveTab(next);
  };

  const handleReload = (): void => {
    breakdownQuery.refetch();
  };

  return (
    <CostPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      breakdown={breakdownQuery.data ?? emptyBreakdown}
      activeTab={activeTab}
      onActiveTabChange={handleActiveTabChange}
      selectedSession={selectedSession}
      onSelectSession={setSelectedSession}
      isLoading={breakdownQuery.isLoading}
      error={error}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
    />
  );
}
