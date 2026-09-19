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
import { useTheme } from '@mui/material/styles';
import type { SessionTokenBreakdown } from '../../../../api';
import {
  cacheEfficiencyBand,
  cacheEfficiencyRatio,
  formatCacheEfficiency,
} from '../../../../lib/cacheEfficiency';
import { formatCompact } from '../../../../lib/format';
import { colorForIndex } from '../../../../theme/theme';
import { fontFamilies } from '../../../../theme/typography';
import { cacheEfficiencyBandColor } from '../cacheEfficiencyBandColors';
import { TOKEN_KIND_COLORS, TOKEN_KIND_LABELS } from '../../tokenKindColors';

/**
 * One per-model row combining the window's token sum and this model's own
 * four-way token split. Built from `summary.byModel` (`TokenModelShare[]`,
 * which now carries `breakdown` directly) — see `TokensPageView`'s
 * `tokenCostRows`.
 */
export interface TokenCostByModelRow {
  model: string;
  colorIndex: number;
  /** Pre-formatted token sum, e.g. "5.2M". */
  tokens: string;
  /** Share of window tokens, 0-100. */
  tokenShare: number;
  /** This model's own four-way split (input/output/cacheCreation/cacheRead). */
  breakdown: SessionTokenBreakdown;
}

export interface TokenCostByModelCardProps {
  rows: TokenCostByModelRow[];
  note?: string;
}

// Fixed px width for Model and Tokens; Cache composition is left unset in
// the colgroup below, which is what lets it absorb 100% of whatever width
// the other two don't need under table-layout: fixed — same idiom as
// CacheEfficiencyRankCard's Session column, just with the flexible column
// last instead of first.
const MODEL_COLUMN_WIDTH = 230;
const TOKENS_COLUMN_WIDTH = 130;

/** Height of the cache-composition segmented bar. */
const COMPOSITION_BAR_HEIGHT = 10;

/** One entry in the header color key and each row's composition legend. */
const KEY_ENTRIES: Array<{ kind: keyof typeof TOKEN_KIND_LABELS; color: string }> = [
  { kind: 'cacheRead', color: TOKEN_KIND_COLORS.cacheRead },
  { kind: 'input', color: TOKEN_KIND_COLORS.input },
  { kind: 'cacheCreation', color: TOKEN_KIND_COLORS.cacheCreation },
  { kind: 'output', color: TOKEN_KIND_COLORS.output },
];

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
  '& tbody td': {
    padding: '13px 12px',
    fontSize: '13.5px',
    borderBottom: 1,
    borderColor: 'divider',
    color: 'text.primary',
  },
  '& tbody tr:last-of-type td': { borderBottom: 0 },
  '& tbody tr:hover td': { backgroundColor: 'action.hover' },
  '& td.state': {
    textAlign: 'center',
    color: 'text.secondary',
    padding: '26px 12px',
  },
};

/**
 * "Tokens by model" — a one-time color key row, then Model | Tokens (plain
 * value + share, no bar — redundant with the donut card above) | Cache
 * composition (cache-read % banded by `cacheEfficiencyBand`, a 4-segment mini
 * bar of this model's own input/output/cache-creation/cache-read split, and a
 * legend row of each kind's absolute value — the color key above lets the
 * legend skip repeating the kind label on every row). Sits below the
 * composition card, above the trend chart.
 */
