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
import { Box, LinearProgress, useTheme } from '@mui/material';
import { colorForIndex, radii } from '../../theme/theme';

/**
 * One row in a BreakdownList. Either `colorIndex` (resolved via the shared
 * chart palette via `colorForIndex`) or an explicit `color` string may be
 * provided; `colorIndex` takes precedence when both are present.
 */
export interface BreakdownRow {
  /** Primary label text (truncated when it overflows). */
  label: string;
  /**
   * Pre-formatted display string for the value (e.g. "7.8M", "903", "$720").
   * Rendered as-is — no further formatting is applied here.
   */
  value: string;
  /** Share of the total, 0–100. Used both for the bar width and the "N%" label. */
  percentage: number;
  /**
   * Index into the dashboard palette (`colorForIndex(colorIndex)`). When
   * provided, takes precedence over `explicitColor`.
   */
  colorIndex?: number;
  /**
   * A resolved CSS color string. Used when the caller already has a specific
   * color and no palette index applies.
   */
  explicitColor?: string;
  /**
   * Optional secondary text rendered below the label (not shown in 'grid-row'
   * layout which has no room for a secondary line).
   */
  secondaryText?: string;
}

/**
 * Controls the overall row layout:
 *
 * - `'grid-row'`  — a 4-column CSS grid (label · value · dot-separator ·
 *   percentage) with the LinearProgress bar spanning all columns on a
 *   sub-row. Matches the ToolRankingCard style: compact, tabular, no leading
 *   color dot. Best for lists where the label column needs to flex.
 *
 * - `'stacked'`   — each row is a flex line (optional dot · label · value ·
 *   percentage) followed by a LinearProgress bar below it. Matches the
 *   MetricBreakdown style. Works well inside a narrow right-hand panel.
 */
export type BreakdownListLayout = 'grid-row' | 'stacked';

export interface BreakdownListProps {
  rows: BreakdownRow[];
  /**
   * Controls how each row is laid out. Defaults to `'stacked'`.
   * See the `BreakdownListLayout` doc for full details.
   */
  layout?: BreakdownListLayout;
  /**
   * When `true`, a small square color swatch is rendered to the left of the
   * label. Defaults to `false`.
   *
   * Not applicable for `'grid-row'` layout (the swatch would break the grid
   * column alignment) — pass `false` or omit for that layout.
   */
  showColorDot?: boolean;
  /**
   * When `true`, the row's 1-based position in `rows` is rendered in place of
   * the color dot, for `'stacked'` layout only. Used by `ContextFootprintCard`,
   * whose ranking is the point of the card — a color dot doesn't communicate
   * "1st, 2nd, 3rd" the way a number does. Mutually exclusive with
   * `showColorDot` in practice; when both are passed the rank wins, since a row
   * that carries a rank has no use for a second leading marker.
   */
  showRank?: boolean;
  /**
   * Percentage precision for the inline "N%" label. Defaults to `1`.
   * Pass `0` to hide decimals (e.g. MetricBreakdown already has integers).
   */
  percentageDecimalPlaces?: number;
}

/**
 * Fixed width of the `showRank` number column, so two-digit ranks don't shift
 * the labels below them out of alignment with the single-digit ones above.
 */
const RANK_COLUMN_WIDTH = 18;

/** Resolves the bar/dot color for a single row. */
const resolveColor = (row: BreakdownRow): string => {
  if (row.colorIndex !== undefined) {
    return colorForIndex(row.colorIndex);
  }
  if (row.explicitColor !== undefined) {
    return row.explicitColor;
  }
  // Fallback to the first palette color so we never render a colorless bar.
  return colorForIndex(0);
};

/**
 * A shared "label · value · percentage · LinearProgress" list used across
 * ToolRankingCard, MetricBreakdown, and ContextFootprintCard.
 *
 * The call sites use different `layout` variants; `showColorDot` / `showRank`
 * cover the remaining visual differences. The surrounding Paper/title/heading
 * stays in each card — this component replaces only the repeated row+bar
 * markup.
 */
