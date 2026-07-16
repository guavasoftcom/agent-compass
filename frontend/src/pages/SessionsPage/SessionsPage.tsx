import { useState } from 'react';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import {
  fetchSessionPrompts,
  fetchSessions,
  fetchSessionsSummary,
  type SessionsSortModel,
  type WindowSelection,
} from '../../api';
import { AUTO_REFRESH_INTERVAL_MS, PAGE_SIZE_OPTIONS, WINDOWS } from '../../lib/constants';
import { useWindowContext } from '../../lib/windowContext';
import SessionsPageView, {
  type PaginationModel,
  type SessionsKpis,
} from './SessionsPageView';

const DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];
// Sessions land sorted by most-recent activity (recency = the operational default;
// cost is one click away on its sortable column). Maps to the existing `endTimestamp`.
const DEFAULT_SORT: SessionsSortModel = { field: 'endTimestamp', direction: 'desc' };

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
  // Prompt-timeline row expansion: only one session's prompts are shown at a time.
  const [expandedSessionId, setExpandedSessionId] = useState<string | null>(null);

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

  // Full, untruncated prompt timeline for the expanded session. Fires only while
  // a row is open (enabled gate); not polled. Re-expanding refetches past the
  // global staleTime so a live session's growing timeline stays current.
  const sessionPromptsQuery = useQuery({
    queryKey: ['session-prompts', expandedSessionId],
    queryFn: () => fetchSessionPrompts(expandedSessionId as string),
    enabled: expandedSessionId !== null,
  });

  const handleToggleExpand = (sessionId: string) => {
    setExpandedSessionId((previous) => (previous === sessionId ? null : sessionId));
  };

  const handleReload = () => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    summaryQuery.refetch();
    sessionsQuery.refetch();
  };

  // An expanded row rarely maps to the same position after a window/sort/page
  // change, so drop the open panel defensively on each. onReload is intentionally
  // NOT reset — it revalidates the same page and shouldn't collapse the panel.
  const handleSelectionChange = (next: WindowSelection) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setExpandedSessionId(null);
    setSelection(next);
  };

  const handleSortModelChange = (next: SessionsSortModel) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setExpandedSessionId(null);
    setSortModel(next);
  };

  const handlePaginationModelChange = (next: PaginationModel) => {
    setExpandedSessionId(null);
    setPaginationModel(next);
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
      onPaginationModelChange={handlePaginationModelChange}
      sortModel={sortModel}
      onSortModelChange={handleSortModelChange}
      kpis={summaryQuery.data ?? EMPTY_KPIS}
      isLoading={sessionsQuery.isLoading}
      error={(sessionsQuery.error ?? summaryQuery.error) as Error | null}
      onReload={handleReload}
      autoRefresh={autoRefresh}
      onAutoRefreshChange={setAutoRefresh}
      isPolling={isPolling}
      expandedSessionId={expandedSessionId}
      onToggleExpand={handleToggleExpand}
      promptTimeline={sessionPromptsQuery.data ?? null}
      promptTimelineLoading={sessionPromptsQuery.isLoading}
      promptTimelineError={sessionPromptsQuery.error as Error | null}
    />
  );
}
