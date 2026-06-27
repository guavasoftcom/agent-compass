import { Box, Paper, Tooltip, alpha } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import PageLayout from '../../components/PageLayout';
import PageActions from '../../components/PageActions';
import StatCard from '../../components/StatCard';
import SegmentedToggle from '../../components/SegmentedToggle';
import type { WindowOption } from '../../constants';
import type { SessionSummaryRow, SessionsSortModel, WindowSelection } from '../../api';

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
}

const PAGE_SIZES = [25, 50, 100] as const;

const USD_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const USD_PER_MINUTE_FORMATTER = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
});

const formatDuration = (seconds: number): string => {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '—';
  }
  if (seconds < 60) {
    return `${Math.round(seconds)}s`;
  }
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    const remainder = Math.round(seconds % 60);
    return remainder === 0 ? `${minutes}m` : `${minutes}m ${remainder}s`;
  }
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.round((seconds % 3600) / 60);
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
};

// Compact token formatter (raw int → "5.4M" / "820K"); matches the Cost column's
// raw-number / client-format convention used across the Sessions page.
const formatTokens = (n: number): string => {
  if (!Number.isFinite(n) || n <= 0) {
    return '—';
  }
  if (n >= 1e6) {
    return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
  }
  if (n >= 1e3) {
    return `${Math.round(n / 1e3)}K`;
  }
  return `${Math.round(n)}`;
};

const formatTimestamp = (value: string): string =>
  value ? new Date(value).toLocaleString() : '';

// ---- column model -----------------------------------------------------------
interface SessionColumn {
  field: string;
  label: string;
  numeric: boolean;
  sortable: boolean;
  tip?: string;
}

const COLUMNS: SessionColumn[] = [
  { field: 'startTimestamp', label: 'Started', numeric: false, sortable: true },
  { field: 'costUsd', label: 'Cost', numeric: true, sortable: true },
  {
    field: 'tokens',
    label: 'Tokens',
    numeric: true,
    sortable: true,
    tip: 'Total tokens billed to the session — input, output and cache read/creation summed (claude_code.token.usage joined on session.id). Cache reads typically dominate.',
  },
  { field: 'toolCallCount', label: 'Tool calls', numeric: true, sortable: false },
  {
    field: 'denialCount',
    label: 'Denials',
    numeric: true,
    sortable: false,
    tip: 'Tool calls rejected by the user or blocked by a settings.json rule during this session.',
  },
  { field: 'activeTimeSeconds', label: 'Active time', numeric: true, sortable: true },
  {
    field: 'costPerActiveMinuteUsd',
    label: '$/active min',
    numeric: true,
    sortable: true,
    tip: 'Burn rate: cost divided by active time, per minute. Independent of how long the session sat idle — surfaces sessions that spend more per minute of actual work.',
  },
  {
    field: 'terminalType',
    label: 'Terminal',
    numeric: false,
    sortable: false,
    tip: 'Whether the session ran in an interactive TTY or a non-interactive (piped / CI / scripted) context — from the terminal.type attribute.',
  },
  { field: 'sessionId', label: 'Session', numeric: false, sortable: false },
];

