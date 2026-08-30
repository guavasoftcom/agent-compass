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
// Which SpanWaterfallRow badge families are currently hidden. This is a
// display preference, not per-trace state — it's read from localStorage on
// init rather than reset by the view's `key={traceId}` remount, so muting a
// family stays muted while paging through traces.
export type ChipFamily = 'tok' | 'cr' | 'cost' | 'mdl' | 'tool';

const STORAGE_KEY = 'ac-wf-chips-off';

export const loadChipsOff = (): Set<ChipFamily> => {
  if (typeof window === 'undefined') {
    return new Set();
  }
  try {
    const stored = window.localStorage?.getItem(STORAGE_KEY);
    const parsed = stored ? (JSON.parse(stored) as ChipFamily[]) : [];
    return new Set(parsed);
  } catch {
    return new Set();
  }
};

export const persistChipsOff = (chipsOff: Set<ChipFamily>): void => {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage?.setItem(STORAGE_KEY, JSON.stringify([...chipsOff]));
  } catch {
    // ignore quota / disabled storage — the toggle still works for the
    // session, it just won't survive a reload
  }
};
