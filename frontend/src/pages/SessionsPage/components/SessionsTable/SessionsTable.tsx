import { Fragment } from 'react';
import { Box, Tooltip } from '@mui/material';
import { alpha } from '@mui/material/styles';
import type { SxProps, Theme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type {
  SessionPromptRow,
  SessionSummaryRow,
  SessionsSortModel,
  SessionTokenBreakdown,
} from '../../../../api';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import PromptTimelinePanel, { TokenBreakdownTooltip } from '../PromptTimelinePanel';
import {
  USD_FORMATTER,
  USD_PER_MINUTE_FORMATTER,
  cacheEfficiencyRatio,
  formatCacheEfficiency,
  formatDuration,
  formatRelativeTime,
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

// Aurora sync: Terminal and Session columns removed from this listing (per
// design review) — the session id is still available in the expanded prompt
// timeline header if needed.
const COLUMNS: SessionColumn[] = [
  { field: 'startTimestamp', label: 'Started', numeric: false, sortable: true },
  {
    field: 'endTimestamp',
    label: 'Last activity',
    numeric: false,
    sortable: true,
    tip: 'When the session was most recently active (its latest captured turn / session end). Distinct from Started — a long-running or resumed session can have started well before its last activity. Default sort.',
  },
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
    tip: 'Total tokens billed to the session — input, output and cache read/creation summed (claude_code.token.usage joined on session.id). Cache reads typically dominate. Hover the value for the four-way breakdown.',
  },
  {
    field: 'cacheEfficiency',
    label: 'Cache eff.',
    numeric: true,
    sortable: true,
    tip: "Share of the session's input-side tokens (input + cache creation + cache read) served from the prompt cache. Higher is cheaper — cache reads are billed at a fraction of fresh input. Blank when the session recorded no input-side tokens.",
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
];

// Hand-built table styled to match the Aurora mockup exactly (Sora uppercase
// headers, hairline dividers, zebra rows, soft hover) — MUI X DataGrid couldn't
// be themed this far, so the page renders its own table. Sorting + pagination
// stay server-side via the container callbacks.
const tableSx: SxProps<Theme> = {
  width: '100%',
  borderCollapse: 'collapse',
  minWidth: 1080,
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
  '& td.prompt': {
    whiteSpace: 'nowrap',
    maxWidth: 320,
  },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '40px 14px',
  },
  // The expanded prompt-timeline cell has to out-specify '& tbody td' above:
  // that's one class plus two element selectors, so the cell's own single-class
  // sx={{ p: 0 }} loses and the panel gets inset 13px/14px instead of sitting
  // flush with the table edges. Pairs with className="expand-cell" below.
  '& tbody td.expand-cell': {
    padding: 0,
    whiteSpace: 'normal',
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

// Cache efficiency bands: at/above STRONG reads calm-positive (green), below WEAK draws
// the eye (amber) as a low-reuse / more-expensive session; in between stays neutral.
const CACHE_EFFICIENCY_STRONG = 0.85;
const CACHE_EFFICIENCY_WEAK = 0.6;

// Cache-efficiency cell: percentage of a session's input-side tokens served from cache,
// colored by band, with the exact ratio + raw cached/total on hover. Renders "—" when
// the session logged no input-side tokens (ratio undefined — the same rows the backend
// sorts NULLS LAST on the cacheEfficiency column).
const CacheEfficiencyCell = ({
  breakdown,
}: {
  breakdown: SessionTokenBreakdown | null;
}) => {
  const ratio = cacheEfficiencyRatio(breakdown);
  if (ratio == null || breakdown == null) {
    return (
      <Box component="span" sx={{ color: 'text.disabled' }}>
        —
      </Box>
    );
  }
  const color =
    ratio >= CACHE_EFFICIENCY_STRONG
      ? 'success.main'
      : ratio >= CACHE_EFFICIENCY_WEAK
        ? 'text.primary'
        : 'warning.main';
  const inputSideTokens =
    breakdown.input + breakdown.cacheCreation + breakdown.cacheRead;
  return (
    <Tooltip
      title={`${(ratio * 100).toFixed(1)}% of input-side tokens from cache (${formatTokens(breakdown.cacheRead)} of ${formatTokens(inputSideTokens)})`}
      placement="top"
      arrow
    >
      <Box
        component="span"
        sx={{
          color,
          fontWeight: 600,
          fontVariantNumeric: 'tabular-nums',
          cursor: 'help',
          borderBottom: (t) => `1px dotted ${t.palette.text.disabled}`,
          pb: '1px',
        }}
      >
        {formatCacheEfficiency(ratio)}
      </Box>
    </Tooltip>
  );
};

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

// Expand affordance in the Started cell — the only cue that a row opens its prompt
// timeline on click. Points right when closed, rotates to point down and takes the
// primary color when open, reusing the rotation treatment SortArrow already uses.
const ExpandCaret = ({ expanded }: { expanded: boolean }) => (
  <Box
    component="svg"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={2.5}
    sx={{
      display: 'inline-block',
      width: 12,
      height: 12,
      mr: '3px',
      verticalAlign: '-1px',
      flexShrink: 0,
      color: expanded ? 'primary.main' : 'text.disabled',
      transform: expanded ? 'rotate(90deg)' : 'none',
      transition: 'transform .15s',
    }}
  >
    <path d="M9 6l6 6-6 6" />
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
  expandedSessionId: string | null;
  onToggleExpand: (sessionId: string) => void;
  promptTimeline: SessionPromptRow[] | null;
  promptTimelineLoading: boolean;
  promptTimelineError: Error | null;
  // Active dashboard window (epoch ms), forwarded to PromptTimelinePanel so it
  // can dim turns that fall outside the selected window (the prompts endpoint
  // returns the whole session, not just the windowed slice).
  windowStartMs?: number;
  windowEndMs?: number;
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
  windowStartMs,
  windowEndMs,
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
                <Box component="td">
                  <ExpandCaret expanded={isExpanded} />
                  {formatTimestamp(row.startTimestamp)}
                </Box>
                <Box component="td" sx={{ whiteSpace: 'nowrap' }}>
                  <Tooltip title={formatTimestamp(row.endTimestamp)} placement="top" arrow>
                    <Box component="span">{formatRelativeTime(row.endTimestamp)}</Box>
                  </Tooltip>
                </Box>
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
                  {row.tokenBreakdown ? (
                    <TokenBreakdownTooltip tokens={row.tokenBreakdown}>
                      <Box
                        component="span"
                        sx={{
                          cursor: 'help',
                          borderBottom: (t) => `1px dotted ${t.palette.text.disabled}`,
                          pb: '1px',
                        }}
                      >
                        {formatTokens(row.tokens)}
                      </Box>
                    </TokenBreakdownTooltip>
                  ) : (
                    formatTokens(row.tokens)
                  )}
                </Box>
                <Box component="td" className="num">
                  <CacheEfficiencyCell breakdown={row.tokenBreakdown} />
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
              </Box>
              {isExpanded ? (
                <Box component="tr">
                  <Box
                    component="td"
                    className="expand-cell"
                    colSpan={COLUMNS.length}
                    sx={{ p: 0 }}
                  >
                    <PromptTimelinePanel
                      sessionId={row.sessionId}
                      prompts={promptTimeline}
                      loading={promptTimelineLoading}
                      error={promptTimelineError}
                      windowStartMs={windowStartMs}
                      windowEndMs={windowEndMs}
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
