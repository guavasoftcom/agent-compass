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
import type { SxProps, Theme } from '@mui/material/styles';
import { fontFamilies } from '../../theme/typography';

export interface DenseCostTableOptions {
  /** Fixed layout + minimum width, for a table with `<colgroup>`-sized columns. */
  minWidth?: number;
  /**
   * When set, hover/cursor styling targets rows carrying this class instead of every
   * `tbody tr` — for a table whose rows are clickable and shouldn't show a pointer/hover
   * on non-interactive rows.
   */
  interactiveRowClassName?: string;
}

// Shared by CostDriversCard and TopSessionsCard, the two dense tables on the "What
// drove it" tab (see CostPage/CLAUDE.md — they're meant to match pixel-for-pixel, and
// drifted out of sync once already when only one of two hand-rolled copies was brought
// in line with the Aurora mockup's table CSS). Matches the mockup's styling: uppercase
// Sora headers via the shared eyebrowSm typography variant, 13px/12px cell padding,
// hairline row dividers. Zebra + hover are two distinct theme tokens (`rowStripe` /
// `progressTrack`), not one token wrapped in `alpha(trackColor, 0.5)` — `alpha()`
// overwrites a color's existing alpha channel rather than multiplying it, so
// re-wrapping the already-translucent progressTrack would silently discard its real
// opacity (0.08) and substitute the literal 0.5, rendering the stripe as a near-opaque
// wash instead of a faint tint.
export const denseCostTableSx = (
  stripeColor: string,
  hoverColor: string,
  options: DenseCostTableOptions = {},
): SxProps<Theme> => {
  const { minWidth, interactiveRowClassName } = options;
  const hoverRowSelector = interactiveRowClassName
    ? `& tbody tr.${interactiveRowClassName}:hover td`
    : '& tbody tr:hover td';

  return {
    width: '100%',
    ...(minWidth !== undefined ? { minWidth, tableLayout: 'fixed' } : {}),
    borderCollapse: 'collapse',
    mt: 1,
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
    },
    '& tbody tr:last-of-type td': { borderBottom: 0 },
    ...(interactiveRowClassName ? { [`& tbody tr.${interactiveRowClassName}`]: { cursor: 'pointer' } } : {}),
    '& tbody tr:nth-of-type(even) td': { backgroundColor: stripeColor },
    [hoverRowSelector]: { backgroundColor: hoverColor },
    '& td.num': { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  };
};
