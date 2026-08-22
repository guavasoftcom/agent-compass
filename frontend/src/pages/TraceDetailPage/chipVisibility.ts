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
