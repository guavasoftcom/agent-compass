import { useQuery } from '@tanstack/react-query';
import { AUTO_REFRESH_INTERVAL_MS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import TrendReportPageView from './TrendReportPageView';
import { fetchTrendReport } from './trendReportApi';

/**
 * Trend Report page container — sources the shared window selection/auto-refresh state and
 * runs the single `GET /api/trends` query. Mirrors `MetricsPage`'s wiring: the window/auto-refresh
 * pattern, no page-local state beyond what `useWindowContext` already provides.
 */
export default function TrendReportPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval: number | false =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  const trendReportQuery = useQuery({
    queryKey: ['trend-report', selectionKey],
    queryFn: () => fetchTrendReport(selection),
    refetchInterval,
  });

  const isPolling = autoRefresh && selection.kind === 'preset' && trendReportQuery.isFetching;

  return (
    <TrendReportPageView
      selection={selection}
      onSelectionChange={setSelection}
      windows={WINDOWS}
      onReload={() => trendReportQuery.refetch()}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      report={trendReportQuery.data}
      isLoading={trendReportQuery.isLoading}
      error={trendReportQuery.error as Error | null}
    />
  );
}
