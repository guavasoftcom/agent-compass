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
