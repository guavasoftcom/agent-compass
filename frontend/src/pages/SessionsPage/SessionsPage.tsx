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
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
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

/**
 * Deep-link query param — `/sessions?sessionId=…` lands with that session's
 * prompt timeline already open. The Tokens page's cache-efficiency detail
 * dialog is the current caller.
 */
const DEEP_LINK_SESSION_PARAM = 'sessionId';

/** Builds the `?sessionId=` deep link consumed by this page's mount-time
 * seeding above — the single place the param name is spelled, for any other
 * page (e.g. the Tokens page's cache-efficiency detail dialog) that wants to
 * link a user into a session's prompt timeline. */
export const sessionsDeepLink = (sessionId: string): string =>
  `/sessions?${DEEP_LINK_SESSION_PARAM}=${encodeURIComponent(sessionId)}`;

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
  const [searchParams, setSearchParams] = useSearchParams();
  // Session detail drawer: only one session's prompt timeline is open at a time.
  // Seeded from the deep-link param so arriving from another page opens that
  // session's timeline instead of an unscrolled, unfiltered table.
  const [openSessionId, setOpenSessionId] = useState<string | null>(
    () => searchParams.get(DEEP_LINK_SESSION_PARAM),
  );

  // The deep-link param is consumed once, at mount, by the initializer above,
  // then dropped from the URL: expansion is ordinary page state from that point
  // on, so leaving the param in place would only mean a reload silently
  // re-opening a row the user had closed.
  useEffect(() => {
    if (!searchParams.has(DEEP_LINK_SESSION_PARAM)) {
      return;
    }
    setSearchParams(
      (previous) => {
        previous.delete(DEEP_LINK_SESSION_PARAM);
        return previous;
      },
      { replace: true },
    );
  }, [searchParams, setSearchParams]);

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

  const rows = sessionsQuery.data?.items ?? [];

  // Full, untruncated prompt timeline for the open session. Fires only while a
  // session is both open AND actually present in the currently loaded page (not
  // polled; re-opening refetches past the global staleTime so a live session's
  // growing timeline stays current). The second half of that gate matters for
  // the `?sessionId=` deep link: it can name a session that isn't on the table's
  // first page under the default sort, and without the `rows.some(...)` check
  // this query would fire a wasted whole-session fetch for a drawer that can
  // never open (the view resolves the header row from the same page).
  const sessionPromptsQuery = useQuery({
    queryKey: ['session-prompts', openSessionId],
    queryFn: () => fetchSessionPrompts(openSessionId as string),
    enabled:
      openSessionId !== null
      && rows.some((row) => row.sessionId === openSessionId),
  });

  // Clicking the open session's row again closes its drawer; clicking another
  // row swaps the drawer's contents without a close-then-open round trip.
  const handleToggleSessionDetail = (sessionId: string) => {
    setOpenSessionId((previous) => (previous === sessionId ? null : sessionId));
  };

  // The drawer's own close affordances: × button, backdrop click, Escape.
  const handleCloseSessionDetail = () => {
    setOpenSessionId(null);
  };

  const handleReload = () => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    summaryQuery.refetch();
    sessionsQuery.refetch();
  };

  // An open session rarely survives a window/sort/page change (its row may not
  // even be on the new page), so close the drawer defensively on each. onReload
  // is intentionally NOT reset — it revalidates the same page and shouldn't
  // yank the drawer out from under the user.
  const handleSelectionChange = (next: WindowSelection) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setOpenSessionId(null);
    setSelection(next);
  };

  const handleSortModelChange = (next: SessionsSortModel) => {
    setPaginationModel((previous) => ({ ...previous, page: 0 }));
    setOpenSessionId(null);
    setSortModel(next);
  };

  const handlePaginationModelChange = (next: PaginationModel) => {
    setOpenSessionId(null);
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
      rows={rows}
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
      openSessionId={openSessionId}
      onToggleSessionDetail={handleToggleSessionDetail}
      onCloseSessionDetail={handleCloseSessionDetail}
      promptTimeline={sessionPromptsQuery.data ?? null}
      promptTimelineLoading={sessionPromptsQuery.isLoading}
      promptTimelineError={sessionPromptsQuery.error as Error | null}
    />
  );
}
