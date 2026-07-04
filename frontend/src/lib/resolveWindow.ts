// Shared window-resolution helper for LogsPage and TracesPage: turns the
// global `WindowSelection` (preset minutes or an explicit custom range) into
// concrete `startTimestamp`/`endTimestamp` ISO strings plus a display label.
//
// Preset spans are clamped to `MAX_WINDOW_SPAN_MS` so a 30-day (or larger,
// were one ever added to WINDOWS) preset can never produce a span wider than
// the backend's `@ValidDateRange(maxDays = 30)` cap — see the constant's
// comment in `lib/constants.ts`. Custom selections already pass through
// WindowSelector's own `MAX_RANGE_MS`-equivalent guard, so they're returned
// unchanged here.

import { MAX_WINDOW_SPAN_MS, MS_PER_MINUTE, WINDOWS } from './constants';
import type { WindowSelection } from '../api';

export interface ResolvedWindow {
  startTimestamp: string;
  endTimestamp: string;
  label: string;
}

export const resolveWindow = (selection: WindowSelection): ResolvedWindow => {
  if (selection.kind === 'custom') {
    return {
      startTimestamp: selection.startTimestamp,
      endTimestamp: selection.endTimestamp,
      label: 'selected range',
    };
  }
  const label = WINDOWS.find((option) => option.value === selection.minutes)?.label ?? 'window';
  const endMs = Date.now() + MS_PER_MINUTE;
  // Clamp the span so the +1-minute end lookahead clips the OLDEST minute of a
  // maxed-out window, not the newest — keeps live tail's freshest edge intact.
  const spanMs = Math.min(selection.minutes * MS_PER_MINUTE, MAX_WINDOW_SPAN_MS);
  const startMs = endMs - spanMs;
  return {
    startTimestamp: new Date(startMs).toISOString(),
    endTimestamp: new Date(endMs).toISOString(),
    label,
  };
};
