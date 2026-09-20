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
// Shared cardinality-health copy + color lookup for a MetricSeries's `health` field.
//
// Pure, no React — reused by MetricHeader today and by the Phase 2 MetricCatalogRail
// (each metric's catalog-row status dot), so nothing here should assume it's only ever
// rendered inside the detail header.

import type { Theme } from '@mui/material/styles';

/** Full copy for a health state, e.g. shown as a tooltip on hover over the compact dot/label. */
export const HEALTH_LABEL: Record<'ok' | 'warn' | 'bad', string> = {
  ok: 'Healthy · ingesting',
  warn: 'High cardinality — watch this series',
  bad: 'Cardinality spike — labels exploding',
};

/** Maps a health state to the theme's existing success/warning/error palette tokens. */
export const healthColor = (health: 'ok' | 'warn' | 'bad', theme: Theme): string => {
  if (health === 'bad') {
    return theme.palette.error.main;
  }
  if (health === 'warn') {
    return theme.palette.warning.main;
  }
  return theme.palette.success.main;
};
