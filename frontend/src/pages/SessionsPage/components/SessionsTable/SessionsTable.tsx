import { Box, Tooltip } from '@mui/material';
import { alpha } from '@mui/material/styles';
import type { SxProps, Theme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type { SessionSummaryRow, SessionsSortModel } from '../../../../api';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import {
  USD_FORMATTER,
  USD_PER_MINUTE_FORMATTER,
  formatDuration,
  formatTokens,
  formatTimestamp,
} from '../sessionsFormat';

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
  {
    field: 'toolCallCount',
    label: 'Tool calls',
    numeric: true,
    sortable: false,
  },
  {
    field: 'denialCount',
    label: 'Denials',
    numeric: true,
    sortable: false,
    tip: 'Tool calls rejected by the user or blocked by a settings.json rule during this session.',
  },
  {
    field: 'activeTimeSeconds',
    label: 'Active time',
    numeric: true,
    sortable: true,
  },
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
  fontFamily: fontFamilies.body,
  '& thead th': {
    typography: 'eyebrowSm',
    position: 'sticky',
    top: 0,
    zIndex: 1,
    backgroundColor: 'background.paper',
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
    backgroundColor: (t) =>
      alpha(t.palette.primary.main, t.palette.mode === 'dark' ? 0.04 : 0.022),
  },
  '& tbody tr:hover td': { backgroundColor: 'action.hover' },
  '& td.num': { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  '& td.cost': {
    fontFamily: fontFamilies.display,
    fontWeight: 700,
    fontSize: '14px',
  },
  '& td.tokens': { fontWeight: 600 },
  '& td.session': {
    typography: 'mono',
    fontSize: '12.5px',
    letterSpacing: '-0.2px',
    color: 'text.secondary',
  },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '40px 14px',
  },
};

// Right-aligned denial count rendered as an Aurora chip: 0 dims out, 1–3 amber, 4+ red.
const DenialChip = ({ count }: { count: number }) => {
  if (!count) {
    return (
      <Box
        component="span"
        sx={{ color: 'text.disabled', fontVariantNumeric: 'tabular-nums' }}
      >
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
        borderRadius: radii.sm,
        fontWeight: 700,
        fontSize: 13,
        fontVariantNumeric: 'tabular-nums',
        color: hot ? 'error.main' : 'warning.main',
        bgcolor: (t) =>
          alpha(hot ? t.palette.error.main : t.palette.warning.main, 0.16),
      }}
    >
      {count}
    </Box>
  );
};

// Interactive / non-interactive pill for the Terminal column.
const TerminalBadge = ({ interactive }: { interactive: boolean }) => (
  <Box
    component="span"
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 0.75,
      height: 22,
      px: 1,
      borderRadius: radii.lg,
      fontFamily: fontFamilies.display,
      fontSize: 11,
      fontWeight: 600,
      letterSpacing: 0.2,
      color: interactive ? 'primary.main' : 'text.secondary',
      bgcolor: (t) =>
        interactive
          ? alpha(t.palette.primary.main, 0.12)
          : alpha(
              t.palette.text.primary,
              t.palette.mode === 'dark' ? 0.08 : 0.06,
            ),
    }}
  >
    <Box
      component="span"
      sx={{
        width: 6,
        height: 6,
        borderRadius: '50%',
        bgcolor: interactive ? 'primary.main' : 'text.disabled',
      }}
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

interface SessionsTableProps {
  rows: SessionSummaryRow[];
  sortModel: SessionsSortModel;
  onSortModelChange: (next: SessionsSortModel) => void;
  showLoading: boolean;
  showEmpty: boolean;
}

const SessionsTable = ({
  rows,
  sortModel,
  onSortModelChange,
  showLoading,
  showEmpty,
}: SessionsTableProps) => {
  const handleSort = (field: string) => {
    if (field === sortModel.field) {
      onSortModelChange({
        field,
        direction: sortModel.direction === 'asc' ? 'desc' : 'asc',
      });
    } else {
      onSortModelChange({ field, direction: 'desc' });
    }
  };

  return (
    <Box component="table" sx={tableSx}>
      <Box component="thead">
        <Box component="tr">
          {COLUMNS.map((column) => {
            const active = column.sortable && sortModel.field === column.field;
            const classNames = [
              column.numeric ? 'num' : '',
              column.sortable ? 'sortable' : '',
              active ? 'active' : '',
            ]
              .filter(Boolean)
              .join(' ');
            return (
              <Box
                key={column.field}
                component="th"
                className={classNames}
                onClick={
                  column.sortable ? () => handleSort(column.field) : undefined
                }
              >
                <Box component="span" className="hd">
                  {active ? (
                    <SortArrow direction={sortModel.direction} />
                  ) : null}
                  <span>{column.label}</span>
                  {column.tip ? (
                    <Tooltip title={column.tip} placement="top" arrow>
                      <InfoOutlinedIcon
                        sx={{
                          fontSize: 16,
                          color: 'text.disabled',
                          cursor: 'help',
                        }}
                      />
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
            row.activeTimeSeconds > 0
              ? (row.costUsd / row.activeTimeSeconds) * 60
              : null;
          return (
            <Box component="tr" key={row.sessionId}>
              <Box component="td">{formatTimestamp(row.startTimestamp)}</Box>
              <Box component="td" className="num cost">
                {USD_FORMATTER.format(row.costUsd)}
              </Box>
              <Box component="td" className="num tokens">
                {formatTokens(row.tokens)}
              </Box>
              <Box component="td" className="num">
                {row.toolCallCount.toLocaleString()}
              </Box>
              <Box component="td" className="num">
                <DenialChip count={row.denialCount} />
              </Box>
              <Box component="td" className="num">
                {formatDuration(row.activeTimeSeconds)}
              </Box>
              <Box component="td" className="num">
                {burn == null ? '—' : USD_PER_MINUTE_FORMATTER.format(burn)}
              </Box>
              <Box component="td">
                <TerminalBadge
                  interactive={row.terminalType === 'interactive'}
                />
              </Box>
              <Box component="td" className="session">
                {row.sessionId}
              </Box>
            </Box>
          );
        })}
      </Box>
    </Box>
  );
};

export default SessionsTable;