const BreakdownList = ({
  rows,
  layout = 'stacked',
  showColorDot = false,
  showRank = false,
  percentageDecimalPlaces = 1,
}: BreakdownListProps) => {
  const theme = useTheme();
  const trackColor = theme.custom?.progressTrack ?? theme.palette.action.hover;

  if (layout === 'grid-row') {
    return (
      <Box
        sx={{
          mt: 1,
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr) auto auto auto',
          columnGap: 1,
          rowGap: 0.5,
          alignItems: 'baseline',
        }}
      >
        {rows.map((row, index) => {
          const color = resolveColor(row);
          return (
            <Box key={`${row.label}-${index}`} sx={{ display: 'contents' }}>
              <Box
                sx={{
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  fontWeight: 600,
                  fontSize: '0.875rem',
                }}
              >
                {row.label}
              </Box>
              <Box
                sx={{
                  textAlign: 'right',
                  fontVariantNumeric: 'tabular-nums',
                  fontWeight: 600,
                  fontSize: '0.875rem',
                }}
              >
                {row.value}
              </Box>
              <Box sx={{ color: 'text.disabled', fontSize: '0.875rem' }}>·</Box>
              <Box
                sx={{
                  textAlign: 'right',
                  fontVariantNumeric: 'tabular-nums',
                  color: 'text.secondary',
                  fontSize: '0.875rem',
                }}
              >
                {row.percentage.toFixed(percentageDecimalPlaces)}%
              </Box>
              <LinearProgress
                variant="determinate"
                value={row.percentage}
                sx={{
                  gridColumn: '1 / -1',
                  height: 8,
                  mb: 1.5,
                  bgcolor: trackColor,
                  '& .MuiLinearProgress-bar': { bgcolor: color },
                }}
              />
            </Box>
          );
        })}
      </Box>
    );
  }

  // Default: 'stacked' layout (MetricBreakdown style)
  return (
    <>
      {rows.map((row, index) => {
        const color = resolveColor(row);
        return (
          <Box key={`${row.label}-${index}`} sx={{ mt: 1.875 }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'baseline',
                gap: 1,
                mb: 0.75,
                fontSize: 13,
              }}
            >
              {showRank && (
                <Box
                  sx={{
                    width: RANK_COLUMN_WIDTH,
                    flexShrink: 0,
                    color: 'text.disabled',
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  {index + 1}
                </Box>
              )}
              {showColorDot && !showRank && (
                <Box
                  sx={{
                    width: 9,
                    height: 9,
                    borderRadius: '3px',
                    flexShrink: 0,
                    alignSelf: 'center',
                    bgcolor: color,
                  }}
                />
              )}
              <Box
                sx={{
                  fontWeight: 600,
                  flex: 1,
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {row.label}
              </Box>
              <Box
                sx={{
                  color: 'text.secondary',
                  fontVariantNumeric: 'tabular-nums',
                  whiteSpace: 'nowrap',
                }}
              >
                <Box component="b" sx={{ color: 'text.primary', fontWeight: 700 }}>
                  {row.value}
                </Box>{' '}
                · {row.percentage.toFixed(percentageDecimalPlaces)}%
              </Box>
            </Box>
            <LinearProgress
              variant="determinate"
              value={row.percentage}
              sx={{
                height: 8,
                borderRadius: radii.pill,
                bgcolor: trackColor,
                '& .MuiLinearProgress-bar': { backgroundColor: color, borderRadius: radii.pill },
              }}
            />
            {row.secondaryText && (
              <Box sx={{ mt: 0.5, fontSize: 12, color: 'text.secondary' }}>
                {row.secondaryText}
              </Box>
            )}
          </Box>
        );
      })}
    </>
  );
};

export default BreakdownList;
