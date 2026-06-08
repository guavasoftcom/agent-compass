import { useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  fetchSessions,
  fetchSessionsSummary,
  type SessionsSortModel,
  type WindowSelection,
} from '../../api';
import { WINDOWS } from '../../constants';
import { useWindowContext } from '../../windowContext';
import SessionsPageView, {
  type PaginationModel,
  type SessionsKpis,
} from './SessionsPageView';

const AUTO_REFRESH_INTERVAL_MS = 60_000;
const DEFAULT_PAGE_SIZE = 25;
const DEFAULT_SORT: SessionsSortModel = { field: 'costUsd', direction: 'desc' };

const EMPTY_KPIS: SessionsKpis = {
  totalSessions: 0,
  medianCostUsd: 0,
  p95CostUsd: 0,
  medianCostPerActiveMinuteUsd: 0,
  // Aurora: per-bucket new-session counts for the Total-sessions sparkline.
  sessionsTrend: [],
};

export default function SessionsPage() {
  const { selection, setSelection, autoRefresh, setAutoRefresh } = useWindowContext();
  const [paginationModel, setPaginationModel] = useState<PaginationModel>({
    page: 0,
    pageSize: DEFAULT_PAGE_SIZE,
  });
  const [sortModel, setSortModel] = useState<SessionsSortModel>(DEFAULT_SORT);

  const selectionKey =
    selection.kind === 'preset'
      ? `preset:${selection.minutes}`
      : `custom:${selection.startTimestamp}:${selection.endTimestamp}`;

  const refetchInterval =
    autoRefresh && selection.kind === 'preset' ? AUTO_REFRESH_INTERVAL_MS : false;

  // Window-level KPIs are keyed on the window only, so paging or re-sorting the grid reuses the
  // cached summary instead of re-running the heavy percentile aggregation.
  const summaryQuery = useQuery({
    queryKey: ['sessions-summary', selectionKey],
    queryFn: () => fetchSessionsSummary(selection),
    refetchInterval,
  });

  const sessionsQuery = useQuery({
    queryKey: [
      'sessions',
      selectionKey,
      paginationModel.page,
      paginationModel.pageSize,
      sortModel.field,
      sortModel.direction,
    ],
    queryFn: () => fetchSessions(selection, { ...paginationModel, sort: sortModel }),
    refetchInterval,
    placeholderData: keepPreviousData,
  });

  const handleReload = () => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    summaryQuery.refetch();
    sessionsQuery.refetch();
  };

  const handleSelectionChange = (next: WindowSelection) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setSelection(next);
  };

  const handleSortModelChange = (next: SessionsSortModel) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setSortModel(next);
  };

  const isPolling =
    autoRefresh
    && selection.kind === 'preset'
    && (sessionsQuery.isFetching || summaryQuery.isFetching);

  return (
    <SessionsPageView
      selection={selection}
      onSelectionChange={handleSelectionChange}
      windows={WINDOWS}
      rows={sessionsQuery.data?.items ?? []}
      rowCount={sessionsQuery.data?.totalCount ?? summaryQuery.data?.totalSessions ?? 0}
      paginationModel={paginationModel}
      onPaginationModelChange={setPaginationModel}
      sortModel={sortModel}
      onSortModelChange={handleSortModelChange}
      kpis={summaryQuery.data ?? EMPTY_KPIS}
      isLoading={sessionsQuery.isLoading}
      error={(sessionsQuery.error ?? summaryQuery.error) as Error | null}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
    />
  );
}
