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
import type { Theme } from '@mui/material/styles';
import type { CacheEfficiencyBand } from '../../../lib/cacheEfficiency';

/**
 * Band → theme color, shared by the ranking table and the session detail dialog
 * so one session's bar, percentage, and chip can never disagree about which
 * band it is in.
 *
 * The mapping itself is the same one the Sessions grid's Cache eff. column uses
 * (`CacheEfficiencyCell`): ≥85% success, ≥60% text.primary, below that warning,
 * and text.disabled for the undefined ratio. Bands come from
 * `lib/cacheEfficiency`; only the paint lives here, because that module is
 * deliberately theme-free.
 */
export const cacheEfficiencyBandColor = (
  band: CacheEfficiencyBand,
  theme: Theme,
): string => {
  switch (band) {
    case 'strong':
      return theme.palette.success.main;
    case 'mixed':
      return theme.palette.text.primary;
    case 'weak':
      return theme.palette.warning.main;
    default:
      return theme.palette.text.disabled;
  }
};

/** Short band name for the detail dialog's chip. */
export const CACHE_EFFICIENCY_BAND_CHIP_LABELS: Record<CacheEfficiencyBand, string> = {
  strong: 'Healthy cache efficiency',
  mixed: 'Mixed cache efficiency',
  weak: 'Poor cache efficiency',
  unknown: 'No cache activity',
};
