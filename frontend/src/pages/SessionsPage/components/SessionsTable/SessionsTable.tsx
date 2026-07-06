import { Fragment, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { Box, CircularProgress, Tooltip, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import type { SxProps, Theme } from '@mui/material/styles';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type {
  SessionPromptRow,
  SessionSummaryRow,
  SessionsSortModel,
} from '../../../../api';
import { AttributeList } from '../../../../components/AttributeList';
import {
  AttributeValue,
  type ValueDialogState,
} from '../../../../components/AttributeList/AttributeValue';
import { ExpandedValueDialog } from '../../../../components/AttributeList/ExpandedValueDialog';
import { neutralColors } from '../../../../theme/colors';
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
  {
    field: 'firstUserPrompt',
    label: 'Prompt',
    numeric: false,
    sortable: false,
    tip: "The session's first meaningful user prompt. Blank when prompt capture is disabled (OTEL_LOG_USER_PROMPTS) — the count can still be nonzero.",
  },
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
  minWidth: 1260,
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
  '& tbody tr.data-row:hover td': { backgroundColor: 'action.hover' },
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
  '& td.prompt': {
    whiteSpace: 'nowrap',
    maxWidth: 320,
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

// Small dimmed "+N" pill after the truncated prompt text: N additional prompts
// beyond the one shown. Muted/neutral tone (unlike DenialChip's severity tint)
// since a high prompt count isn't itself a problem signal.
const PromptCountPill = ({ additionalCount }: { additionalCount: number }) => (
  <Box
    component="span"
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      minWidth: 20,
      height: 18,
      px: 0.6,
      ml: 0.75,
      flexShrink: 0,
      borderRadius: radii.pill,
      fontSize: 10.5,
      fontWeight: 600,
      fontVariantNumeric: 'tabular-nums',
      color: 'text.secondary',
      bgcolor: (t) =>
        alpha(t.palette.text.primary, t.palette.mode === 'dark' ? 0.1 : 0.07),
    }}
  >
    {`+${additionalCount}`}
  </Box>
);

// Prompt cell: single ellipsized line of the (already-truncated) first prompt,
// full text in a native title tooltip, plus a "+N" pill when the session has
// more prompts than the one shown. `firstUserPrompt` can be null even when
// `userPromptCount` is nonzero (prompt capture disabled server-side via
// OTEL_LOG_USER_PROMPTS) — the pill must render independently of the text, so
// the em-dash and the pill share one wrapper rather than the null check
// early-returning before the pill logic runs.
const PromptCell = ({
  prompt,
  count,
}: {
  prompt: string | null;
  count: number;
}) => (
  <Box
    component="span"
    sx={{
      display: 'inline-flex',
      alignItems: 'center',
      maxWidth: '100%',
      minWidth: 0,
    }}
  >
    {prompt == null ? (
      <Box component="span" sx={{ color: 'text.disabled' }}>
        —
      </Box>
    ) : (
      <Box
        component="span"
        title={prompt}
        sx={{
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
          minWidth: 0,
          maxWidth: 300,
          color: 'text.primary',
        }}
      >
        {prompt}
      </Box>
    )}
    {count > 1 ? <PromptCountPill additionalCount={count - 1} /> : null}
  </Box>
);

// Expansion panel: the session's full prompt timeline, rendered beneath the
// clicked row. Recessed/inset surface to read as a nested panel rather than
// another table row — same alpha-over-neutral treatment TraceSummaryInline
// uses for the trace table's expanded row. Long prompts reuse the shared
// AttributeValue "View more" → ExpandedValueDialog machinery from
// components/AttributeList (same pattern as LogTable) rather than rendering
// full text inline — prompts are plain text, so the dialog's JSON-repair path
// simply never triggers (tryParseJson bails unless the text starts with `{`
// or `[`) and it falls back to the raw pre-wrapped string. `promptRow.prompt`
// can itself be null (pre-capture event) — those rows are kept, not filtered,
// but render a dimmed italic placeholder instead of handing null to
// AttributeValue (which would stringify it to the literal text "null"), and
// skip the View-more/dialog affordance since there's no text to expand.
const PromptTimelinePanel = ({
  prompts,
  loading,
  error,
}: {
  prompts: SessionPromptRow[] | null;
  loading: boolean;
  error: Error | null;
}) => {
  const [expandedValue, setExpandedValue] = useState<ValueDialogState | null>(
    null,
  );

  return (
    <Box
      sx={{
        px: 2,
        py: 1.75,
        maxHeight: 320,
        overflowY: 'auto',
        bgcolor: (t) =>
          t.palette.mode === 'dark'
            ? alpha(neutralColors.white, 0.03)
            : alpha(neutralColors.inkLight, 0.025),
      }}
    >
      {loading ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            py: 1,
            color: 'text.secondary',
          }}
        >
          <CircularProgress size={14} thickness={5} />
          <Typography sx={{ fontSize: 12.5 }}>Loading prompts…</Typography>
        </Box>
      ) : null}
      {!loading && error ? (
        <Typography sx={{ fontSize: 12.5, color: 'error.main' }}>
          {`Failed to load prompts: ${error.message}`}
        </Typography>
      ) : null}
      {!loading && !error && (prompts == null || prompts.length === 0) ? (
        <Typography sx={{ fontSize: 12.5, color: 'text.disabled' }}>
          No prompts captured for this session.
        </Typography>
      ) : null}
      {!loading && !error && prompts != null && prompts.length > 0 ? (
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
          {prompts.map((promptRow, index) => (
            <Box
              key={`${promptRow.timestamp}-${index}`}
              sx={{ display: 'flex', flexDirection: 'column', gap: 0.35 }}
            >
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Box
                  component="span"
                  sx={{
                    typography: 'mono',
                    fontSize: 11,
                    color: 'text.disabled',
                  }}
                >
                  {formatTimestamp(promptRow.timestamp)}
                </Box>
                {promptRow.traceId ? (
                  <Box
                    component={RouterLink}
                    to={`/traces/${promptRow.traceId}`}
                    sx={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 0.35,
                      fontSize: 11,
                      fontWeight: 600,
                      color: 'primary.main',
                      textDecoration: 'none',
                      '&:hover': { textDecoration: 'underline' },
                    }}
                  >
                    View trace
                    <ArrowForwardIcon sx={{ fontSize: 12 }} />
                  </Box>
                ) : null}
              </Box>
              <Box sx={{ fontSize: 13, color: 'text.primary' }}>
                {promptRow.prompt == null ? (
                  <Box
                    component="span"
                    sx={{ fontStyle: 'italic', color: 'text.disabled' }}
                  >
                    (prompt text not captured)
                  </Box>
                ) : (
                  <AttributeValue
                    attrKey={formatTimestamp(promptRow.timestamp)}
                    value={promptRow.prompt}
                    truncate
                    inlineExpand={false}
                    onExpand={setExpandedValue}
                  />
                )}
              </Box>
            </Box>
          ))}
        </Box>
      ) : null}
      <ExpandedValueDialog
        state={expandedValue}
        onClose={() => setExpandedValue(null)}
        renderAttributeList={(attrs) => (
          <AttributeList attributes={attrs} truncate inlineExpand />
        )}
      />
    </Box>
  );
};

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
  expandedSessionId: string | null;
  onToggleExpand: (sessionId: string) => void;
  promptTimeline: SessionPromptRow[] | null;
  promptTimelineLoading: boolean;
  promptTimelineError: Error | null;
}

