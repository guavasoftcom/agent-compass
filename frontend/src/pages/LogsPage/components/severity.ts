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
import type { Severity } from '../logsApi';

/**
 * Maps a log severity to its theme palette color. Shared across the Logs presentation
 * components — histogram bars/legend, the stream severity rail + chip, and the facet dots.
 */
export const severityColor = (t: Theme, s: Severity): string => {
  switch (s) {
    case 'ERROR':
      return t.palette.error.main;
    case 'WARN':
      return t.palette.warning.main;
    case 'INFO':
      return t.palette.primary.main;
    default:
      return t.palette.text.disabled;
  }
};
