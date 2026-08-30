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
import { useCallback, useMemo, useState } from 'react';

export interface SeriesVisibility {
  /** One flag per series (same order as the series array). */
  active: boolean[];
  /** Label of the series currently hovered in the legend, or null. */
  focused: string | null;
  /** Toggle a series on/off. Keeps at least one series visible. */
  toggle: (index: number) => void;
  /** Set the hovered/focused series label (pass null to clear). */
  setFocused: (label: string | null) => void;
}

/**
 * Visibility + focus state for an interactive chart legend. Drives both the
 * legend chips (`AreaTrendLegend`) and the chart (`AreaTrendChart` consumes
 * `active` / `focusedLabel`). Resets if the series count changes.
 */
export const useSeriesVisibility = (count: number): SeriesVisibility => {
  const [active, setActive] = useState<boolean[]>(() => Array.from({ length: count }, () => true));
  const [focused, setFocused] = useState<string | null>(null);

  // Re-sync if the number of series changes (e.g. window reload returns fewer tools).
  const synced = useMemo(() => {
    if (active.length === count) {
      return active;
    }
    return Array.from({ length: count }, (_, i) => active[i] ?? true);
  }, [active, count]);

  const toggle = useCallback((index: number) => {
    setActive((prev) => {
      const base = prev.length === count ? prev : Array.from({ length: count }, (_, i) => prev[i] ?? true);
      const next = base.slice();
      const turningOff = next[index];
      // Don't allow turning off the last visible series.
      if (turningOff && next.filter(Boolean).length <= 1) {
        return next;
      }
      next[index] = !next[index];
      return next;
    });
  }, [count]);

  return { active: synced, focused, toggle, setFocused };
};
