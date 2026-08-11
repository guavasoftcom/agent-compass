import { Box, LinearProgress, Paper, Tooltip, Typography } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import type { SxProps, Theme } from '@mui/material/styles';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import type { SessionCacheEfficiencyRow } from '../../../../api';
import {
  cacheEfficiencyBand,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
import { formatCompact, USD_FORMATTER } from '../../../../lib/format';
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

// Hand-built table matching the Aurora mockup and the Sessions grid's styling
// (Sora uppercase headers, hairline dividers, soft hover) — same idiom as
// SessionsTable, deliberately not a DataGrid.
const tableSx: SxProps<Theme> = {
  width: '100%',
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
            'Share of each session\'s input-side tokens (input + cache creation + cache read) '
            + 'that were served from the prompt cache. Cache reads bill at a fraction of fresh '
            + 'input, so a session sitting well below the rest is usually the cheapest thing to '
            + 'fix: look for idle gaps past the cache TTL, hooks injecting changing content into '
            + 'context, or frequent restarts.'
          }
          arrow
        >
          <InfoOutlinedIcon sx={{ fontSize: 18, color: 'text.secondary', cursor: 'help' }} />
        </Tooltip>
      </Box>
      <Typography variant="body2" color="text.secondary">
        Sessions below {minimumInputTokensLabel} input-side tokens are excluded — too small to judge.
      </Typography>

      {/* overflowX: 'auto' wrapper — the nowrap columns (full-uuid session id +
          the fixed-width efficiency track) add up to ~700px, wider than the
          card on narrow viewports. Same idiom as SessionsPageView's table
          wrapper. */}
      <Box sx={{ overflowX: 'auto', mt: 1.75 }}>
        <Box component="table" sx={tableSx}>
          <Box component="thead">
            <Box component="tr">
              <Box component="th">Session</Box>
              <Box component="th">Efficiency</Box>
              <Box component="th" className="num">Cost</Box>
              <Box component="th">Cached</Box>
            </Box>
          </Box>
          <Box component="tbody">
            {rows.length === 0 ? (
              <Box component="tr">
                <Box component="td" className="state" colSpan={4}>
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
                    {/* Full session id, not the mockup's leading segment: every
                        other surface that names a session (the prompt-timeline
                        header, the Sessions deep link) prints it whole, and a
                        truncated id here would be one the user can't match. */}
                    <Box component="td">
                      <Box
                        component="span"
                        sx={{
                          fontFamily: fontFamilies.mono,
                          fontWeight: 600,
                          color: 'primary.main',
                        }}
                      >
                        {row.sessionId}
                      </Box>
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
                    <Box component="td" sx={{ color: 'text.secondary', fontSize: 12 }}>
                      {formatCompact(row.cacheReadTokens)} of {formatCompact(row.inputSideTokens)}
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
