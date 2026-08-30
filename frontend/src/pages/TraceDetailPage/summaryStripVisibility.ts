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
// Whether the SummaryStrip "Overview" panel is collapsed. This is a display
// preference, not per-trace state — same idiom as chipVisibility.ts — so it's
// read from localStorage on init and survives navigating between traces.
const STORAGE_KEY = 'ac-wf-overview-collapsed';

export const loadOverviewCollapsed = (): boolean => {
  if (typeof window === 'undefined') {
    return false;
  }
  try {
    return window.localStorage?.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
};

export const persistOverviewCollapsed = (collapsed: boolean): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(STORAGE_KEY, String(collapsed));
  } catch {
    // ignore quota / disabled storage — the toggle still works for the
    // session, it just won't survive a reload
  }
};
