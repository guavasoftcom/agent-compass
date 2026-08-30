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
import { Box } from '@mui/material';
import { fontFamilies } from '../../theme/typography';

export interface KpiTileProps {
  label: string;
  value: string;
  /** Value text color; defaults to `text.primary`. */
  color?: string;
}

/**
 * A small label-over-big-value tile, for a dialog's KPI strip (a 2-up grid above
 * a detail body) — not the page-level `StatCard`, which carries its own accent
 * border, trend arrow, and info tooltip for a strip that sits above a whole page.
 * Shared by `SessionCostDialog` (Cost page) and `SessionCacheEfficiencyDialog`
 * (Tokens page), which had the identical tile duplicated before this existed.
 */
const KpiTile = ({ label, value, color }: KpiTileProps) => (
  <Box>
    <Box sx={{ typography: 'eyebrowSm', color: 'text.disabled' }}>{label}</Box>
    <Box
      sx={{
        mt: 0.6,
        fontFamily: fontFamilies.display,
        fontWeight: 800,
        fontSize: 24,
        color: color ?? 'text.primary',
      }}
    >
      {value}
    </Box>
  </Box>
);

export default KpiTile;
