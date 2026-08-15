import { Box, LinearProgress, Paper, Tooltip, Typography } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import type { SxProps, Theme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type { SessionCacheEfficiencyRow } from '../../../../api';
import {
  cacheEfficiencyBand,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
import {
  USD_FORMATTER,
  formatCompact,
  formatRelativeTime,
  formatTimestamp,
} from '../../../../lib/format';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import { cacheEfficiencyBandColor } from '../cacheEfficiencyBandColors';

export interface CacheEfficiencyRankCardProps {
  rows: SessionCacheEfficiencyRow[];
  /** Server-side floor, for the empty-state copy. */
  minimumInputTokensLabel: string;
  /** Row click — opens that session's detail dialog. */
  onSelectSession: (row: SessionCacheEfficiencyRow) => void;
}

/** Width of the inline efficiency track, in px. */
const EFFICIENCY_TRACK_WIDTH = 108;

// Fixed px widths for every column except Session (the colgroup below leaves
// that one's <col> width unset, which is what lets it absorb 100% of whatever
// space the other four don't need under table-layout: fixed — see the
// tableSx/colgroup note).
const LAST_ACTIVITY_COLUMN_WIDTH = 110;
const EFFICIENCY_COLUMN_WIDTH = 190;
const COST_COLUMN_WIDTH = 90;
const CACHED_COLUMN_WIDTH = 130;
/** Floor under the Session column before the overflowX wrapper takes over. */
const SESSION_COLUMN_MINIMUM_WIDTH = 220;
const TABLE_MINIMUM_WIDTH =
  LAST_ACTIVITY_COLUMN_WIDTH + EFFICIENCY_COLUMN_WIDTH + COST_COLUMN_WIDTH
  + CACHED_COLUMN_WIDTH + SESSION_COLUMN_MINIMUM_WIDTH;

// Hand-built table matching the Aurora mockup and the Sessions grid's styling
// (Sora uppercase headers, hairline dividers, soft hover) — same idiom as
// SessionsTable, deliberately not a DataGrid.
//
// table-layout: fixed (+ the colgroup rendered below) rather than the browser's
// default content-driven auto layout: auto layout sizes the Session column to
// whatever the prompt text's rendered width happens to be, which is exactly the
// opposite of "fill the available width" — a short prompt leaves the column
// (and its ellipsis) sized to that short prompt instead of stretching to use
// the room the other, genuinely fixed-content columns don't need. Fixed layout
// makes column widths authoritative from the colgroup alone, independent of
// content, so the one column with no specified width (Session) is handed 100%
// of whatever space remains.
const tableSx: SxProps<Theme> = {
  width: '100%',
  minWidth: TABLE_MINIMUM_WIDTH,
  tableLayout: 'fixed',
  borderCollapse: 'collapse',
  fontFamily: fontFamilies.body,
  '& thead th': {
    typography: 'eyebrowSm',
    color: 'text.secondary',
    textAlign: 'left',
    whiteSpace: 'nowrap',
    padding: '0 12px 11px',
    borderBottom: 1,
    borderColor: 'divider',
  },
  '& thead th.num': { textAlign: 'right' },
  '& tbody td': {
    padding: '13px 12px',
    fontSize: '13.5px',
    whiteSpace: 'nowrap',
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
  },
  '& tbody tr:last-of-type td': { borderBottom: 0 },
  '& tbody tr.data-row': { cursor: 'pointer' },
  '& tbody tr.data-row:hover td': { backgroundColor: 'action.hover' },
  '& td.num': { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '26px 12px',
  },
};

/**
 * Sessions with the lowest share of input-side tokens served from cache, worst
 * first. The bar length IS the efficiency ratio (not a share of some total), so
 * a short bar reads directly as "this session rebuilt most of its context".
 *
 * The ranking, the ratio, and the noise floor all come from the server; this
 * component only formats. Bands come from `lib/cacheEfficiency` so a row here
 * and the same session's Cache eff. cell on the Sessions page always agree.
 *
 * Clicking a row opens the session detail dialog — the row already carries
 * everything that dialog shows, so opening it costs no fetch.
 */
const CacheEfficiencyRankCard = ({
  rows,
  minimumInputTokensLabel,
  onSelectSession,
}: CacheEfficiencyRankCardProps) => {
  const theme = useTheme();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mb: 0.5 }}>
        <Typography variant="subtitle1">Worst cache efficiency</Typography>
        <Tooltip
          title={
            "Share of each session's input-side tokens (input + cache creation + cache read) " +
            'that were served from the prompt cache. Cache reads bill at a fraction of fresh ' +
            'input, so a session sitting well below the rest is usually the cheapest thing to ' +
            'fix: look for idle gaps past the cache TTL, hooks injecting changing content into ' +
            'context, or frequent restarts.'
          }
          arrow
        >
          <InfoOutlinedIcon
            sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }}
          />
        </Tooltip>
      </Box>
      <Typography variant="body2" color="text.secondary">
        Sessions below {minimumInputTokensLabel} input-side tokens are excluded
        — too small to judge.
      </Typography>

      {/* overflowX: 'auto' wrapper — TABLE_MINIMUM_WIDTH (the four fixed
          columns plus the Session column's floor) is wider than the card on
          narrow viewports. Same idiom as SessionsPageView's table wrapper. */}
      <Box sx={{ overflowX: 'auto', mt: 1.75 }}>
        <Box component="table" sx={tableSx}>
          <Box component="colgroup">
            <Box component="col" />
            <Box component="col" sx={{ width: LAST_ACTIVITY_COLUMN_WIDTH }} />
            <Box component="col" sx={{ width: EFFICIENCY_COLUMN_WIDTH }} />
            <Box component="col" sx={{ width: COST_COLUMN_WIDTH }} />
            <Box component="col" sx={{ width: CACHED_COLUMN_WIDTH }} />
          </Box>
          <Box component="thead">
            <Box component="tr">
              <Box component="th">Session</Box>
              <Box component="th">Last activity</Box>
              <Box component="th">Efficiency</Box>
              <Box component="th" className="num">
                Cost
              </Box>
              <Box component="th">Cached</Box>
            </Box>
          </Box>
          <Box component="tbody">
            {rows.length === 0 ? (
              <Box component="tr">
                <Box component="td" className="state" colSpan={5}>
                  No session in this window is large enough to rank.
                </Box>
              </Box>
            ) : (
              rows.map((row) => {
                const band = cacheEfficiencyBand(row.cacheEfficiency);
                const bandColor = cacheEfficiencyBandColor(band, theme);
                const percentage = row.cacheEfficiency * 100;
                return (
                  <Box
                    component="tr"
                    key={row.sessionId}
                    className="data-row"
                    tabIndex={0}
                    onClick={() => onSelectSession(row)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelectSession(row);
                      }
                    }}
                  >
                    {/* The session's first prompt leads (the useful, human-readable
                        identifier); the full session id still prints in full right
                        below it, mono and de-emphasized, rather than the mockup's
                        leading-segment-only id — every other surface that names a
                        session (the prompt-timeline header, the detail dialog, the
                        Sessions deep link) prints it whole, and a truncated id here
                        would be one the user can't match against any of them.
                        `whiteSpace: 'normal'` overrides tableSx's default nowrap on
                        this one cell so the id can wrap onto a second line instead
                        of being clipped when the (responsive, colgroup-driven)
                        column runs narrow — the prompt line keeps its own
                        `nowrap` + ellipsis below for a single-line truncation. */}
                    <Box component="td" sx={{ whiteSpace: 'normal' }}>
                      <Box
                        sx={{
                          display: 'flex',
                          flexDirection: 'column',
                          gap: 0.25,
                          minWidth: 0,
                        }}
                      >
                        {row.firstUserPrompt ? (
                          <Box
                            component="span"
                            title={row.firstUserPrompt}
                            sx={{
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              color: 'text.primary',
                              fontWeight: 600,
                            }}
                          >
                            {row.firstUserPrompt}
                          </Box>
                        ) : (
                          <Box component="span" sx={{ color: 'text.disabled' }}>
                            No prompt captured
                          </Box>
                        )}
                        <Box
                          component="span"
                          sx={{
                            fontFamily: fontFamilies.mono,
                            fontSize: 11,
                            color: 'text.secondary',
                            wordBreak: 'break-all',
                          }}
                        >
                          {row.sessionId}
                        </Box>
                      </Box>
                    </Box>
                    <Box component="td" sx={{ whiteSpace: 'nowrap' }}>
                      {row.endTimestamp ? (
                        <Tooltip
                          title={formatTimestamp(row.endTimestamp)}
                          placement="top"
                          arrow
                        >
                          <Box component="span">
                            {formatRelativeTime(row.endTimestamp)}
                          </Box>
                        </Tooltip>
                      ) : (
                        <Box component="span" sx={{ color: 'text.disabled' }}>
                          —
                        </Box>
                      )}
                    </Box>
                    <Box component="td">
                      <LinearProgress
                        variant="determinate"
                        value={Math.min(100, percentage)}
                        aria-label={`Cache efficiency for session ${row.sessionId}`}
                        sx={{
                          display: 'inline-block',
                          verticalAlign: 'middle',
                          width: EFFICIENCY_TRACK_WIDTH,
                          height: 7,
                          borderRadius: radii.pill,
                          bgcolor: trackColor,
                          '& .MuiLinearProgress-bar': {
                            borderRadius: radii.pill,
                            background: `linear-gradient(90deg, ${alpha(bandColor, 0.55)}, ${bandColor})`,
                          },
                        }}
                      />
                      <Box
                        component="span"
                        sx={{
                          ml: 1.25,
                          color: bandColor,
                          fontWeight: 700,
                          fontVariantNumeric: 'tabular-nums',
                        }}
                      >
                        {formatCacheEfficiency(row.cacheEfficiency)}
                      </Box>
                    </Box>
                    <Box component="td" className="num">
                      {USD_FORMATTER.format(row.costUsd)}
                    </Box>
                    <Box
                      component="td"
                      sx={{ color: 'text.secondary', fontSize: 12 }}
                    >
                      {formatCompact(row.cacheReadTokens)} of{' '}
                      {formatCompact(row.inputSideTokens)}
                    </Box>
                  </Box>
                );
              })
            )}
          </Box>
        </Box>
      </Box>
    </Paper>
  );
};

export default CacheEfficiencyRankCard;
