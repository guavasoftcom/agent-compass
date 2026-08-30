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
// Shared window-resolution helper for LogsPage and TracesPage: turns the
// global `WindowSelection` (preset minutes or an explicit custom range) into
// concrete `startTimestamp`/`endTimestamp` ISO strings plus a display label.
//
// Preset spans are clamped so the resolved end-minus-start width (which
// includes the +1-minute ingest slack added below) never exceeds
// `MAX_WINDOW_SPAN_MS` — i.e. a 30-day (or larger, were one ever added to
// WINDOWS) preset can never produce a range wider than the backend's
// `@ValidDateRange(maxDays = 30)` cap — see the constant's comment in
// `lib/constants.ts`. Custom selections already pass through WindowSelector's
// own `MAX_RANGE_MS`-equivalent guard, so they're returned unchanged here.

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
  const nowMs = Date.now();
  // +1-minute forward slack on the end only, so points ingested between "now"
  // and the request actually reaching the backend aren't clipped off the
  // freshest edge.
  const endMs = nowMs + MS_PER_MINUTE;
  // Anchor the start to "now - span", NOT "end - span" — deriving start from
  // the slack-shifted end would silently shift the whole window forward by
  // that same minute, dropping the oldest minute of data the user actually
  // asked for (e.g. "Last 1h" would resolve to now-59min..now+1min instead of
  // now-60min..now+1min). The resolved window is therefore span + 1min wide:
  // the full requested span, plus the ingest slack tacked onto the end. The
  // clamp ceiling is MAX_WINDOW_SPAN_MS minus that same 1-minute slack (not
  // MAX_WINDOW_SPAN_MS outright), so the total end-start width for the
  // largest preset (30 days, exactly MAX_WINDOW_SPAN_MS) still lands at
  // MAX_WINDOW_SPAN_MS instead of one minute over it — the backend's
  // DateRangeValidator rejects any range whose seconds strictly exceed
  // maxDays * 86_400, with no tolerance for the ingest slack.
  const spanMs = Math.min(
    selection.minutes * MS_PER_MINUTE,
    MAX_WINDOW_SPAN_MS - MS_PER_MINUTE,
  );
  const startMs = nowMs - spanMs;
  return {
    startTimestamp: new Date(startMs).toISOString(),
    endTimestamp: new Date(endMs).toISOString(),
    label,
  };
};
