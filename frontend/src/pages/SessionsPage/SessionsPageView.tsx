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
import { Paper } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import TablePager from '../../components/TablePager';
import { resolveWindow } from '../../lib/resolveWindow';
import SessionDetailDrawer from './components/SessionDetailDrawer';
import SessionsKpiStrip from './components/SessionsKpiStrip';
import SessionsTable from './components/SessionsTable';
import type {
  SessionPromptRow,
  SessionSummaryRow,
  SessionsSortModel,
  WindowSelection,
} from '../../api';
import type { WindowOption } from '../../lib/constants';

export interface SessionsKpis {
  totalSessions: number;
  medianCostUsd: number;
  p95CostUsd: number;
  medianCostPerActiveMinuteUsd: number;
  // Aurora: per-bucket new-session counts over the window, drawn as the
  // Total-sessions card sparkline. Empty array renders no line.
  sessionsTrend: number[];
}

export interface PaginationModel {
  page: number;
  pageSize: number;
}

export interface SessionsPageViewProps {
  selection: WindowSelection;
  onSelectionChange: (next: WindowSelection) => void;
  windows: readonly WindowOption[];
  rows: SessionSummaryRow[];
  rowCount: number;
  paginationModel: PaginationModel;
  onPaginationModelChange: (next: PaginationModel) => void;
  sortModel: SessionsSortModel;
  onSortModelChange: (next: SessionsSortModel) => void;
  kpis: SessionsKpis;
  isLoading: boolean;
  error: Error | null;
  onReload: () => void;
  autoRefresh: boolean;
  onAutoRefreshChange: (next: boolean) => void;
  isPolling: boolean;
  // Session detail drawer (one session open at a time). The row stays
  // highlighted while its drawer is open; clicking it again toggles closed.
  openSessionId: string | null;
  onToggleSessionDetail: (sessionId: string) => void;
  onCloseSessionDetail: () => void;
  promptTimeline: SessionPromptRow[] | null;
  promptTimelineLoading: boolean;
  promptTimelineError: Error | null;
}

// Viewport height reserved for the page chrome above the table (header + KPI strip),
// subtracted from 100vh to size the scrollable table body.
const BODY_CHROME_PX = 385;

const SessionsPageView = ({
  selection,
  onSelectionChange,
  windows,
  rows,
  rowCount,
  paginationModel,
  onPaginationModelChange,
  sortModel,
  onSortModelChange,
  kpis,
  isLoading,
  error,
  onReload,
  autoRefresh,
  onAutoRefreshChange,
  isPolling,
  openSessionId,
  onToggleSessionDetail,
  onCloseSessionDetail,
  promptTimeline,
  promptTimelineLoading,
  promptTimelineError,
}: SessionsPageViewProps) => {
  const showLoading = isLoading && rows.length === 0;
  const showEmpty = !isLoading && rows.length === 0;

  // The drawer header repeats the open row's own figures, so it reads from the
  // loaded page rather than from a second fetch. A session id that isn't on the
  // current page (a stale `?sessionId=` deep link) resolves to null and the
  // drawer simply stays closed — the same case the prompts query fails closed on.
  const openSession =
    rows.find((row) => row.sessionId === openSessionId) ?? null;

  // Bounds of the active window, so the prompt timeline can dim turns that
  // fall outside it (the /prompts endpoint returns the whole session, not
  // just the windowed slice). Derived from the same resolveWindow helper
  // LogsPage/TracesPage use, so dimming can't contradict what the data
  // fetches actually counted.
  const resolvedWindow = resolveWindow(selection);
  const windowStartMs = Date.parse(resolvedWindow.startTimestamp);
  const windowEndMs = Date.parse(resolvedWindow.endTimestamp);

  return (
    <PageLayout
      eyebrow="Activity"
      title="Sessions"
      subtitle={
        'Per-session cost and token usage over the selected window. The most expensive sessions ' +
        'are the most leveraged tuning targets — inspecting their tool sequences and prompts ' +
        'informs prompt revisions and skill additions.'
      }
      error={error}
      actions={
        <PageActions
          selection={selection}
          onSelectionChange={onSelectionChange}
          windows={windows}
          onReload={onReload}
          autoRefresh={autoRefresh}
          onAutoRefreshChange={onAutoRefreshChange}
          isPolling={isPolling}
        />
      }
    >
      <SessionsKpiStrip kpis={kpis} />

      <Paper
        variant="outlined"
        sx={{
          mt: 2,
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
          height: `calc(100vh - ${BODY_CHROME_PX}px)`,
          minHeight: 420,
        }}
      >
        <div
          style={{
            flex: 1,
            minHeight: 0,
            overflowX: 'auto',
            overflowY: 'auto',
          }}
        >
          <SessionsTable
            rows={rows}
            sortModel={sortModel}
            onSortModelChange={onSortModelChange}
            showLoading={showLoading}
            showEmpty={showEmpty}
            openSessionId={openSessionId}
            onToggleSessionDetail={onToggleSessionDetail}
            hotCostThresholdUsd={kpis.p95CostUsd}
          />
        </div>
        <TablePager
          page={paginationModel.page}
          pageSize={paginationModel.pageSize}
          rowCount={rowCount}
          onPageChange={(nextPage) =>
            onPaginationModelChange({
              page: nextPage,
              pageSize: paginationModel.pageSize,
            })
          }
          onPageSizeChange={(nextPageSize) =>
            onPaginationModelChange({ page: 0, pageSize: nextPageSize })
          }
        />
      </Paper>

      <SessionDetailDrawer
        session={openSession}
        onClose={onCloseSessionDetail}
        prompts={promptTimeline}
        promptsLoading={promptTimelineLoading}
        promptsError={promptTimelineError}
        windowStartMs={windowStartMs}
        windowEndMs={windowEndMs}
        hotCostThresholdUsd={kpis.p95CostUsd}
      />
    </PageLayout>
  );
};

export default SessionsPageView;
