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
import { Box, Paper } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';

/**
 * One per-model row combining the window's token sum and spend. Built by
 * zipping `summary.byModel` (`TokenModelShare[]`) with `summary.cost.byModel`
 * (`CostModelShare[]`) on `model` — see `TokensPageView`'s `tokenCostRows`.
 * Both halves are already pre-formatted by the backend.
 */
export interface TokenCostByModelRow {
  model: string;
  colorIndex: number;
  /** Pre-formatted token sum, e.g. "5.2M". */
  tokens: string;
  /** Share of window tokens, 0-100. */
  tokenShare: number;
  /** Pre-formatted USD spend, e.g. "$380.80". */
  usd: string;
  /** Share of window spend, 0-100. */
  costShare: number;
}

export interface TokenCostByModelCardProps {
  rows: TokenCostByModelRow[];
  note?: string;
}

// Fixed px width for Model and Cost; Tokens is left unset in the colgroup
// below, which is what lets it absorb 100% of whatever width the other two
// don't need under table-layout: fixed — same idiom as CacheEfficiencyRankCard's
// Session column, just with the flexible column in the middle instead of first.
const MODEL_COLUMN_WIDTH = 230;
const COST_COLUMN_WIDTH = 150;

// Hand-built table (Box component="table"), same idiom as CacheEfficiencyRankCard
// and SessionsTable — deliberately not a DataGrid or MUI's Table primitives.
const tableSx: SxProps<Theme> = {
  width: '100%',
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
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
  },
  '& tbody tr:last-of-type td': { borderBottom: 0 },
  '& tbody tr:hover td': { backgroundColor: 'action.hover' },
  '& td.num': { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '26px 12px',
  },
};

/**
 * "Tokens & cost by model" — replaces the separate "Token sum by model" and
 * "Cost by model" cards with one table: Model | Tokens (value + share bar) |
 * Cost (value + share, right-aligned, no bar — a second bar on the same row
 * would compete with the token one for attention). Sits below the composition
 * card, above the trend chart.
 */
const TokenCostByModelCard = ({ rows, note }: TokenCostByModelCardProps) => (
  <Paper variant="outlined" sx={{ p: '22px 24px' }}>
    <Box
      sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}
    >
      Tokens &amp; cost by model
    </Box>

    <Box sx={{ overflowX: 'auto', mt: 1.75 }}>
      <Box component="table" sx={tableSx}>
        <Box component="colgroup">
          <Box component="col" sx={{ width: MODEL_COLUMN_WIDTH }} />
          <Box component="col" />
          <Box component="col" sx={{ width: COST_COLUMN_WIDTH }} />
        </Box>
        <Box component="thead">
          <Box component="tr">
            <Box component="th">Model</Box>
            <Box component="th">Tokens</Box>
            <Box component="th" className="num">Cost</Box>
          </Box>
        </Box>
        <Box component="tbody">
          {rows.length === 0 ? (
            <Box component="tr">
              <Box component="td" className="state" colSpan={3}>
                No model activity in this window.
              </Box>
            </Box>
          ) : (
            rows.map((row) => {
              const color = colorForIndex(row.colorIndex);
              return (
                <Box component="tr" key={row.model}>
                  <Box component="td" sx={{ fontWeight: 600 }}>
                    {/* Dot + name sit in an inner flex wrapper, NOT display:flex
                        on the <td> itself — that breaks the row's height sync
                        with its sibling cells (found in the mockup; shows up as
                        a dead strip on row hover). The name gets its own
                        overflow-hidden span (not the wrapper) so ellipsis has a
                        sized box to clip against under the column's fixed
                        width — the dot stays flexShrink: 0 so it never shrinks
                        to make room. */}
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 1,
                        minWidth: 0,
                      }}
                    >
                      <Box
                        sx={{
                          width: 10,
                          height: 10,
                          borderRadius: '3px',
                          bgcolor: color,
                          flexShrink: 0,
                        }}
                      />
                      <Box
                        component="span"
                        title={row.model}
                        sx={{
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                          minWidth: 0,
                        }}
                      >
                        {row.model}
                      </Box>
                    </Box>
                  </Box>
                  <Box component="td" sx={{ color: 'text.secondary' }}>
                    <Box
                      component="span"
                      sx={{ color: 'text.primary', fontWeight: 700 }}
                    >
                      {row.tokens}
                    </Box>{' '}
                    · {row.tokenShare}%
                    <Box
                      sx={{
                        height: 8,
                        borderRadius: '5px',
                        bgcolor: 'action.hover',
                        overflow: 'hidden',
                        mt: 0.6,
                      }}
                    >
                      <Box
                        sx={{
                          height: '100%',
                          borderRadius: '5px',
                          width: `${row.tokenShare}%`,
                          bgcolor: color,
                        }}
                      />
                    </Box>
                  </Box>
                  <Box component="td" className="num">
                    {row.usd}
                    <br />
                    <Box
                      component="small"
                      sx={{ color: 'text.secondary', fontWeight: 500 }}
                    >
                      {row.costShare}%
                    </Box>
                  </Box>
                </Box>
              );
            })
          )}
        </Box>
      </Box>
    </Box>

    <Box
      sx={{
        mt: 2.25,
        pt: 2,
        borderTop: 1,
        borderColor: 'divider',
        fontSize: 12,
        color: 'text.secondary',
        lineHeight: 1.5,
      }}
    >
      {note ?? 'Token totals and cost by model over the selected window.'}
    </Box>
  </Paper>
);

export default TokenCostByModelCard;