const TokenCostByModelCard = ({ rows, note }: TokenCostByModelCardProps) => {
  const theme = useTheme();

  return (
    <Paper variant="outlined" sx={{ p: '22px 24px' }}>
      <Box
        sx={{ fontFamily: fontFamilies.display, fontWeight: 600, fontSize: 16 }}
      >
        Tokens by model
      </Box>

      {/* One-time color key so each row's composition legend below can show
          bare values without repeating the kind label on every row. */}
      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 2,
          mt: 1,
          fontSize: 12,
          color: 'text.secondary',
        }}
      >
        {KEY_ENTRIES.map((entry) => (
          <Box key={entry.kind} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
            <Box
              sx={{ width: 9, height: 9, borderRadius: '3px', bgcolor: entry.color, flexShrink: 0 }}
            />
            {TOKEN_KIND_LABELS[entry.kind]}
          </Box>
        ))}
      </Box>

      <Box sx={{ overflowX: 'auto', mt: 1.75 }}>
        <Box component="table" sx={tableSx}>
          <Box component="colgroup">
            <Box component="col" sx={{ width: MODEL_COLUMN_WIDTH }} />
            <Box component="col" sx={{ width: TOKENS_COLUMN_WIDTH }} />
            <Box component="col" />
          </Box>
          <Box component="thead">
            <Box component="tr">
              <Box component="th">Model</Box>
              <Box component="th">Tokens</Box>
              <Box component="th">Cache composition</Box>
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
                const ratio = cacheEfficiencyRatio(row.breakdown);
                const band = cacheEfficiencyBand(ratio);
                const bandColor = cacheEfficiencyBandColor(band, theme);
                // Same four kinds and colors as the donut/trend chart/session
                // dialog above — TOKEN_KIND_COLORS keeps a kind's color
                // consistent everywhere it appears on this page.
                const segments = [
                  {
                    kind: TOKEN_KIND_LABELS.cacheRead,
                    value: row.breakdown.cacheRead,
                    color: TOKEN_KIND_COLORS.cacheRead,
                  },
                  {
                    kind: TOKEN_KIND_LABELS.input,
                    value: row.breakdown.input,
                    color: TOKEN_KIND_COLORS.input,
                  },
                  {
                    kind: TOKEN_KIND_LABELS.cacheCreation,
                    value: row.breakdown.cacheCreation,
                    color: TOKEN_KIND_COLORS.cacheCreation,
                  },
                  {
                    kind: TOKEN_KIND_LABELS.output,
                    value: row.breakdown.output,
                    color: TOKEN_KIND_COLORS.output,
                  },
                ];
                const segmentTotal = segments.reduce((sum, segment) => sum + segment.value, 0);

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
                    </Box>
                    <Box component="td">
                      <Box
                        component="span"
                        sx={{
                          fontFamily: fontFamilies.display,
                          fontWeight: 800,
                          fontSize: 15,
                          fontVariantNumeric: 'tabular-nums',
                          color: bandColor,
                        }}
                      >
                        {formatCacheEfficiency(ratio)}
                      </Box>{' '}
                      <Box component="small" sx={{ color: 'text.secondary', fontWeight: 500 }}>
                        cached
                      </Box>
                      <Box
                        sx={{
                          display: 'flex',
                          height: COMPOSITION_BAR_HEIGHT,
                          borderRadius: '6px',
                          overflow: 'hidden',
                          bgcolor: 'action.hover',
                          mt: 0.85,
                        }}
                      >
                        {segmentTotal > 0
                          && segments
                            .filter((segment) => segment.value > 0)
                            .map((segment) => (
                              <Box
                                key={segment.kind}
                                sx={{
                                  height: '100%',
                                  minWidth: '2px',
                                  width: `${(segment.value / segmentTotal) * 100}%`,
                                  bgcolor: segment.color,
                                }}
                              />
                            ))}
                      </Box>
                      <Box
                        sx={{
                          display: 'flex',
                          flexWrap: 'wrap',
                          gap: 1.5,
                          mt: 0.85,
                          fontSize: 11,
                          color: 'text.secondary',
                          fontVariantNumeric: 'tabular-nums',
                        }}
                      >
                        {segments.map((segment) => (
                          <Box
                            key={segment.kind}
                            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}
                          >
                            <Box
                              sx={{
                                width: 7,
                                height: 7,
                                borderRadius: '2px',
                                bgcolor: segment.color,
                                flexShrink: 0,
                              }}
                            />
                            {formatCompact(segment.value)}
                          </Box>
                        ))}
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
        {note ?? 'Token totals and composition by model over the selected window.'}
      </Box>
    </Paper>
  );
};

export default TokenCostByModelCard;
