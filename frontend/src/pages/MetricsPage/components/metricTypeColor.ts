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
// Shared metric-type accent color lookup (counter / gauge / histogram).
//
// Pure, no React — used by MetricCatalogRail's per-row type badge. It lives beside
// metricHealth.ts for the same reason: a tiny theme-token lookup that more than one
// component may want, without either owning it.

import type { Theme } from '@mui/material/styles';
import { auroraColors } from '../../../theme/colors';
import type { MetricSeries } from './metricsSampleData';

/** Maps a metric's instrument type to a theme palette token (aurora fallbacks when a slot is absent). */
export const metricTypeColor = (type: MetricSeries['type'], theme: Theme): string => {
  if (type === 'gauge') {
    return theme.palette.primary.main;
  }
  if (type === 'histogram') {
    return theme.palette.secondary?.main ?? auroraColors.pink;
  }
  return theme.palette.info?.main ?? auroraColors.cyan;
};
