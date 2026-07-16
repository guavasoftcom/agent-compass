import { Paper } from '@mui/material';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import TablePager from '../../components/TablePager';
import { resolveWindow } from '../../lib/resolveWindow';
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
  // Prompt-timeline row expansion (single open row at a time).
  expandedSessionId: string | null;
  onToggleExpand: (sessionId: string) => void;
  promptTimeline: SessionPromptRow[] | null;
  promptTimelineLoading: boolean;
  promptTimelineError: Error | null;
}

// Viewport height reserved for the page chrome above the table (header + KPI strip),
// subtracted from 100vh to size the scrollable table body.
const BODY_CHROME_PX = 320;

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
  expandedSessionId,
  onToggleExpand,
  promptTimeline,
  promptTimelineLoading,
  promptTimelineError,
}: SessionsPageViewProps) => {
  const showLoading = isLoading && rows.length === 0;
  const showEmpty = !isLoading && rows.length === 0;

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
        'Per-session cost and token usage over the selected window. The most expensive sessions '
        + 'are the most leveraged tuning targets — inspecting their tool sequences and prompts '
        + 'informs prompt revisions and skill additions.'
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
        <div style={{ flex: 1, minHeight: 0, overflowX: 'auto', overflowY: 'auto' }}>
          <SessionsTable
            rows={rows}
            sortModel={sortModel}
            onSortModelChange={onSortModelChange}
            showLoading={showLoading}
            showEmpty={showEmpty}
            expandedSessionId={expandedSessionId}
            onToggleExpand={onToggleExpand}
            promptTimeline={promptTimeline}
            promptTimelineLoading={promptTimelineLoading}
            promptTimelineError={promptTimelineError}
            windowStartMs={windowStartMs}
            windowEndMs={windowEndMs}
          />
        </div>
        <TablePager
          page={paginationModel.page}
          pageSize={paginationModel.pageSize}
          rowCount={rowCount}
          onPageChange={(nextPage) => onPaginationModelChange({ page: nextPage, pageSize: paginationModel.pageSize })}
          onPageSizeChange={(nextPageSize) => onPaginationModelChange({ page: 0, pageSize: nextPageSize })}
        />
      </Paper>
    </PageLayout>
  );
};

export default SessionsPageView;
