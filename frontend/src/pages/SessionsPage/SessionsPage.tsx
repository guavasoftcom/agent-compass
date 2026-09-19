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
import { buildWindowSelectionKey } from '../../lib/queryKeys';
import { useWindowContext } from '../../lib/windowContext';
import SessionsPageView, {
  type PaginationModel,
  type SessionsKpis,
} from './SessionsPageView';

const DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

// Open-drawer timeline poll cadence while a turn is still running. Fast enough
// that a live turn's tool chips and cost visibly tick along; cheap because the
// query only fires while that session's drawer is open (the endpoint measured
// ~30 ms on a short session, ~0.6 s on a 100+-turn one).
const RUNNING_TURN_POLL_INTERVAL_MS = 5_000;
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
  const { selection, setSelection, autoRefresh, setAutoRefresh, repositoryUrl, setRepositoryUrl } =
    useWindowContext();
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

  const selectionKey = buildWindowSelectionKey(selection, repositoryUrl);

  const isPresetAutoRefresh = autoRefresh && selection.kind === 'preset';
  const refetchInterval = isPresetAutoRefresh ? AUTO_REFRESH_INTERVAL_MS : false;

  // Window-level KPIs are keyed on the window only, so paging or re-sorting the grid reuses the
  // cached summary instead of re-running the heavy percentile aggregation.
  const summaryQuery = useQuery({
    queryKey: ['sessions-summary', selectionKey],
    queryFn: () => fetchSessionsSummary({ ...selection, repositoryUrl }),
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
    queryFn: () =>
      fetchSessions({ ...selection, repositoryUrl }, { ...paginationModel, sort: sortModel }),
    // Polls every RUNNING_TURN_POLL_INTERVAL_MS whenever the current page carries a running
    // row (SessionSummaryRow.inProgress) — UNCONDITIONALLY, not gated on isPresetAutoRefresh
    // like the plain interval below. autoRefresh defaults to false, so gating this on it (an
    // earlier version of this code did) meant a row's running dot would show once and
    // then never re-fetch to notice the session had actually finished — stuck showing
    // "running" indefinitely for anyone who hadn't opted into auto-refresh, since nothing
    // else on this page causes sessionsQuery to revalidate on its own. A displayed "running"
    // state is a promise the UI has to keep regardless of the user's auto-refresh
    // preference, which is the identical reasoning sessionPromptsQuery below already applies
    // to the drawer's own per-turn dot (and, same as there, custom windows aren't
    // excluded either — inProgress isn't window-scoped, so a session already listed under a
    // fixed custom range can still flip from running to finished while that range stays put).
    // Once the poll sees the row's inProgress flip to false, this falls through to the plain
    // auto-refresh-gated interval on its own — no separate "stop polling" trigger needed.
    refetchInterval: (query) => {
      const hasRunningRow = query.state.data?.items.some((row) => row.inProgress) ?? false;
      if (hasRunningRow) {
        return RUNNING_TURN_POLL_INTERVAL_MS;
      }
      return isPresetAutoRefresh ? AUTO_REFRESH_INTERVAL_MS : false;
    },
    placeholderData: keepPreviousData,
  });

  const rows = sessionsQuery.data?.items ?? [];

  // Full, untruncated prompt timeline for the open session. Fires only while a
  // session is both open AND actually present in the currently loaded page. The
  // second half of that gate matters for the `?sessionId=` deep link: it can
  // name a session that isn't on the table's first page under the default sort,
  // and without the `rows.some(...)` check this query would fire a wasted
  // whole-session fetch for a drawer that can never open (the view resolves the
  // header row from the same page).
  //
  // Polled while the drawer is open: every RUNNING_TURN_POLL_INTERVAL_MS while
  // the backend reports a turn still running (so its dot clears and its
  // figures fill in on their own), otherwise at the page's auto-refresh cadence
  // when auto-refresh is on — which is how a brand-new prompt in an idle
  // session shows up. Not gated on a preset window like the other two queries:
  // this endpoint isn't window-scoped, so a custom range doesn't freeze it.
  const sessionPromptsQuery = useQuery({
    queryKey: ['session-prompts', openSessionId],
    queryFn: () => fetchSessionPrompts(openSessionId as string),
    enabled:
      openSessionId !== null
      && rows.some((row) => row.sessionId === openSessionId),
    refetchInterval: (query) => {
      if (query.state.data?.some((turn) => turn.inProgress)) {
        return RUNNING_TURN_POLL_INTERVAL_MS;
      }
      return autoRefresh ? AUTO_REFRESH_INTERVAL_MS : false;
    },
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
      repositoryUrl={repositoryUrl}
      onRepositoryUrlChange={setRepositoryUrl}
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
