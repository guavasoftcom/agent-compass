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
import type { ReactNode } from 'react';
import { Box, alpha, useTheme } from '@mui/material';
import { fontFamilies } from '../../theme/typography';

/** Whether a delta reads as an improvement, a regression, or noise too small to call either way. */
export type DeltaBadgeState = 'good' | 'flat' | 'bad';

/** Which way the underlying value actually moved — independent of `state` (a big "up" move can
 *  still be "bad", e.g. rising error count). `'flat'` renders the ≈ glyph instead of an arrow. */
export type DeltaBadgeDirection = 'up' | 'down' | 'flat';

export interface DeltaBadgeProps {
  state: DeltaBadgeState;
  direction: DeltaBadgeDirection;
  /** Formatted magnitude text, e.g. "34%", "9.2pt", or "flat" — rendered next to the arrow/≈ glyph. */
  value: ReactNode;
}

const ArrowUp = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={10} height={10}>
    <path d="M7 14l5-5 5 5" />
  </svg>
);

const ArrowDown = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} width={10} height={10}>
    <path d="M7 10l5 5 5-5" />
  </svg>
);

/**
 * Three-state pill badge (good = green, flat = neutral gray, bad = red) with a directional
 * arrow (or ≈ for flat) plus a value string — e.g. "↓ 34%", "↑ 9.2pt", "≈ flat". Generalizes
 * `StatCard`'s inline two-state trend badge (up/down only, no neutral state) into a standalone
 * component so any page needing a genuinely three-way classification (not just "more" vs
 * "less") can reuse it instead of re-deriving the pill styling. See `StatCard.tsx`'s
 * `TrendArrowUp`/`TrendArrowDown` for the sibling two-state idiom this borrows its arrow
 * glyphs from.
 */
const DeltaBadge = ({ state, direction, value }: DeltaBadgeProps) => {
  const theme = useTheme();

  const color =
    state === 'good'
      ? theme.palette.success.main
      : state === 'bad'
        ? theme.palette.error.main
        : theme.palette.text.secondary;

  const backgroundColor = state === 'flat' ? theme.custom.progressTrack : alpha(color, 0.14);

  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '4px',
        px: 1.25,
        py: 0.5,
        borderRadius: 999,
        fontFamily: fontFamilies.display,
        fontWeight: 700,
        fontSize: 12,
        letterSpacing: 0.2,
        whiteSpace: 'nowrap',
        color,
        backgroundColor,
      }}
    >
      {direction === 'flat' ? (
        '≈'
      ) : (
        <Box component="span" sx={{ display: 'inline-flex' }}>
          {direction === 'up' ? <ArrowUp /> : <ArrowDown />}
        </Box>
      )}
      {value}
    </Box>
  );
};

export default DeltaBadge;
