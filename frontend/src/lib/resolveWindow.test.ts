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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_WINDOW_SPAN_MS, MS_PER_MINUTE, WINDOWS } from './constants';
import { resolveWindow } from './resolveWindow';

describe('resolveWindow', () => {
  const fixedNowMs = Date.parse('2026-06-15T12:00:00.000Z');

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(fixedNowMs);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('clamps the 30-day preset so the resolved end-minus-start width never exceeds MAX_WINDOW_SPAN_MS, while still anchoring start to now (not to the slack-shifted end)', () => {
    const thirtyDayOption = WINDOWS.find((option) => option.label === '30 days');
    if (!thirtyDayOption) {
      throw new Error('expected a 30-day preset in WINDOWS');
    }

    const resolved = resolveWindow({ kind: 'preset', minutes: thirtyDayOption.value });

    const resolvedWidthMs = Date.parse(resolved.endTimestamp) - Date.parse(resolved.startTimestamp);
    expect(resolvedWidthMs).toBe(MAX_WINDOW_SPAN_MS);
    expect(resolvedWidthMs).toBeLessThanOrEqual(MAX_WINDOW_SPAN_MS);
    expect(resolved.endTimestamp).toBe(new Date(fixedNowMs + MS_PER_MINUTE).toISOString());
    // The clamp ceiling is MAX_WINDOW_SPAN_MS minus the 1-minute end slack, so
    // the requested span (measured from "now", not from the slack-shifted
    // end) is one minute short of the nominal cap here — that's the price of
    // keeping the total resolved width within the backend's hard limit.
    expect(fixedNowMs - Date.parse(resolved.startTimestamp)).toBe(MAX_WINDOW_SPAN_MS - MS_PER_MINUTE);
    expect(resolved.label).toBe('30 days');
  });

  it('anchors start to now - span (not end - span), so a shorter preset keeps its full requested span instead of shifting forward by the end slack', () => {
    const twentyFourHourOption = WINDOWS.find((option) => option.label === '24 hours');
    if (!twentyFourHourOption) {
      throw new Error('expected a 24-hour preset in WINDOWS');
    }

    const resolved = resolveWindow({ kind: 'preset', minutes: twentyFourHourOption.value });

    const expectedStartMs = fixedNowMs - twentyFourHourOption.value * MS_PER_MINUTE;
    const expectedEndMs = fixedNowMs + MS_PER_MINUTE;
    expect(resolved.startTimestamp).toBe(new Date(expectedStartMs).toISOString());
    expect(resolved.endTimestamp).toBe(new Date(expectedEndMs).toISOString());
    // The resolved window is span + 1min wide: the full 24h the user asked
    // for, plus the ingest slack tacked onto the end — not a 24h window
    // shifted one minute into the future.
    expect(expectedEndMs - expectedStartMs).toBe(twentyFourHourOption.value * MS_PER_MINUTE + MS_PER_MINUTE);
    expect(resolved.label).toBe('24 hours');
  });

  it('passes a custom selection through untouched', () => {
    const startTimestamp = '2026-05-01T00:00:00.000Z';
    const endTimestamp = '2026-05-15T00:00:00.000Z';

    const resolved = resolveWindow({ kind: 'custom', startTimestamp, endTimestamp });

    expect(resolved.startTimestamp).toBe(startTimestamp);
    expect(resolved.endTimestamp).toBe(endTimestamp);
    expect(resolved.label).toBe('selected range');
  });
});