const SessionsTable = ({
  rows,
  sortModel,
  onSortModelChange,
  showLoading,
  showEmpty,
  expandedSessionId,
  onToggleExpand,
  promptTimeline,
  promptTimelineLoading,
  promptTimelineError,
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
        {rows.map((row, index) => {
          const burn =
            row.activeTimeSeconds > 0
              ? (row.costUsd / row.activeTimeSeconds) * 60
              : null;
          const isExpanded = expandedSessionId === row.sessionId;
          return (
            <Fragment key={row.sessionId}>
              <Box
                component="tr"
                className="data-row"
                onClick={() => onToggleExpand(row.sessionId)}
                sx={{
                  cursor: 'pointer',
                  '& > td': {
                    backgroundColor: isExpanded
                      ? 'action.hover'
                      : index % 2
                        ? (t) =>
                            alpha(
                              t.palette.primary.main,
                              t.palette.mode === 'dark' ? 0.04 : 0.022,
                            )
                        : 'transparent',
                  },
                }}
              >
                <Box component="td">{formatTimestamp(row.startTimestamp)}</Box>
                <Box component="td" className="prompt">
                  <PromptCell
                    prompt={row.firstUserPrompt}
                    count={row.userPromptCount}
                  />
                </Box>
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
              {isExpanded ? (
                <Box component="tr">
                  <Box component="td" colSpan={COLUMNS.length} sx={{ p: 0 }}>
                    <PromptTimelinePanel
                      prompts={promptTimeline}
                      loading={promptTimelineLoading}
                      error={promptTimelineError}
                    />
                  </Box>
                </Box>
              ) : null}
            </Fragment>
          );
        })}
      </Box>
    </Box>
  );
};

export default SessionsTable;
