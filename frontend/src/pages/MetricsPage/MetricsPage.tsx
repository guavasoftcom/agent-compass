import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { WINDOWS } from '../../constants';
import { useWindowContext } from '../../windowContext';
import MetricsPageView from './MetricsPageView';
import { fetchMetrics, type MetricsQueryParams } from './metricsApi';

const AUTO_REFRESH_INTERVAL_MS = 60_000;

/**
 * Metrics page container — sources the shared window selection and fetches the
 * claude_code.* metric series. Mirrors the window/auto-refresh wiring used by
 * the other pages; the simplified view does the rest.
 */
export default function MetricsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();

  const params = useMemo<MetricsQueryParams>(() => {
    const now = new Date().toISOString();
    const from =
      selection.kind === 'custom'
        ? selection.startTimestamp
        : new Date(Date.now() - selection.minutes * 60_000).toISOString();
    const to = selection.kind === 'custom' ? selection.endTimestamp : now;
    return { from, to };
  }, [selection]);

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

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
