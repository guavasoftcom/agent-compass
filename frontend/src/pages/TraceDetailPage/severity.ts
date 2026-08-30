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
const SEVERITY_TRACE_MAX = 4;
const SEVERITY_DEBUG_MAX = 8;
const SEVERITY_INFO_MAX = 12;
const SEVERITY_WARN_MAX = 16;
const SEVERITY_ERROR_MAX = 20;

export const severityLabel = (severityNumber: number | null): string => {
  if (severityNumber == null) {
    return 'UNSET';
  }
  if (severityNumber <= SEVERITY_TRACE_MAX) {
    return 'TRACE';
  }
  if (severityNumber <= SEVERITY_DEBUG_MAX) {
    return 'DEBUG';
  }
  if (severityNumber <= SEVERITY_INFO_MAX) {
    return 'INFO';
  }
  if (severityNumber <= SEVERITY_WARN_MAX) {
    return 'WARN';
  }
  if (severityNumber <= SEVERITY_ERROR_MAX) {
    return 'ERROR';
  }
  return 'FATAL';
};

export const severityColor = (
  severityNumber: number | null,
): 'default' | 'info' | 'warning' | 'error' => {
  if (severityNumber == null) {
    return 'default';
  }
  if (severityNumber <= SEVERITY_DEBUG_MAX) {
    return 'default';
  }
  if (severityNumber <= SEVERITY_INFO_MAX) {
    return 'info';
  }
  if (severityNumber <= SEVERITY_WARN_MAX) {
    return 'warning';
  }
  return 'error';
};