// Hand-built table styled to match the Aurora mockup exactly (Sora uppercase
// headers, hairline dividers, zebra rows, soft hover) — MUI X DataGrid couldn't
// be themed this far, so the page renders its own table. Sorting + pagination
// stay server-side via the container callbacks.
const tableSx: SxProps<Theme> = {
  width: '100%',
  borderCollapse: 'collapse',
  minWidth: 980,
  fontFamily: "'Space Grotesk', sans-serif",
  '& thead th': {
    position: 'sticky',
    top: 0,
    zIndex: 1,
    backgroundColor: 'background.paper',
    fontFamily: "'Sora', sans-serif",
    fontSize: '11px',
    fontWeight: 700,
    letterSpacing: '0.6px',
    textTransform: 'uppercase',
    color: 'text.secondary',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    padding: '15px 14px 13px',
    borderBottom: 1,
    borderColor: 'divider',
  },
  '& thead th.num': { textAlign: 'right' },
  '& thead th.sortable': { cursor: 'pointer', userSelect: 'none' },
  '& thead th.sortable:hover': { color: 'text.primary' },
  '& thead th.active': { color: 'primary.main' },
  '& thead th .hd': { display: 'inline-flex', alignItems: 'center', gap: 0.6 },
  '& tbody td': {
    padding: '13px 14px',
    fontSize: '13.5px',
    whiteSpace: 'nowrap',
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
  },
  '& tbody tr:last-of-type td': { borderBottom: 0 },
  '& tbody tr:nth-of-type(even) td': {
    backgroundColor: (t) => alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.04 : 0.022),
  },
  '& tbody tr:hover td': { backgroundColor: 'action.hover' },
  '& td.num': { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  '& td.cost': { fontFamily: "'Sora', sans-serif", fontWeight: 700, fontSize: '14px' },
  '& td.tokens': { fontWeight: 600 },
  '& td.session': {
    fontFamily: 'monospace',
    fontSize: '12.5px',
    letterSpacing: '-0.2px',
    color: 'text.secondary',
  },
  '& td.state': { textAlign: 'center', color: 'text.secondary', padding: '40px 14px' },
};

// Right-aligned denial count rendered as an Aurora chip: 0 dims out, 1–3 amber, 4+ red.
const DenialChip = ({ count }: { count: number }) => {
  if (!count) {
    return (
      <Box component="span" sx={{ color: 'text.disabled', fontVariantNumeric: 'tabular-nums' }}>
        0
      </Box>
    );
  }
  const hot = count >= 4;
  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        minWidth: 24,
        height: 22,
        px: 0.75,
        borderRadius: 1.25,
        fontWeight: 700,
        fontSize: 13,
        fontVariantNumeric: 'tabular-nums',
        color: hot ? 'error.main' : 'warning.main',
        bgcolor: (t) => alpha(hot ? t.palette.error.main : t.palette.warning.main, 0.16),
      }}
    >
      {count}
    </Box>
  );
};

// interactive / non-interactive pill for the Terminal column.
const TerminalBadge = ({ interactive }: { interactive: boolean }) => (
  <Box
    component="span"
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 0.75,
      height: 22,
      px: 1,
      borderRadius: 1.5,
      fontFamily: "'Sora', sans-serif",
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.2,
      color: interactive ? 'primary.main' : 'text.secondary',
      bgcolor: (t) =>
        interactive
          ? alpha(t.palette.primary.main, 0.12)
          : alpha(t.palette.text.primary, t.palette.mode === 'dark' ? 0.08 : 0.06),
    }}
  >
    <Box
      component="span"
      sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: interactive ? 'primary.main' : 'text.disabled' }}
    />
    {interactive ? 'Interactive' : 'Non-interactive'}
  </Box>
);

// Sort caret — points up for asc, flips for desc; only rendered on the active column.
const SortArrow = ({ direction }: { direction: 'asc' | 'desc' }) => (
  <Box
    component="svg"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={3}
    sx={{
      width: 13,
      height: 13,
      color: 'primary.main',
      flexShrink: 0,
      transform: direction === 'desc' ? 'rotate(180deg)' : 'none',
    }}
  >
    <path d="M7 14l5-5 5 5" />
  </Box>
);

// Simple line sparkline (new sessions per bucket) for the Total-sessions card.
const SessionsSparkline = ({ values }: { values: number[] }) => {
  if (values.length < 2) {
    return null;
  }
  const w = 120;
  const h = 36;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const x = (i: number) => (i * w) / (values.length - 1);
  const y = (v: number) => h - 2 - ((v - min) / range) * (h - 6);
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
  const area = `${line}L${w},${h}L0,${h}Z`;
  return (
    <Box component="svg" viewBox={`0 0 ${w} ${h}`} sx={{ display: 'block', width: '100%', height: 36, mt: 0.5 }} preserveAspectRatio="none">
      <Box component="path" d={area} sx={{ fill: (t) => alpha(t.palette.primary.main, 0.13) }} />
      <Box
        component="path"
        d={line}
        sx={{ fill: 'none', stroke: (t) => t.palette.primary.main, strokeWidth: 1.7, strokeLinejoin: 'round', strokeLinecap: 'round' }}
        vectorEffect="non-scaling-stroke"
      />
    </Box>
  );
};

