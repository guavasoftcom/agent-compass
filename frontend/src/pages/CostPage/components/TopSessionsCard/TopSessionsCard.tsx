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
import { Box, LinearProgress, Paper, Typography } from '@mui/material';
import { alpha, useTheme } from '@mui/material/styles';
import type { CostSessionShare } from '../../../../api';
import { USD_FORMATTER } from '../../../../lib/format';
import { fontFamilies } from '../../../../theme/typography';
import { radii } from '../../../../theme/theme';
import { denseCostTableSx } from '../../costTableStyles';

export interface TopSessionsCardProps {
  sessions: CostSessionShare[];
  /** `CostBreakdown.totalCostUsd` for the window — the share-of-spend denominator. */
  totalCostUsd: number;
  isLoading: boolean;
  /** Row click (or Enter/Space) — opens that session's cost detail dialog. */
  onSelectSession: (session: CostSessionShare) => void;
}

// Fixed px widths for every column except Session — same table-layout: fixed +
// colgroup idiom as the Tokens page's CacheEfficiencyRankCard, so the Session
// column (left unset) absorbs whatever width the other three don't need instead
// of shrink-wrapping to the prompt text's own rendered width.
const REQUESTS_COLUMN_WIDTH = 90;
const SHARE_COLUMN_WIDTH = 190;
const COST_COLUMN_WIDTH = 90;
const SESSION_COLUMN_MINIMUM_WIDTH = 220;
const TABLE_MINIMUM_WIDTH =
  REQUESTS_COLUMN_WIDTH + SHARE_COLUMN_WIDTH + COST_COLUMN_WIDTH + SESSION_COLUMN_MINIMUM_WIDTH;

/** Width of the inline share-of-spend track, in px. */
const SHARE_TRACK_WIDTH = 108;

/** CSS class marking a row as clickable — see `denseCostTableSx`'s `interactiveRowClassName`. */
const DATA_ROW_CLASS_NAME = 'data-row';

/**
 * Biggest line items: the sessions that spent the most in the window. Row shape
 * mirrors the Tokens page's `CacheEfficiencyRankCard` — first prompt bold, full
 * session id mono/muted below it, never truncated (see that card's CLAUDE.md
 * gotcha for why: it's the one id-bearing surface a reader can match against the
 * prompt-timeline header, the detail dialog, and the Sessions deep link). Rows
 * are clickable and keyboard-activatable, opening `SessionCostDialog`.
 */
const TopSessionsCard = ({ sessions, totalCostUsd, isLoading, onSelectSession }: TopSessionsCardProps) => {
  const theme = useTheme();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;
  const stripeColor = theme.custom?.rowStripe ?? alpha(trackColor, 0.5);
  const barColor = theme.palette.primary.main;

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="subtitle1">Most expensive sessions</Typography>
      <Typography variant="body2" color="text.secondary">
        Ranked by priced api_request cost within the selected window.
      </Typography>

      {!isLoading && sessions.length === 0 ? (
        <Typography color="text.secondary" sx={{ mt: 1.5 }}>
          No priced requests in this window.
        </Typography>
      ) : (
        <Box sx={{ overflowX: 'auto' }}>
          <Box
            component="table"
            sx={denseCostTableSx(stripeColor, trackColor, {
              minWidth: TABLE_MINIMUM_WIDTH,
              interactiveRowClassName: DATA_ROW_CLASS_NAME,
            })}
          >
            <Box component="colgroup">
              <Box component="col" />
              <Box component="col" sx={{ width: REQUESTS_COLUMN_WIDTH }} />
              <Box component="col" sx={{ width: SHARE_COLUMN_WIDTH }} />
              <Box component="col" sx={{ width: COST_COLUMN_WIDTH }} />
            </Box>
            <Box component="thead">
              <Box component="tr">
                <Box component="th">Session</Box>
                <Box component="th" className="num">Requests</Box>
                <Box component="th">Share of spend</Box>
                <Box component="th" className="num">Cost</Box>
              </Box>
            </Box>
            <Box component="tbody">
              {sessions.map((session) => {
                const share = totalCostUsd === 0 ? 0 : (session.costUsd / totalCostUsd) * 100;
                return (
                  <Box
                    component="tr"
                    key={session.sessionId}
                    className={DATA_ROW_CLASS_NAME}
                    tabIndex={0}
                    onClick={() => onSelectSession(session)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        onSelectSession(session);
                      }
                    }}
                  >
                    <Box component="td" sx={{ whiteSpace: 'normal' }}>
                      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.25, minWidth: 0 }}>
                        {session.firstUserPrompt ? (
                          <Box
                            component="span"
                            title={session.firstUserPrompt}
                            sx={{
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                              color: 'text.primary',
                              fontWeight: 600,
                            }}
                          >
                            {session.firstUserPrompt}
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
                          {session.sessionId}
                        </Box>
                      </Box>
                    </Box>
                    <Box component="td" className="num">
                      {session.requests.toLocaleString()}
                    </Box>
                    <Box component="td">
                      <LinearProgress
                        variant="determinate"
                        value={Math.min(100, share)}
                        aria-label={`Share of spend for session ${session.sessionId}`}
                        sx={{
                          display: 'inline-block',
                          verticalAlign: 'middle',
                          width: SHARE_TRACK_WIDTH,
                          height: 7,
                          borderRadius: radii.pill,
                          bgcolor: trackColor,
                          '& .MuiLinearProgress-bar': { borderRadius: radii.pill, bgcolor: barColor },
                        }}
                      />
                      <Box component="span" sx={{ ml: 1.25, fontVariantNumeric: 'tabular-nums', color: 'text.secondary' }}>
                        {share.toFixed(1)}%
                      </Box>
                    </Box>
                    <Box component="td" className="num" sx={{ fontWeight: 700 }}>
                      {USD_FORMATTER.format(session.costUsd)}
                    </Box>
                  </Box>
                );
              })}
            </Box>
          </Box>
        </Box>
      )}
    </Paper>
  );
};

export default TopSessionsCard;