// Square icon button for the pager prev/next chevrons.
const pagerNavSx: SxProps<Theme> = {
  width: 32,
  height: 32,
  display: 'grid',
  placeItems: 'center',
  borderRadius: 1.25,
  border: 1,
  borderColor: 'divider',
  bgcolor: 'background.paper',
  color: 'text.secondary',
  cursor: 'pointer',
  transition: 'all .12s',
  '&:hover:not(:disabled)': { color: 'primary.main', borderColor: 'primary.main' },
  '&:disabled': { opacity: 0.4, cursor: 'default' },
  '& svg': { width: 15, height: 15 },
};

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
}: SessionsPageViewProps) => {
  const handleSort = (field: string) => {
    if (field === sortModel.field) {
      onSortModelChange({ field, direction: sortModel.direction === 'asc' ? 'desc' : 'asc' });
    } else {
      onSortModelChange({ field, direction: 'desc' });
    }
  };

  const { page, pageSize } = paginationModel;
  const total = rowCount;
  const startIndex = page * pageSize;
  const maxPage = Math.max(0, Math.ceil(total / pageSize) - 1);
  const rangeLabel =
    total === 0 ? '0 of 0' : `${startIndex + 1}–${Math.min(startIndex + pageSize, total)} of ${total}`;

  const showLoading = isLoading && rows.length === 0;
  const showEmpty = !isLoading && rows.length === 0;

  // P95 ⇒ ~5% of sessions exceed it; surfaced as the card caption.
  const sessionsAboveP95 = Math.round(kpis.totalSessions * 0.05);

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
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
          gap: 2,
        }}
      >
        <StatCard label="Total sessions" value={kpis.totalSessions.toLocaleString()}>
          <SessionsSparkline values={kpis.sessionsTrend} />
        </StatCard>
        <StatCard
          label="Median cost / session"
          value={USD_FORMATTER.format(kpis.medianCostUsd)}
          sub="half of sessions cost less"
          accent
        />
        <StatCard
          label={
            <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
              P95 cost / session
              <Tooltip
                title="95th percentile: 1 in 20 sessions cost more than this. Tracks the expensive tail rather than the typical session (the median)."
                placement="top"
                arrow
              >
                <InfoOutlinedIcon sx={{ fontSize: 15, color: 'text.disabled', cursor: 'help' }} />
              </Tooltip>
            </Box>
          }
          value={USD_FORMATTER.format(kpis.p95CostUsd)}
          sub={
            <>
              <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
                {sessionsAboveP95}
              </Box>
              {' sessions above this'}
            </>
          }
        />
        <StatCard
          label={
            <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
              Median $/active min
              <Tooltip
                title="Burn rate: cost divided by active time, per minute. Independent of idle — surfaces sessions that spend more per minute of real work."
                placement="top"
                arrow
              >
                <InfoOutlinedIcon sx={{ fontSize: 15, color: 'text.disabled', cursor: 'help' }} />
              </Tooltip>
            </Box>
          }
          value={USD_PER_MINUTE_FORMATTER.format(kpis.medianCostPerActiveMinuteUsd)}
          sub="cost per minute of work"
        />
      </Box>

      <Paper
        variant="outlined"
        sx={{
          mt: 2,
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
          height: 'calc(100vh - 320px)',
          minHeight: 420,
        }}
      >
        <Box sx={{ flex: 1, minHeight: 0, overflowX: 'auto', overflowY: 'auto' }}>
          <Box component="table" sx={tableSx}>
            <Box component="thead">
              <Box component="tr">
                {COLUMNS.map((col) => {
                  const active = col.sortable && sortModel.field === col.field;
                  const classNames = [
                    col.numeric ? 'num' : '',
                    col.sortable ? 'sortable' : '',
                    active ? 'active' : '',
                  ]
                    .filter(Boolean)
                    .join(' ');
                  return (
                    <Box
                      key={col.field}
                      component="th"
                      className={classNames}
                      onClick={col.sortable ? () => handleSort(col.field) : undefined}
                    >
                      <Box component="span" className="hd">
                        {active ? <SortArrow direction={sortModel.direction} /> : null}
                        <span>{col.label}</span>
                        {col.tip ? (
                          <Tooltip title={col.tip} placement="top" arrow>
                            <InfoOutlinedIcon sx={{ fontSize: 16, color: 'text.disabled', cursor: 'help' }} />
                          </Tooltip>
                        ) : null}
                      </Box>
                    </Box>
                  );
                })}
              </Box>
            </Box>
            <Box component="tbody">
              {showLoading ? (
                <Box component="tr">
                  <Box component="td" className="state" colSpan={COLUMNS.length}>
                    Loading sessions…
                  </Box>
                </Box>
              ) : null}
              {showEmpty ? (
                <Box component="tr">
                  <Box component="td" className="state" colSpan={COLUMNS.length}>
                    No sessions in this window.
                  </Box>
                </Box>
              ) : null}
              {rows.map((row) => {
                const burn =
                  row.activeTimeSeconds > 0 ? (row.costUsd / row.activeTimeSeconds) * 60 : null;
                return (
                  <Box component="tr" key={row.sessionId}>
                    <Box component="td">{formatTimestamp(row.startTimestamp)}</Box>
                    <Box component="td" className="num cost">{USD_FORMATTER.format(row.costUsd)}</Box>
                    <Box component="td" className="num tokens">{formatTokens(row.tokens)}</Box>
                    <Box component="td" className="num">{row.toolCallCount.toLocaleString()}</Box>
                    <Box component="td" className="num"><DenialChip count={row.denialCount} /></Box>
                    <Box component="td" className="num">{formatDuration(row.activeTimeSeconds)}</Box>
                    <Box component="td" className="num">
                      {burn == null ? '—' : USD_PER_MINUTE_FORMATTER.format(burn)}
                    </Box>
                    <Box component="td">
                      <TerminalBadge interactive={row.terminalType === 'interactive'} />
                    </Box>
                    <Box component="td" className="session">{row.sessionId}</Box>
                  </Box>
                );
              })}
            </Box>
          </Box>
        </Box>

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            flexWrap: 'wrap',
            gap: 2.25,
            px: 2,
            py: 1.5,
            flexShrink: 0,
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, fontSize: 12.5, color: 'text.secondary' }}>
            Rows per page
            <SegmentedToggle
              options={PAGE_SIZES.map((size) => ({ value: size, label: size }))}
              value={pageSize}
              onChange={(size) => onPaginationModelChange({ page: 0, pageSize: size })}
            />
          </Box>
          <Box sx={{ fontSize: 12.5, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
            <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
              {rangeLabel}
            </Box>
          </Box>
          <Box sx={{ display: 'flex', gap: 0.75 }}>
            <Box
              component="button"
              disabled={page <= 0}
              onClick={() => onPaginationModelChange({ page: page - 1, pageSize })}
              sx={pagerNavSx}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <path d="M15 6l-6 6 6 6" />
              </svg>
            </Box>
            <Box
              component="button"
              disabled={page >= maxPage}
              onClick={() => onPaginationModelChange({ page: page + 1, pageSize })}
              sx={pagerNavSx}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}>
                <path d="M9 6l6 6-6 6" />
              </svg>
            </Box>
          </Box>
        </Box>
      </Paper>
    </PageLayout>
  );
};

export default SessionsPageView;
